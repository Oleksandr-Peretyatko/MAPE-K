package org.anonymous.msrefactor.quizzes.microservices.topic.coordination.functionalities;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.quizzes.microservices.exception.QuizzesException;
import org.anonymous.msrefactor.quizzes.microservices.topic.aggregate.TopicDto;
import org.anonymous.msrefactor.quizzes.microservices.topic.coordination.sagas.*;

import java.util.List;

import static org.anonymous.msrefactor.quizzes.microservices.exception.QuizzesErrorMessage.TOPIC_MISSING_NAME;

@Service
public class TopicFunctionalities {
    @Autowired
    private SagaUnitOfWorkService sagaUnitOfWorkService;

    @Autowired
    private CommandGateway commandGateway;

    public List<TopicDto> findTopicsByCourseAggregateId(Integer courseAggregateId) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        FindTopicsByCourseFunctionalitySagas findTopicsByCourseFunctionalitySagas = new FindTopicsByCourseFunctionalitySagas(
                sagaUnitOfWorkService, courseAggregateId, sagaUnitOfWork, commandGateway);
        findTopicsByCourseFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
        return findTopicsByCourseFunctionalitySagas.getTopics();
    }

    public TopicDto getTopicByAggregateId(Integer topicAggregateId) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        GetTopicByIdFunctionalitySagas getTopicByIdFunctionalitySagas = new GetTopicByIdFunctionalitySagas(
                sagaUnitOfWorkService, topicAggregateId, sagaUnitOfWork, commandGateway);
        getTopicByIdFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
        return getTopicByIdFunctionalitySagas.getTopicDto();
    }

    public TopicDto createTopic(Integer courseAggregateId, TopicDto topicDto) throws QuizzesException {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();
        checkInput(topicDto);

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        CreateTopicFunctionalitySagas createTopicFunctionalitySagas = new CreateTopicFunctionalitySagas(
                sagaUnitOfWorkService, courseAggregateId, topicDto, sagaUnitOfWork,
                commandGateway);
        createTopicFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
        return createTopicFunctionalitySagas.getCreatedTopicDto();
    }

    public void updateTopic(TopicDto topicDto) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();
        checkInput(topicDto);

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        UpdateTopicFunctionalitySagas updateTopicFunctionalitySagas = new UpdateTopicFunctionalitySagas(
                sagaUnitOfWorkService, topicDto, sagaUnitOfWork, commandGateway);
        updateTopicFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
    }

    public void deleteTopic(Integer topicAggregateId) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        DeleteTopicFunctionalitySagas deleteTopicFunctionalitySagas = new DeleteTopicFunctionalitySagas(
                sagaUnitOfWorkService, topicAggregateId, sagaUnitOfWork, commandGateway);
        deleteTopicFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
    }

    private void checkInput(TopicDto topicDto) {
        if (topicDto.getName() == null) {
            throw new QuizzesException(TOPIC_MISSING_NAME);
        }
    }
}
