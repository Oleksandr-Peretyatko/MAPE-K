package org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.messaging;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.messaging.CommandHandler;
import org.anonymous.msrefactor.quizzes.commands.tournamentSatellite.*;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.service.TournamentSatelliteService;

import java.util.logging.Logger;

@Component
public class TournamentSatelliteCommandHandler extends CommandHandler {
    private static final Logger logger = Logger.getLogger(TournamentSatelliteCommandHandler.class.getName());

    @Autowired
    private TournamentSatelliteService tournamentSatelliteService;

    @Override
    public String getAggregateTypeName() {
        return "TournamentSatellite";
    }

    @Override
    public Object handleDomainCommand(Command command) {
        return switch (command) {
            case GetTournamentSatelliteByIdCommand cmd -> handleGetTournamentSatelliteById(cmd);
            case FindTournamentSatelliteByTournamentIdCommand cmd -> handleFindTournamentSatelliteByTournamentId(cmd);
            case CreateTournamentSatelliteCommand cmd -> handleCreateTournamentSatellite(cmd);
            case AddParticipantCommand cmd -> handleAddParticipant(cmd);
            case LeaveTournamentCommand cmd -> handleLeaveTournament(cmd);
            case SolveQuizCommand cmd -> handleSolveQuiz(cmd);
            case UpdateTournamentSatelliteInfoCommand cmd -> handleUpdateTournamentSatelliteInfo(cmd);
            case DeactivateTournamentSatelliteCommand cmd -> handleDeactivateTournamentSatellite(cmd);
            case RemoveTournamentSatelliteCommand cmd -> handleRemoveTournamentSatellite(cmd);
            case AnonymizeUserCommand cmd -> handleAnonymizeUser(cmd);
            case RemoveUserCommand cmd -> handleRemoveUser(cmd);
            case UpdateUserNameCommand cmd -> handleUpdateUserName(cmd);
            case UpdateParticipantAnswerCommand cmd -> handleUpdateParticipantAnswer(cmd);
            default -> {
                logger.warning("Unknown command type: " + command.getClass().getName());
                yield null;
            }
        };
    }

    private Object handleGetTournamentSatelliteById(GetTournamentSatelliteByIdCommand command) {
        logger.info("Getting tournament satellite: " + command.getAggregateId());
        return tournamentSatelliteService.getTournamentSatelliteById(
                command.getAggregateId(),
                command.getUnitOfWork());
    }

    private Object handleFindTournamentSatelliteByTournamentId(FindTournamentSatelliteByTournamentIdCommand command) {
        logger.info("Finding tournament satellite by tournament: " + command.getTournamentAggregateId());
        return tournamentSatelliteService.findTournamentSatelliteByTournamentId(
                command.getTournamentAggregateId(),
                command.getUnitOfWork());
    }

    private Object handleCreateTournamentSatellite(CreateTournamentSatelliteCommand command) {
        logger.info("Creating tournament satellite for tournament: " + command.getTournamentAggregateId());
        return tournamentSatelliteService.createTournamentSatellite(
                command.getTournamentAggregateId(),
                command.getTournamentDto(),
                command.getCreatorDto(),
                command.getCourseExecutionDto(),
                command.getUnitOfWork());
    }

    private Object handleAddParticipant(AddParticipantCommand command) {
        logger.info("Adding participant to tournament: " + command.getTournamentAggregateId());
        tournamentSatelliteService.addParticipant(
                command.getTournamentAggregateId(),
                command.getUserDto(),
                command.getTournamentDto(),
                command.getUnitOfWork());
        return null;
    }

    private Object handleLeaveTournament(LeaveTournamentCommand command) {
        logger.info("Leaving tournament: " + command.getTournamentAggregateId());
        tournamentSatelliteService.leaveTournament(
                command.getTournamentAggregateId(),
                command.getUserAggregateId(),
                command.getTournamentDto(),
                command.getUnitOfWork());
        return null;
    }

    private Object handleSolveQuiz(SolveQuizCommand command) {
        logger.info("Solving quiz for tournament: " + command.getTournamentAggregateId());
        tournamentSatelliteService.solveQuiz(
                command.getTournamentAggregateId(),
                command.getUserAggregateId(),
                command.getAnswerAggregateId(),
                command.getUnitOfWork());
        return null;
    }

    private Object handleUpdateTournamentSatelliteInfo(UpdateTournamentSatelliteInfoCommand command) {
        logger.info("Updating replicated tournament info for tournament: " + command.getTournamentAggregateId());
        return tournamentSatelliteService.updateTournamentInfoByTournamentId(
                command.getTournamentAggregateId(),
                command.getStartTime(),
                command.isCancelled(),
                command.getTournamentVersion(),
                command.getUnitOfWork());
    }

    private Object handleDeactivateTournamentSatellite(DeactivateTournamentSatelliteCommand command) {
        logger.info("Deactivating tournament satellite for tournament: " + command.getTournamentAggregateId());
        tournamentSatelliteService.deactivateTournamentSatellite(
                command.getTournamentAggregateId(),
                command.getUnitOfWork());
        return null;
    }

    private Object handleRemoveTournamentSatellite(RemoveTournamentSatelliteCommand command) {
        logger.info("Removing tournament satellite for tournament: " + command.getTournamentAggregateId());
        tournamentSatelliteService.removeTournamentSatellite(
                command.getTournamentAggregateId(),
                command.getUnitOfWork());
        return null;
    }

    private Object handleAnonymizeUser(AnonymizeUserCommand command) {
        logger.info("Anonymizing user for tournament satellite: " + command.getSatelliteAggregateId());
        return tournamentSatelliteService.anonymizeUser(
                command.getSatelliteAggregateId(),
                command.getExecutionAggregateId(),
                command.getUserAggregateId(),
                command.getName(),
                command.getUsername(),
                command.getEventVersion(),
                command.getUnitOfWork());
    }

    private Object handleRemoveUser(RemoveUserCommand command) {
        logger.info("Removing user " + command.getUserAggregateId() + " from tournament satellite "
                + command.getSatelliteAggregateId());
        return tournamentSatelliteService.removeUser(
                command.getSatelliteAggregateId(),
                command.getCourseExecutionAggregateId(),
                command.getUserAggregateId(),
                command.getEventVersion(),
                command.getUnitOfWork());
    }

    private Object handleUpdateUserName(UpdateUserNameCommand command) {
        logger.info("Updating user name in tournament satellite: " + command.getSatelliteAggregateId());
        tournamentSatelliteService.updateUserName(
                command.getSatelliteAggregateId(),
                command.getExecutionAggregateId(),
                command.getEventVersion(),
                command.getUserAggregateId(),
                command.getName(),
                command.getUnitOfWork());
        return null;
    }

    private Object handleUpdateParticipantAnswer(UpdateParticipantAnswerCommand command) {
        logger.info("Updating participant answer: satellite=" + command.getSatelliteAggregateId() + ", student="
                + command.getStudentAggregateId());
        return tournamentSatelliteService.updateParticipantAnswer(
                command.getSatelliteAggregateId(),
                command.getStudentAggregateId(),
                command.getQuizAnswerAggregateId(),
                command.getQuestionAggregateId(),
                command.isCorrect(),
                command.getEventVersion(),
                command.getUnitOfWork());
    }
}
