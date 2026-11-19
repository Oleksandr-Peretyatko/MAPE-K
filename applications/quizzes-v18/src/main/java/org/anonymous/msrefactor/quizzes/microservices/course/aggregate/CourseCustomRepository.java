package org.anonymous.msrefactor.quizzes.microservices.course.aggregate;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface CourseCustomRepository {
    Optional<Integer> findCourseIdByName(String courseName);
    Optional<Integer> findCourseIdByQuestionId(Integer questionId);
    Optional<Integer> findCourseIdByTopicId(Integer topicId);
    Set<Integer> findCourseIdsWithQuestionsOfTopics(List<Integer> topicIds);
}
