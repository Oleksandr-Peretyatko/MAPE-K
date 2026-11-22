package org.anonymous.msrefactor.quizzes.microservices.topic.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.ServiceMapping;
import org.anonymous.msrefactor.quizzes.commands.topic.GetTopicByIdCommand;
import org.anonymous.msrefactor.quizzes.microservices.topic.aggregate.TopicDto;

public class GetTopicByIdFunctionalitySagas extends WorkflowFunctionality {
    private TopicDto topicDto;
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CommandGateway CommandGateway;

    public GetTopicByIdFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService,
                                          Integer topicAggregateId, SagaUnitOfWork unitOfWork, CommandGateway CommandGateway) {
        this.unitOfWorkService = unitOfWorkService;
        this.CommandGateway = CommandGateway;
        this.buildWorkflow(topicAggregateId, unitOfWork);
    }

    public void buildWorkflow(Integer topicAggregateId, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep getTopicStep = new SagaStep("getTopicStep", () -> {
            GetTopicByIdCommand getTopicByIdCommand = new GetTopicByIdCommand(unitOfWork, ServiceMapping.TOPIC.getServiceName(), topicAggregateId);
            TopicDto topicDto = (TopicDto) CommandGateway.send(getTopicByIdCommand);
            this.setTopicDto(topicDto);
        });

        workflow.addStep(getTopicStep);
    }

    public void setTopicDto(TopicDto topicDto) {
        this.topicDto = topicDto;
    }

    public TopicDto getTopicDto() {
        return this.topicDto;
    }

}
