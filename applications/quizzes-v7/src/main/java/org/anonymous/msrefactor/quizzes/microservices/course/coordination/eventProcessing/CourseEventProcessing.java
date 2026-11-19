package org.anonymous.msrefactor.quizzes.microservices.course.coordination.eventProcessing;

import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.anonymous.msrefactor.quizzes.events.DeleteTopicEvent;
import org.anonymous.msrefactor.quizzes.events.UpdateTopicEvent;
import org.anonymous.msrefactor.quizzes.microservices.course.coordination.functionalities.CourseFunctionalities;

@Service
public class CourseEventProcessing {
    private static final Logger logger = org.slf4j.LoggerFactory.getLogger(CourseEventProcessing.class);

    @Autowired
    private CourseFunctionalities courseFunctionalities;

    /************************************************
     * EVENT PROCESSING (merged from the former Question aggregate, merge course <- question).
     * aggregateId is the subscribing Course; the functionalities fan out over its Question members.
     ************************************************/

    public void processUpdateTopic(Integer aggregateId, UpdateTopicEvent updateTopicEvent) {
        logger.info("Processing UpdateTopicEvent: aggregateId={}, event={}", aggregateId, updateTopicEvent);
        courseFunctionalities.updateTopicInQuestion(aggregateId, updateTopicEvent.getPublisherAggregateId(),
                updateTopicEvent.getTopicName(), updateTopicEvent.getPublisherAggregateVersion());
    }

    public void processDeleteTopic(Integer aggregateId, DeleteTopicEvent deleteTopicEvent) {
        logger.info("Processing DeleteTopicEvent: aggregateId={}, event={}", aggregateId, deleteTopicEvent);
        courseFunctionalities.deleteTopicInQuestion(aggregateId, deleteTopicEvent.getPublisherAggregateId(),
                deleteTopicEvent.getPublisherAggregateVersion());
    }
}
