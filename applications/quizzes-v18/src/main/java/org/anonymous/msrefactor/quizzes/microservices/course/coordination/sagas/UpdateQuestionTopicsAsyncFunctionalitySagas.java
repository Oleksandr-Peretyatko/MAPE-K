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

public class UpdateQuestionTopicsAsyncFunctionalitySagas extends WorkflowFunctionality {
    private Set<QuestionTopic> topics;
    private QuestionDto question;
    private Set<TopicDto> topicDtos;
    private final SagaUnitOfWorkService unitOfWorkService;
    private final CourseService courseService;

    public UpdateQuestionTopicsAsyncFunctionalitySagas(SagaUnitOfWorkService unitOfWorkService, CourseService courseService,
                                                       Integer questionAggregateId, List<Integer> topicIds,
                                                       SagaUnitOfWork unitOfWork) {
        this.unitOfWorkService = unitOfWorkService;
        this.courseService = courseService;
        this.buildWorkflow(questionAggregateId, topicIds, unitOfWork);
    }

    public void buildWorkflow(Integer questionAggregateId, List<Integer> topicIds, SagaUnitOfWork unitOfWork) {
        this.workflow = new SagaWorkflow(this, unitOfWorkService, unitOfWork);

        SagaStep getTopicsAsyncStep = new SagaStep("getTopicsAsyncStep", () -> {
            // the async remote topic reads collapsed to direct in-process calls (topics
            // are members of the course now), so the async hop to topic-service is gone;
            // their READ_TOPIC locks were dropped — Topic's states were unchecked
            Set<QuestionTopic> topics = topicIds.stream()
                    .map(topicId -> courseService.getTopicById(topicId, unitOfWork))
                    .map(QuestionTopic::new)
                    .collect(Collectors.toSet());
            this.setTopics(topics);
        });

        SagaStep updateQuestionTopicsStep = new SagaStep("updateQuestionTopicsStep", () -> {
            // the async remote getQuestionAsyncStep collapsed into its only dependent:
            // the read is in-process now (its READ_QUESTION lock was dropped — Question
            // unchecked), so the async hop to question-service is gone
            this.setQuestion(courseService.getQuestionById(questionAggregateId, unitOfWork));
            this.setTopicDtos(this.question.getTopicDto());

            courseService.updateQuestionTopics(questionAggregateId, this.getTopics(), unitOfWork);
        }, new ArrayList<>(Arrays.asList(getTopicsAsyncStep)));

        workflow.addStep(getTopicsAsyncStep);
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
