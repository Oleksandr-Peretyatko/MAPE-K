package org.anonymous.msrefactor.quizzes.microservices.course.aggregate;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import org.anonymous.msrefactor.ms.aggregate.Aggregate;

/*
    Member entity of the Course aggregate (formerly the Topic aggregate root).
    Topic had no intra-invariants and no event subscriptions. COURSE-EXISTS holds
    structurally: the topic belongs to exactly one Course root for its whole life
    (it replaces the deleted TopicCourse replica).
 */
@Entity
public class Topic {
    @Id
    @GeneratedValue
    private Integer id;
    /*
     * Entity identity inside the Course aggregate: the value of the old topic
     * aggregateId. Commands, DTOs, REST paths and replicas in other services
     * (TournamentTopic in execution) keep resolving it, and Update/DeleteTopicEvent
     * keep publishing it.
     */
    private Integer topicId;
    @Column
    private String name;
    /*
     * Member-level deletion flag: execution subscribes DeleteTopicEvent and
     * TournamentTopic holds the topic id, so deletion stays observable.
     */
    @Enumerated(EnumType.STRING)
    private Aggregate.AggregateState state;
    @ManyToOne
    private Course course;

    public Topic() {}

    public Topic(Integer topicId, String name) {
        setTopicId(topicId);
        setName(name);
        setState(Aggregate.AggregateState.ACTIVE);
    }

    public Topic(Topic other) {
        setTopicId(other.getTopicId());
        setName(other.getName());
        setState(other.getState());
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public Integer getTopicId() {
        return topicId;
    }

    public void setTopicId(Integer topicId) {
        this.topicId = topicId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Aggregate.AggregateState getState() {
        return state;
    }

    public void setState(Aggregate.AggregateState state) {
        this.state = state;
    }

    @JsonIgnore
    public Course getCourse() {
        return course;
    }

    public void setCourse(Course course) {
        this.course = course;
    }
}
