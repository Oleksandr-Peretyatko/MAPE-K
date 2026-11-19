package org.anonymous.msrefactor.quizzes.microservices.execution.aggregate;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import org.anonymous.msrefactor.ms.aggregate.Aggregate;
import org.anonymous.msrefactor.ms.utils.DateHandler;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/*
    Member entity of the Execution aggregate (formerly the Quiz aggregate root).
    Its intra-invariants are enforced by Execution.verifyInvariants():
        QUESTIONS_FINAL_AFTER_AVAILABLE_DATE
        CREATION_DATE_FINAL
        AVAILABLE_DATE_FINAL_AFTER_AVAILABLE_DATE
        CONCLUSION_DATE_FINAL_AFTER_AVAILABLE_DATE
        RESULTS_DATE_FINAL_AFTER_AVAILABLE_DATE
        QUIZ_IN_CLASS_REQUIRES_CONCLUSION_DATE
        QUIZ_QUESTION_SEQUENCE_CONSECUTIVE
    COURSE_EXECUTION_FINAL and COURSE_EXECUTION_EXISTS hold structurally: the quiz
    belongs to exactly one Execution root for its whole life.
 */
@Entity
public class Quiz {
    @Id
    @GeneratedValue
    private Integer id;
    /*
     * Entity identity inside the Execution aggregate: the value of the old quiz
     * aggregateId. Commands, DTOs and replicas in other services keep resolving it.
     */
    private Integer quizId;
    private Aggregate.AggregateState state;
    /*
        CREATION_DATE_FINAL
     */
    private final LocalDateTime creationDate;
    protected LocalDateTime availableDate;
    private LocalDateTime conclusionDate;
    private LocalDateTime resultsDate;
    private String title = "Title";
    @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER, mappedBy = "quiz")
    private Set<QuizQuestion> quizQuestions = new HashSet<>();
    @Enumerated(EnumType.STRING)
    private QuizType quizType;
    @ManyToOne
    private Execution execution;
    /*
     * Timestamp captured at mutation time so that QUESTIONS_FINAL_AFTER_AVAILABLE_DATE,
     * AVAILABLE_DATE_FINAL_AFTER_AVAILABLE_DATE, CONCLUSION_DATE_FINAL_AFTER_AVAILABLE_DATE,
     * and RESULTS_DATE_FINAL_AFTER_AVAILABLE_DATE can be verified post-hoc inside
     * verifyInvariants() without calling DateHandler.now() there.
     */
    private LocalDateTime lastModifiedTime;

    public Quiz() {
        this.creationDate = null;
    }

    public Quiz(Integer quizId, Set<QuizQuestion> quizQuestions, QuizDto quizDto, QuizType quizType) {
        setQuizId(quizId);
        setState(Aggregate.AggregateState.ACTIVE);
        setQuizQuestions(quizQuestions);
        setTitle(quizDto.getTitle());
        this.creationDate = DateHandler.now();
        setAvailableDate(DateHandler.toLocalDateTime(quizDto.getAvailableDate()));
        setConclusionDate(DateHandler.toLocalDateTime(quizDto.getConclusionDate()));
        setResultsDate(DateHandler.toLocalDateTime(quizDto.getResultsDate()));
        setQuizType(quizType);
    }

    public Quiz(Quiz other) {
        setQuizId(other.getQuizId());
        setState(other.getState());
        // Copy plain fields directly (bypasses setters so lastModifiedTime is not stamped on copy)
        this.quizQuestions = other.getQuizQuestions().stream().map(QuizQuestion::new).collect(Collectors.toSet());
        this.quizQuestions.forEach(quizQuestion -> quizQuestion.setQuiz(this));
        setTitle(other.getTitle());
        this.creationDate = other.getCreationDate();
        this.availableDate = other.getAvailableDate();
        this.conclusionDate = other.getConclusionDate();
        this.resultsDate = other.getResultsDate();
        setQuizType(other.getQuizType());
        this.lastModifiedTime = other.getLastModifiedTime();
    }

    public boolean invariantDateOrdering() {
        return getCreationDate().isBefore(getConclusionDate()) &&
                getAvailableDate().isBefore(getConclusionDate()) &&
                (getConclusionDate().isEqual(getResultsDate()) || getConclusionDate().isBefore(getResultsDate()));
    }

    /*
     * QUESTIONS_FINAL_AFTER_AVAILABLE_DATE / AVAILABLE_DATE_FINAL_AFTER_AVAILABLE_DATE
     * CONCLUSION_DATE_FINAL_AFTER_AVAILABLE_DATE / RESULTS_DATE_FINAL_AFTER_AVAILABLE_DATE
     * lastModifiedTime > prev.availableDate => final questions, availableDate, conclusionDate, resultsDate
     * prevQuiz is this quiz as it was in the previous version of the Execution root.
     */
    public boolean invariantFieldsFinalAfterAvailableDate(Quiz prevQuiz) {
        if (prevQuiz != null && prevQuiz.getAvailableDate() != null && this.lastModifiedTime != null
                && this.lastModifiedTime.isAfter(prevQuiz.getAvailableDate())) {
            return Objects.equals(this.availableDate, prevQuiz.getAvailableDate())
                    && Objects.equals(this.conclusionDate, prevQuiz.getConclusionDate())
                    && Objects.equals(this.resultsDate, prevQuiz.getResultsDate())
                    && this.quizQuestions.stream().map(QuizQuestion::getQuestionAggregateId)
                            .collect(Collectors.toSet())
                            .equals(prevQuiz.getQuizQuestions().stream().map(QuizQuestion::getQuestionAggregateId)
                                    .collect(Collectors.toSet()));
        }
        return true;
    }

    /*
     * QUIZ_IN_CLASS_REQUIRES_CONCLUSION_DATE
     * Quiz.type == IN_CLASS => Quiz.conclusionDate != null
     */
    public boolean invariantInClassRequiresConclusionDate() {
        return quizType != QuizType.IN_CLASS || conclusionDate != null;
    }

    /*
     * QUIZ_QUESTION_SEQUENCE_CONSECUTIVE
     * QuizQuestions are numbered starting at 1 with no gaps.
     * Only enforced when at least one active question has a non-null sequence;
     * generated quizzes that never assign sequences are exempt.
     */
    public boolean invariantQuizQuestionSequenceConsecutive() {
        List<Integer> sequences = quizQuestions.stream()
                .filter(qq -> qq.getState() == Aggregate.AggregateState.ACTIVE && qq.getSequence() != null)
                .map(QuizQuestion::getSequence)
                .sorted()
                .collect(Collectors.toList());
        if (sequences.isEmpty()) {
            return true;
        }
        for (int i = 0; i < sequences.size(); i++) {
            if (sequences.get(i) != i + 1) {
                return false;
            }
        }
        return true;
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public Integer getQuizId() {
        return quizId;
    }

    public void setQuizId(Integer quizId) {
        this.quizId = quizId;
    }

    public Aggregate.AggregateState getState() {
        return state;
    }

    public void setState(Aggregate.AggregateState state) {
        this.state = state;
    }

    public LocalDateTime getCreationDate() {
        return creationDate;
    }

    public LocalDateTime getAvailableDate() {
        return availableDate;
    }

    public void setAvailableDate(LocalDateTime availableDate) {
        /*
         * AVAILABLE_DATE_FINAL_AFTER_AVAILABLE_DATE — enforced by Execution.verifyInvariants() using lastModifiedTime
         */
        setLastModifiedTime(DateHandler.now());
        this.availableDate = availableDate;
    }

    public LocalDateTime getConclusionDate() {
        return conclusionDate;
    }

    public void setConclusionDate(LocalDateTime conclusionDate) {
        /*
         * CONCLUSION_DATE_FINAL_AFTER_AVAILABLE_DATE — enforced by Execution.verifyInvariants() using lastModifiedTime
         */
        setLastModifiedTime(DateHandler.now());
        this.conclusionDate = conclusionDate;
    }

    public LocalDateTime getResultsDate() {
        return resultsDate;
    }

    public void setResultsDate(LocalDateTime resultsDate) {
        /*
         * RESULTS_DATE_FINAL_AFTER_AVAILABLE_DATE — enforced by Execution.verifyInvariants() using lastModifiedTime
         */
        setLastModifiedTime(DateHandler.now());
        this.resultsDate = resultsDate;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Set<QuizQuestion> getQuizQuestions() {
        return quizQuestions;
    }

    public void setQuizQuestions(Set<QuizQuestion> quizQuestions) {
        /*
         * QUESTIONS_FINAL_AFTER_AVAILABLE_DATE — enforced by Execution.verifyInvariants() using lastModifiedTime
         */
        setLastModifiedTime(DateHandler.now());
        this.quizQuestions = quizQuestions;
        this.quizQuestions.forEach(quizQuestion -> quizQuestion.setQuiz(this));
    }

    @JsonIgnore
    public Execution getExecution() {
        return execution;
    }

    public void setExecution(Execution execution) {
        this.execution = execution;
    }

    public QuizType getQuizType() {
        return quizType;
    }

    public void setQuizType(QuizType quizType) {
        this.quizType = quizType;
    }

    public void update(QuizDto quizDto) {
        setTitle(quizDto.getTitle());
        setAvailableDate(DateHandler.toLocalDateTime(quizDto.getAvailableDate()));
        setConclusionDate(DateHandler.toLocalDateTime(quizDto.getConclusionDate()));
        setResultsDate(DateHandler.toLocalDateTime(quizDto.getResultsDate()));
    }

    public QuizQuestion findQuestion(Integer questionAggregateId) {
        for (QuizQuestion qq : quizQuestions) {
            if (qq.getQuestionAggregateId().equals(questionAggregateId)) {
                return qq;
            }
        }
        return null;
    }

    public LocalDateTime getLastModifiedTime() {
        return lastModifiedTime;
    }

    public void setLastModifiedTime(LocalDateTime lastModifiedTime) {
        this.lastModifiedTime = lastModifiedTime;
    }
}
