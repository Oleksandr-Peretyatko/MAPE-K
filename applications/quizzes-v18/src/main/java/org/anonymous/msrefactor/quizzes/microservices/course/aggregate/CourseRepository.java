package org.anonymous.msrefactor.quizzes.microservices.course.aggregate;

import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
@Transactional
public interface CourseRepository extends JpaRepository<Course, Integer> {
    @Query(value = "select c1.aggregateId from Course c1 where c1.name = :courseName AND c1.state = 'ACTIVE' AND c1.sagaState = org.anonymous.msrefactor.ms.transaction.sagas.aggregate.GenericSagaState.NOT_IN_SAGA")
    Optional<Integer> findCourseIdByNameForSaga(String courseName);

    @Query(value = "select distinct c.aggregateId from Course c join c.questions q where q.questionId = :questionId AND c.state != 'DELETED'")
    Set<Integer> findCourseIdsByQuestionId(Integer questionId);

    @Query(value = "select distinct c.aggregateId from Course c join c.topics t where t.topicId = :topicId AND c.state != 'DELETED'")
    Set<Integer> findCourseIdsByTopicId(Integer topicId);

    @Query(value = "select distinct c.aggregateId from Course c join c.questions q join q.questionTopics qt where qt.topicId in :topicIds AND c.state != 'DELETED'")
    Set<Integer> findCourseIdsWithQuestionsOfTopics(List<Integer> topicIds);

    Optional<Course> findTopByOrderByVersionDesc();

    default Optional<Course> findLatestCourse() {
        return findTopByOrderByVersionDesc();
    }
}
