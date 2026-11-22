package org.anonymous.msrefactor.quizzes.microservices.course.coordination.functionalities;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.TopicDto;
import org.anonymous.msrefactor.quizzes.microservices.course.coordination.sagas.CreateTopicFunctionalitySagas;
import org.anonymous.msrefactor.quizzes.microservices.course.service.CourseService;
import org.anonymous.msrefactor.quizzes.microservices.exception.QuizzesException;

import java.util.List;

import static org.anonymous.msrefactor.quizzes.microservices.exception.QuizzesErrorMessage.TOPIC_MISSING_NAME;

@Service
public class CourseFunctionalities {
    @Autowired
    private SagaUnitOfWorkService sagaUnitOfWorkService;

    @Autowired
    private CourseService courseService;

    // Absorbed from TopicFunctionalities (merge course <- topic). The former Course <-> Topic
    // saga steps are direct CourseService calls in one unit of work.
    public List<TopicDto> findTopicsByCourseAggregateId(Integer courseAggregateId) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        return courseService.findTopicsByCourseId(courseAggregateId, sagaUnitOfWork);
    }

    public TopicDto getTopicByAggregateId(Integer topicAggregateId) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        return courseService.getTopicById(topicAggregateId, sagaUnitOfWork);
    }

    public TopicDto createTopic(Integer courseAggregateId, TopicDto topicDto) throws QuizzesException {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();
        checkInput(topicDto);

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        CreateTopicFunctionalitySagas createTopicFunctionalitySagas = new CreateTopicFunctionalitySagas(
                sagaUnitOfWorkService, courseService, courseAggregateId, topicDto, sagaUnitOfWork);
        createTopicFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
        return createTopicFunctionalitySagas.getCreatedTopicDto();
    }

    public void updateTopic(TopicDto topicDto) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();
        checkInput(topicDto);

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        courseService.updateTopic(topicDto, sagaUnitOfWork);
    }

    public void deleteTopic(Integer topicAggregateId) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        courseService.deleteTopic(topicAggregateId, sagaUnitOfWork);
    }

    private void checkInput(TopicDto topicDto) {
        if (topicDto.getName() == null) {
            throw new QuizzesException(TOPIC_MISSING_NAME);
        }
    }
}
