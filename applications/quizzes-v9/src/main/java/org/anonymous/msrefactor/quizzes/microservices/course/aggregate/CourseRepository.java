package org.anonymous.msrefactor.quizzes.microservices.course.aggregate;

import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@Transactional
public interface CourseRepository extends JpaRepository<Course, Integer> {
    @Query(value = "select c1.aggregateId from Course c1 where c1.name = :courseName AND c1.state = 'ACTIVE' AND c1.sagaState = org.anonymous.msrefactor.ms.transaction.sagas.aggregate.GenericSagaState.NOT_IN_SAGA")
    Optional<Integer> findCourseIdByNameForSaga(String courseName);

    @Query(value = "select distinct c1.aggregateId from Course c1 join c1.topics t1 where t1.topicId = :topicId AND c1.state = 'ACTIVE'")
    List<Integer> findCourseIdsByTopicId(Integer topicId);

    Optional<Course> findTopByOrderByVersionDesc();

    default Optional<Course> findLatestCourse() {
        return findTopByOrderByVersionDesc();
    }
}
