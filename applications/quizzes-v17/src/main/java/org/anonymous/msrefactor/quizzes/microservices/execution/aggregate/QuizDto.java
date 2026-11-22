package org.anonymous.msrefactor.quizzes.microservices.execution.aggregate;

import org.anonymous.msrefactor.ms.utils.DateHandler;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.QuestionDto;

import java.io.Serializable;
import java.util.List;
import java.util.stream.Collectors;

public class QuizDto implements Serializable {
    private Integer aggregateId;
    private String title;
    private String availableDate;
    private String conclusionDate;
    private String resultsDate;
    private Long version;
    private List<QuestionDto> questionDtos;
    private Integer courseExecutionAggregateId;
    private Long courseExecutionVersion;
    private String state;

    public QuizDto() {

    }

    public QuizDto(Quiz quiz) {
        setAggregateId(quiz.getQuizId());
        setTitle(quiz.getTitle());
        setAvailableDate(DateHandler.toISOString(quiz.getAvailableDate()));
        setConclusionDate(DateHandler.toISOString(quiz.getConclusionDate()));
        setResultsDate(quiz.getResultsDate().toString());
        setVersion(quiz.getExecution().getVersion());
        setQuestionDtos(quiz.getQuizQuestions().stream()
                .map(qq -> qq.buildDto())
                .collect(Collectors.toList()));
        setCourseExecutionAggregateId(quiz.getExecution().getAggregateId());
        setCourseExecutionVersion(quiz.getExecution().getVersion());
        setState(quiz.getState().toString());
    }

    public Integer getAggregateId() {
        return aggregateId;
    }

    public void setAggregateId(Integer aggregateId) {
        this.aggregateId = aggregateId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getAvailableDate() {
        return availableDate;
    }

    public void setAvailableDate(String availableDate) {
        this.availableDate = availableDate;
    }

    public String getConclusionDate() {
        return conclusionDate;
    }

    public void setConclusionDate(String conclusionDate) {
        this.conclusionDate = conclusionDate;
    }

    public String getResultsDate() {
        return resultsDate;
    }

    public void setResultsDate(String resultsDate) {
        this.resultsDate = resultsDate;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public List<QuestionDto> getQuestionDtos() {
        return this.questionDtos;
    }

    public void setQuestionDtos(List<QuestionDto> questionDtos) {
        this.questionDtos = questionDtos;
    }

    public Integer getCourseExecutionAggregateId() {
        return courseExecutionAggregateId;
    }

    public void setCourseExecutionAggregateId(Integer courseExecutionAggregateId) {
        this.courseExecutionAggregateId = courseExecutionAggregateId;
    }

    public Long getCourseExecutionVersion() {
        return courseExecutionVersion;
    }

    public void setCourseExecutionVersion(Long courseExecutionVersion) {
        this.courseExecutionVersion = courseExecutionVersion;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }
}
