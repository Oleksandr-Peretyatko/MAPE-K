package org.anonymous.msrefactor.quizzes.microservices.question.coordination.sagas;

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
import org.anonymous.msrefactor.quizzes.commands.question.GetQuestionByIdCommand;
import org.anonymous.msrefactor.quizzes.commands.question.UpdateQuestionTopicsCommand;
import org.anonymous.msrefactor.quizzes.commands.topic.GetTopicByIdCommand;
import org.anonymous.msrefactor.quizzes.microservices.question.aggregate.QuestionDto;
import org.anonymous.msrefactor.quizzes.microservices.question.aggregate.QuestionTopic;
import org.anonymous.msrefactor.quizzes.microservices.question.aggregate.sagas.states.QuestionSagaState;
import org.anonymous.msrefactor.quizzes.microservices.topic.aggregate.TopicDto;
import org.anonymous.msrefactor.quizzes.microservices.topic.aggregate.sagas.states.TopicSagaState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class UpdateQuestionTopicsAsyncFunctionalitySagas extends WorkflowFunctionality {
    private Set<QuestionTopic> topics;
    private QuestionDto question;
    private Set<TopicDto> topicDtos;
    private CompletableFuture<Set<QuestionTopic>> topicsFuture;
    private CompletableFuture<QuestionDto> questionFuture;
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CommandGateway commandGateway;

    public UpdateQuestionTopicsAsyncFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService,
                                                       Integer courseAggregateId, List<Integer> topicIds,
                                                       SagaUnitOfWork unitOfWork, CommandGateway commandGateway) {
        this.unitOfWorkService = unitOfWorkService;
        this.commandGateway = commandGateway;
        this.buildWorkflow(courseAggregateId, topicIds, unitOfWork);
    }

    public void buildWorkflow(Integer courseAggregateId, List<Integer> topicIds, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep getTopicsAsyncStep = new SagaStep("getTopicsAsyncStep", () -> {
            List<CompletableFuture<QuestionTopic>> topicFutures = topicIds.stream()
                    .map(topicId -> {
                        GetTopicByIdCommand getTopicByIdCommand = new GetTopicByIdCommand(unitOfWork,
                                ServiceMapping.TOPIC.getServiceName(), topicId);
                        SagaCommand sagaCommand = new SagaCommand(getTopicByIdCommand);
                        sagaCommand.setSemanticLock(TopicSagaState.READ_TOPIC);
                        return commandGateway.sendAsync(sagaCommand)
                                .thenApply(dto -> new QuestionTopic((TopicDto) dto));
                    })
                    .collect(Collectors.toList());

            this.topicsFuture = CompletableFuture.allOf(topicFutures.toArray(new CompletableFuture[0]))
                    .thenApply(ignored -> topicFutures.stream().map(CompletableFuture::join).collect(Collectors.toSet()));
        });

        getTopicsAsyncStep.registerCompensation(() -> {
            topicIds.forEach(topicId -> {
                Command command = new Command(unitOfWork, ServiceMapping.TOPIC.getServiceName(), topicId);
                SagaCommand sagaCommand = new SagaCommand(command);
                sagaCommand.setSemanticLock(GenericSagaState.NOT_IN_SAGA);
                commandGateway.send(sagaCommand);
            });
        }, unitOfWork);

        SagaStep getQuestionAsyncStep = new SagaStep("getQuestionAsyncStep", () -> {
            GetQuestionByIdCommand getQuestionByIdCommand =
                    new GetQuestionByIdCommand(unitOfWork, ServiceMapping.QUESTION.getServiceName(), courseAggregateId);
            SagaCommand sagaCommand = new SagaCommand(getQuestionByIdCommand);
            sagaCommand.setSemanticLock(QuestionSagaState.READ_QUESTION);
            this.questionFuture = commandGateway.sendAsync(sagaCommand).thenApply(dto -> (QuestionDto) dto);
        });

        getQuestionAsyncStep.registerCompensation(() -> {
            if (this.question != null) {
                Command command = new Command(unitOfWork, ServiceMapping.QUESTION.getServiceName(), this.question.getAggregateId());
                SagaCommand sagaCommand = new SagaCommand(command);
                sagaCommand.setSemanticLock(GenericSagaState.NOT_IN_SAGA);
                commandGateway.send(sagaCommand);
            }
        }, unitOfWork);

        SagaStep updateQuestionTopicsStep = new SagaStep("updateQuestionTopicsStep", () -> {
            this.setTopics(this.topicsFuture.join());
            this.setQuestion(this.questionFuture.join());
            this.setTopicDtos(this.question.getTopicDto());

            UpdateQuestionTopicsCommand updateQuestionTopicsCommand =
                    new UpdateQuestionTopicsCommand(unitOfWork, ServiceMapping.QUESTION.getServiceName(),
                            courseAggregateId, this.getTopics());
            commandGateway.send(updateQuestionTopicsCommand);
        }, new ArrayList<>(Arrays.asList(getTopicsAsyncStep, getQuestionAsyncStep)));

        workflow.addStep(getTopicsAsyncStep);
        workflow.addStep(getQuestionAsyncStep);
        workflow.addStep(updateQuestionTopicsStep);
    }

    public Set<QuestionTopic> getTopics() {
        return topics;
    }

    public void setTopics(Set<QuestionTopic> topics) {
        this.topics = topics;
    }

    public Set<TopicDto> getTopicDtos() {
        return topicDtos;
    }

    public void setTopicDtos(Set<TopicDto> topics) {
        this.topicDtos = topics;
    }

    public QuestionDto getQuestion() {
        return question;
    }

    public void setQuestion(QuestionDto question) {
        this.question = question;
    }
}