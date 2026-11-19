package org.anonymous.msrefactor.quizzes.microservices.quiz.aggregate;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import org.anonymous.msrefactor.ms.aggregate.Replica;
import org.anonymous.msrefactor.quizzes.microservices.question.aggregate.QuestionDto;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

// The former quizAggregateId/quizVersion copy of Quiz is gone (merge quiz <- answer): the
// owning Quiz root is reachable through QuizAnswer's back-reference. What remains copied is
// the set of question ids the quiz had when the student started it.
@Entity
@Replica("Question")
public class AnsweredQuiz {
    @Id
    @GeneratedValue
    private Long id;
    @OneToOne
    private QuizAnswer quizAnswer;
    @ElementCollection
    private List<Integer> quizQuestionsAggregateIds;

    public AnsweredQuiz() {
    }

    public AnsweredQuiz(QuizDto quizDto) {
        setQuizQuestionsAggregateIds(quizDto.getQuestionDtos().stream()
                .map(QuestionDto::getAggregateId)
                .collect(Collectors.toList()));
    }

    public AnsweredQuiz(Quiz quiz) {
        setQuizQuestionsAggregateIds(quiz.getQuizQuestions().stream()
                .map(QuizQuestion::getQuestionAggregateId)
                .collect(Collectors.toList()));
    }

    public AnsweredQuiz(AnsweredQuiz other) {
        setQuizQuestionsAggregateIds(new ArrayList<>(other.getQuizQuestionsAggregateIds()));
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getId() {
        return id;
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
