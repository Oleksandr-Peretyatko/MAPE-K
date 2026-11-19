package org.anonymous.msrefactor.quizzes.microservices.topic.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.transaction.sagas.aggregate.GenericSagaState;
import org.anonymous.msrefactor.ms.transaction.sagas.messaging.SagaCommand;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.ServiceMapping;
import org.anonymous.msrefactor.quizzes.commands.topic.DeleteTopicCommand;
import org.anonymous.msrefactor.quizzes.commands.topic.GetTopicByIdCommand;
import org.anonymous.msrefactor.quizzes.microservices.topic.aggregate.TopicDto;
import org.anonymous.msrefactor.quizzes.microservices.topic.aggregate.sagas.states.TopicSagaState;

import java.util.ArrayList;
import java.util.Arrays;

public class DeleteTopicFunctionalitySagas extends WorkflowFunctionality {
    private TopicDto topic;
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CommandGateway commandGateway;

    public DeleteTopicFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService,
                                         Integer topicAggregateId, SagaUnitOfWork unitOfWork, CommandGateway commandGateway) {
        this.unitOfWorkService = unitOfWorkService;
        this.commandGateway = commandGateway;
        this.buildWorkflow(topicAggregateId, unitOfWork);
    }

    public void buildWorkflow(Integer topicAggregateId, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep getTopicStep = new SagaStep("getTopicStep", () -> {
            GetTopicByIdCommand getTopicByIdCommand = new GetTopicByIdCommand(unitOfWork, ServiceMapping.TOPIC.getServiceName(), topicAggregateId);
            SagaCommand sagaCommand = new SagaCommand(getTopicByIdCommand);
            sagaCommand.setSemanticLock(TopicSagaState.READ_TOPIC);
            TopicDto topicDto = (TopicDto) commandGateway.send(sagaCommand);
            this.setTopic(topicDto);
        });

        getTopicStep.registerCompensation(() -> {
            Command command = new Command(unitOfWork, ServiceMapping.TOPIC.getServiceName(), topicAggregateId);
            SagaCommand sagaCommand = new SagaCommand(command);
            sagaCommand.setSemanticLock(GenericSagaState.NOT_IN_SAGA);
            commandGateway.send(sagaCommand);
        }, unitOfWork);

        SagaStep deleteTopicStep = new SagaStep("deleteTopicStep", () -> {
            DeleteTopicCommand deleteTopicCommand = new DeleteTopicCommand(unitOfWork, ServiceMapping.TOPIC.getServiceName(), topicAggregateId);
            commandGateway.send(deleteTopicCommand);
        }, new ArrayList<>(Arrays.asList(getTopicStep)));

        workflow.addStep(getTopicStep);
        workflow.addStep(deleteTopicStep);
    }

    public TopicDto getTopic() {
        return topic;
    }

    public void setTopic(TopicDto topic) {
        this.topic = topic;
    }
}
