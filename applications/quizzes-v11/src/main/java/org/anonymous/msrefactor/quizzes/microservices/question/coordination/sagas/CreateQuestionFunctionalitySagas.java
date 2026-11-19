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
import org.anonymous.msrefactor.quizzes.commands.course.GetCourseByIdCommand;
import org.anonymous.msrefactor.quizzes.commands.course.UpdateCourseQuestionCountCommand;
import org.anonymous.msrefactor.quizzes.commands.question.CreateQuestionCommand;
import org.anonymous.msrefactor.quizzes.commands.course.GetTopicByIdCommand;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.CourseDto;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.sagas.states.CourseSagaState;
import org.anonymous.msrefactor.quizzes.microservices.question.aggregate.QuestionCourse;
import org.anonymous.msrefactor.quizzes.microservices.question.aggregate.QuestionDto;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.TopicDto;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class CreateQuestionFunctionalitySagas extends WorkflowFunctionality {
    private QuestionCourse course;
    private List<TopicDto> topics;
    private QuestionDto createdQuestion;
    private CourseDto courseDto;
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CommandGateway commandGateway;

    public CreateQuestionFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService,
                                            Integer courseAggregateId, QuestionDto questionDto, SagaUnitOfWork unitOfWork,
                                            CommandGateway CommandGateway) {
        this.unitOfWorkService = unitOfWorkService;
        this.commandGateway = CommandGateway;
        this.buildWorkflow(courseAggregateId, questionDto, unitOfWork);
    }

    public void buildWorkflow(Integer courseAggregateId, QuestionDto questionDto, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep getCourseStep = new SagaStep("getCourseStep", () -> {
            GetCourseByIdCommand getCourseByIdCommand = new GetCourseByIdCommand(unitOfWork, ServiceMapping.COURSE.getServiceName(), courseAggregateId);
            SagaCommand sagaCommand = new SagaCommand(getCourseByIdCommand);
            sagaCommand.setSemanticLock(CourseSagaState.READ_COURSE);
            CourseDto course = (CourseDto) commandGateway.send(sagaCommand);
            this.courseDto = course;

            QuestionCourse questionCourse = new QuestionCourse(course);
            this.setCourse(questionCourse);
        });

        getCourseStep.registerCompensation(() -> {
            Logger.getLogger(CreateQuestionFunctionalitySagas.class.getName()).info("Compensating getCourseStep");
            Command command = new Command(unitOfWork, ServiceMapping.COURSE.getServiceName(), courseDto.getAggregateId());
            SagaCommand sagaCommand = new SagaCommand(command);
            sagaCommand.setSemanticLock(GenericSagaState.NOT_IN_SAGA);
            commandGateway.send(sagaCommand);
        }, unitOfWork);

        SagaStep getTopicsStep = new SagaStep("getTopicsStep", () -> {
            List<TopicDto> topics = questionDto.getTopicDto().stream()
                    .map(topicDto -> {
                        GetTopicByIdCommand getTopicByIdCommand = new GetTopicByIdCommand(unitOfWork, ServiceMapping.COURSE.getServiceName(), topicDto.getAggregateId());
                        TopicDto fetchedTopic = (TopicDto) commandGateway.send(getTopicByIdCommand);
                        return fetchedTopic;
                    })
                    .collect(Collectors.toList());
            this.setTopics(topics);
        });

        SagaStep createQuestionStep = new SagaStep("createQuestionStep", () -> {
            CreateQuestionCommand createQuestionCommand = new CreateQuestionCommand(unitOfWork, ServiceMapping.QUESTION.getServiceName(), this.getCourse(), questionDto, this.getTopics());
            QuestionDto createdQuestion = (QuestionDto) commandGateway.send(createQuestionCommand);
            this.setCreatedQuestion(createdQuestion);
        }, new ArrayList<>(Arrays.asList(getCourseStep, getTopicsStep)));

        // CANNOT_DELETE_LAST_EXECUTION_WITH_CONTENT
        SagaStep updateCourseQuestionCountStep = new SagaStep("updateCourseQuestionCountStep", () -> {
            UpdateCourseQuestionCountCommand cmd = new UpdateCourseQuestionCountCommand(unitOfWork, ServiceMapping.COURSE.getServiceName(), this.courseDto.getAggregateId(), true);
            commandGateway.send(cmd);
        }, new ArrayList<>(Arrays.asList(createQuestionStep)));

        workflow.addStep(getCourseStep);
        workflow.addStep(getTopicsStep);
        workflow.addStep(createQuestionStep);
        workflow.addStep(updateCourseQuestionCountStep);
    }

    public QuestionCourse getCourse() {
        return course;
    }

    public void setCourse(QuestionCourse course) {
        this.course = course;
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
