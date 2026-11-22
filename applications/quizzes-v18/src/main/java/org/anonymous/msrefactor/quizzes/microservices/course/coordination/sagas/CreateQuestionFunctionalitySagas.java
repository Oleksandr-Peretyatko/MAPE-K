package org.anonymous.msrefactor.quizzes.microservices.course.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.QuestionDto;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.TopicDto;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.sagas.states.CourseSagaState;
import org.anonymous.msrefactor.quizzes.microservices.course.service.CourseService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class CreateQuestionFunctionalitySagas extends WorkflowFunctionality {
    private List<TopicDto> topics;
    private QuestionDto createdQuestion;
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CourseService courseService;

    public CreateQuestionFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService, CourseService courseService,
                                            Integer courseAggregateId, QuestionDto questionDto, SagaUnitOfWork unitOfWork) {
        this.unitOfWorkService = unitOfWorkService;
        this.courseService = courseService;
        this.buildWorkflow(courseAggregateId, questionDto, unitOfWork);
    }

    public void buildWorkflow(Integer courseAggregateId, QuestionDto questionDto, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep getTopicsStep = new SagaStep("getTopicsStep", () -> {
            // the remote topic reads collapsed to direct calls (topics are members of
            // the course now); their READ_TOPIC locks were dropped — Topic's states
            // were unchecked — so the release compensation went with them
            List<TopicDto> topics = questionDto.getTopicDto().stream()
                    .map(topicDto -> courseService.getTopicById(topicDto.getAggregateId(), unitOfWork))
                    .collect(Collectors.toList());
            this.setTopics(topics);
        });

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
