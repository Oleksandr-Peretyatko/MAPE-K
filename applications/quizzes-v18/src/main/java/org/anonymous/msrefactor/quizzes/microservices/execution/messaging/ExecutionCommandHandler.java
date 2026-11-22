package org.anonymous.msrefactor.quizzes.microservices.execution.messaging;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.messaging.CommandHandler;
import org.anonymous.msrefactor.quizzes.commands.execution.*;

import org.anonymous.msrefactor.quizzes.microservices.execution.service.ExecutionService;

import java.util.logging.Logger;

@Component
public class ExecutionCommandHandler extends CommandHandler {
    private static final Logger logger = Logger.getLogger(ExecutionCommandHandler.class.getName());

    @Autowired
    private ExecutionService executionService;

    @Override
    public String getAggregateTypeName() {
        return "Execution";
    }

    @Override
    public Object handleDomainCommand(Command command) {
        return switch (command) {
            case CreateCourseExecutionCommand cmd -> handleCreateCourseExecution(cmd);
            case RemoveCourseExecutionCommand cmd -> handleRemoveCourseExecution(cmd);
            case RemoveStudentFromCourseExecutionCommand cmd -> handleRemoveStudentFromCourseExecution(cmd);
            case UpdateExecutionStudentNameCommand cmd -> handleUpdateExecutionStudentName(cmd);
            case GetStudentByExecutionIdAndUserIdCommand cmd -> handleGetStudentByExecutionIdAndUserId(cmd);
            case GetCourseExecutionsByUserIdCommand cmd -> handleGetCourseExecutionsByUserId(cmd);
            case GetCourseExecutionByIdCommand cmd -> handleGetCourseExecutionById(cmd);
            case GetAllCourseExecutionsCommand cmd -> handleGetAllCourseExecutions(cmd);
            case EnrollStudentCommand cmd -> handleEnrollStudent(cmd);
            case AnonymizeStudentCommand cmd -> handleAnonymizeStudent(cmd);
            case RemoveUserCommand cmd -> handleRemoveUser(cmd);
            case StartTournamentQuizCommand cmd -> handleStartTournamentQuiz(cmd);
            case GetQuizByIdCommand cmd -> handleGetQuizById(cmd);
            case GenerateQuizCommand cmd -> handleGenerateQuiz(cmd);
            case UpdateGeneratedQuizCommand cmd -> handleUpdateGeneratedQuiz(cmd);
            case RemoveQuizCommand cmd -> handleRemoveQuiz(cmd);
            default -> {
                logger.warning("Unknown command type: " + command.getClass().getName());
                yield null;
            }
        };
    }

    private Object handleCreateCourseExecution(CreateCourseExecutionCommand command) {
        logger.info("Creating course execution: " + command.getCourseExecutionDto());
        return executionService.createCourseExecution(
                command.getCourseExecutionDto(),
                command.getUnitOfWork());
    }

    private Object handleRemoveCourseExecution(RemoveCourseExecutionCommand command) {
        logger.info("Removing course execution: " + command.getExecutionAggregateId());
        executionService.removeCourseExecution(
                command.getExecutionAggregateId(),
                command.getUnitOfWork());
        return null;
    }

    private Object handleRemoveStudentFromCourseExecution(RemoveStudentFromCourseExecutionCommand command) {
        logger.info("Removing student from course execution: " + command.getCourseExecutionAggregateId() + ", "
                + command.getUserAggregateId());
        executionService.removeStudentFromCourseExecution(
                command.getCourseExecutionAggregateId(),
                command.getUserAggregateId(),
                command.getUnitOfWork());
        return null;
    }

    private Object handleUpdateExecutionStudentName(UpdateExecutionStudentNameCommand command) {
        logger.info("Updating execution student name: " + command.getExecutionAggregateId() + ", "
                + command.getUserAggregateId() + ", "
                + command.getName());
        executionService.updateExecutionStudentName(
                command.getExecutionAggregateId(),
                command.getUserAggregateId(),
                command.getName(),
                command.getUnitOfWork());
        return null;
    }

    private Object handleGetStudentByExecutionIdAndUserId(GetStudentByExecutionIdAndUserIdCommand command) {
        logger.info("Getting student by execution id and user id: " + command.getExecutionAggregateId() + ", "
                + command.getUserAggregateId());
        return executionService.getStudentByExecutionIdAndUserId(
                command.getExecutionAggregateId(),
                command.getUserAggregateId(),
                command.getUnitOfWork());
    }

    private Object handleGetCourseExecutionsByUserId(GetCourseExecutionsByUserIdCommand command) {
        logger.info("Getting course executions by user id: " + command.getUserAggregateId());
        return executionService.getCourseExecutionsByUserId(
                command.getUserAggregateId(),
                command.getUnitOfWork());
    }

    private Object handleGetCourseExecutionById(GetCourseExecutionByIdCommand command) {
        logger.info("Getting course execution by id: " + command.getExecutionAggregateId());
        return executionService.getCourseExecutionById(
                command.getExecutionAggregateId(),
                command.getUnitOfWork());
    }

    private Object handleGetAllCourseExecutions(GetAllCourseExecutionsCommand command) {
        logger.info("Getting all course executions");
        return executionService.getAllCourseExecutions(command.getUnitOfWork());
    }

    private Object handleEnrollStudent(EnrollStudentCommand command) {
        logger.info("Enrolling student: " + command.getUserDto().getAggregateId() + " in course execution: "
                + command.getCourseExecutionAggregateId());
        executionService.enrollStudent(
                command.getCourseExecutionAggregateId(),
                command.getUserDto(),
                command.getUnitOfWork());
        return null;
    }

    private Object handleAnonymizeStudent(AnonymizeStudentCommand command) {
        logger.info(
                "Anonymizing student: " + command.getUserAggregateId() + " in course execution: "
                        + command.getExecutionAggregateId());
        executionService.anonymizeStudent(
                command.getExecutionAggregateId(),
                command.getUserAggregateId(),
                command.getUnitOfWork());
        return null;
    }

    private Object handleRemoveUser(RemoveUserCommand command) {
        logger.info(
                "Removing user: " + command.getUserAggregateId() + " in course execution: "
                        + command.getCourseExecutionAggregateId());
        executionService.removeUser(
                command.getCourseExecutionAggregateId(),
                command.getUserAggregateId(),
                command.getUnitOfWork());
        return null;
    }

    private Object handleStartTournamentQuiz(StartTournamentQuizCommand command) {
        logger.info("Starting tournament quiz: " + command.getQuizAggregateId());
        return executionService.startTournamentQuiz(
                command.getUserAggregateId(),
                command.getQuizAggregateId(),
                command.getUnitOfWork());
    }

    private Object handleGetQuizById(GetQuizByIdCommand command) {
        logger.info("Getting quiz by ID: " + command.getAggregateId());
        return executionService.getQuizById(
                command.getAggregateId(),
                command.getUnitOfWork());
    }

    private Object handleGenerateQuiz(GenerateQuizCommand command) {
        logger.info("Generating quiz for course execution: " + command.getCourseExecutionDto().getAggregateId());
        return executionService.generateQuiz(
                command.getCourseExecutionDto().getAggregateId(),
                command.getQuizDto(),
                command.getQuestionDtos(),
                command.getNumberOfQuestions(),
                command.getUnitOfWork());
    }

    private Object handleUpdateGeneratedQuiz(UpdateGeneratedQuizCommand command) {
        logger.info("Updating generated quiz: " + command.getQuizDto().getAggregateId());
        return executionService.updateGeneratedQuiz(
                command.getQuizDto(),
                command.getTopicsAggregateIds(),
                command.getNumberOfQuestions(),
                command.getQuestionDtos(),
                command.getUnitOfWork());
    }

    private Object handleRemoveQuiz(RemoveQuizCommand command) {
        logger.info("Removing quiz: " + command.getQuizAggregateId());
        executionService.removeQuiz(
                command.getQuizAggregateId(),
                command.getUnitOfWork());
        return null;
    }
}
