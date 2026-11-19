package org.anonymous.msrefactor.rt.quality;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.anonymous.msrefactor.rt.monitor.FunctionalityInfo;
import org.anonymous.msrefactor.rt.monitor.ServiceStaticMetrics;
import org.anonymous.msrefactor.rt.monitor.StaticMetrics;
import org.anonymous.msrefactor.rt.monitor.StructuralSignals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The normalised signals have a reachable maximum and the directions a merge or split should
 * move them in. Raw M1 ({@code μ/N²}) has neither: a tree-shaped aggregate caps at
 * {@code 2(N−1)/N²}, and that cap falls as N grows.
 */
class StructuralSignalsTest {

    // ─── Primitives ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("a complete composition tree with back-references is maximally cohesive, whatever its size")
    void structuralCohesion_treeIsOne() {
        for (int n = 2; n <= 8; n++) {
            assertThat(StructuralSignals.structuralCohesion(n, 2 * (n - 1))).isEqualTo(1.0);
        }
    }

    @Test
    @DisplayName("a single entity is a whole aggregate, not a zero")
    void structuralCohesion_singleEntityIsOne() {
        assertThat(StructuralSignals.structuralCohesion(1, 0)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("members not wired to the root lower structural cohesion")
    void structuralCohesion_looseMembersScoreLower() {
        assertThat(StructuralSignals.structuralCohesion(4, 2)).isCloseTo(2.0 / 6.0, within(1e-9));
        assertThat(StructuralSignals.structuralCohesion(4, 0)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("cluster factor is 1 with no external references and falls as they grow")
    void clusterFactor_boundary() {
        assertThat(StructuralSignals.clusterFactor(3, 4, 0)).isEqualTo(1.0);
        assertThat(StructuralSignals.clusterFactor(3, 4, 8)).isCloseTo(0.5, within(1e-9));
        assertThat(StructuralSignals.clusterFactor(1, 0, 0)).isEqualTo(1.0);
        assertThat(StructuralSignals.clusterFactor(1, 0, 3)).isEqualTo(0.0);
    }

    // ─── Enrichment ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("enrich fills every signal from raw counts and keeps values the collector set")
    void enrich_fillsFromRawCounts() {
        StaticMetrics sm = StructuralSignals.enrich(twoServices());

        ServiceStaticMetrics a = sm.getServices().get("a");
        assertThat(a.getStructuralCohesion()).isCloseTo(4.0 / 4.0, within(1e-9));   // N=3, μ=4
        assertThat(a.getClusterFactor()).isCloseTo(8.0 / (8.0 + 2.0), within(1e-9)); // 1 out + 1 in
        assertThat(a.getCouplingDensity()).isCloseTo(2.0 / (3 * 1 + 2), within(1e-9));
        assertThat(a.getReplicaDensity()).isCloseTo(1.0 / 3.0, within(1e-9));
        assertThat(a.getEventEdgeDensity()).isCloseTo(1.0 / 3.0, within(1e-9));
        assertThat(a.getLocalStepRatio()).isCloseTo(2.0 / 3.0, within(1e-9));       // 3 steps, 1 cross
        assertThat(a.getAvgDistributedSteps()).isEqualTo(3.0);
        assertThat(a.getFunctionalCohesion()).isCloseTo(2.0 / 3.0, within(1e-9));   // touches 2 of 3

        assertThat(sm.getCouplingDensity()).isCloseTo(2.0 / (1 * 5), within(1e-9));
        assertThat(sm.getLocalStepRatio()).isCloseTo(2.0 / 3.0, within(1e-9));
        assertThat(sm.getM2Max()).isCloseTo((3 + 2) / (2.0 * 3 * 2), within(1e-9));
        assertThat(sm.getStructuralCohesion()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a service no functionality reaches has no functional cohesion rather than a zero")
    void enrich_unreachedServiceHasNullFunctionalCohesion() {
        StaticMetrics sm = StructuralSignals.enrich(twoServices());
        assertThat(sm.getServices().get("b").getFunctionalCohesion()).isNull();
        assertThat(sm.getServices().get("b").getLocalStepRatio()).isNull();
    }

    @Test
    @DisplayName("removing a service that holds no entities changes no system signal")
    void enrich_entityWeightingIgnoresEmptyServices() {
        StaticMetrics base = StructuralSignals.enrich(twoServices());
        Map<String, ServiceStaticMetrics> withEmpty = new LinkedHashMap<>(twoServices().getServices());
        withEmpty.put("exception", ServiceStaticMetrics.builder().serviceName("exception")
                .entityCount(0).externalRelations(Map.of()).build());
        StaticMetrics padded = StructuralSignals.enrich(twoServices().toBuilder().services(withEmpty).build());

        assertThat(padded.getStructuralCohesion()).isEqualTo(base.getStructuralCohesion());
        assertThat(padded.getClusterFactor()).isEqualTo(base.getClusterFactor());
        assertThat(padded.getFunctionalCohesion()).isEqualTo(base.getFunctionalCohesion());
        assertThat(padded.getReplicaDensity()).isEqualTo(base.getReplicaDensity());
    }

    @Test
    @DisplayName("enrich keeps a collector-computed value instead of recomputing it")
    void enrich_keepsExistingValues() {
        StaticMetrics sm = twoServices();
        sm.getServices().put("a", sm.getServices().get("a").toBuilder().functionalCohesion(0.9).build());
        assertThat(StructuralSignals.enrich(sm).getServices().get("a").getFunctionalCohesion()).isEqualTo(0.9);
    }

    // ─── Fixtures ────────────────────────────────────────────────────────────

    /**
     * a: 3 entities in a complete tree, one replica of b, one subscription to b's events, one
     * workflow of 3 steps of which 1 sends to b. b: 2 entities, one replica of a, no workflow.
     */
    static StaticMetrics twoServices() {
        Map<String, ServiceStaticMetrics> services = new LinkedHashMap<>();
        services.put("a", ServiceStaticMetrics.builder()
                .serviceName("a").entityCount(3).internalRelations(4)
                .entityNames(List.of("A", "AMember", "AB"))
                .externalRelations(Map.of("b", 1))
                .replicaEntities(Map.of("B", List.of("AB")))
                .eventSubscriptions(Map.of("b", List.of("UpdateBEvent")))
                .build());
        services.put("b", ServiceStaticMetrics.builder()
                .serviceName("b").entityCount(2).internalRelations(2)
                .entityNames(List.of("B", "BA"))
                .externalRelations(Map.of("a", 1))
                .replicaEntities(Map.of("A", List.of("BA")))
                .build());
        FunctionalityInfo f = FunctionalityInfo.builder()
                .name("DoAFunctionalitySagas").ownerService("a")
                .touchedServices(List.of("a", "b")).stepCount(3)
                .steps(List.of(
                        step(1, List.of()),
                        step(2, List.of(command("GetBCommand", "b"))),
                        step(3, List.of(command("UpdateACommand", "a")))))
                .touchedEntities(Map.of("a", List.of("A", "AMember")))
                .build();
        return StaticMetrics.builder().services(services).serviceCount(2)
                .functionalities(List.of(f)).build();
    }

    static FunctionalityInfo.WorkflowStep step(int index, List<FunctionalityInfo.SentCommand> commands) {
        return FunctionalityInfo.WorkflowStep.builder().index(index).name("s" + index)
                .dependsOn(List.of()).registered(true).commands(commands).build();
    }

    static FunctionalityInfo.SentCommand command(String name, String target) {
        return FunctionalityInfo.SentCommand.builder().name(name).targetService(target).access("read").build();
    }
}
