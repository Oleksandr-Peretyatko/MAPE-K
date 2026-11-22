package org.anonymous.msrefactor.quizzes.microservices.course.aggregate;

import java.util.Optional;

public interface CourseCustomRepository {
    Optional<Integer> findCourseIdByName(String courseName);
    Optional<Integer> findCourseIdByTopicId(Integer topicId);
}
