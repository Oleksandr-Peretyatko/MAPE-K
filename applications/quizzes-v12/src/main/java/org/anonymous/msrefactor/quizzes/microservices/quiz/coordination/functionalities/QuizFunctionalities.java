package org.anonymous.msrefactor.quizzes.microservices.quiz.coordination.functionalities;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.quizzes.events.DeleteCourseExecutionEvent;
import org.anonymous.msrefactor.quizzes.events.DeleteQuestionEvent;
import org.anonymous.msrefactor.quizzes.events.UpdateQuestionEvent;
import org.anonymous.msrefactor.quizzes.microservices.quiz.aggregate.QuestionAnswerDto;
import org.anonymous.msrefactor.quizzes.microservices.quiz.aggregate.QuizDto;
import org.anonymous.msrefactor.quizzes.microservices.quiz.coordination.sagas.*;
import org.anonymous.msrefactor.quizzes.microservices.quiz.service.QuizService;

import java.util.List;

@Service
public class QuizFunctionalities {
    @Autowired
    private SagaUnitOfWorkService sagaUnitOfWorkService;

    @Autowired
    private CommandGateway commandGateway;

    @Autowired
    private QuizService quizService;

    public QuizDto createQuiz(Integer courseExecutionId, QuizDto quizDto) throws Exception {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        CreateQuizFunctionalitySagas createQuizFunctionalitySagas = new CreateQuizFunctionalitySagas(
                sagaUnitOfWorkService, courseExecutionId,
                quizDto, sagaUnitOfWork, commandGateway);
        createQuizFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
        return createQuizFunctionalitySagas.getCreatedQuizDto();
    }

    public QuizDto findQuiz(Integer quizAggregateId) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        FindQuizFunctionalitySagas findQuizFunctionalitySagas = new FindQuizFunctionalitySagas(
                sagaUnitOfWorkService, quizAggregateId, sagaUnitOfWork, commandGateway);
        findQuizFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
        return findQuizFunctionalitySagas.getQuizDto();
    }

    public List<QuizDto> getAvailableQuizzes(Integer userAggregateId, Integer courseExecutionAggregateId) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        GetAvailableQuizzesFunctionalitySagas getAvailableQuizzesFunctionalitySagas = new GetAvailableQuizzesFunctionalitySagas(
                sagaUnitOfWorkService, courseExecutionAggregateId, courseExecutionAggregateId,
                sagaUnitOfWork, commandGateway);
        getAvailableQuizzesFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
        return getAvailableQuizzesFunctionalitySagas.getAvailableQuizzes();
    }

    public QuizDto updateQuiz(QuizDto quizDto) throws Exception {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        UpdateQuizFunctionalitySagas updateQuizFunctionalitySagas = new UpdateQuizFunctionalitySagas(
                sagaUnitOfWorkService, quizDto, sagaUnitOfWork, commandGateway);
        updateQuizFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
        return updateQuizFunctionalitySagas.getUpdatedQuizDto();
    }

    public void removeCourseExecutionFromQuiz(Integer quizAggregateId, DeleteCourseExecutionEvent event) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();
        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        RemoveCourseExecutionFromQuizFunctionalitySagas functionalitySagas = new RemoveCourseExecutionFromQuizFunctionalitySagas(
                sagaUnitOfWorkService, quizAggregateId, event.getPublisherAggregateId(),
                event.getPublisherAggregateVersion(), sagaUnitOfWork, commandGateway);
        functionalitySagas.executeWorkflow(sagaUnitOfWork);
    }

    public void updateQuestionInQuiz(Integer quizAggregateId, UpdateQuestionEvent event) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();
        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        UpdateQuestionInQuizFunctionalitySagas functionalitySagas = new UpdateQuestionInQuizFunctionalitySagas(
                sagaUnitOfWorkService, quizAggregateId, event.getPublisherAggregateId(), event.getTitle(),
                event.getContent(), event.getPublisherAggregateVersion(), sagaUnitOfWork, commandGateway);
        functionalitySagas.executeWorkflow(sagaUnitOfWork);
    }

    public void removeQuizQuestion(Integer quizAggregateId, DeleteQuestionEvent event) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();
        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        RemoveQuizQuestionFunctionalitySagas functionalitySagas = new RemoveQuizQuestionFunctionalitySagas(
                sagaUnitOfWorkService, quizAggregateId, event.getPublisherAggregateId(), sagaUnitOfWork,
                commandGateway);
        functionalitySagas.executeWorkflow(sagaUnitOfWork);
    }

    /************************************************
     * Absorbed from QuizAnswerFunctionalities (merge quiz <- answer). The multi-step sagas kept
     * a workflow for their remaining remote step or checked lock; the single-step ones are
     * direct QuizService calls in one unit of work.
     ************************************************/

    public void answerQuestion(Integer quizAggregateId, Integer userAggregateId,
            QuestionAnswerDto userQuestionAnswerDto) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        AnswerQuestionFunctionalitySagas answerQuestionFunctionalitySagas = new AnswerQuestionFunctionalitySagas(
                sagaUnitOfWorkService, quizService, quizAggregateId,
                userAggregateId, userQuestionAnswerDto, sagaUnitOfWork, commandGateway);
        answerQuestionFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
    }

    public void startQuiz(Integer quizAggregateId, Integer courseExecutionAggregateId, Integer userAggregateId) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        StartQuizFunctionalitySagas startQuizFunctionalitySagas = new StartQuizFunctionalitySagas(
                sagaUnitOfWorkService, quizService, quizAggregateId,
                courseExecutionAggregateId, userAggregateId, sagaUnitOfWork, commandGateway);
        startQuizFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
    }

    public void concludeQuiz(Integer quizAggregateId, Integer userAggregateId) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        ConcludeQuizFunctionalitySagas concludeQuizFunctionalitySagas = new ConcludeQuizFunctionalitySagas(
                sagaUnitOfWorkService, quizService, quizAggregateId, userAggregateId, sagaUnitOfWork);
        concludeQuizFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
    }

    public void removeQuizAnswer(Integer quizAnswerAggregateId) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();
        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        quizService.removeQuizAnswer(quizAnswerAggregateId, sagaUnitOfWork);
    }

    public void removeUserFromQuizAnswers(Integer quizAggregateId, Integer userAggregateId,
            Long publisherAggregateVersion) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();
        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        quizService.removeStudentFromQuizAnswers(quizAggregateId, userAggregateId, publisherAggregateVersion,
                sagaUnitOfWork);
    }

    public void removeQuestionFromQuizAnswer(Integer quizAnswerAggregateId, Integer questionAggregateId,
            Long publisherAggregateVersion) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();
        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        quizService.removeQuestionFromQuizAnswer(quizAnswerAggregateId, questionAggregateId,
                publisherAggregateVersion, sagaUnitOfWork);
    }

    public void updateUserNameInQuizAnswers(Integer quizAggregateId, Integer executionAggregateId,
            Long publisherAggregateVersion, Integer studentAggregateId, String updatedName) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();
        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        quizService.updateStudentNameInQuizAnswers(quizAggregateId, executionAggregateId,
                publisherAggregateVersion, studentAggregateId, updatedName, sagaUnitOfWork);
    }

}
