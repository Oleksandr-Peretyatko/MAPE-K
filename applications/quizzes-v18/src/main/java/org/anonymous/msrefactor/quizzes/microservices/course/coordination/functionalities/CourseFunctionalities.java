package org.anonymous.msrefactor.quizzes.microservices.course.coordination.functionalities;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.QuestionDto;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.TopicDto;
import org.anonymous.msrefactor.quizzes.microservices.course.coordination.sagas.*;
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
                questionDto, sagaUnitOfWork);
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
                topicIds, sagaUnitOfWork);
        updateQuestionTopicsFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
    }

    public void updateQuestionTopicsAsync(Integer questionAggregateId, List<Integer> topicIds) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        UpdateQuestionTopicsAsyncFunctionalitySagas updateQuestionTopicsAsyncFunctionalitySagas =
                new UpdateQuestionTopicsAsyncFunctionalitySagas(
                        sagaUnitOfWorkService, courseService, questionAggregateId,
                        topicIds, sagaUnitOfWork);
        updateQuestionTopicsAsyncFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
    }

    /************************************************
     * TOPIC USE CASES (formerly TopicFunctionalities)
     ************************************************/

    public List<TopicDto> findTopicsByCourseAggregateId(Integer courseAggregateId) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        FindTopicsByCourseFunctionalitySagas findTopicsByCourseFunctionalitySagas = new FindTopicsByCourseFunctionalitySagas(
                sagaUnitOfWorkService, courseService, courseAggregateId, sagaUnitOfWork);
        findTopicsByCourseFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
        return findTopicsByCourseFunctionalitySagas.getTopics();
    }

    public TopicDto getTopicByAggregateId(Integer topicAggregateId) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        GetTopicByIdFunctionalitySagas getTopicByIdFunctionalitySagas = new GetTopicByIdFunctionalitySagas(
                sagaUnitOfWorkService, courseService, topicAggregateId, sagaUnitOfWork);
        getTopicByIdFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
        return getTopicByIdFunctionalitySagas.getTopicDto();
    }

    public TopicDto createTopic(Integer courseAggregateId, TopicDto topicDto) throws QuizzesException {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();
        checkTopicInput(topicDto);

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        CreateTopicFunctionalitySagas createTopicFunctionalitySagas = new CreateTopicFunctionalitySagas(
                sagaUnitOfWorkService, courseService, courseAggregateId, topicDto, sagaUnitOfWork);
        createTopicFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
        return createTopicFunctionalitySagas.getCreatedTopicDto();
    }

    public void updateTopic(TopicDto topicDto) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();
        checkTopicInput(topicDto);

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        UpdateTopicFunctionalitySagas updateTopicFunctionalitySagas = new UpdateTopicFunctionalitySagas(
                sagaUnitOfWorkService, courseService, topicDto, sagaUnitOfWork);
        updateTopicFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
    }

    public void deleteTopic(Integer topicAggregateId) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        DeleteTopicFunctionalitySagas deleteTopicFunctionalitySagas = new DeleteTopicFunctionalitySagas(
                sagaUnitOfWorkService, courseService, topicAggregateId, sagaUnitOfWork);
        deleteTopicFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
    }

    private void checkTopicInput(TopicDto topicDto) {
        if (topicDto.getName() == null) {
            throw new QuizzesException(TOPIC_MISSING_NAME);
        }
    }
}
