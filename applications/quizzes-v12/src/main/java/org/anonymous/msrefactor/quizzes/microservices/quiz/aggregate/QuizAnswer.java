package org.anonymous.msrefactor.quizzes.microservices.quiz.aggregate;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import org.anonymous.msrefactor.ms.aggregate.Aggregate;
import org.anonymous.msrefactor.quizzes.microservices.exception.QuizzesException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.anonymous.msrefactor.quizzes.microservices.exception.QuizzesErrorMessage.QUESTION_ALREADY_ANSWERED;

/*
    Member entity of Quiz (merge quiz <- answer). The former intra-invariants
    FINAL_ANSWER_DATE / FINAL_CREATION_DATE / FINAL_USER / FINAL_QUIZ / FINAL_COURSE_EXECUTION
    remain structural (no setter re-points a member once set in the constructor), and QUIZ_EXISTS
    is containment under the Quiz root. The remaining inter-invariants (USER_EXISTS,
    COURSE_EXECUTION_EXISTS, QUESTION_EXISTS) are registered by Quiz.getEventSubscriptions().
 */
@Entity
public class QuizAnswer {
    @Id
    @GeneratedValue
    private Integer id;
    // entity identity: the former QuizAnswer aggregateId value — external references
    // (tournament's replica, QuizAnswerQuestionAnswerEvent) keep resolving it
    private Integer quizAnswerId;
    private LocalDateTime creationDate;
    private LocalDateTime answerDate;
    private boolean completed;
    // member-level state replacing the former root's AggregateState (deleted answers keep
    // their row: tournament still references the answer by this identifier)
    private Aggregate.AggregateState state;
    @OneToOne(cascade = CascadeType.ALL, mappedBy = "quizAnswer")
    private AnswerCourseExecution answerCourseExecution;
    @OneToOne(cascade = CascadeType.ALL, mappedBy = "quizAnswer")
    private AnswerStudent student;
    /* it is not final because of the question ids inside*/
    @OneToOne(cascade = CascadeType.ALL, mappedBy = "quizAnswer")
    private AnsweredQuiz answeredQuiz;
    @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER, mappedBy = "quizAnswer")
    private List<QuestionAnswer> questionAnswers = new ArrayList<>();
    @ManyToOne
    private Quiz quiz;

    public QuizAnswer() {
    }

    public QuizAnswer(Integer quizAnswerId, AnswerCourseExecution answerCourseExecution, AnswerStudent answerStudent,
            AnsweredQuiz answeredQuiz) {
        setQuizAnswerId(quizAnswerId);
        setState(Aggregate.AggregateState.ACTIVE);
        setAnswerCourseExecution(answerCourseExecution);
        setStudent(answerStudent);
        setAnsweredQuiz(answeredQuiz);
    }

    public QuizAnswer(QuizAnswer other) {
        setQuizAnswerId(other.getQuizAnswerId());
        setState(other.getState());
        setAnswerCourseExecution(new AnswerCourseExecution(other.getAnswerCourseExecution()));
        setStudent(new AnswerStudent(other.getStudent()));
        setAnsweredQuiz(new AnsweredQuiz(other.getAnsweredQuiz()));
        setAnswerDate(other.getAnswerDate());
        setCreationDate(other.getCreationDate());
        setCompleted(other.isCompleted());
        setQuestionAnswers(other.getQuestionAnswers().stream().map(QuestionAnswer::new).collect(Collectors.toList()));
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public Integer getQuizAnswerId() {
        return quizAnswerId;
    }

    public void setQuizAnswerId(Integer quizAnswerId) {
        this.quizAnswerId = quizAnswerId;
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

    public void setCreationDate(LocalDateTime creationDate) {
        this.creationDate = creationDate;
    }

    public LocalDateTime getAnswerDate() {
        return answerDate;
    }

    public void setAnswerDate(LocalDateTime answerDate) {
        this.answerDate = answerDate;
    }

    public boolean isCompleted() {
        return completed;
    }

    public void setCompleted(boolean completed) {
        this.completed = completed;
    }

    public AnswerCourseExecution getAnswerCourseExecution() {
        return answerCourseExecution;
    }

    public void setAnswerCourseExecution(AnswerCourseExecution answerCourseExecution) {
        this.answerCourseExecution = answerCourseExecution;
        answerCourseExecution.setQuizAnswer(this);
    }

    public AnswerStudent getStudent() {
        return student;
    }

    public void setStudent(AnswerStudent answerStudent) {
        this.student = answerStudent;
        answerStudent.setQuizAnswer(this);
    }

    public AnsweredQuiz getAnsweredQuiz() {
        return answeredQuiz;
    }

    public void setAnsweredQuiz(AnsweredQuiz answeredQuiz) {
        this.answeredQuiz = answeredQuiz;
        answeredQuiz.setQuizAnswer(this);
    }

    public List<QuestionAnswer> getQuestionAnswers() {
        return questionAnswers;
    }

    public void setQuestionAnswers(List<QuestionAnswer> questionAnswers) {
        this.questionAnswers.forEach(questionAnswer -> questionAnswer.setQuizAnswer(null));
        this.questionAnswers = questionAnswers;
        questionAnswers.forEach(questionAnswer -> questionAnswer.setQuizAnswer(this));
    }

    @JsonIgnore
    public Quiz getQuiz() {
        return quiz;
    }

    public void setQuiz(Quiz quiz) {
        this.quiz = quiz;
    }

    public void addQuestionAnswer(QuestionAnswer questionAnswer) {
        List<Integer> answeredQuestionIds = this.questionAnswers.stream()
                .map(QuestionAnswer::getQuestionAggregateId)
                .collect(Collectors.toList());
        if (answeredQuestionIds.contains(questionAnswer.getQuestionAggregateId())) {
            throw new QuizzesException(QUESTION_ALREADY_ANSWERED, questionAnswer.getQuestionAggregateId(),
                    this.getQuiz().getAggregateId());
        }
        this.questionAnswers.add(questionAnswer);
        questionAnswer.setQuizAnswer(this);
    }

    public QuestionAnswer findQuestionAnswer(Integer questionAggregateId) {
        for (QuestionAnswer qa : this.questionAnswers) {
            if (qa.getQuestionAggregateId().equals(questionAggregateId)) {
                return qa;
            }
        }
        return null;
    }

}
