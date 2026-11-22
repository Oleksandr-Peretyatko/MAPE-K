package org.anonymous.msrefactor.quizzes.microservices.course.coordination.functionalities;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.quizzes.microservices.exception.QuizzesException;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.QuestionDto;
import org.anonymous.msrefactor.quizzes.microservices.course.coordination.sagas.*;

import java.util.List;

@Service
public class CourseFunctionalities {
    @Autowired
    private SagaUnitOfWorkService sagaUnitOfWorkService;

    @Autowired
    private CommandGateway commandGateway;

    public QuestionDto findQuestionByAggregateId(Integer aggregateId) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        FindQuestionByAggregateIdFunctionalitySagas findQuestionByAggregateIdFunctionalitySagas = new FindQuestionByAggregateIdFunctionalitySagas(
                sagaUnitOfWorkService, aggregateId, sagaUnitOfWork, commandGateway);
        findQuestionByAggregateIdFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
        return findQuestionByAggregateIdFunctionalitySagas.getQuestionDto();
    }

    public List<QuestionDto> findQuestionsByCourseAggregateId(Integer courseAggregateId) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        FindQuestionsByCourseFunctionalitySagas findQuestionsByCourseFunctionalitySagas = new FindQuestionsByCourseFunctionalitySagas(
                sagaUnitOfWorkService, courseAggregateId, sagaUnitOfWork, commandGateway);
        findQuestionsByCourseFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
        return findQuestionsByCourseFunctionalitySagas.getQuestions();
    }

    public QuestionDto createQuestion(Integer courseAggregateId, QuestionDto questionDto) throws QuizzesException {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        CreateQuestionFunctionalitySagas createQuestionFunctionalitySagas = new CreateQuestionFunctionalitySagas(
                sagaUnitOfWorkService, courseAggregateId,
                questionDto, sagaUnitOfWork, commandGateway);
        createQuestionFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
        return createQuestionFunctionalitySagas.getCreatedQuestion();
    }

    public void updateQuestion(QuestionDto questionDto) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        UpdateQuestionFunctionalitySagas updateQuestionFunctionalitySagas = new UpdateQuestionFunctionalitySagas(
                sagaUnitOfWorkService, questionDto, sagaUnitOfWork,
                commandGateway);
        updateQuestionFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
    }

    public void removeQuestion(Integer questionAggregateId) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        RemoveQuestionFunctionalitySagas removeQuestionFunctionalitySagas = new RemoveQuestionFunctionalitySagas(
                sagaUnitOfWorkService, questionAggregateId, sagaUnitOfWork, commandGateway);
        removeQuestionFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
    }

    public void updateQuestionTopics(Integer courseAggregateId, List<Integer> topicIds) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        UpdateQuestionTopicsFunctionalitySagas updateQuestionTopicsFunctionalitySagas = new UpdateQuestionTopicsFunctionalitySagas(
                sagaUnitOfWorkService, courseAggregateId,
                topicIds, sagaUnitOfWork, commandGateway);
        updateQuestionTopicsFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
    }

    public void updateQuestionTopicsAsync(Integer courseAggregateId, List<Integer> topicIds) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        UpdateQuestionTopicsAsyncFunctionalitySagas updateQuestionTopicsAsyncFunctionalitySagas =
                new UpdateQuestionTopicsAsyncFunctionalitySagas(
                        sagaUnitOfWorkService, courseAggregateId,
                        topicIds, sagaUnitOfWork, commandGateway);
        updateQuestionTopicsAsyncFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
    }

    public void updateTopicInQuestion(Integer questionAggregateId, Integer aggregateTopicId, String topicName,
            Long eventVersion) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();
        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        UpdateTopicInQuestionFunctionalitySagas functionalitySagas = new UpdateTopicInQuestionFunctionalitySagas(
                sagaUnitOfWorkService, questionAggregateId, aggregateTopicId, topicName, eventVersion,
                sagaUnitOfWork, commandGateway);
        functionalitySagas.executeWorkflow(sagaUnitOfWork);
    }

    public void deleteTopicInQuestion(Integer questionAggregateId, Integer aggregateTopicId, Long eventVersion) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();
        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        DeleteTopicInQuestionFunctionalitySagas functionalitySagas = new DeleteTopicInQuestionFunctionalitySagas(
                sagaUnitOfWorkService, questionAggregateId, aggregateTopicId, eventVersion, sagaUnitOfWork,
                commandGateway);
        functionalitySagas.executeWorkflow(sagaUnitOfWork);
    }
}
