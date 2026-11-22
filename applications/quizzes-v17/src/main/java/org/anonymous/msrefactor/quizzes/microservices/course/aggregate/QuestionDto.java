package org.anonymous.msrefactor.quizzes.microservices.course.aggregate;

import org.anonymous.msrefactor.ms.aggregate.Aggregate;
import org.anonymous.msrefactor.ms.aggregate.Aggregate.AggregateState;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.CourseDto;
import org.anonymous.msrefactor.quizzes.microservices.topic.aggregate.TopicDto;

import java.io.Serializable;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class QuestionDto implements Serializable {
    private Integer aggregateId;
    private Long version;
    private String title;
    private String content;
    private String creationDate;
    private CourseDto course;
    private Set<TopicDto> topicDto;
    private List<OptionDto> optionDtos;
    private Integer sequence;
    private Aggregate.AggregateState state;

    public QuestionDto() {}

   public QuestionDto(Question question) {
       // wire names unchanged: aggregateId still carries the question's old aggregate
       // id (now the member's entity id); version reads the owning root's version
       setAggregateId(question.getQuestionId());
       setVersion(question.getCourse() == null ? null : question.getCourse().getVersion());
       setTitle(question.getTitle());
       setContent(question.getContent());
       setCreationDate(question.getCreationDate().toString());
       setCourse(buildCourseDto(question));
       setTopicDto(question.getQuestionTopics().stream()
               .map(QuestionTopic::buildDto)
               .collect(Collectors.toSet()));
       setOptionDtos(question.getOptions().stream()
               .map(OptionDto::new)
               .collect(Collectors.toList()));
   }

    /*
     * Replaces the deleted QuestionCourse replica's buildDto(): same observable
     * shape (only the course aggregate id is populated), sourced from the owning root.
     */
    private static CourseDto buildCourseDto(Question question) {
        CourseDto dto = new CourseDto();
        if (question.getCourse() != null) {
            dto.setAggregateId(question.getCourse().getAggregateId());
        }
        return dto;
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

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getCreationDate() {
        return creationDate;
    }

    public void setCreationDate(String creationDate) {
        this.creationDate = creationDate;
    }

    public CourseDto getCourse() {
        return course;
    }

    public void setCourse(CourseDto course) {
        this.course = course;
    }

    public Set<TopicDto> getTopicDto() {
        return topicDto;
    }

    public void setTopicDto(Set<TopicDto> topicDto) {
        this.topicDto = topicDto;
    }

    public List<OptionDto> getOptionDtos() {
        return optionDtos;
    }

    public void setOptionDtos(List<OptionDto> optionDtos) {
        this.optionDtos = optionDtos;
    }

    public Integer getSequence() {
        return sequence;
    }

    public void setSequence(Integer sequence) {
        this.sequence = sequence;
    }

    public Aggregate.AggregateState getState() {
        return state;
    }

    public void setState(AggregateState aggregateState) {
        this.state = aggregateState;
    }
}
