package org.anonymous.msrefactor.quizzes.microservices.quiz.aggregate;

import java.io.Serializable;
import java.time.LocalDateTime;

public class QuizAnswerDto implements Serializable {
    private Integer aggregateId;
    private Long version;
    private LocalDateTime answerDate;
    private boolean completed;
    private Integer studentAggregateId;
    private String studentName;
    private Integer quizAggregateId;
    private String state;

    //private List<QuestionAnswer> questionAnswers;

    public QuizAnswerDto() {}

    // Wire shape unchanged by the merge quiz <- answer: aggregateId keeps carrying the former
    // QuizAnswer aggregate id (now the member's quizAnswerId); version and quizAggregateId come
    // from the owning Quiz root; state from the member-level flag.
    public QuizAnswerDto(QuizAnswer quizAnswer) {
        setAggregateId(quizAnswer.getQuizAnswerId());
        setAnswerDate(quizAnswer.getAnswerDate());
        setCompleted(quizAnswer.isCompleted());
        setStudentAggregateId(quizAnswer.getStudent().getStudentAggregateId());
        setStudentName(quizAnswer.getStudent().getName());
        if (quizAnswer.getState() != null) {
            setState(quizAnswer.getState().toString());
        }
        if (quizAnswer.getQuiz() != null) {
            setVersion(quizAnswer.getQuiz().getVersion());
            setQuizAggregateId(quizAnswer.getQuiz().getAggregateId());
        }
    }

    public Integer getAggregateId() {
        return aggregateId;
    }

    public void setAggregateId(Integer aggregateId) {
        this.aggregateId = aggregateId;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
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

    public Integer getStudentAggregateId() {
        return studentAggregateId;
    }

    public void setStudentAggregateId(Integer studentAggregateId) {
        this.studentAggregateId = studentAggregateId;
    }

    public String getStudentName() {
        return studentName;
    }

    public void setStudentName(String studentName) {
        this.studentName = studentName;
    }

    public Integer getQuizAggregateId() {
        return quizAggregateId;
    }

    public void setQuizAggregateId(Integer quizAggregateId) {
        this.quizAggregateId = quizAggregateId;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }
}
