package org.anonymous.msrefactor.quizzes.microservices.course.aggregate.sagas.repositories;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.CourseCustomRepository;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.CourseRepository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Profile("sagas")
@Service
public class CourseCustomRepositorySagas implements CourseCustomRepository {

    @Autowired
    private CourseRepository courseRepository;

    @Override
    public Optional<Integer> findCourseIdByName(String courseName) {
        return courseRepository.findCourseIdByNameForSaga(courseName);
    }

    @Override
    public Optional<Integer> findCourseIdByQuestionId(Integer questionId) {
        return courseRepository.findCourseIdsByQuestionId(questionId).stream().findFirst();
    }

    @Override
    public Set<Integer> findCourseIdsByQuestionTopicIds(List<Integer> topicIds) {
        return courseRepository.findCourseIdsByQuestionTopicIds(topicIds);
    }
}
