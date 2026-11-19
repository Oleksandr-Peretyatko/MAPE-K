package org.anonymous.msrefactor.rt.analyze.selector;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.anonymous.msrefactor.rt.analyze.StructuralEvidence;
import org.anonymous.msrefactor.rt.monitor.CommandInfo;
import org.anonymous.msrefactor.rt.monitor.ServiceStaticMetrics;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The selector's own job: adapting a snapshot to {@code OwnershipForest} and phrasing the verdict.
 *
 * <p>Four services are enough for all four shapes — an ownership edge, a sibling pair, an ambiguous
 * link and an unrelated root. The verdict rules themselves are covered by {@code OwnershipForestTest}.
 */
class OwnershipSelectorTest {

    private final OwnershipSelector selector = new OwnershipSelector();

    private static ServiceStaticMetrics svc(String name, String root,
                                            Map<String, List<String>> singleValued,
                                            Map<String, List<String>> replicas,
                                            List<String> posts, List<String> commands) {
        return ServiceStaticMetrics.builder()
                .serviceName(name).entityCount(3).rootNames(List.of(root))
                .singleValuedMembers(singleValued).replicaEntities(replicas)
                .postEndpoints(posts)
                .handledCommands(commands.stream().map(c -> CommandInfo.builder().name(c).build()).toList())
                .externalRelations(Map.of())
                .build();
    }

    private OwnershipSelector.Index index() {
        Map<String, ServiceStaticMetrics> perService = new LinkedHashMap<>();
        perService.put("course", svc("course", "Course", Map.of(), Map.of(), List.of(), List.of()));
        perService.put("user", svc("user", "User", Map.of(), Map.of(),
                List.of("/users/create"), List.of()));
        perService.put("topic", svc("topic", "Topic",
                Map.of("Topic", List.of("TopicCourse")), Map.of("Course", List.of("TopicCourse")),
                List.of("/courses/{courseAggregateId}/create"), List.of("FindTopicsByCourseIdCommand")));
        perService.put("question", svc("question", "Question",
                Map.of("Question", List.of("QuestionCourse")),
                Map.of("Course", List.of("QuestionCourse"), "Topic", List.of("QuestionTopic")),
                List.of("/courses/{courseAggregateId}/questions/create"),
                List.of("FindQuestionsByCourseAggregateIdCommand")));
        perService.put("execution", svc("execution", "Execution",
                Map.of("Execution", List.of("CourseExecutionCourse")),
                Map.of("Course", List.of("CourseExecutionCourse")),
                List.of("/executions/create"), List.of("GetCourseExecutionsByUserIdCommand")));

        return selector.index(StructuralEvidence.builder().perService(perService).build());
    }

    @Test
    @DisplayName("the owner survives, whichever order the pair is given in")
    void directionIsNormalised() {
        OwnershipSelector.Index ix = index();

        assertThat(selector.mergeable(ix, "topic", "course")).get()
                .satisfies(p -> {
                    assertThat(p.owner()).isEqualTo("course");
                    assertThat(p.child()).isEqualTo("topic");
                });
        assertThat(selector.mergeable(ix, "course", "topic")).get()
                .satisfies(p -> assertThat(p.owner()).isEqualTo("course"));
    }

    @Test
    @DisplayName("the five shapes that are not an ownership edge")
    void nonEdges() {
        OwnershipSelector.Index ix = index();

        assertThat(selector.mergeable(ix, "question", "topic")).isEmpty();
        assertThat(selector.mergeable(ix, "topic", "user")).isEmpty();
        assertThat(selector.mergeable(ix, "course", "execution")).isEmpty();
        assertThat(selector.mergeable(ix, "topic", "topic")).isEmpty();
        assertThat(selector.mergeable(ix, "topic", null)).isEmpty();
    }

    @Test
    @DisplayName("the next step is one hop, never the whole chain")
    void nextStepIsOneHop() {
        OwnershipSelector.Index ix = index();

        // Siblings: the service the pair was asked about moves first; topic's own hop comes later,
        // from the snapshot this one produces.
        assertThat(selector.chainFor(ix, "question", "topic")).containsExactly("course<-question");
        assertThat(selector.chainFor(ix, "topic", "question")).containsExactly("course<-topic");

        assertThat(selector.chainFor(ix, "course", "topic")).isEmpty();   // already an ownership edge
        assertThat(selector.chainFor(ix, "topic", "user")).isEmpty();     // different trees
    }

    @Test
    @DisplayName("an ancestor pair gets the descendant's hop, not a refusal")
    void ancestorPairGetsItsHop() {
        OwnershipSelector.Index ix = index();

        // course is execution's own O1 parent, so the hop is the pair itself — and it is not feasible.
        assertThat(selector.nextStep(ix, "course", "execution")).get()
                .satisfies(s -> {
                    assertThat(s.notation()).isEqualTo("course<-execution");
                    assertThat(s.feasible()).isFalse();
                });
    }

    @Test
    @DisplayName("each refusal names its own cause")
    void blockReasons() {
        OwnershipSelector.Index ix = index();

        assertThat(selector.blockReason(ix, "question", "topic"))
                .contains("next step", "course<-question", "re-evaluated");
        assertThat(selector.blockReason(ix, "topic", "user"))
                .contains("no ownership relation");
        assertThat(selector.blockReason(ix, "course", "execution"))
                .contains("ambiguous", "created and queried independently");
        assertThat(selector.blockReason(ix, "execution", "topic"))
                .contains("next step course<-execution is itself blocked", "ambiguous");
        assertThat(selector.blockReason(null, "a", "b"))
                .contains("re-run Monitor");
    }

    @Test
    @DisplayName("the confirmed edges of the snapshot, owner first")
    void ownedEdges() {
        assertThat(selector.ownedEdges(index()))
                .extracting(e -> e.ownerService() + "<-" + e.childService())
                .containsExactlyInAnyOrder("course<-topic", "course<-question");
    }
}
