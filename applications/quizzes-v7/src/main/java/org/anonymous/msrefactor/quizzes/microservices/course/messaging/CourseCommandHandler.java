package org.anonymous.msrefactor.quizzes.microservices.course.messaging;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.messaging.CommandHandler;
import org.anonymous.msrefactor.quizzes.commands.course.*;
import org.anonymous.msrefactor.quizzes.microservices.course.service.CourseService;

import java.util.logging.Logger;

@Component
public class CourseCommandHandler extends CommandHandler {
    private static final Logger logger = Logger.getLogger(CourseCommandHandler.class.getName());

    @Autowired
    private CourseService courseService;

    @Override
    public String getAggregateTypeName() {
        return "Course";
    }

    @Override
    public Object handleDomainCommand(Command command) {
        return switch (command) {
            case GetCourseByIdCommand cmd -> handleGetCourseById(cmd);
            case GetAndOrCreateCourseRemoteCommand cmd -> handleGetAndOrCreateCourseRemote(cmd);
            case GetCourseByNameRemoteCommand cmd -> handleGetCourseByNameRemote(cmd);
            case CreateCourseRemoteCommand cmd -> handleCreateCourseRemote(cmd);
            case DeleteCourseCommand cmd -> handleDeleteCourse(cmd);
            case UpdateCourseExecutionCountCommand cmd -> handleUpdateCourseExecutionCount(cmd);
            // Question arms (merged from the former Question aggregate, merge course <- question).
            case CreateQuestionCommand cmd -> handleCreateQuestion(cmd);
            case UpdateQuestionCommand cmd -> handleUpdateQuestion(cmd);
            case RemoveQuestionCommand cmd -> handleRemoveQuestion(cmd);
            case FindQuestionsByTopicIdsCommand cmd -> handleFindQuestionsByTopicIds(cmd);
            case UpdateQuestionTopicsCommand cmd -> handleUpdateQuestionTopics(cmd);
            case GetQuestionByIdCommand cmd -> handleGetQuestionById(cmd);
            case FindQuestionsByCourseAggregateIdCommand cmd -> handleFindQuestionsByCourseAggregateId(cmd);
            case UpdateTopicCommand cmd -> handleUpdateTopic(cmd);
            case RemoveTopicCommand cmd -> handleRemoveTopic(cmd);
            default -> {
                logger.warning("Unknown command type: " + command.getClass().getName());
                yield null;
            }
        };
    }

    private Object handleGetCourseById(GetCourseByIdCommand command) {
        logger.info("Getting course by ID: " + command.getAggregateId());
        return courseService.getCourseById(command.getAggregateId(), command.getUnitOfWork());
    }

    private Object handleGetAndOrCreateCourseRemote(GetAndOrCreateCourseRemoteCommand command) {
        logger.info("Getting or creating course by ID: " + command.getCourseExecutionDto().getAggregateId());
        return courseService.getAndOrCreateCourseRemote(command.getCourseExecutionDto(), command.getUnitOfWork());
    }

    private Object handleGetCourseByNameRemote(GetCourseByNameRemoteCommand command) {
        logger.info("Getting course by name: " + command.getCourseExecutionDto().getName());
        return courseService.getCourseByNameRemote(command.getCourseExecutionDto(), command.getUnitOfWork());
    }

    private Object handleCreateCourseRemote(CreateCourseRemoteCommand command) {
        logger.info("Creating course: " + command.getCourseExecutionDto().getName());
        return courseService.createCourseRemote(command.getCourseExecutionDto(), command.getUnitOfWork());
    }

    private Object handleDeleteCourse(DeleteCourseCommand command) {
        logger.info("Deleting course: " + command.getCourseAggregateId());
        courseService.deleteCourse(command.getCourseAggregateId(), command.getUnitOfWork());
        return null;
    }

    private Object handleUpdateCourseExecutionCount(UpdateCourseExecutionCountCommand command) {
        logger.info("Updating course execution count for course: " + command.getCourseAggregateId() + " increment: " + command.isIncrement());
        if (command.isIncrement()) {
            courseService.incrementCourseExecutionCount(command.getCourseAggregateId(), command.getUnitOfWork());
        } else {
            courseService.decrementCourseExecutionCount(command.getCourseAggregateId(), command.getUnitOfWork());
        }
        return null;
    }

    private Object handleCreateQuestion(CreateQuestionCommand command) {
        logger.info("Creating question in course: " + command.getCourseAggregateId());
        return courseService.createQuestion(
                command.getCourseAggregateId(),
                command.getQuestionDto(),
                command.getTopics(),
                command.getUnitOfWork());
    }

    private Object handleUpdateQuestion(UpdateQuestionCommand command) {
        logger.info("Updating question: " + command.getQuestionDto().getAggregateId());
        courseService.updateQuestion(
                command.getQuestionDto(),
                command.getUnitOfWork());
        return null;
    }

    private Object handleRemoveQuestion(RemoveQuestionCommand command) {
        logger.info("Removing question: " + command.getCourseAggregateId());
        courseService.removeQuestion(
                command.getCourseAggregateId(),
                command.getUnitOfWork());
        return null;
    }

    private Object handleFindQuestionsByTopicIds(FindQuestionsByTopicIdsCommand command) {
        logger.info("Finding questions by topic IDs: " + command.getTopicIds());
        return courseService.findQuestionsByTopicIds(
                command.getTopicIds(),
                command.getUnitOfWork());
    }

    private Object handleUpdateQuestionTopics(UpdateQuestionTopicsCommand command) {
        logger.info("Updating question topics for question: " + command.getCourseAggregateId());
        courseService.updateQuestionTopics(
                command.getCourseAggregateId(),
                command.getTopics(),
                command.getUnitOfWork());
        return null;
    }

    private Object handleGetQuestionById(GetQuestionByIdCommand command) {
        logger.info("Getting question by ID: " + command.getAggregateId());
        return courseService.getQuestionById(
                command.getAggregateId(),
                command.getUnitOfWork());
    }

    private Object handleFindQuestionsByCourseAggregateId(FindQuestionsByCourseAggregateIdCommand command) {
        logger.info("Finding questions by course aggregate ID: " + command.getCourseAggregateId());
        return courseService.findQuestionsByCourseAggregateId(
                command.getCourseAggregateId(),
                command.getUnitOfWork());
    }

    private Object handleUpdateTopic(UpdateTopicCommand command) {
        logger.info("Updating topic in questions of course: " + command.getQuestionAggregateId() + ", topic="
                + command.getTopicAggregateId());
        courseService.updateTopic(
                command.getQuestionAggregateId(),
                command.getTopicAggregateId(),
                command.getTopicName(),
                command.getAggregateVersion(),
                command.getUnitOfWork());
        return null;
    }

    private Object handleRemoveTopic(RemoveTopicCommand command) {
        logger.info("Removing topic from questions of course: " + command.getQuestionAggregateId() + ", topic="
                + command.getTopicAggregateId());
        courseService.removeTopic(
                command.getQuestionAggregateId(),
                command.getTopicAggregateId(),
                command.getAggregateVersion(),
                command.getUnitOfWork());
        return null;
    }
}
