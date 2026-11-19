package org.anonymous.msrefactor.quizzes.microservices.course.aggregate.sagas.factories;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.Course;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.CourseDto;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.CourseFactory;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.sagas.SagaCourse;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.CourseExecutionDto;

@Service
@Profile("sagas")
public class SagasCourseFactory implements CourseFactory {
    @Override
    public Course createCourse(Integer aggregateId, CourseExecutionDto courseExecutionDto) {
        return new SagaCourse(aggregateId, courseExecutionDto);
    }

    @Override
    public Course createCourseFromExisting(Course existing) {
        return new SagaCourse((SagaCourse) existing);
    }

    @Override
    public CourseDto createCourseDto(Course course) {
        return new CourseDto(course);
    }
}
