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
            case AnonymizeUserCommand cmd -> handleAnonymizeUser(cmd);
            case RemoveCourseExecutionCommand cmd -> handleRemoveCourseExecution(cmd);
            case UpdateTopicCommand cmd -> handleUpdateTopic(cmd);
            case RemoveTopicCommand cmd -> handleRemoveTopic(cmd);
            case RemoveUserCommand cmd -> handleRemoveUser(cmd);
            case InvalidateQuizCommand cmd -> handleInvalidateQuiz(cmd);
            default -> {
                logger.warning("Unknown command type: " + command.getClass().getName());
                yield null;
            }
        };
    }

    private Object handleGetTournamentById(GetTournamentByIdCommand command) {
        return tournamentService.getTournamentById(
                command.getAggregateId(),
                command.getUnitOfWork());
    }

    private Object handleCreateTournament(CreateTournamentCommand command) {
        return tournamentService.createTournament(
                command.getTournamentDto(),
                command.getCreatorDto(),
                command.getCourseExecutionDto(),
                command.getTopicDtos(),
                command.getQuizDto(),
                command.getUnitOfWork());
    }

    private Object handleGetTournamentsByCourseExecutionId(GetTournamentsByCourseExecutionIdCommand command) {
        return tournamentService.getTournamentsByCourseExecutionId(
                command.getExecutionAggregateId(),
                command.getUnitOfWork());
    }

    private Object handleGetOpenedTournamentsForCourseExecution(GetOpenedTournamentsForCourseExecutionCommand command) {
        return tournamentService.getOpenedTournamentsForCourseExecution(
                command.getExecutionAggregateId(),
                command.getUnitOfWork());
    }

    private Object handleGetClosedTournamentsForCourseExecution(GetClosedTournamentsForCourseExecutionCommand command) {
        return tournamentService.getClosedTournamentsForCourseExecution(
                command.getExecutionAggregateId(),
                command.getUnitOfWork());
    }

    private Object handleRemoveTournament(RemoveTournamentCommand command) {
        tournamentService.removeTournament(
                command.getTournamentAggregateId(),
                command.getUnitOfWork());
        return null;
    }

    private Object handleUpdateTournament(UpdateTournamentCommand command) {
        return tournamentService.updateTournament(
                command.getTournamentDto(),
                command.getTopicDtos(),
                command.getUnitOfWork());
    }

    private Object handleCancelTournament(CancelTournamentCommand command) {
        tournamentService.cancelTournament(
                command.getTournamentAggregateId(),
                command.getUnitOfWork());
        return null;
    }

    private Object handleAnonymizeUser(AnonymizeUserCommand command) {
        return tournamentService.anonymizeUser(
                command.getTournamentAggregateId(),
                command.getExecutionAggregateId(),
                command.getUserAggregateId(),
                command.getName(),
                command.getUsername(),
                command.getEventVersion(),
                command.getUnitOfWork());
    }

    private Object handleRemoveCourseExecution(RemoveCourseExecutionCommand command) {
        return tournamentService.removeCourseExecution(
                command.getTournamentAggregateId(),
                command.getCourseExecutionId(),
                command.getEventVersion(),
                command.getUnitOfWork());
    }

    private Object handleUpdateTopic(UpdateTopicCommand command) {
        return tournamentService.updateTopic(
                command.getTournamentAggregateId(),
                command.getTopicAggregateId(),
                command.getTopicName(),
                command.getEventVersion(),
                command.getUnitOfWork());
    }

    private Object handleRemoveTopic(RemoveTopicCommand command) {
        return tournamentService.removeTopic(
                command.getTournamentAggregateId(),
                command.getTopicAggregateId(),
                command.getEventVersion(),
                command.getUnitOfWork());
    }

    private Object handleRemoveUser(RemoveUserCommand command) {
        return tournamentService.removeUser(
                command.getTournamentAggregateId(),
                command.getCourseExecutionAggregateId(),
                command.getUserAggregateId(),
                command.getEventVersion(),
                command.getUnitOfWork());
    }

    private Object handleInvalidateQuiz(InvalidateQuizCommand command) {
        return tournamentService.invalidateQuiz(
                command.getTournamentAggregateId(),
                command.getAggregateId(),
                command.getAggregateVersion(),
                command.getUnitOfWork());
    }
}
