package org.anonymous.msrefactor.quizzes.microservices.course.aggregate;

import org.anonymous.msrefactor.ms.aggregate.Aggregate.AggregateState;

import java.io.Serializable;

public class TopicDto implements Serializable {
    private Integer aggregateId;
    private Long version;
    private Integer courseId;
    private String name;
    private AggregateState state;

    public TopicDto() {
    }

    public TopicDto(Topic topic) {
        setAggregateId(topic.getTopicId());
        if (topic.getCourse() != null) {
            setVersion(topic.getCourse().getVersion());
            setCourseId(topic.getCourse().getAggregateId());
        }
        setName(topic.getName());
        setState(topic.isDeleted() ? AggregateState.DELETED : AggregateState.ACTIVE);
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

    public Integer getCourseId() {
        return courseId;
    }

    public void setCourseId(Integer courseId) {
        this.courseId = courseId;
    }

    public String getAcronym() {
        return name;
    }

    public void setAcronym(String acronym) {
        this.name = acronym;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public AggregateState getState() {
        return state;
    }

    public void setState(AggregateState state) {
        this.state = state;
    }
}
