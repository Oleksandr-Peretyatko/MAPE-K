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
            case CreateQuizCommand cmd -> handleCreateQuiz(cmd);
            case UpdateGeneratedQuizCommand cmd -> handleUpdateGeneratedQuiz(cmd);
            case UpdateQuizCommand cmd -> handleUpdateQuiz(cmd);
            case GetAvailableQuizzesCommand cmd -> handleGetAvailableQuizzes(cmd);
            case UpdateQuestionCommand cmd -> handleUpdateQuestion(cmd);
            case RemoveQuizQuestionCommand cmd -> handleRemoveQuizQuestion(cmd);
            case RemoveQuizCommand cmd -> handleRemoveQuiz(cmd);
            case GetExecutionIdByTournamentIdCommand cmd -> handleGetExecutionIdByTournamentId(cmd);
            case GetTournamentByIdCommand cmd -> handleGetTournamentById(cmd);
            case CreateTournamentCommand cmd -> handleCreateTournament(cmd);
            case GetTournamentsByCourseExecutionIdCommand cmd -> handleGetTournamentsByCourseExecutionId(cmd);
            case GetOpenedTournamentsForCourseExecutionCommand cmd -> handleGetOpenedTournamentsForCourseExecution(cmd);
            case GetClosedTournamentsForCourseExecutionCommand cmd -> handleGetClosedTournamentsForCourseExecution(cmd);
            case RemoveTournamentCommand cmd -> handleRemoveTournament(cmd);
            case UpdateTournamentCommand cmd -> handleUpdateTournament(cmd);
            case CancelTournamentCommand cmd -> handleCancelTournament(cmd);
            case AnonymizeUserCommand cmd -> handleAnonymizeTournamentUser(cmd);
            case RemoveTournamentUserCommand cmd -> handleRemoveTournamentUser(cmd);
            case UpdateTopicCommand cmd -> handleUpdateTopic(cmd);
            case RemoveTopicCommand cmd -> handleRemoveTopic(cmd);
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

    /* Quiz command arms (merged from the former Quiz aggregate) */

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
                command.getCourseExecutionDto(),
                command.getQuizDto(),
                command.getQuestionDtos(),
                command.getNumberOfQuestions(),
                command.getUnitOfWork());
    }

    private Object handleCreateQuiz(CreateQuizCommand command) {
        logger.info("Creating quiz for course execution: " + command.getCourseExecutionAggregateId());
        return executionService.createQuiz(
                command.getCourseExecutionAggregateId(),
                command.getQuestions(),
                command.getQuizDto(),
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

    private Object handleUpdateQuiz(UpdateQuizCommand command) {
        logger.info("Updating quiz: " + command.getQuizDto().getAggregateId());
        return executionService.updateQuiz(
                command.getQuizDto(),
                command.getQuizQuestions(),
                command.getUnitOfWork());
    }

    private Object handleGetAvailableQuizzes(GetAvailableQuizzesCommand command) {
        logger.info("Getting available quizzes for course execution: " + command.getCourseExecutionAggregateId());
        return executionService.getAvailableQuizzes(
                command.getCourseExecutionAggregateId(),
                command.getUnitOfWork());
    }

    private Object handleUpdateQuestion(UpdateQuestionCommand command) {
        logger.info("Updating quiz question in execution: " + command.getExecutionAggregateId());
        executionService.updateQuestion(
                command.getExecutionAggregateId(),
                command.getQuestionAggregateId(),
                command.getTitle(),
                command.getContent(),
                command.getAggregateVersion(),
                command.getUnitOfWork());
        return null;
    }

    private Object handleRemoveQuizQuestion(RemoveQuizQuestionCommand command) {
        logger.info("Removing question from quizzes in execution: " + command.getExecutionAggregateId());
        executionService.removeQuizQuestion(
                command.getExecutionAggregateId(),
                command.getQuestionAggregateId(),
                command.getUnitOfWork());
        return null;
    }

    private Object handleRemoveQuiz(RemoveQuizCommand command) {
        logger.info("Removing quiz: " + command.getQuizAggregateId());
        executionService.removeQuiz(
                command.getQuizAggregateId(),
                command.getUnitOfWork());
        return null;
    }

    /* Tournament command arms (merged from the former Tournament aggregate) */

    private Object handleGetExecutionIdByTournamentId(GetExecutionIdByTournamentIdCommand command) {
        return executionService.getExecutionIdByTournamentId(command.getTournamentAggregateId());
    }

    private Object handleGetTournamentById(GetTournamentByIdCommand command) {
        return executionService.getTournamentById(
                command.getAggregateId(),
                command.getUnitOfWork());
    }

    private Object handleCreateTournament(CreateTournamentCommand command) {
        return executionService.createTournament(
                command.getTournamentDto(),
                command.getCreatorDto(),
                command.getCourseExecutionDto(),
                command.getTopicDtos(),
                command.getQuizDto(),
                command.getUnitOfWork());
    }

    private Object handleGetTournamentsByCourseExecutionId(GetTournamentsByCourseExecutionIdCommand command) {
        return executionService.getTournamentsByCourseExecutionId(
                command.getExecutionAggregateId(),
                command.getUnitOfWork());
    }

    private Object handleGetOpenedTournamentsForCourseExecution(GetOpenedTournamentsForCourseExecutionCommand command) {
        return executionService.getOpenedTournamentsForCourseExecution(
                command.getExecutionAggregateId(),
                command.getUnitOfWork());
    }

    private Object handleGetClosedTournamentsForCourseExecution(GetClosedTournamentsForCourseExecutionCommand command) {
        return executionService.getClosedTournamentsForCourseExecution(
                command.getExecutionAggregateId(),
                command.getUnitOfWork());
    }

    private Object handleRemoveTournament(RemoveTournamentCommand command) {
        executionService.removeTournament(
                command.getTournamentAggregateId(),
                command.getUnitOfWork());
        return null;
    }

    private Object handleUpdateTournament(UpdateTournamentCommand command) {
        return executionService.updateTournament(
                command.getTournamentDto(),
                command.getTopicDtos(),
                command.getUnitOfWork());
    }

    private Object handleCancelTournament(CancelTournamentCommand command) {
        executionService.cancelTournament(
                command.getTournamentAggregateId(),
                command.getUnitOfWork());
        return null;
    }

    private Object handleAnonymizeTournamentUser(AnonymizeUserCommand command) {
        return executionService.anonymizeTournamentUser(
                command.getTournamentAggregateId(),
                command.getExecutionAggregateId(),
                command.getUserAggregateId(),
                command.getName(),
                command.getUsername(),
                command.getEventVersion(),
                command.getUnitOfWork());
    }

    private Object handleRemoveTournamentUser(RemoveTournamentUserCommand command) {
        return executionService.removeTournamentUser(
                command.getTournamentAggregateId(),
                command.getCourseExecutionAggregateId(),
                command.getUserAggregateId(),
                command.getEventVersion(),
                command.getUnitOfWork());
    }

    private Object handleUpdateTopic(UpdateTopicCommand command) {
        return executionService.updateTopicInTournaments(
                command.getExecutionAggregateId(),
                command.getTopicAggregateId(),
                command.getTopicName(),
                command.getEventVersion(),
                command.getUnitOfWork());
    }

    private Object handleRemoveTopic(RemoveTopicCommand command) {
        return executionService.removeTopicFromTournaments(
                command.getExecutionAggregateId(),
                command.getTopicAggregateId(),
                command.getEventVersion(),
                command.getUnitOfWork());
    }
}
