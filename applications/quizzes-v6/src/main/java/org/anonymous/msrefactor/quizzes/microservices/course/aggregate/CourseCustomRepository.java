package org.anonymous.msrefactor.quizzes.microservices.course.aggregate;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface CourseCustomRepository {
    Optional<Integer> findCourseIdByName(String courseName);

    // Member-id -> root-id lookup for the absorbed Question entities (merge course <- question).
    Optional<Integer> findCourseIdByQuestionId(Integer questionId);

    // Courses holding a Question whose topics intersect the given topic ids (merge course <- question).
    Set<Integer> findCourseIdsByQuestionTopicIds(List<Integer> topicIds);
}
