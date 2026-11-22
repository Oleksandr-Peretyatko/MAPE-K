package org.anonymous.msrefactor.quizzes.microservices.course.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.ServiceMapping;
import org.anonymous.msrefactor.quizzes.commands.course.FindQuestionsByCourseAggregateIdCommand;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.QuestionDto;

import java.util.List;

public class FindQuestionsByCourseFunctionalitySagas extends WorkflowFunctionality {
    private List<QuestionDto> questions;
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CommandGateway commandGateway;

    public FindQuestionsByCourseFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService,
                                                   Integer courseAggregateId, SagaUnitOfWork unitOfWork, CommandGateway CommandGateway) {
        this.unitOfWorkService = unitOfWorkService;
        this.commandGateway = CommandGateway;
        this.buildWorkflow(courseAggregateId, unitOfWork);
    }

    public void buildWorkflow(Integer courseAggregateId, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep findQuestionsStep = new SagaStep("findQuestionsStep", () -> {
            FindQuestionsByCourseAggregateIdCommand findQuestionsByCourseAggregateIdCommand = new FindQuestionsByCourseAggregateIdCommand(unitOfWork, ServiceMapping.COURSE.getServiceName(), courseAggregateId);
            List<QuestionDto> questions = (List<QuestionDto>) commandGateway.send(findQuestionsByCourseAggregateIdCommand);
            this.setQuestions(questions);
        });

        workflow.addStep(findQuestionsStep);
    }

    public List<QuestionDto> getQuestions() {
        return questions;
    }

    public void setQuestions(List<QuestionDto> questions) {
        this.questions = questions;
    }
}
