package org.anonymous.msrefactor.quizzes.microservices.answer.coordination.functionalities;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.quizzes.microservices.answer.aggregate.QuestionAnswerDto;
import org.anonymous.msrefactor.quizzes.microservices.answer.aggregate.QuizAnswerFactory;
import org.anonymous.msrefactor.quizzes.microservices.answer.coordination.sagas.*;

@Service
public class QuizAnswerFunctionalities {
    @Autowired
    private SagaUnitOfWorkService sagaUnitOfWorkService;
    @Autowired
    private QuizAnswerFactory quizAnswerFactory;
    @Autowired
    private CommandGateway commandGateway;

    public void answerQuestion(Integer quizAggregateId, Integer userAggregateId,
            QuestionAnswerDto userQuestionAnswerDto) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        AnswerQuestionFunctionalitySagas answerQuestionFunctionalitySagas = new AnswerQuestionFunctionalitySagas(
                sagaUnitOfWorkService, quizAnswerFactory, quizAggregateId,
                userAggregateId, userQuestionAnswerDto, sagaUnitOfWork, commandGateway);
        answerQuestionFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
    }

    public void startQuiz(Integer quizAggregateId, Integer courseExecutionAggregateId, Integer userAggregateId) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        StartQuizFunctionalitySagas startQuizFunctionalitySagas = new StartQuizFunctionalitySagas(
                sagaUnitOfWorkService, quizAggregateId,
                courseExecutionAggregateId, userAggregateId, sagaUnitOfWork, commandGateway);
        startQuizFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
    }

    public void concludeQuiz(Integer quizAggregateId, Integer userAggregateId) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();

        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        ConcludeQuizFunctionalitySagas concludeQuizFunctionalitySagas = new ConcludeQuizFunctionalitySagas(
                sagaUnitOfWorkService, quizAggregateId, userAggregateId, sagaUnitOfWork,
                commandGateway);
        concludeQuizFunctionalitySagas.executeWorkflow(sagaUnitOfWork);
    }

    public void removeUserFromQuizAnswer(Integer quizAnswerAggregateId, Integer userAggregateId,
            Long publisherAggregateVersion) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();
        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        RemoveUserFromQuizAnswerFunctionalitySagas functionalitySagas = new RemoveUserFromQuizAnswerFunctionalitySagas(
                sagaUnitOfWorkService, quizAnswerAggregateId, userAggregateId, publisherAggregateVersion,
                sagaUnitOfWork, commandGateway);
        functionalitySagas.executeWorkflow(sagaUnitOfWork);
    }

    public void removeQuestionFromQuizAnswer(Integer quizAnswerAggregateId, Integer questionAggregateId,
            Long publisherAggregateVersion) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();
        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        RemoveQuestionFromQuizAnswerFunctionalitySagas functionalitySagas = new RemoveQuestionFromQuizAnswerFunctionalitySagas(
                sagaUnitOfWorkService, quizAnswerAggregateId, questionAggregateId, publisherAggregateVersion,
                sagaUnitOfWork, commandGateway);
        functionalitySagas.executeWorkflow(sagaUnitOfWork);
    }

    public void removeQuizAnswer(Integer quizAnswerAggregateId) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();
        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        RemoveQuizAnswerFunctionalitySagas functionalitySagas = new RemoveQuizAnswerFunctionalitySagas(
                sagaUnitOfWorkService, quizAnswerAggregateId, sagaUnitOfWork, commandGateway);
        functionalitySagas.executeWorkflow(sagaUnitOfWork);
    }

    public void updateUserNameInQuizAnswer(Integer quizAnswerAggregateId, Integer publisherAggregateId,
            Long publisherAggregateVersion, Integer studentAggregateId, String updatedName) {
        String functionalityName = new Throwable().getStackTrace()[0].getMethodName();
        SagaUnitOfWork sagaUnitOfWork = sagaUnitOfWorkService.createUnitOfWork(functionalityName);
        UpdateUserNameInQuizAnswerFunctionalitySagas functionalitySagas = new UpdateUserNameInQuizAnswerFunctionalitySagas(
                sagaUnitOfWorkService, quizAnswerAggregateId, publisherAggregateId, publisherAggregateVersion,
                studentAggregateId, updatedName, sagaUnitOfWork, commandGateway);
        functionalitySagas.executeWorkflow(sagaUnitOfWork);
    }

}