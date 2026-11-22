package org.anonymous.msrefactor.quizzes.microservices.quiz.coordination.functionalities;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.quizzes.events.DeleteCourseExecutionEvent;
import org.anonymous.msrefactor.quizzes.events.DeleteQuestionEvent;
import org.anonymous.msrefactor.quizzes.events.UpdateQuestionEvent;
import org.anonymous.msrefactor.quizzes.microservices.quiz.aggregate.QuizDto;
import org.anonymous.msrefactor.quizzes.microservices.quiz.coordination.sagas.*;

import java.util.List;

@Service
public class QuizFunctionalities {
    @Autowired
    private SagaUnitOfWorkService sagaUnitOfWorkService;

    @Autowired
    private CommandGateway commandGateway;

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

}
