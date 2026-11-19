package org.anonymous.msrefactor.quizzes.microservices.course.aggregate;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;

@Entity
public class Topic {
    @Id
    @GeneratedValue
    private Integer id;
    private Integer topicId;
    @Column
    private String name;
    private boolean deleted;
    @ManyToOne
    private Course course;

    public Topic() {}

    public Topic(Integer topicId, String name) {
        setTopicId(topicId);
        setName(name);
        setDeleted(false);
    }

    public Topic(Topic other) {
        setTopicId(other.getTopicId());
        setName(other.getName());
        setDeleted(other.isDeleted());
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

    public boolean isDeleted() {
        return deleted;
    }

    public void setDeleted(boolean deleted) {
        this.deleted = deleted;
    }

    @JsonIgnore
    public Course getCourse() {
        return course;
    }

    public void setCourse(Course course) {
        this.course = course;
    }
}
