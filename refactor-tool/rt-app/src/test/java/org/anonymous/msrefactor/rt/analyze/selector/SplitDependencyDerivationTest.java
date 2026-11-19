package org.anonymous.msrefactor.rt.analyze.selector;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.anonymous.msrefactor.rt.analyze.AnalysisConfig;
import org.anonymous.msrefactor.rt.analyze.CandidateOperation;
import org.anonymous.msrefactor.rt.analyze.Hotspot;
import org.anonymous.msrefactor.rt.analyze.HotspotType;
import org.anonymous.msrefactor.rt.analyze.OperationType;
import org.anonymous.msrefactor.rt.analyze.QualityAttribute;
import org.anonymous.msrefactor.rt.analyze.StructuralEvidence;
import org.anonymous.msrefactor.rt.analyze.generator.CandidateGenerator;
import org.anonymous.msrefactor.rt.monitor.InvariantInfo;
import org.anonymous.msrefactor.rt.monitor.ServiceStaticMetrics;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sub-operations a SPLIT_AGGREGATE derives from its own cost.
 *
 * <p>{@code dependencies} had two channels on paper and one in practice: a reactive one, filled when
 * a skill hit a prerequisite during a real execution, and a derived one that every construction site
 * left as {@code List.of()}. The cut already knows which invariants it splits and what each of them
 * reads, so the chain that has to run first is deducible — and which sub-operation resolves a rule
 * depends on <i>what</i> makes it cross, not on how many rules cross.
 */
class SplitDependencyDerivationTest {

    private static InvariantInfo inv(String name, List<String> members, List<String> rootScalars,
                                     boolean readsState, boolean readsPrev) {
        return InvariantInfo.builder()
                .name(name).declaredIn("Tournament").kind(InvariantInfo.Kind.METHOD)
                .memberEntities(members).rootScalars(rootScalars)
                .readsState(readsState).readsPrev(readsPrev)
                .build();
    }

    private List<String> dependenciesFor(ServiceStaticMetrics sm, String service) {
        CandidateGenerator generator = new CandidateGenerator(
                new AggregatePartitionAnalyzer(),
                new ReplicaCandidateAnalyzer(),
                new InvariantImpactAnalyzer(),
                new ValidationPlanBuilder());

        Hotspot hotspot = Hotspot.builder()
                .type(HotspotType.LOW_COHESION)
                .qualityAttribute(QualityAttribute.MAINTAINABILITY)
                .affectedServices(List.of(service))
                .severity(0.8)
                .evidence("test fixture")
                .build();

        StructuralEvidence structural = StructuralEvidence.builder()
                .perService(Map.of(service, sm))
                .serviceCount(1)
                .build();

        List<CandidateOperation> splits = generator
                .generate(List.of(hotspot), structural, null, null, AnalysisConfig.builder().build(), null)
                .stream()
                .filter(c -> c.getOperation() == OperationType.SPLIT_AGGREGATE)
                .toList();

        assertThat(splits).as("fixture must produce exactly one split candidate").hasSize(1);
        return splits.get(0).getDependencies();
    }

    @Test
    @DisplayName("a rule that only lacks a root scalar needs the scalar replicated, and stays intra")
    void rootScalarYieldsANewEventAndNoInterInvariant() {
        // Participants cost 1 (one rule also reads startTime); topics cost 2. Participants wins.
        ServiceStaticMetrics sm = ServiceStaticMetrics.builder()
                .serviceName("tournament")
                .entityNames(List.of("Tournament", "TournamentParticipant", "TournamentTopic"))
                .rootNames(List.of("Tournament"))
                .compositions(Map.of("Tournament",
                        List.of("TournamentParticipant", "TournamentTopic")))
                .invariants(List.of(
                        inv("invariantParticipantsEnrolledBeforeStarTime",
                                List.of("TournamentParticipant"), List.of("startTime"), false, false),
                        inv("invariantTopicsFrozenAfterStart",
                                List.of("TournamentTopic"), List.of("startTime"), false, false),
                        inv("invariantTopicCountAtMost30",
                                List.of("TournamentTopic"), List.of("numberOfQuestions"), false, false)))
                .entityCount(3)
                .build();

        List<String> deps = dependenciesFor(sm, "tournament");

        assertThat(deps).singleElement().satisfies(d -> {
            assertThat(d).startsWith(
                    "/new-event UpdateTournamentStartTimeEvent Tournament TournamentSatellite");
            assertThat(d).contains("invariantParticipantsEnrolledBeforeStarTime");
        });
        assertThat(deps).as("a replicated scalar leaves the rule a plain verifyInvariants() check")
                .noneMatch(d -> d.startsWith("/inter-invariant"));
    }

    @Test
    @DisplayName("a rule reading an entity on the other side needs the event and the inter-invariant")
    void crossMemberYieldsBothOperations() {
        // Creator costs 2 (consistency + its own scalar rule), participants 1. Participants wins,
        // and what it still reads — TournamentCreator — is a whole collection, not a field.
        ServiceStaticMetrics sm = ServiceStaticMetrics.builder()
                .serviceName("tournament")
                .entityNames(List.of("Tournament", "TournamentParticipant", "TournamentCreator"))
                .rootNames(List.of("Tournament"))
                .compositions(Map.of("Tournament",
                        List.of("TournamentParticipant", "TournamentCreator")))
                .invariants(List.of(
                        inv("invariantCreatorParticipantConsistency",
                                List.of("TournamentParticipant", "TournamentCreator"),
                                List.of(), false, false),
                        inv("invariantCreatorIsNotAnonymous",
                                List.of("TournamentCreator"), List.of("startTime"), false, false)))
                .entityCount(3)
                .build();

        List<String> deps = dependenciesFor(sm, "tournament");

        assertThat(deps).hasSize(2);
        assertThat(deps.get(0)).startsWith(
                "/new-event UpdateTournamentCreatorEvent Tournament TournamentSatellite");
        assertThat(deps.get(1)).startsWith(
                "/inter-invariant TournamentSatellite invariantCreatorParticipantConsistency");
    }

    @Test
    @DisplayName("a getPrev() rule derives nothing — it is duplicated inside the split itself")
    void temporalRuleDerivesNoSubOperation() {
        // Participants straddle once, temporally; creator straddles twice. Participants wins.
        ServiceStaticMetrics sm = ServiceStaticMetrics.builder()
                .serviceName("tournament")
                .entityNames(List.of("Tournament", "TournamentParticipant", "TournamentCreator"))
                .rootNames(List.of("Tournament"))
                .compositions(Map.of("Tournament",
                        List.of("TournamentParticipant", "TournamentCreator")))
                .invariants(List.of(
                        inv("invariantParticipantsFinalAfterStart",
                                List.of("TournamentParticipant"), List.of("startTime"), false, true),
                        inv("invariantCreatorIsNotAnonymous",
                                List.of("TournamentCreator"), List.of("startTime"), false, false),
                        inv("invariantCreatorMatchesCourse",
                                List.of("TournamentCreator"), List.of("courseId"), false, false)))
                .entityCount(3)
                .build();

        assertThat(dependenciesFor(sm, "tournament"))
                .as("each side keeps its own version chain, so there is nothing to prepare beforehand")
                .isEmpty();
    }

    @Test
    @DisplayName("a rule reading getState() is left as a decision, not issued as a command")
    void ownStateIsReportedAsAQuestion() {
        ServiceStaticMetrics sm = ServiceStaticMetrics.builder()
                .serviceName("tournament")
                .entityNames(List.of("Tournament", "TournamentParticipant", "TournamentCreator"))
                .rootNames(List.of("Tournament"))
                .compositions(Map.of("Tournament",
                        List.of("TournamentParticipant", "TournamentCreator")))
                .invariants(List.of(
                        inv("invariantDeleteWhenNoParticipants",
                                List.of("TournamentParticipant"), List.of(), true, false),
                        inv("invariantCreatorIsNotAnonymous",
                                List.of("TournamentCreator"), List.of("startTime"), false, false),
                        inv("invariantCreatorMatchesCourse",
                                List.of("TournamentCreator"), List.of("courseId"), false, false)))
                .entityCount(3)
                .build();

        assertThat(dependenciesFor(sm, "tournament")).singleElement().satisfies(d -> {
            assertThat(d).startsWith("Decide before splitting:");
            assertThat(d).contains("invariantDeleteWhenNoParticipants");
        });
    }

    @Test
    @DisplayName("a cut that splits no invariant derives no chain")
    void freeCutHasNoDependencies() {
        ServiceStaticMetrics sm = ServiceStaticMetrics.builder()
                .serviceName("tournament")
                .entityNames(List.of("Tournament", "TournamentParticipant", "TournamentQuiz"))
                .rootNames(List.of("Tournament"))
                .compositions(Map.of("Tournament",
                        List.of("TournamentParticipant", "TournamentQuiz")))
                .invariants(List.of(
                        inv("invariantUniqueParticipant",
                                List.of("TournamentParticipant"), List.of(), false, false)))
                .entityCount(3)
                .build();

        assertThat(dependenciesFor(sm, "tournament")).isEmpty();
    }

    @Test
    @DisplayName("two rules needing the same event ask for it once, naming both")
    void oneEventPerNeed_notPerRule() {
        ServiceStaticMetrics sm = ServiceStaticMetrics.builder()
                .serviceName("tournament")
                .entityNames(List.of("Tournament", "TournamentParticipant", "TournamentCreator"))
                .rootNames(List.of("Tournament"))
                .compositions(Map.of("Tournament",
                        List.of("TournamentParticipant", "TournamentCreator")))
                .invariants(List.of(
                        inv("invariantParticipantsEnrolledBeforeStarTime",
                                List.of("TournamentParticipant"), List.of("startTime"), false, false),
                        inv("invariantParticipantsAnswerBeforeStart",
                                List.of("TournamentParticipant"), List.of("startTime"), false, false),
                        inv("invariantCreatorIsNotAnonymous",
                                List.of("TournamentCreator"), List.of("startTime"), false, false),
                        inv("invariantCreatorMatchesCourse",
                                List.of("TournamentCreator"), List.of("courseId"), false, false),
                        inv("invariantCreatorIsEnrolled",
                                List.of("TournamentCreator"), List.of("endTime"), false, false)))
                .entityCount(3)
                .build();

        List<String> deps = dependenciesFor(sm, "tournament");

        assertThat(deps).as("one event, not one per rule that needs it").hasSize(1);
        assertThat(deps.get(0))
                .contains("invariantParticipantsEnrolledBeforeStarTime",
                          "invariantParticipantsAnswerBeforeStart");
    }
}
