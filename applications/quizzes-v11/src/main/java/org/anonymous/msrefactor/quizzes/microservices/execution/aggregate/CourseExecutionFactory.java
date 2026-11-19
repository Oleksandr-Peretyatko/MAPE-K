package org.anonymous.msrefactor.quizzes.microservices.execution.aggregate;

public interface CourseExecutionFactory {
    Execution createCourseExecution(Integer aggregateId, CourseExecutionDto courseExecutionDto, CourseExecutionCourse courseExecutionCourse);
    Execution createCourseExecutionFromExisting(Execution existingExecution);
    CourseExecutionDto createCourseExecutionDto(Execution execution);
}
