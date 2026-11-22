package org.anonymous.msrefactor.rt.analyze.selector;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.anonymous.msrefactor.rt.knowledge.DiscoveryGraph;
import org.anonymous.msrefactor.rt.monitor.ServiceStaticMetrics;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SatelliteNamingTest {

    @Test
    @DisplayName("the default is <Aggr>Satellite, numbered past every taken name")
    void defaultName() {
        assertThat(SatelliteNaming.defaultName("tournament", Set.of())).isEqualTo("TournamentSatellite");
        assertThat(SatelliteNaming.defaultName("tournament", Set.of("tournamentsatellite")))
                .isEqualTo("TournamentSatellite2");
        assertThat(SatelliteNaming.defaultName("Tournament",
                Set.of("TournamentSatellite", "TOURNAMENTSATELLITE2"))).isEqualTo("TournamentSatellite3");
    }

    @Test
    @DisplayName("taken names come from every service, aggregate and entity known")
    void takenNames() {
        Map<String, ServiceStaticMetrics> perService = Map.of("tournament", ServiceStaticMetrics.builder()
                .serviceName("tournament")
                .entityNames(List.of("Tournament", "TournamentCreator"))
                .rootNames(List.of("Tournament"))
                .build());
        DiscoveryGraph graph = DiscoveryGraph.builder()
                .services(List.of(DiscoveryGraph.DiscoveredService.builder()
                        .name("quiz")
                        .aggregates(List.of(DiscoveryGraph.DiscoveredAggregate.builder()
                                .name("Quiz").entities(List.of("QuizQuestion")).build()))
                        .build()))
                .build();

        assertThat(SatelliteNaming.takenNames(perService, graph)).containsExactlyInAnyOrder(
                "tournament", "Tournament", "TournamentCreator", "quiz", "Quiz", "QuizQuestion");
        assertThat(SatelliteNaming.takenNames(null, null)).isEmpty();
    }
}
