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
            case CreateTournamentSatelliteCommand cmd -> handleCreate(cmd);
            case FindTournamentSatelliteByTournamentIdCommand cmd -> handleFindByTournamentId(cmd);
            case GetTournamentSatelliteByIdCommand cmd -> handleGetById(cmd);
            case AddParticipantCommand cmd -> handleAddParticipant(cmd);
            case LeaveTournamentCommand cmd -> handleLeaveTournament(cmd);
            case SolveQuizCommand cmd -> handleSolveQuiz(cmd);
            case UpdateParticipantAnswerCommand cmd -> handleUpdateParticipantAnswer(cmd);
            case AnonymizeUserCommand cmd -> handleAnonymizeUser(cmd);
            case UpdateUserNameCommand cmd -> handleUpdateUserName(cmd);
            case RemoveUserCommand cmd -> handleRemoveUser(cmd);
            case UpdateTournamentSatelliteScalarsCommand cmd -> handleUpdateScalars(cmd);
            case RemoveTournamentSatelliteCommand cmd -> handleRemove(cmd);
            default -> {
                logger.warning("Unknown command type: " + command.getClass().getName());
                yield null;
            }
        };
    }

    private Object handleCreate(CreateTournamentSatelliteCommand command) {
        return tournamentSatelliteService.createTournamentSatellite(
                command.getTournamentAggregateId(),
                command.getCreatorDto(),
                command.getCourseExecutionAggregateId(),
                command.getCourseExecutionVersion(),
                command.getStartTime(),
                command.isCancelled(),
                command.getUnitOfWork());
    }

    private Object handleFindByTournamentId(FindTournamentSatelliteByTournamentIdCommand command) {
        return tournamentSatelliteService.findTournamentSatelliteByTournamentId(
                command.getTournamentAggregateId(),
                command.getUnitOfWork());
    }

    private Object handleGetById(GetTournamentSatelliteByIdCommand command) {
        return tournamentSatelliteService.getTournamentSatelliteById(
                command.getSatelliteAggregateId(),
                command.getUnitOfWork());
    }

    private Object handleAddParticipant(AddParticipantCommand command) {
        tournamentSatelliteService.addParticipant(
                command.getTournamentAggregateId(),
                command.getUserDto(),
                command.getUnitOfWork());
        return null;
    }

    private Object handleLeaveTournament(LeaveTournamentCommand command) {
        tournamentSatelliteService.leaveTournament(
                command.getTournamentAggregateId(),
                command.getUserAggregateId(),
                command.getUnitOfWork());
        return null;
    }

    private Object handleSolveQuiz(SolveQuizCommand command) {
        tournamentSatelliteService.solveQuiz(
                command.getTournamentAggregateId(),
                command.getUserAggregateId(),
                command.getAnswerAggregateId(),
                command.getUnitOfWork());
        return null;
    }

    private Object handleUpdateParticipantAnswer(UpdateParticipantAnswerCommand command) {
        return tournamentSatelliteService.updateParticipantAnswer(
                command.getSatelliteAggregateId(),
                command.getStudentAggregateId(),
                command.getQuizAnswerAggregateId(),
                command.getQuestionAggregateId(),
                command.isCorrect(),
                command.getEventVersion(),
                command.getUnitOfWork());
    }

    private Object handleAnonymizeUser(AnonymizeUserCommand command) {
        return tournamentSatelliteService.anonymizeUser(
                command.getSatelliteAggregateId(),
                command.getExecutionAggregateId(),
                command.getUserAggregateId(),
                command.getName(),
                command.getUsername(),
                command.getEventVersion(),
                command.getUnitOfWork());
    }

    private Object handleUpdateUserName(UpdateUserNameCommand command) {
        tournamentSatelliteService.updateUserName(
                command.getSatelliteAggregateId(),
                command.getExecutionAggregateId(),
                command.getEventVersion(),
                command.getUserAggregateId(),
                command.getName(),
                command.getUnitOfWork());
        return null;
    }

    private Object handleRemoveUser(RemoveUserCommand command) {
        return tournamentSatelliteService.removeUser(
                command.getSatelliteAggregateId(),
                command.getExecutionAggregateId(),
                command.getUserAggregateId(),
                command.getEventVersion(),
                command.getUnitOfWork());
    }

    private Object handleUpdateScalars(UpdateTournamentSatelliteScalarsCommand command) {
        tournamentSatelliteService.updateScalars(
                command.getTournamentAggregateId(),
                command.getStartTime(),
                command.getCancelled(),
                command.getUnitOfWork());
        return null;
    }

    private Object handleRemove(RemoveTournamentSatelliteCommand command) {
        tournamentSatelliteService.removeTournamentSatellite(
                command.getTournamentAggregateId(),
                command.getUnitOfWork());
        return null;
    }
}
