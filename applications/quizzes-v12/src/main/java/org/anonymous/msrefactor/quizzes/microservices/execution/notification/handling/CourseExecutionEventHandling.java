package org.anonymous.msrefactor.quizzes.microservices.execution.notification.handling;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.anonymous.msrefactor.ms.aggregate.EventApplicationService;
import org.anonymous.msrefactor.ms.notification.EventHandling;
import org.anonymous.msrefactor.quizzes.events.DeleteTopicEvent;
import org.anonymous.msrefactor.quizzes.events.DeleteUserEvent;
import org.anonymous.msrefactor.quizzes.events.InvalidateQuizEvent;
import org.anonymous.msrefactor.quizzes.events.QuizAnswerQuestionAnswerEvent;
import org.anonymous.msrefactor.quizzes.events.UpdateTopicEvent;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.CourseExecutionRepository;
import org.anonymous.msrefactor.quizzes.microservices.execution.coordination.eventProcessing.ExecutionEventProcessing;
import org.anonymous.msrefactor.quizzes.microservices.execution.notification.handling.handlers.DeleteTopicEventHandler;
import org.anonymous.msrefactor.quizzes.microservices.execution.notification.handling.handlers.DeleteUserEventHandler;
import org.anonymous.msrefactor.quizzes.microservices.execution.notification.handling.handlers.InvalidateQuizEventHandler;
import org.anonymous.msrefactor.quizzes.microservices.execution.notification.handling.handlers.QuizAnswerQuestionAnswerEventHandler;
import org.anonymous.msrefactor.quizzes.microservices.execution.notification.handling.handlers.UpdateTopicEventHandler;

@Component
public class CourseExecutionEventHandling implements EventHandling {
    @Autowired
    private EventApplicationService eventApplicationService;
    @Autowired
    private ExecutionEventProcessing executionEventProcessing;
    @Autowired
    private CourseExecutionRepository courseExecutionRepository;

    /*
     * USER_EXISTS
     */
    @Scheduled(fixedDelay = 1000)
    public void handleRemoveUserEvents() {
        eventApplicationService.handleSubscribedEvent(DeleteUserEvent.class,
                new DeleteUserEventHandler(courseExecutionRepository, executionEventProcessing));
    }

    /* moved from tournament (merge execution <- tournament) */

    /*
        TOPIC_EXISTS
            t: tournament.tournamentTopics | t.state != INACTIVE => EXISTS Topic(t.id) && t.name == Topic(t.id).name
    */
    @Scheduled(fixedDelay = 1000)
    public void handleUpdateTopicEvents() {
        eventApplicationService.handleSubscribedEvent(UpdateTopicEvent.class,
                new UpdateTopicEventHandler(courseExecutionRepository, executionEventProcessing));
    }

    @Scheduled(fixedDelay = 1000)
    public void handleDeleteTopicEvents() {
        eventApplicationService.handleSubscribedEvent(DeleteTopicEvent.class,
                new DeleteTopicEventHandler(courseExecutionRepository, executionEventProcessing));
    }

    /*
        QUIZ_ANSWER_EXISTS
            p: tournament.participants | (!p.answer.isEmpty && p.answer.state != INACTIVE) => EXISTS QuizAnswer(p.answer.id)
     */
    @Scheduled(fixedDelay = 1000)
    public void handleAnswerQuestionEvent() {
        eventApplicationService.handleSubscribedEvent(QuizAnswerQuestionAnswerEvent.class,
                new QuizAnswerQuestionAnswerEventHandler(courseExecutionRepository, executionEventProcessing));
    }

    /*
        QUIZ_EXISTS
            tournament.tournamentQuiz.state != INACTIVE => EXISTS Quiz(tournament.tournamentQuiz.id)
    */
    @Scheduled(fixedDelay = 1000)
    public void handleInvalidateQuizEvent() {
        eventApplicationService.handleSubscribedEvent(InvalidateQuizEvent.class,
                new InvalidateQuizEventHandler(courseExecutionRepository, executionEventProcessing));
    }

}
