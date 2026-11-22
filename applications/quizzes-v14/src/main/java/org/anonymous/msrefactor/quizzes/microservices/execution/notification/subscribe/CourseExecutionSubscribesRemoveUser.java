package org.anonymous.msrefactor.quizzes.microservices.execution.notification.subscribe;

import org.anonymous.msrefactor.ms.aggregate.Event;
import org.anonymous.msrefactor.ms.aggregate.EventSubscription;
import org.anonymous.msrefactor.quizzes.events.DeleteUserEvent;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.CourseExecutionStudent;

public class CourseExecutionSubscribesRemoveUser extends EventSubscription {
    private CourseExecutionStudent courseExecutionStudent;

    public CourseExecutionSubscribesRemoveUser(CourseExecutionStudent courseExecutionStudent) {
        super(courseExecutionStudent.getUserAggregateId(),
                courseExecutionStudent.getUserVersion(),
                DeleteUserEvent.class.getSimpleName());
        this.courseExecutionStudent = courseExecutionStudent;
    }

    public CourseExecutionSubscribesRemoveUser() {}

    public boolean subscribesEvent(Event event) {
         return super.subscribesEvent(event) && checkCourseExecutionInfo((DeleteUserEvent)event);
    }

    private boolean checkCourseExecutionInfo(DeleteUserEvent event) {
        return this.courseExecutionStudent.getUserAggregateId().equals(event.getPublisherAggregateId()) && this.courseExecutionStudent.isActive();
    }

}