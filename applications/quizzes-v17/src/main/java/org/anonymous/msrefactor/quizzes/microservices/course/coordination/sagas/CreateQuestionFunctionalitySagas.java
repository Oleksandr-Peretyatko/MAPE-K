package org.anonymous.msrefactor.quizzes.microservices.course.coordination.sagas;

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
import org.anonymous.msrefactor.quizzes.commands.topic.GetTopicByIdCommand;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.QuestionDto;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.sagas.states.CourseSagaState;
import org.anonymous.msrefactor.quizzes.microservices.course.service.CourseService;
import org.anonymous.msrefactor.quizzes.microservices.topic.aggregate.TopicDto;
import org.anonymous.msrefactor.quizzes.microservices.topic.aggregate.sagas.states.TopicSagaState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class CreateQuestionFunctionalitySagas extends WorkflowFunctionality {
    private List<TopicDto> topics;
    private QuestionDto createdQuestion;
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CourseService courseService;
    private final CommandGateway commandGateway;

    public CreateQuestionFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService, CourseService courseService,
                                            Integer courseAggregateId, QuestionDto questionDto, SagaUnitOfWork unitOfWork,
                                            CommandGateway CommandGateway) {
        this.unitOfWorkService = unitOfWorkService;
        this.courseService = courseService;
        this.commandGateway = CommandGateway;
        this.buildWorkflow(courseAggregateId, questionDto, unitOfWork);
    }

    public void buildWorkflow(Integer courseAggregateId, QuestionDto questionDto, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep getTopicsStep = new SagaStep("getTopicsStep", () -> {
            List<TopicDto> topics = questionDto.getTopicDto().stream()
                    .map(topicDto -> {
                        GetTopicByIdCommand getTopicByIdCommand = new GetTopicByIdCommand(unitOfWork, ServiceMapping.TOPIC.getServiceName(), topicDto.getAggregateId());
                        SagaCommand sagaCommand = new SagaCommand(getTopicByIdCommand);
                        sagaCommand.setSemanticLock(TopicSagaState.READ_TOPIC);
                        TopicDto fetchedTopic = (TopicDto) commandGateway.send(sagaCommand);
                        return fetchedTopic;
                    })
                    .collect(Collectors.toList());
            this.setTopics(topics);
        });

        getTopicsStep.registerCompensation(() -> {
            Logger.getLogger(CreateQuestionFunctionalitySagas.class.getName()).info("Compensating getTopicsStep");
            questionDto.getTopicDto().forEach(topicDto -> {
                Command command = new Command(unitOfWork, ServiceMapping.TOPIC.getServiceName(), topicDto.getAggregateId());
                SagaCommand sagaCommand = new SagaCommand(command);
                sagaCommand.setSemanticLock(GenericSagaState.NOT_IN_SAGA);
                commandGateway.send(sagaCommand);
            });
        }, unitOfWork);

        SagaStep createQuestionStep = new SagaStep("createQuestionStep", () -> {
            // the course read and the question creation are local now; the semantic lock
            // the remote getCourseStep took on the course is kept in-process (Course
            // states are checked). The counter update step is gone: the invariant reads
            // the owned collection directly.
            unitOfWorkService.registerSagaState(courseAggregateId, CourseSagaState.READ_COURSE, unitOfWork);
            QuestionDto createdQuestion = courseService.createQuestion(courseAggregateId, questionDto, this.getTopics(), unitOfWork);
            this.setCreatedQuestion(createdQuestion);
        }, new ArrayList<>(Arrays.asList(getTopicsStep)));

        workflow.addStep(getTopicsStep);
        workflow.addStep(createQuestionStep);
    }

    public List<TopicDto> getTopics() {
        return topics.stream()
                .map(TopicDto -> (TopicDto) TopicDto)
                .collect(Collectors.toList());
    }

    public void setTopics(List<TopicDto> topics) {
        this.topics = topics;
    }

    public QuestionDto getCreatedQuestion() {
        return createdQuestion;
    }

    public void setCreatedQuestion(QuestionDto createdQuestion) {
        this.createdQuestion = createdQuestion;
    }
}
