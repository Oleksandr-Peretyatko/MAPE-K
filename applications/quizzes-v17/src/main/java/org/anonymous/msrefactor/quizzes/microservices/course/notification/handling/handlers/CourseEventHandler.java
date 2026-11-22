package org.anonymous.msrefactor.quizzes.microservices.course.notification.handling.handlers;

import org.anonymous.msrefactor.ms.aggregate.EventHandler;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.CourseRepository;
import org.anonymous.msrefactor.quizzes.microservices.course.coordination.eventProcessing.CourseEventProcessing;

public abstract class CourseEventHandler extends EventHandler {
    protected CourseEventProcessing courseEventProcessing;

    public CourseEventHandler(CourseRepository courseRepository, CourseEventProcessing courseEventProcessing) {
        super(courseRepository);
        this.courseEventProcessing = courseEventProcessing;
    }

}
