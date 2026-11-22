package org.anonymous.msrefactor.quizzes.microservices.course.coordination.functionalities;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.quizzes.events.DeleteTopicEvent;
import org.anonymous.msrefactor.quizzes.events.UpdateTopicEvent;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.QuestionDto;
import org.anonymous.msrefactor.quizzes.microservices.course.coordination.sagas.*;
import org.anonymous.msrefactor.quizzes.microservices.course.service.CourseService;
import org.anonymous.msrefactor.quizzes.microservices.exception.QuizzesException;

import java.util.List;

@Service
public class CourseFunctionalities {
    @Autowired
    private SagaUnitOfWorkService sagaUnitOfWorkService;

    @Autowired
    private CourseService courseService;

    @Autowired
    private CommandGateway commandGateway;

    public QuestionDto findQuestionByAggregateId(Integer aggregateId) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        FindQuestionByAggregateIdFunctionalitySagas findQuestionByAggregateIdFunctionalitySagas = new FindQuestionByAggregateIdFunctionalitySagas(
                sagaUnitOfWorkService, courseService, aggregateId, sagaUnitOfWork);
        findQuestionByAggregateIdFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
        return findQuestionByAggregateIdFunctionalitySagas.getQuestionDto();
    }

    public List<QuestionDto> findQuestionsByCourseAggregateId(Integer courseAggregateId) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        FindQuestionsByCourseFunctionalitySagas findQuestionsByCourseFunctionalitySagas = new FindQuestionsByCourseFunctionalitySagas(
                sagaUnitOfWorkService, courseService, courseAggregateId, sagaUnitOfWork);
        findQuestionsByCourseFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
        return findQuestionsByCourseFunctionalitySagas.getQuestions();
    }

    public QuestionDto createQuestion(Integer courseAggregateId, QuestionDto questionDto) throws QuizzesException {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        CreateQuestionFunctionalitySagas createQuestionFunctionalitySagas = new CreateQuestionFunctionalitySagas(
                sagaUnitOfWorkService, courseService, courseAggregateId,
                questionDto, sagaUnitOfWork, commandGateway);
        createQuestionFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
        return createQuestionFunctionalitySagas.getCreatedQuestion();
    }

    public void updateQuestion(QuestionDto questionDto) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        UpdateQuestionFunctionalitySagas updateQuestionFunctionalitySagas = new UpdateQuestionFunctionalitySagas(
                sagaUnitOfWorkService, courseService, questionDto, sagaUnitOfWork);
        updateQuestionFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
    }

    public void removeQuestion(Integer questionAggregateId) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        RemoveQuestionFunctionalitySagas removeQuestionFunctionalitySagas = new RemoveQuestionFunctionalitySagas(
                sagaUnitOfWorkService, courseService, questionAggregateId, sagaUnitOfWork);
        removeQuestionFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
    }

    public void updateQuestionTopics(Integer questionAggregateId, List<Integer> topicIds) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        UpdateQuestionTopicsFunctionalitySagas updateQuestionTopicsFunctionalitySagas = new UpdateQuestionTopicsFunctionalitySagas(
                sagaUnitOfWorkService, courseService, questionAggregateId,
                topicIds, sagaUnitOfWork, commandGateway);
        updateQuestionTopicsFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
    }

    public void updateQuestionTopicsAsync(Integer questionAggregateId, List<Integer> topicIds) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        UpdateQuestionTopicsAsyncFunctionalitySagas updateQuestionTopicsAsyncFunctionalitySagas =
                new UpdateQuestionTopicsAsyncFunctionalitySagas(
                        sagaUnitOfWorkService, courseService, questionAggregateId,
                        topicIds, sagaUnitOfWork, commandGateway);
        updateQuestionTopicsAsyncFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
    }

    public void updateTopicInQuestions(Integer courseAggregateId, UpdateTopicEvent event) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();
        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        UpdateTopicInQuestionFunctionalitySagas functionalitySagas = new UpdateTopicInQuestionFunctionalitySagas(
                sagaUnitOfWorkService, courseService, courseAggregateId, event.getPublisherAggregateId(),
                event.getTopicName(), event.getPublisherAggregateVersion(), sagaUnitOfWork);
        functionalitySagas.executeWorkflow(sagaUnitOfWork);
    }

    public void deleteTopicInQuestions(Integer courseAggregateId, DeleteTopicEvent event) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();
        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        DeleteTopicInQuestionFunctionalitySagas functionalitySagas = new DeleteTopicInQuestionFunctionalitySagas(
                sagaUnitOfWorkService, courseService, courseAggregateId, event.getPublisherAggregateId(),
                event.getPublisherAggregateVersion(), sagaUnitOfWork);
        functionalitySagas.executeWorkflow(sagaUnitOfWork);
    }
}
