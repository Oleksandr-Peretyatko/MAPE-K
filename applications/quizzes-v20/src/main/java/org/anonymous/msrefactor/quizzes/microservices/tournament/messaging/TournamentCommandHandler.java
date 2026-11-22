package org.anonymous.msrefactor.quizzes.microservices.tournament.messaging;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.messaging.CommandHandler;
import org.anonymous.msrefactor.quizzes.commands.tournament.*;
import org.anonymous.msrefactor.quizzes.microservices.tournament.service.TournamentService;

import java.util.logging.Logger;

@Component
public class TournamentCommandHandler extends CommandHandler {
    private static final Logger logger = Logger.getLogger(TournamentCommandHandler.class.getName());

    @Autowired
    private TournamentService tournamentService;

    @Override
    public String getAggregateTypeName() {
        return "Tournament";
    }

    @Override
    public Object handleDomainCommand(Command command) {
        return switch (command) {
            case GetTournamentByIdCommand cmd -> handleGetTournamentById(cmd);
            case CreateTournamentCommand cmd -> handleCreateTournament(cmd);
            case GetTournamentsByCourseExecutionIdCommand cmd -> handleGetTournamentsByCourseExecutionId(cmd);
            case GetOpenedTournamentsForCourseExecutionCommand cmd -> handleGetOpenedTournamentsForCourseExecution(cmd);
            case GetClosedTournamentsForCourseExecutionCommand cmd -> handleGetClosedTournamentsForCourseExecution(cmd);
            case RemoveTournamentCommand cmd -> handleRemoveTournament(cmd);
            case UpdateTournamentCommand cmd -> handleUpdateTournament(cmd);
            case CancelTournamentCommand cmd -> handleCancelTournament(cmd);
            case DeactivateTournamentCommand cmd -> handleDeactivateTournament(cmd);
            case RemoveCourseExecutionCommand cmd -> handleRemoveCourseExecution(cmd);
            case UpdateTopicCommand cmd -> handleUpdateTopic(cmd);
            case RemoveTopicCommand cmd -> handleRemoveTopic(cmd);
            case InvalidateQuizCommand cmd -> handleInvalidateQuiz(cmd);
            default -> {
                logger.warning("Unknown command type: " + command.getClass().getName());
                yield null;
            }
        };
    }

    private Object handleGetTournamentById(GetTournamentByIdCommand command) {
        logger.info("Getting tournament: " + command.getAggregateId());
        return tournamentService.getTournamentById(
                command.getAggregateId(),
                command.getUnitOfWork());
    }

    private Object handleCreateTournament(CreateTournamentCommand command) {
        logger.info("Creating tournament: ");
        return tournamentService.createTournament(
                command.getTournamentDto(),
                command.getCourseExecutionDto(),
                command.getTopicDtos(),
                command.getQuizDto(),
                command.getUnitOfWork());
    }

    private Object handleGetTournamentsByCourseExecutionId(GetTournamentsByCourseExecutionIdCommand command) {
        logger.info("Getting tournaments by course execution ID: " + command.getExecutionAggregateId());
        return tournamentService.getTournamentsByCourseExecutionId(
                command.getExecutionAggregateId(),
                command.getUnitOfWork());
    }

    private Object handleGetOpenedTournamentsForCourseExecution(GetOpenedTournamentsForCourseExecutionCommand command) {
        logger.info("Getting opened tournaments for course execution ID: " + command.getExecutionAggregateId());
        return tournamentService.getOpenedTournamentsForCourseExecution(
                command.getExecutionAggregateId(),
                command.getUnitOfWork());
    }

    private Object handleGetClosedTournamentsForCourseExecution(GetClosedTournamentsForCourseExecutionCommand command) {
        logger.info("Getting closed tournaments for course execution ID: " + command.getExecutionAggregateId());
        return tournamentService.getClosedTournamentsForCourseExecution(
                command.getExecutionAggregateId(),
                command.getUnitOfWork());
    }

    private Object handleRemoveTournament(RemoveTournamentCommand command) {
        logger.info("Removing tournament: " + command.getTournamentAggregateId());
        tournamentService.removeTournament(
                command.getTournamentAggregateId(),
                command.getUnitOfWork());
        return null;
    }

    private Object handleUpdateTournament(UpdateTournamentCommand command) {
        logger.info("Updating tournament: " + command.getTournamentDto().getAggregateId());
        return tournamentService.updateTournament(
                command.getTournamentDto(),
                command.getTopicDtos(),
                command.getUnitOfWork());
    }

    private Object handleCancelTournament(CancelTournamentCommand command) {
        logger.info("Cancelling tournament: " + command.getTournamentAggregateId());
        tournamentService.cancelTournament(
                command.getTournamentAggregateId(),
                command.getUnitOfWork());
        return null;
    }

    private Object handleDeactivateTournament(DeactivateTournamentCommand command) {
        logger.info("Deactivating tournament: " + command.getTournamentAggregateId());
        tournamentService.deactivateTournament(
                command.getTournamentAggregateId(),
                command.getUnitOfWork());
        return null;
    }

    private Object handleRemoveCourseExecution(RemoveCourseExecutionCommand command) {
        logger.info("Removing course execution from tournament: " + command.getTournamentAggregateId());
        return tournamentService.removeCourseExecution(
                command.getTournamentAggregateId(),
                command.getCourseExecutionId(),
                command.getEventVersion(),
                command.getUnitOfWork());
    }

    private Object handleUpdateTopic(UpdateTopicCommand command) {
        logger.info("Updating tournament topic: tournament=" + command.getTournamentAggregateId() + ", topic="
                + command.getTopicAggregateId());
        return tournamentService.updateTopic(
                command.getTournamentAggregateId(),
                command.getTopicAggregateId(),
                command.getTopicName(),
                command.getEventVersion(),
                command.getUnitOfWork());
    }

    private Object handleRemoveTopic(RemoveTopicCommand command) {
        logger.info("Removing tournament topic: tournament=" + command.getTournamentAggregateId() + ", topic="
                + command.getTopicAggregateId());
        return tournamentService.removeTopic(
                command.getTournamentAggregateId(),
                command.getTopicAggregateId(),
                command.getEventVersion(),
                command.getUnitOfWork());
    }

    private Object handleInvalidateQuiz(InvalidateQuizCommand command) {
        logger.info("Invalidating quiz for tournament: " + command.getTournamentAggregateId());
        return tournamentService.invalidateQuiz(
                command.getTournamentAggregateId(),
                command.getAggregateId(),
                command.getAggregateVersion(),
                command.getUnitOfWork());
    }
}
