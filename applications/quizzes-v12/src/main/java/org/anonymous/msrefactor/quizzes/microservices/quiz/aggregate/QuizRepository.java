package org.anonymous.msrefactor.quizzes.microservices.quiz.aggregate;

import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
@Transactional
public interface QuizRepository extends JpaRepository<Quiz, Integer> {
    @Query(value = "select q1.aggregateId from Quiz q1 where q1.aggregateId NOT IN (select q2.aggregateId from Quiz q2 where q2.state = 'DELETED') AND q1.quizCourseExecution.courseExecutionAggregateId = :courseExecutionAggregateId")
    Set<Integer> findAllQuizIdsByCourseExecution(Integer courseExecutionAggregateId);

    @Query("SELECT q.aggregateId FROM Quiz q")
    Set<Integer> findAllAggregateIds();

    // member-id -> root-id lookup (merge quiz <- answer): external callers still address a
    // QuizAnswer by its former aggregate id. Quiz has no custom repository (the only aggregate
    // without one), so the lookup lives here, beside the queries QuizService already uses.
    @Query(value = "select distinct q1.aggregateId from Quiz q1 join q1.quizAnswers a1 where a1.quizAnswerId = :quizAnswerId AND q1.state = 'ACTIVE'")
    List<Integer> findQuizIdsByQuizAnswerId(Integer quizAnswerId);

    @Query(value = "select a1 from Quiz a1 where a1.aggregateId = :aggregateId AND a1.state = 'ACTIVE' AND a1.version = (select max(a2.version) from Aggregate a2 where a2.aggregateId = :aggregateId)")
    Optional<Quiz> findLastAggregateVersion(Integer aggregateId);

    Optional<Quiz> findTopByOrderByVersionDesc();

    // absorbed from the dissolved answer aggregate repository (merge quiz <- answer); used by tests
    default Optional<Quiz> findLatestQuiz() {
        return findTopByOrderByVersionDesc();
    }
}
