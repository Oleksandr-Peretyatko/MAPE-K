package org.anonymous.msrefactor.rt.knowledge.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.anonymous.msrefactor.rt.knowledge.persistence.CodeVersionEntity;
import org.anonymous.msrefactor.rt.knowledge.persistence.CodeVersionRepository;
import org.anonymous.msrefactor.rt.monitor.FunctionalityInfo;
import org.anonymous.msrefactor.rt.monitor.InterServiceDependency;
import org.anonymous.msrefactor.rt.monitor.InvariantInfo;
import org.anonymous.msrefactor.rt.monitor.ProjectStructure;
import org.anonymous.msrefactor.rt.monitor.ServiceStaticMetrics;
import org.anonymous.msrefactor.rt.monitor.StaticMetrics;
import org.anonymous.msrefactor.rt.monitor.VersionAnalysisResult;
import org.anonymous.msrefactor.rt.monitor.collector.StaticAnalysisCollector;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regrouping a version's static analysis around its aggregates.
 *
 * <p>The collector reports per service; this is where the same facts become "what is inside this
 * aggregate". The fixture is the real {@code tournament}: its composition forest with the level-2
 * child, its twelve invariants, and its replica classes — because the two failure modes worth
 * guarding are both shape errors that invented data hides. A nested member rendered at root level
 * looks plausible, and an entity dropped for having no parent looks like an aggregate that is simply
 * smaller than it is.
 */
class ProjectStructureServiceTest {

    private CodeVersionRepository repository;
    private StaticAnalysisCollector collector;
    private ProjectStructureService service;

    private static final String VERSION_ID = "ver-1";

    @BeforeEach
    void setUp() {
        repository = mock(CodeVersionRepository.class);
        collector  = mock(StaticAnalysisCollector.class);
        service    = new ProjectStructureService(repository, collector);

        when(repository.findById(VERSION_ID)).thenReturn(Optional.of(CodeVersionEntity.builder()
                .versionId(VERSION_ID).versionNumber(2).versionLabel("v2")
                .gitCommitSha("aa11bb22deadbeef").build()));
    }

    private void collectorReturns(Map<String, ServiceStaticMetrics> services) {
        collectorReturns(services, null);
    }

    private void collectorReturns(Map<String, ServiceStaticMetrics> services,
                                  List<FunctionalityInfo> functionalities) {
        when(collector.collectCommit(anyString(), anyString())).thenReturn(
                VersionAnalysisResult.builder()
                        .version("v2")
                        .metrics(StaticMetrics.builder()
                                .services(services).serviceCount(services.size())
                                .functionalities(functionalities).build())
                        .build());
    }

    private static InvariantInfo inv(String name, List<String> members, List<String> rootScalars) {
        return InvariantInfo.builder()
                .name(name).declaredIn("Tournament").kind(InvariantInfo.Kind.METHOD)
                .memberEntities(members).rootScalars(rootScalars)
                .build();
    }

    private ServiceStaticMetrics tournament() {
        return ServiceStaticMetrics.builder()
                .serviceName("tournament")
                .entityNames(List.of("Tournament", "TournamentCreator", "TournamentParticipant",
                        "TournamentParticipantQuizAnswer", "TournamentCourseExecution",
                        "TournamentTopic", "TournamentQuiz"))
                .rootNames(List.of("Tournament"))
                .entityCount(7)
                .compositions(Map.of(
                        "Tournament", List.of("TournamentCreator", "TournamentParticipant",
                                "TournamentCourseExecution", "TournamentTopic", "TournamentQuiz"),
                        "TournamentParticipant", List.of("TournamentParticipantQuizAnswer")))
                .invariants(List.of(
                        inv("invariantUniqueParticipant", List.of("TournamentParticipant"), List.of()),
                        inv("invariantMustHaveOneTopic", List.of("TournamentTopic"), List.of())))
                .crossServiceEntities(Map.of(
                        "execution", List.of("TournamentCourseExecution"),
                        "user", List.of("TournamentCreator", "TournamentParticipant")))
                // Keyed by aggregate root, and read from @Replica rather than from which foreign
                // services a class happens to name.
                .replicaEntities(Map.of(
                        "Execution", List.of("TournamentCourseExecution"),
                        "User", List.of("TournamentCreator", "TournamentParticipant")))
                .publishedEvents(Map.of(
                        "DeleteCourseExecutionEvent", "TournamentService.removeTournament"))
                .eventSubscriptions(Map.of("user", List.of("UpdateStudentNameEvent")))
                .interServiceDependencies(Map.of(
                        "user", InterServiceDependency.builder()
                                .targetService("user").structuralRefs(2)
                                .eventTypes(List.of("UpdateStudentNameEvent", "AnonymizeStudentEvent"))
                                .functionalityNames(List.of("AddParticipantFunctionalitySagas"))
                                .totalWeight(5).build()))
                .build();
    }

    private ServiceStaticMetrics user() {
        return ServiceStaticMetrics.builder()
                .serviceName("user")
                .entityNames(List.of("User"))
                .rootNames(List.of("User"))
                .entityCount(1)
                .compositions(Map.of())
                .invariants(List.of(InvariantInfo.builder()
                        .name("deletedState").declaredIn("User").kind(InvariantInfo.Kind.METHOD)
                        .memberEntities(List.of()).rootScalars(List.of("active")).readsState(true)
                        .build()))
                .build();
    }

    // ─── The shape of an aggregate ──────────────────────────────────────────────

    @Test
    @DisplayName("a nested member hangs off its parent, not off the root")
    void compositionTreeKeepsItsDepth() {
        collectorReturns(Map.of("tournament", tournament()));

        ProjectStructure.AggregateStructure agg = aggregate("Tournament");

        assertThat(agg.getEntityCount()).isEqualTo(7);
        assertThat(agg.getRoot().getName()).isEqualTo("Tournament");
        assertThat(agg.getRoot().getChildren()).extracting(ProjectStructure.EntityNode::getName)
                .containsExactlyInAnyOrder("TournamentCreator", "TournamentParticipant",
                        "TournamentCourseExecution", "TournamentTopic", "TournamentQuiz");

        ProjectStructure.EntityNode participant = child(agg.getRoot(), "TournamentParticipant");
        assertThat(participant.getChildren()).extracting(ProjectStructure.EntityNode::getName)
                .as("the level-2 child cannot be separated from its parent, and must not read as a sibling")
                .containsExactly("TournamentParticipantQuizAnswer");
    }

    @Test
    @DisplayName("a replica class says which aggregate it holds a copy of")
    void replicasAreLabelled() {
        collectorReturns(Map.of("tournament", tournament()));

        ProjectStructure.EntityNode root = aggregate("Tournament").getRoot();

        assertThat(child(root, "TournamentCourseExecution").getReplicaOf()).isEqualTo("Execution");
        assertThat(child(root, "TournamentCreator").getReplicaOf()).isEqualTo("User");
        assertThat(root.getReplicaOf()).as("the root is nobody's copy").isNull();
    }

    @Test
    @DisplayName("a class the source merely names is not labelled a replica of it")
    void aMentionIsNotAReplica() {
        // crossServiceEntities records every foreign service a class mentions; inverting it made
        // CourseExecutionStudent — a copy of User — read as a replica of tournament, because its
        // equals names TournamentParticipant. Only the declaration counts now.
        ServiceStaticMetrics mentionsButDoesNotCopy = tournament().toBuilder()
                .crossServiceEntities(Map.of(
                        "execution", List.of("TournamentCourseExecution"),
                        "answer", List.of("TournamentCreator")))
                .replicaEntities(Map.of("User", List.of("TournamentCreator")))
                .build();
        collectorReturns(Map.of("tournament", mentionsButDoesNotCopy));

        ProjectStructure.EntityNode root = aggregate("Tournament").getRoot();
        assertThat(child(root, "TournamentCreator").getReplicaOf()).isEqualTo("User");
        assertThat(child(root, "TournamentCourseExecution").getReplicaOf()).isNull();
    }

    @Test
    @DisplayName("a snapshot that predates @Replica labels nothing rather than guessing")
    void unknownReplicasAreNotInvented() {
        ServiceStaticMetrics older = tournament().toBuilder().replicaEntities(null).build();
        collectorReturns(Map.of("tournament", older));

        ProjectStructure.EntityNode root = aggregate("Tournament").getRoot();
        assertThat(child(root, "TournamentCourseExecution").getReplicaOf()).isNull();
    }

    @Test
    @DisplayName("the aggregate carries the rules with their footprint, not just their names")
    void invariantsKeepTheirFootprint() {
        collectorReturns(Map.of("tournament", tournament()));

        assertThat(aggregate("Tournament").getIntraInvariants())
                .extracting(InvariantInfo::getName)
                .containsExactly("invariantUniqueParticipant", "invariantMustHaveOneTopic");
        assertThat(aggregate("Tournament").getIntraInvariants().get(0).getMemberEntities())
                .containsExactly("TournamentParticipant");
    }

    // ─── Edges ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an outgoing edge keeps the three coupling dimensions apart and names the target aggregate")
    void outgoingRelationsAreDimensioned() {
        collectorReturns(Map.of("tournament", tournament(), "user", user()));

        ProjectStructure.AggregateRelation rel = aggregate("Tournament").getOutgoing().get(0);

        assertThat(rel.getToService()).isEqualTo("user");
        assertThat(rel.getToAggregate()).as("the far end is an aggregate, not a package name").isEqualTo("User");
        assertThat(rel.getStructuralRefs()).isEqualTo(2);
        assertThat(rel.getViaEntities())
                .containsExactlyInAnyOrder("TournamentCreator", "TournamentParticipant");
        assertThat(rel.getEvents())
                .containsExactly("UpdateStudentNameEvent", "AnonymizeStudentEvent");
        assertThat(rel.getFunctionalities()).containsExactly("AddParticipantFunctionalitySagas");
    }

    @Test
    @DisplayName("the same edge shows up on the other aggregate as incoming")
    void incomingRelationsAreDerivedByInversion() {
        collectorReturns(Map.of("tournament", tournament(), "user", user()));

        ProjectStructure.AggregateStructure userAgg = aggregate("User");

        assertThat(userAgg.getOutgoing()).isEmpty();
        assertThat(userAgg.getIncoming()).singleElement().satisfies(rel -> {
            assertThat(rel.getToService()).isEqualTo("tournament");
            assertThat(rel.getEvents()).contains("UpdateStudentNameEvent");
        });
    }

    // ─── Nothing is quietly dropped ─────────────────────────────────────────────

    @Test
    @DisplayName("an entity outside the forest is reported, not lost")
    void orphansAreReported() {
        ServiceStaticMetrics withOrphan = tournament().toBuilder()
                .entityNames(List.of("Tournament", "TournamentTopic", "TournamentStrayClass"))
                .compositions(Map.of("Tournament", List.of("TournamentTopic")))
                .build();
        collectorReturns(Map.of("tournament", withOrphan));

        ProjectStructure structure = service.forVersion(VERSION_ID).orElseThrow();

        assertThat(structure.getOrphanEntities()).containsKey("tournament");
        assertThat(structure.getOrphanEntities().get("tournament"))
                .containsExactly("TournamentStrayClass");
    }

    @Test
    @DisplayName("a snapshot with no composition data still yields the aggregate, just without a tree")
    void survivesMissingCompositions() {
        ServiceStaticMetrics old = ServiceStaticMetrics.builder()
                .serviceName("course")
                .entityNames(List.of("Course"))
                .rootNames(List.of("Course"))
                .entityCount(1)
                .build();
        collectorReturns(Map.of("course", old));

        ProjectStructure.AggregateStructure agg = aggregate("Course");

        assertThat(agg.getEntityCount()).isEqualTo(1);
        assertThat(agg.getRoot().getChildren()).isNull();
        assertThat(agg.getIntraInvariants()).isEmpty();
    }

    @Test
    @DisplayName("an unknown version is absent, not an empty structure")
    void unknownVersionIsEmpty() {
        when(repository.findById("nope")).thenReturn(Optional.empty());
        assertThat(service.forVersion("nope")).isEmpty();
    }

    // ─── Where the source came from ─────────────────────────────────────────────

    @Test
    @DisplayName("resolution by commit is reported, so the reader can place what they are looking at")
    void resolvedFromNamesTheCommit() {
        collectorReturns(Map.of("tournament", tournament()));

        assertThat(service.forVersion(VERSION_ID).orElseThrow().getResolvedFrom())
                .isEqualTo("commit aa11bb22");
    }

    @Test
    @DisplayName("a version whose commit yields nothing falls back to the working tree")
    void fallsBackWhenTheCommitIsUnreadable() {
        when(collector.collectCommit(anyString(), anyString())).thenReturn(
                VersionAnalysisResult.builder().version("v2")
                        .metrics(StaticMetrics.builder().services(Map.of()).build()).build());
        when(collector.hasTag("v2")).thenReturn(false);
        when(collector.collectVersion("current")).thenReturn(
                VersionAnalysisResult.builder().version("current")
                        .metrics(StaticMetrics.builder()
                                .services(Map.of("tournament", tournament())).build()).build());

        ProjectStructure structure = service.forVersion(VERSION_ID).orElseThrow();

        assertThat(structure.getResolvedFrom()).isEqualTo("working tree");
        assertThat(structure.getAggregates()).hasSize(1);
    }

    // ─── Helpers ────────────────────────────────────────────────────────────────

    private ProjectStructure.AggregateStructure aggregate(String name) {
        return service.forVersion(VERSION_ID).orElseThrow().getAggregates().stream()
                .filter(a -> a.getName().equals(name))
                .findFirst().orElseThrow(() -> new AssertionError("no aggregate named " + name));
    }

    private ProjectStructure.EntityNode child(ProjectStructure.EntityNode parent, String name) {
        return parent.getChildren().stream()
                .filter(c -> c.getName().equals(name))
                .findFirst().orElseThrow(() -> new AssertionError("no child named " + name));
    }

    // ─── Functionalities and event flow ─────────────────────────────────────────

    @Test
    @DisplayName("an aggregate carries the operations its service owns, named as the docs name them")
    void functionalitiesAreGroupedByOwner() {
        collectorReturns(Map.of("tournament", tournament(), "user", user()),
                List.of(
                        func("AddParticipantFunctionalitySagas", "tournament", List.of("execution", "user")),
                        func("CreateTournamentFunctionalitySagas", "tournament", List.of("tournament")),
                        func("DeleteUserFunctionalitySagas", "user", List.of("user"))));

        List<ProjectStructure.FunctionalityRef> owned = aggregate("Tournament").getFunctionalities();

        assertThat(owned).extracting(ProjectStructure.FunctionalityRef::getOperation)
                .as("the Functionality suffix is dropped, as the domain model writes them")
                .containsExactly("AddParticipant", "CreateTournament");
        // touchedServices are service names; the view speaks aggregates, and the service's own is not
        // a dependency on anything.
        assertThat(owned.get(0).getTouchedAggregates()).containsExactly("User");
    }

    @Test
    @DisplayName("a published event names the aggregates that subscribe to it")
    void publishedEventsNameTheirConsumers() {
        collectorReturns(Map.of("tournament", tournament(), "user", user()));

        List<ProjectStructure.EventFlow> published = aggregate("Tournament").getPublishes();

        assertThat(published).singleElement().satisfies(e -> {
            assertThat(e.getEventType()).isEqualTo("DeleteCourseExecutionEvent");
            assertThat(e.getPublishedIn()).isEqualTo("TournamentService.removeTournament");
            assertThat(e.getConsumerAggregates()).as("nobody subscribes to it here").isNull();
        });
    }

    @Test
    @DisplayName("a consumed event whose publisher nothing registers is left blank, not guessed")
    void anUnpublishedEventHasNoPublisher() {
        // The subscription's anchor could supply an answer. That inference is precisely what reading
        // the registerEvent call site replaces, so it must not leak back in as a fallback.
        collectorReturns(Map.of("tournament", tournament(), "user", user()));

        List<ProjectStructure.EventFlow> consumed = aggregate("Tournament").getConsumes();

        assertThat(consumed).singleElement().satisfies(e -> {
            assertThat(e.getEventType()).isEqualTo("UpdateStudentNameEvent");
            assertThat(e.getPublisherAggregate()).isNull();
            assertThat(e.getPublishedIn()).isNull();
        });
    }

    // ─── The workflow, step by step ─────────────────────────────────────────────

    @Test
    @DisplayName("steps reach the view with their order intact and their commands named as aggregates")
    void stepsCarryTheirOrderAndTargets() {
        FunctionalityInfo addParticipant = func(
                "AddParticipantFunctionalitySagas", "tournament", List.of("execution", "user"))
                .toBuilder()
                .steps(List.of(
                        step(1, "getUserStep", null, 0,
                                sent("GetStudentByExecutionIdAndUserIdCommand", "user", "READ")),
                        step(2, "addParticipantStep", List.of("getUserStep"), 1,
                                sent("AddParticipantCommand", "tournament", "WRITE"))))
                .build();
        collectorReturns(Map.of("tournament", tournament(), "user", user()), List.of(addParticipant));

        List<ProjectStructure.WorkflowStepRef> steps =
                aggregate("Tournament").getFunctionalities().get(0).getSteps();

        assertThat(steps).extracting(ProjectStructure.WorkflowStepRef::getName)
                .containsExactly("getUserStep", "addParticipantStep");
        // The wave, not the index, is what says these two cannot be swapped.
        assertThat(steps).extracting(ProjectStructure.WorkflowStepRef::getWave).containsExactly(0, 1);
        assertThat(steps.get(1).getDependsOn()).containsExactly("getUserStep");
        assertThat(steps.get(0).getSends()).singleElement().satisfies(c -> {
            assertThat(c.getToAggregate()).as("service names never reach the view").isEqualTo("User");
            assertThat(c.getAccess()).isEqualTo("READ");
        });
    }

    @Test
    @DisplayName("a compensation is not measured against the handler index")
    void compensationsAreNeverFlaggedUnhandled() {
        // The bare Command is meant to fall through every handler's default arm: SagaCommandHandler
        // releases the lock after the dispatch returns. Checking it against the cases would paint
        // all 26 compensation sites red for doing exactly what they are supposed to do.
        FunctionalityInfo f = func("RemoveStudentFunctionalitySagas", "tournament", List.of("user"))
                .toBuilder()
                .steps(List.of(FunctionalityInfo.WorkflowStep.builder()
                        .index(1).name("getOldStep").wave(0).registered(true)
                        .compensation(List.of(sent("Command", "user", null)))
                        .build()))
                .build();
        collectorReturns(Map.of("tournament", tournament(), "user", user()), List.of(f));

        ProjectStructure.WorkflowStepRef step =
                aggregate("Tournament").getFunctionalities().get(0).getSteps().get(0);

        assertThat(step.getCompensates()).singleElement().satisfies(c -> {
            assertThat(c.getName()).isEqualTo("Command");
            assertThat(c.getToAggregate()).isEqualTo("User");
            assertThat(c.isUnhandled()).isFalse();
        });
        assertThat(step.getSends()).as("a step that only compensates sends nothing").isNull();
    }

    @Test
    @DisplayName("a functionality the parser found no step in reports none rather than an empty list")
    void anUnparsedWorkflowHasNoSteps() {
        // Absent and empty mean different things downstream: absent is "not read", empty would read
        // as "a workflow with no steps", which no functionality has.
        collectorReturns(Map.of("tournament", tournament(), "user", user()),
                List.of(func("AddParticipantFunctionalitySagas", "tournament", List.of("user"))));

        assertThat(aggregate("Tournament").getFunctionalities().get(0).getSteps()).isNull();
    }

    private static FunctionalityInfo.WorkflowStep step(int index, String name, List<String> dependsOn,
                                                       int wave, FunctionalityInfo.SentCommand... sent) {
        return FunctionalityInfo.WorkflowStep.builder()
                .index(index).name(name).dependsOn(dependsOn).wave(wave).registered(true)
                .commands(List.of(sent))
                .build();
    }

    private static FunctionalityInfo.SentCommand sent(String name, String target, String access) {
        return FunctionalityInfo.SentCommand.builder()
                .name(name).targetService(target).access(access).build();
    }

    private FunctionalityInfo func(String name, String owner, List<String> touched) {
        return FunctionalityInfo.builder()
                .name(name).ownerService(owner).protocol("sagas")
                .stepCount(2).touchedServices(touched)
                .build();
    }
}
