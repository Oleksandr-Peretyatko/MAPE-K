package org.anonymous.msrefactor.rt.monitor.collector;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which entity is a replica, read from {@code @Replica} instead of guessed from its fields.
 *
 * <p>The guess failed twice over on one class. {@code CourseExecutionStudent} is a copy of
 * {@code User}, and it was reported as a replica of {@code tournament}: the field scan matched the
 * {@code getParticipantAggregateId()} call inside its {@code equals}, and the resolver then reached
 * {@code tournament} through its weakest step, "the declaring file imports exactly one foreign
 * service". Both fixtures below are transcribed from that class.
 */
class ReplicaDeclarationTest {

    private StaticAnalysisCollector collector;

    @BeforeEach
    void setUp() {
        collector = new StaticAnalysisCollector();
    }

    /** The real shape: a User replica whose equals reaches into a foreign aggregate's class. */
    private static final String COURSE_EXECUTION_STUDENT = """
            import org.anonymous.msrefactor.quizzes.microservices.tournament.aggregate.TournamentParticipant;

            @Entity
            @Replica("User")
            public class CourseExecutionStudent {
                private Integer userAggregateId;
                private Long userVersion;
                private String name;

                @Override
                public boolean equals(Object obj) {
                    if (!(obj instanceof TournamentParticipant)) {
                        return false;
                    }
                    TournamentParticipant tournamentParticipant = (TournamentParticipant) obj;
                    return getUserAggregateId().equals(tournamentParticipant.getParticipantAggregateId());
                }
            }
            """;

    private static final String EXECUTION_ROOT = """
            @Entity
            public class Execution extends Aggregate {
                private String acronym;
                private Set<CourseExecutionStudent> students = new HashSet<>();
            }
            """;

    private Map<String, List<String>> collect(Map<String, String> contents, List<String> entities) {
        return collector.declaredReplicas(
                Map.of("execution", contents),
                Map.<String, Collection<String>>of("execution", entities)).get("execution");
    }

    @Test
    @DisplayName("the annotation decides, not the classes the source happens to name")
    void readsTheDeclarationAndIgnoresTheEqualsReference() {
        Map<String, List<String>> replicas = collect(
                Map.of("CourseExecutionStudent", COURSE_EXECUTION_STUDENT, "Execution", EXECUTION_ROOT),
                List.of("CourseExecutionStudent", "Execution"));

        assertThat(replicas)
                .containsExactly(Map.entry("User", List.of("CourseExecutionStudent")));
    }

    @Test
    @DisplayName("a class that copies two roots keeps both, in the order written")
    void keepsEveryDeclaredRoot() {
        // QuestionTopic carries a topic and, denormalised, the course that topic belongs to.
        Map<String, List<String>> replicas = collect(Map.of("QuestionTopic", """
                @Entity
                @Replica({ "Topic", "Course" })
                public class QuestionTopic {
                    private Integer topicAggregateId;
                    private Integer courseAggregateId;
                }
                """), List.of("QuestionTopic"));

        assertThat(replicas).containsOnlyKeys("Topic", "Course");
    }

    @Test
    @DisplayName("an entity with an aggregate-id field but no annotation is not a replica")
    void anUnannotatedEntityIsALocalEntity() {
        // The old heuristic would call this a replica of question. Absence now means something.
        Map<String, List<String>> replicas = collect(Map.of(
                "QuestionAnswer", """
                        @Entity
                        public class QuestionAnswer {
                            private Integer questionAggregateId;
                        }
                        """,
                "Marker", "@Entity @Replica(\"User\") public class Marker { }"),
                List.of("QuestionAnswer", "Marker"));

        assertThat(replicas).doesNotContainKey("Question");
        assertThat(replicas.get("User")).containsExactly("Marker");
    }

    @Test
    @DisplayName("a version with no @Replica at all reports unknown, not none")
    void aVersionPredatingTheConventionReportsUnknown() {
        // Older commits the Monitor reads from git carry no annotation. Saying "no replicas" there
        // would be a claim the source does not support; null is the same "unknown" that invariants
        // and compositions already use.
        Map<String, List<String>> replicas = collect(Map.of("QuestionAnswer", """
                @Entity
                public class QuestionAnswer {
                    private Integer questionAggregateId;
                }
                """), List.of("QuestionAnswer"));

        assertThat(replicas).isNull();
    }

    @Test
    @DisplayName("a Saga/Causal variant does not duplicate the entity's declaration")
    void ignoresNonEntityVariants() {
        Map<String, List<String>> replicas = collect(Map.of(
                "CourseExecutionStudent", COURSE_EXECUTION_STUDENT,
                "SagaCourseExecutionStudent", COURSE_EXECUTION_STUDENT),
                List.of("CourseExecutionStudent"));

        assertThat(replicas.get("User")).containsExactly("CourseExecutionStudent");
    }
}
