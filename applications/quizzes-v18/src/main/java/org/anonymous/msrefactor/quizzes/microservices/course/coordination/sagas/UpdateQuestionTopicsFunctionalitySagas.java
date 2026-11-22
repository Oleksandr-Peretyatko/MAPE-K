package org.anonymous.msrefactor.quizzes.microservices.course.coordination.sagas;

import org.anonymous.msrefactor.ms.coordination.WorkflowFunctionality;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWork;
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaStep;
import org.anonymous.msrefactor.ms.transaction.sagas.workflow.SagaWorkflow;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.QuestionDto;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.QuestionTopic;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.TopicDto;
import org.anonymous.msrefactor.quizzes.microservices.course.service.CourseService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class UpdateQuestionTopicsFunctionalitySagas extends WorkflowFunctionality {
    private Set<QuestionTopic> topics;
    private QuestionDto question;
    private Set<TopicDto> topicDtos;
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CourseService courseService;

    public UpdateQuestionTopicsFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService, CourseService courseService,
                                                  Integer questionAggregateId, List<Integer> topicIds, SagaUnitOfWork unitOfWork) {
        this.unitOfWorkService = unitOfWorkService;
        this.courseService = courseService;
        this.buildWorkflow(questionAggregateId, topicIds, unitOfWork);
    }

    public void buildWorkflow(Integer questionAggregateId, List<Integer> topicIds, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep getTopicsStep = new SagaStep("getTopicsStep", () -> {
            // the remote topic reads collapsed to direct calls (topics are members of
            // the course now); their READ_TOPIC locks were dropped — Topic's states
            // were unchecked — so the release compensation went with them
            Set<QuestionTopic> topics = topicIds.stream()
                    .map(topicId -> courseService.getTopicById(topicId, unitOfWork))
                    .map(QuestionTopic::new)
                    .collect(Collectors.toSet());
            this.setTopics(topics);
        });

        SagaStep updateQuestionTopicsStep = new SagaStep("updateQuestionTopicsStep", () -> {
            // the remote getQuestionStep collapsed into its only dependent: the read is
            // in-process now (its READ_QUESTION lock was dropped — Question unchecked)
            QuestionDto question = courseService.getQuestionById(questionAggregateId, unitOfWork);
            this.setQuestion(question);
            this.setTopicDtos(question.getTopicDto());

            courseService.updateQuestionTopics(questionAggregateId, this.getTopics(), unitOfWork);
        }, new ArrayList<>(Arrays.asList(getTopicsStep)));

        workflow.addStep(getTopicsStep);
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
