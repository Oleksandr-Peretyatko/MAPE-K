package org.anonymous.msrefactor.quizzes.microservices.execution.aggregate;

import jakarta.persistence.*;
import org.anonymous.msrefactor.ms.aggregate.Aggregate;
import org.anonymous.msrefactor.ms.utils.DateHandler;
import org.anonymous.msrefactor.quizzes.microservices.exception.QuizzesException;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static org.anonymous.msrefactor.quizzes.microservices.exception.QuizzesErrorMessage.INVARIANT_BREAK;

/*
    Member entity of Execution (merged from the former Quiz aggregate).
    INTRA-INVARIANTS (verified from Execution.verifyInvariants(), per quiz member)
        QUESTIONS_FINAL_AFTER_AVAILABLE_DATE
        CREATION_DATE_FINAL
        AVAILABLE_DATE_FINAL_AFTER_AVAILABLE_DATE
        CONCLUSION_DATE_FINAL_AFTER_AVAILABLE_DATE
        RESULTS_DATE_FINAL_AFTER_AVAILABLE_DATE
        QUIZ_IN_CLASS_REQUIRES_CONCLUSION_DATE
        QUIZ_QUESTION_SEQUENCE_CONSECUTIVE
        COURSE_EXECUTION_FINAL (the owning execution is the root; structurally final)
    QUESTION_EXISTS inter-invariant is registered from Execution.getEventSubscriptions().
 */
@Entity
public class Quiz {
    @Id
    @GeneratedValue
    private Long id;
    // entity identity: the former aggregateId value. Not an aggregate id any more.
    private Integer quizId;
    @ManyToOne
    private Execution execution;

    private LocalDateTime creationDate;
    protected LocalDateTime availableDate;
    private LocalDateTime conclusionDate;
    private LocalDateTime resultsDate;
    private String title = "Title";
    @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER, mappedBy = "quiz")
    private Set<QuizQuestion> quizQuestions = new HashSet<>();
    @Enumerated(EnumType.STRING)
    private QuizType quizType;
    @Enumerated(EnumType.STRING)
    private Aggregate.AggregateState state;
    /*
     * Timestamp captured at mutation time so that QUESTIONS_FINAL_AFTER_AVAILABLE_DATE,
     * AVAILABLE_DATE_FINAL_AFTER_AVAILABLE_DATE, CONCLUSION_DATE_FINAL_AFTER_AVAILABLE_DATE,
     * and RESULTS_DATE_FINAL_AFTER_AVAILABLE_DATE can be verified post-hoc inside
     * verifyInvariants() without calling DateHandler.now() there.
     */
    private LocalDateTime lastModifiedTime;

    public Quiz() {
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
     * lastModifiedTime > prev.availableDate => final questions, availableDate, conclusionDate, resultsDate.
     * prev is the matching quiz member of the previous Execution version (supplied by the root).
     */
    private boolean invariantFieldsFinalAfterAvailableDate(Quiz prev) {
        if (prev != null && prev.getAvailableDate() != null && this.lastModifiedTime != null
                && this.lastModifiedTime.isAfter(prev.getAvailableDate())) {
            return Objects.equals(this.availableDate, prev.getAvailableDate())
                    && Objects.equals(this.conclusionDate, prev.getConclusionDate())
                    && Objects.equals(this.resultsDate, prev.getResultsDate())
                    && this.quizQuestions.stream().map(QuizQuestion::getQuestionAggregateId)
                            .collect(Collectors.toSet())
                            .equals(prev.getQuizQuestions().stream().map(QuizQuestion::getQuestionAggregateId)
                                    .collect(Collectors.toSet()));
        }
        return true;
    }

    /*
     * QUIZ_IN_CLASS_REQUIRES_CONCLUSION_DATE
     * Quiz.type == IN_CLASS => Quiz.conclusionDate != null
     */
    private boolean invariantInClassRequiresConclusionDate() {
        return quizType != QuizType.IN_CLASS || conclusionDate != null;
    }

    /*
     * QUIZ_QUESTION_SEQUENCE_CONSECUTIVE
     * QuizQuestions are numbered starting at 1 with no gaps.
     * Only enforced when at least one active question has a non-null sequence;
     * generated quizzes that never assign sequences are exempt.
     */
    private boolean invariantQuizQuestionSequenceConsecutive() {
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

    public void verifyInvariants(Quiz prev) {
        if (!(invariantDateOrdering()
                && invariantFieldsFinalAfterAvailableDate(prev)
                && invariantInClassRequiresConclusionDate()
                && invariantQuizQuestionSequenceConsecutive())) {
            throw new QuizzesException(INVARIANT_BREAK, getQuizId());
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Integer getQuizId() {
        return quizId;
    }

    public void setQuizId(Integer quizId) {
        this.quizId = quizId;
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public Execution getExecution() {
        return execution;
    }

    public void setExecution(Execution execution) {
        this.execution = execution;
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
        setLastModifiedTime(DateHandler.now());
        this.availableDate = availableDate;
    }

    public LocalDateTime getConclusionDate() {
        return conclusionDate;
    }

    public void setConclusionDate(LocalDateTime conclusionDate) {
        setLastModifiedTime(DateHandler.now());
        this.conclusionDate = conclusionDate;
    }

    public LocalDateTime getResultsDate() {
        return resultsDate;
    }

    public void setResultsDate(LocalDateTime resultsDate) {
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
        setLastModifiedTime(DateHandler.now());
        this.quizQuestions = quizQuestions;
        this.quizQuestions.forEach(quizQuestion -> quizQuestion.setQuiz(this));
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
