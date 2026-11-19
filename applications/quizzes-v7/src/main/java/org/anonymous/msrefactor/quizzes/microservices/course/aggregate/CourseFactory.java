package org.anonymous.msrefactor.quizzes.microservices.course.aggregate;

import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.CourseExecutionDto;

public interface CourseFactory {
    Course createCourse(Integer aggregateId, CourseExecutionDto courseExecutionDto);
    Course createCourseFromExisting(Course existing);
    CourseDto createCourseDto(Course course);
}
