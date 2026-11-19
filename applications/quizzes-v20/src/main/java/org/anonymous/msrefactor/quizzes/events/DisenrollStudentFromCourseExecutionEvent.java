package org.anonymous.msrefactor.quizzes.events;

import jakarta.persistence.Entity;
import org.anonymous.msrefactor.ms.aggregate.Event;

@Entity
public class DisenrollStudentFromCourseExecutionEvent extends Event {
    private Integer studentAggregateId;

    public DisenrollStudentFromCourseExecutionEvent() {
        super();
    }

    public DisenrollStudentFromCourseExecutionEvent(Integer courseExecutionAggregateId, Integer studentAggregateId) {
        super(courseExecutionAggregateId);
        setStudentAggregateId(studentAggregateId);
    }

    public Integer getStudentAggregateId() {
        return studentAggregateId;
    }

    public void setStudentAggregateId(Integer studentAggregateId) {
        this.studentAggregateId = studentAggregateId;
    }
}
