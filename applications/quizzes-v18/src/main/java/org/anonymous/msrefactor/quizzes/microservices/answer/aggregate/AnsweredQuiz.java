package org.anonymous.msrefactor.quizzes.microservices.answer.aggregate;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import org.anonymous.msrefactor.ms.aggregate.Replica;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.QuestionDto;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.QuizDto;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Entity
@Replica({ "Execution", "Course" })
public class AnsweredQuiz {
    @Id
    @GeneratedValue
    private Long id;
    private Integer quizAggregateId;
    private Long quizVersion;
    @OneToOne
    private QuizAnswer quizAnswer;
    @ElementCollection
    private List<Integer> quizQuestionsAggregateIds;

    public AnsweredQuiz() {
        this.quizAggregateId = 0;
    }

    public AnsweredQuiz(QuizDto quizDto) {
        this.quizAggregateId = quizDto.getAggregateId();
        setQuizVersion(quizDto.getVersion());
        setQuizQuestionsAggregateIds(quizDto.getQuestionDtos().stream()
                .map(QuestionDto::getAggregateId)
                .collect(Collectors.toList()));
    }

    public AnsweredQuiz(AnsweredQuiz other) {
        this.quizAggregateId = other.getQuizAggregateId();
        setQuizVersion(other.getQuizVersion());
        setQuizQuestionsAggregateIds(new ArrayList<>(other.getQuizQuestionsAggregateIds()));
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getId() {
        return id;
    }

    public Integer getQuizAggregateId() {
        return quizAggregateId;
    }

    public Long getQuizVersion() {
        return quizVersion;
    }

    public void setQuizVersion(Long quizVersion) {
        this.quizVersion = quizVersion;
    }

    public List<Integer> getQuizQuestionsAggregateIds() {
        return quizQuestionsAggregateIds;
    }

    public void setQuizQuestionsAggregateIds(List<Integer> quizQuestionsAggregateIds) {
        this.quizQuestionsAggregateIds = quizQuestionsAggregateIds;
    }

    @JsonIgnore
    public QuizAnswer getQuizAnswer() {
        return quizAnswer;
    }

    public void setQuizAnswer(QuizAnswer quizAnswer) {
        this.quizAnswer = quizAnswer;
    }
}
