package org.anonymous.msrefactor.rt.monitor.collector;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/**
 * Reference resolution has to survive the catalogue's own operations: a merge deletes a service and
 * demotes its root to a member entity, a split adds a root without adding a service. Both used to
 * make references to the affected aggregate resolve to {@code null} and disappear from σ.
 *
 * <p>The three fixtures are the quizzes index before a merge, after {@code course ← question}, and
 * after a split creating {@code TournamentParticipation}.
 */
class AggregateReferenceResolverTest {

    // ─── fixtures ─────────────────────────────────────────────────────────────

    private static final Set<String> SERVICES_8 = new LinkedHashSet<>(Set.of(
            "answer", "course", "execution", "question", "quiz", "topic", "tournament", "user"));

    /** className → owning service, for classes that {@code extends Aggregate}. */
    private static Map<String, String> roots8() {
        Map<String, String> roots = new LinkedHashMap<>();
        roots.put("QuizAnswer", "answer");
        roots.put("Course", "course");
        roots.put("Execution", "execution");
        roots.put("Question", "question");
        roots.put("Quiz", "quiz");
        roots.put("Topic", "topic");
        roots.put("Tournament", "tournament");
        roots.put("User", "user");
        return roots;
    }

    /** Every counted entity: the roots above plus the member/replica classes. */
    private static Map<String, String> entities8() {
        Map<String, String> entities = new LinkedHashMap<>(roots8());
        entities.put("AnswerCourseExecution", "answer");
        entities.put("AnswerStudent", "answer");
        entities.put("AnsweredQuiz", "answer");
        entities.put("QuestionAnswer", "answer");
        entities.put("CourseExecutionCourse", "execution");
        entities.put("CourseExecutionStudent", "execution");
        entities.put("Option", "question");
        entities.put("QuestionCourse", "question");
        entities.put("QuestionTopic", "question");
        entities.put("QuizCourseExecution", "quiz");
        entities.put("QuizQuestion", "quiz");
        entities.put("TopicCourse", "topic");
        entities.put("TournamentCourseExecution", "tournament");
        entities.put("TournamentCreator", "tournament");
        entities.put("TournamentParticipant", "tournament");
        entities.put("TournamentParticipantQuizAnswer", "tournament");
        entities.put("TournamentQuiz", "tournament");
        entities.put("TournamentTopic", "tournament");
        // Role is deliberately absent: it is `public enum Role`, which Pass 1 no longer counts.
        return entities;
    }

    private static AggregateReferenceResolver preMerge() {
        return AggregateReferenceResolver.build(SERVICES_8, roots8(), entities8());
    }

    /**
     * {@code course ← question}: question-service is gone, and {@code Question} is now a member
     * entity of course — no longer a root, which is why step 2 cannot be the one that saves it.
     */
    private static AggregateReferenceResolver postMerge() {
        Set<String> services = new LinkedHashSet<>(SERVICES_8);
        services.remove("question");

        Map<String, String> roots = roots8();
        roots.remove("Question");

        Map<String, String> entities = entities8();
        for (String absorbed : new String[]{"Question", "Option", "QuestionCourse", "QuestionTopic"}) {
            entities.put(absorbed, "course");
        }
        return AggregateReferenceResolver.build(services, roots, entities);
    }

    /** A split adds a root inside an existing service; the service set does not change. */
    private static AggregateReferenceResolver postSplit() {
        Map<String, String> roots = roots8();
        roots.put("TournamentParticipation", "tournament");

        Map<String, String> entities = entities8();
        entities.put("TournamentParticipation", "tournament");
        return AggregateReferenceResolver.build(SERVICES_8, roots, entities);
    }

    // ─── pre-merge: the whole prefix table ────────────────────────────────────

    @Nested
    @DisplayName("baseline — every id prefix present in the reference app")
    class Baseline {

        @Test
        @DisplayName("plain aggregate names resolve to their own service")
        void plainNames() {
            AggregateReferenceResolver r = preMerge();
            assertThat(r.resolve("answer", Set.of())).isEqualTo("answer");
            assertThat(r.resolve("course", Set.of())).isEqualTo("course");
            assertThat(r.resolve("execution", Set.of())).isEqualTo("execution");
            assertThat(r.resolve("question", Set.of())).isEqualTo("question");
            assertThat(r.resolve("quiz", Set.of())).isEqualTo("quiz");
            assertThat(r.resolve("topic", Set.of())).isEqualTo("topic");
            assertThat(r.resolve("user", Set.of())).isEqualTo("user");
        }

        @Test
        @DisplayName("compound names resolve on their last word, not the longest substring")
        void compoundNames() {
            AggregateReferenceResolver r = preMerge();
            // Execution's member family is named CourseExecution*, but the root is Execution.
            assertThat(r.resolve("courseExecution", Set.of())).isEqualTo("execution");
            // A possessive phrase: the quiz's questions are Questions.
            assertThat(r.resolve("quizQuestions", Set.of())).isEqualTo("question");
            assertThat(r.resolve("quizAnswer", Set.of())).isEqualTo("answer");
            assertThat(r.resolve("topicCourse", Set.of())).isEqualTo("course");
        }

        @Test
        @DisplayName("questionAnswerAggregateId points at answer, not question")
        void questionAnswerIsOwnedByAnswer() {
            // Longest-substring matching used to pick "question" (8) over "answer" (6). The field
            // in TournamentParticipant refers to QuestionAnswer, a member entity of answer-service.
            assertThat(preMerge().resolve("questionAnswer", Set.of())).isEqualTo("answer");
        }

        @Test
        @DisplayName("accessor prefixes resolve to the same service as the bare field")
        void accessorPrefixes() {
            AggregateReferenceResolver r = preMerge();
            assertThat(r.resolve("getQuizQuestions", Set.of())).isEqualTo("question");
            assertThat(r.resolve("setAnswer", Set.of())).isEqualTo("answer");
        }

        @Test
        @DisplayName("a bare getAggregateId() is not a reference and is not reported")
        void ownIdAccessorIsIgnored() {
            // Every Aggregate and member entity inherits getAggregateId()/setAggregateId(), so these
            // arrive ~30 times per run. Counting them would bury the three gaps that matter.
            AggregateReferenceResolver r = preMerge();
            assertThat(r.resolve("get", Set.of())).isNull();
            assertThat(r.resolve("set", Set.of())).isNull();
            assertThat(r.unresolved()).isEmpty();
        }

        @Test
        @DisplayName("role-named references are unresolvable by name, and are reported once per role")
        void unresolvedAreCounted() {
            AggregateReferenceResolver r = preMerge();
            assertThat(r.resolve("creator", Set.of())).isNull();
            assertThat(r.resolve("student", Set.of())).isNull();
            assertThat(r.resolve("participant", Set.of())).isNull();
            assertThat(r.resolve("findAll", Set.of())).isNull();
            assertThat(r.resolve("getCreator", Set.of())).isNull();   // same gap, reported under "creator"

            assertThat(r.unresolved())
                    .containsOnlyKeys("creator", "student", "participant", "findall")
                    .containsEntry("creator", 2);
        }

        @Test
        @DisplayName("resolved prefixes are not reported as unresolved")
        void resolvedAreNotCounted() {
            AggregateReferenceResolver r = preMerge();
            r.resolve("course", Set.of());
            r.resolve("quizQuestions", Set.of());
            assertThat(r.unresolved()).isEmpty();
        }
    }

    // ─── the bug this class exists for ────────────────────────────────────────

    @Nested
    @DisplayName("after MergeAggregates(course, question)")
    class AfterMerge {

        @Test
        @DisplayName("references to the absorbed root follow it to the surviving service")
        void absorbedRootStillResolves() {
            // Before the fix: no service is named "question" and no surviving service name is a
            // substring of "question", so this returned null and quiz's/answer's dependency on the
            // merged aggregate vanished from the metric.
            assertThat(postMerge().resolve("question", Set.of())).isEqualTo("course");
        }

        @Test
        @DisplayName("compound references to the absorbed root are not reattributed to a bystander")
        void compoundReferenceIsNotStolen() {
            // "quizQuestions" contains "quiz"; without last-word resolution it would silently
            // become a quiz reference the moment question-service disappeared.
            assertThat(postMerge().resolve("quizQuestions", Set.of())).isEqualTo("course");
        }

        @Test
        @DisplayName("untouched services keep resolving exactly as before")
        void noCollateralChange() {
            AggregateReferenceResolver r = postMerge();
            assertThat(r.resolve("courseExecution", Set.of())).isEqualTo("execution");
            assertThat(r.resolve("quizAnswer", Set.of())).isEqualTo("answer");
            assertThat(r.resolve("questionAnswer", Set.of())).isEqualTo("answer");
            assertThat(r.resolve("topic", Set.of())).isEqualTo("topic");
            assertThat(r.unresolved()).isEmpty();
        }
    }

    @Nested
    @DisplayName("after SplitAggregate creating a new root")
    class AfterSplit {

        @Test
        @DisplayName("a new root resolves in the version it first appears, with no new service")
        void newRootResolves() {
            // The satellite is a root inside tournament-service; there is no "tournamentparticipation"
            // service, so only the whole-prefix root lookup can find it.
            assertThat(postSplit().resolve("tournamentParticipation", Set.of())).isEqualTo("tournament");
        }

        @Test
        @DisplayName("the new root does not shadow references to its parent")
        void parentStillResolves() {
            AggregateReferenceResolver r = postSplit();
            assertThat(r.resolve("tournament", Set.of())).isEqualTo("tournament");
            assertThat(r.resolve("participant", Set.of())).isNull();
        }
    }

    // ─── step 5: the declaring file's imports ─────────────────────────────────

    @Nested
    @DisplayName("role-named references, resolved from the declaring file")
    class FileContext {

        @Test
        @DisplayName("a single foreign import resolves what no name lookup can")
        void singleImportResolves() {
            // AnswerStudent.studentAggregateId, TournamentCreator.creatorAggregateId and
            // TournamentParticipant.participantAggregateId all point at User and share no text with
            // it. All three files import exactly user.aggregate.UserDto.
            AggregateReferenceResolver r = preMerge();
            assertThat(r.resolve("student", Set.of("user"))).isEqualTo("user");
            assertThat(r.resolve("creator", Set.of("user"))).isEqualTo("user");
            assertThat(r.resolve("participant", Set.of("user"))).isEqualTo("user");
            assertThat(r.unresolved()).isEmpty();
        }

        @Test
        @DisplayName("these are reported separately, being the one match not justified by a name")
        void contextMatchesAreReported() {
            AggregateReferenceResolver r = preMerge();
            r.resolve("creator", Set.of("user"));
            r.resolve("getCreator", Set.of("user"));
            assertThat(r.contextResolved()).containsExactly(entry("creator", 2));
        }

        @Test
        @DisplayName("two foreign imports resolve to neither — a guess would be worse than a gap")
        void ambiguousContextAbstains() {
            AggregateReferenceResolver r = preMerge();
            assertThat(r.resolve("creator", Set.of("user", "execution"))).isNull();
            assertThat(r.unresolved()).containsKey("creator");
            assertThat(r.contextResolved()).isEmpty();
        }

        @Test
        @DisplayName("context never overrides a name that already resolved")
        void contextIsLastResort() {
            // A file may import several services; that must not re-point an id whose name is explicit.
            AggregateReferenceResolver r = preMerge();
            assertThat(r.resolve("topic", Set.of("user"))).isEqualTo("topic");
            assertThat(r.resolve("courseExecution", Set.of("user"))).isEqualTo("execution");
            assertThat(r.contextResolved()).isEmpty();
        }
    }

    // ─── guards ───────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("guards")
    class Guards {

        @Test
        @DisplayName("a member replica never wins a whole-prefix match")
        void memberReplicasAreNotRoots() {
            // QuizQuestion and QuestionAnswer concatenate two aggregate names. Accepting them whole
            // would send quizQuestions to quiz and questionAnswer to question — both wrong.
            AggregateReferenceResolver r = preMerge();
            assertThat(r.resolve("quizQuestion", Set.of())).isEqualTo("question");
            assertThat(r.resolve("questionAnswer", Set.of())).isEqualTo("answer");
        }

        @Test
        @DisplayName("a name claimed by two services resolves to neither")
        void ambiguousEntityIsRejected() {
            // Distinct class names that collide once lower-cased. Letting the last writer win would
            // attribute the reference to whichever service happened to be scanned second.
            Map<String, String> entities = new LinkedHashMap<>();
            entities.put("Shared", "alpha");
            entities.put("SHARED", "beta");

            AggregateReferenceResolver r = AggregateReferenceResolver.build(
                    Set.of("alpha", "beta"), Map.of(), entities);
            assertThat(r.resolve("shared", Set.of())).isNull();
            assertThat(r.unresolved()).containsKey("shared");
        }

        @Test
        @DisplayName("null and blank prefixes are not reported as unresolved")
        void emptyInput() {
            AggregateReferenceResolver r = preMerge();
            assertThat(r.resolve(null, Set.of())).isNull();
            assertThat(r.resolve("", Set.of())).isNull();
            assertThat(r.unresolved()).isEmpty();
        }
    }
}
