package org.anonymous.msrefactor.quizzes.events;

import jakarta.persistence.Entity;
import org.anonymous.msrefactor.ms.aggregate.Event;

@Entity
public class UpdateStudentNameEvent extends Event {
    private Integer studentAggregateId;
    private String updatedName;

    public UpdateStudentNameEvent() {
    }

    public UpdateStudentNameEvent(Integer executionAggregateId, Integer studentAggregateId, String updatedName) {
        super(executionAggregateId);
        setStudentAggregateId(studentAggregateId);
        setUpdatedName(updatedName);
    }

    public Integer getStudentAggregateId() {
        return studentAggregateId;
    }

    public void setStudentAggregateId(Integer studentAggregateId) {
        this.studentAggregateId = studentAggregateId;
    }

    public String getUpdatedName() {
        return updatedName;
    }

    public void setUpdatedName(String updatedName) {
        this.updatedName = updatedName;
    }
}
