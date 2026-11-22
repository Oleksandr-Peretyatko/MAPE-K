package org.anonymous.msrefactor.rt.monitor.collector;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A member held one-to-one against one held in a collection.
 *
 * <p>{@code compositions} keeps both as the same edge, which is right for a split — moving an entity
 * has to take its children either way — and wrong for a merge: only a single-valued member can make
 * its root a candidate for absorption. {@code Question} is the pair that proves it, holding
 * {@code QuestionCourse} one-to-one and {@code QuestionTopic} as a set, both of them replicas of a
 * foreign root.
 */
class CompositionCardinalityTest {

    private StaticAnalysisCollector collector;

    @BeforeEach
    void setUp() {
        collector = new StaticAnalysisCollector();
    }

    private static final String QUESTION_ROOT = """
            @Entity
            public class Question extends Aggregate {
                private String title;

                @OneToOne(cascade = CascadeType.ALL, mappedBy = "question")
                private QuestionCourse questionCourse;

                @OneToMany(cascade = CascadeType.ALL, mappedBy = "question")
                private Set<QuestionTopic> questionTopics = new HashSet<>();

                @OneToMany(cascade = CascadeType.ALL, mappedBy = "question")
                private List<Option> options = new ArrayList<>();
            }
            """;

    private static final String QUIZ_ANSWER_ROOT = """
            @Entity
            public class QuizAnswer extends Aggregate {
                @OneToOne(cascade = CascadeType.ALL, mappedBy = "quizAnswer")
                private AnswerCourseExecution answerCourseExecution;

                @OneToOne(cascade = CascadeType.ALL, mappedBy = "quizAnswer")
                private AnswerStudent student;

                @OneToOne(cascade = CascadeType.ALL, mappedBy = "quizAnswer")
                private AnsweredQuiz quiz;

                @OneToMany(cascade = CascadeType.ALL, mappedBy = "quizAnswer")
                private List<QuestionAnswer> questionAnswers = new ArrayList<>();
            }
            """;

    @Test
    @DisplayName("compositions still holds every child, whatever the cardinality")
    void compositionsUnchanged() {
        Map<String, List<String>> compositions = collector.extractCompositions(
                Map.of("Question", QUESTION_ROOT),
                List.of("Question", "QuestionCourse", "QuestionTopic", "Option"));

        assertThat(compositions.get("Question"))
                .containsExactlyInAnyOrder("QuestionCourse", "QuestionTopic", "Option");
    }

    @Test
    @DisplayName("only the @OneToOne member is single-valued")
    void singleValuedExcludesCollections() {
        Map<String, List<String>> singleValued = collector.extractSingleValuedMembers(
                Map.of("Question", QUESTION_ROOT),
                List.of("Question", "QuestionCourse", "QuestionTopic", "Option"));

        assertThat(singleValued.get("Question")).containsExactly("QuestionCourse");
    }

    @Test
    @DisplayName("a root with several @OneToOne members reports them all")
    void severalSingleValuedMembers() {
        Map<String, List<String>> singleValued = collector.extractSingleValuedMembers(
                Map.of("QuizAnswer", QUIZ_ANSWER_ROOT),
                List.of("QuizAnswer", "AnswerCourseExecution", "AnswerStudent", "AnsweredQuiz",
                        "QuestionAnswer"));

        assertThat(singleValued.get("QuizAnswer"))
                .containsExactlyInAnyOrder("AnswerCourseExecution", "AnswerStudent", "AnsweredQuiz");
    }

    @Test
    @DisplayName("a root holding nothing one-to-one is absent, not empty")
    void noSingleValuedMember() {
        String course = """
                @Entity
                public class Course extends Aggregate {
                    private String name;
                }
                """;

        assertThat(collector.extractSingleValuedMembers(Map.of("Course", course), List.of("Course")))
                .doesNotContainKey("Course");
    }
}
