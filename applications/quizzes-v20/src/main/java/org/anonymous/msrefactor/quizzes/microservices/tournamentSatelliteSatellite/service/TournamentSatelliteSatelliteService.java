package org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.anonymous.msrefactor.ms.aggregate.Aggregate;
import org.anonymous.msrefactor.ms.aggregate.AggregateIdGeneratorService;
import org.anonymous.msrefactor.ms.exception.SimulatorException;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWorkService;
import org.anonymous.msrefactor.ms.utils.DateHandler;
import org.anonymous.msrefactor.quizzes.microservices.exception.QuizzesErrorMessage;
import org.anonymous.msrefactor.quizzes.microservices.exception.QuizzesException;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.CourseExecutionDto;
import org.anonymous.msrefactor.quizzes.microservices.tournament.aggregate.TournamentDto;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.aggregate.*;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;

import static org.anonymous.msrefactor.ms.exception.SimulatorErrorMessage.AGGREGATE_NOT_FOUND;
import static org.anonymous.msrefactor.quizzes.microservices.exception.QuizzesErrorMessage.CANNOT_UPDATE_TOURNAMENT;
import static org.anonymous.msrefactor.quizzes.microservices.exception.QuizzesErrorMessage.TOURNAMENT_PARTICIPANT_NOT_FOUND;

@Service
public class TournamentSatelliteSatelliteService {
    private static final Logger logger = LoggerFactory.getLogger(TournamentSatelliteSatelliteService.class);

    @Autowired
    private AggregateIdGeneratorService aggregateIdGeneratorService;

    private final UnitOfWorkService<UnitOfWork> unitOfWorkService;

    private final TournamentSatelliteSatelliteCustomRepository tournamentSatelliteSatelliteRepository;

    @Autowired
    private TournamentSatelliteSatelliteFactory tournamentSatelliteSatelliteFactory;

    public TournamentSatelliteSatelliteService(UnitOfWorkService unitOfWorkService,
            TournamentSatelliteSatelliteCustomRepository tournamentSatelliteSatelliteRepository) {
        this.unitOfWorkService = unitOfWorkService;
        this.tournamentSatelliteSatelliteRepository = tournamentSatelliteSatelliteRepository;
    }

    /*
     * The satellite resolves its own root from the id the caller carries — the
     * core Tournament's tournamentAggregateId (boundary rule: external ids do not
     * change).
     */
    private TournamentSatelliteSatellite loadByTournamentAggregateId(Integer tournamentAggregateId,
            UnitOfWork unitOfWork) {
        Integer satelliteAggregateId = tournamentSatelliteSatelliteRepository
                .findTournamentSatelliteSatelliteIdByTournamentId(tournamentAggregateId)
                .orElseThrow(() -> new SimulatorException(AGGREGATE_NOT_FOUND, tournamentAggregateId));
        return (TournamentSatelliteSatellite) unitOfWorkService.aggregateLoadAndRegisterRead(satelliteAggregateId,
                unitOfWork);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public TournamentSatelliteSatelliteDto getTournamentSatelliteSatelliteById(Integer aggregateId,
            UnitOfWork unitOfWork) {
        return tournamentSatelliteSatelliteFactory.createTournamentSatelliteSatelliteDto(
                (TournamentSatelliteSatellite) unitOfWorkService.aggregateLoadAndRegisterRead(aggregateId, unitOfWork));
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public TournamentSatelliteSatelliteDto findTournamentSatelliteSatelliteByTournamentId(Integer tournamentAggregateId,
            UnitOfWork unitOfWork) {
        return tournamentSatelliteSatelliteFactory
                .createTournamentSatelliteSatelliteDto(loadByTournamentAggregateId(tournamentAggregateId, unitOfWork));
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public TournamentSatelliteSatelliteDto createTournamentSatelliteSatellite(Integer tournamentAggregateId,
            Integer tournamentSatelliteAggregateId, TournamentDto tournamentDto,
            CourseExecutionDto courseExecutionDto, UnitOfWork unitOfWork) {
        Integer aggregateId = aggregateIdGeneratorService.getNewAggregateId();
        TournamentSatelliteSatellite tournamentSatelliteSatellite = tournamentSatelliteSatelliteFactory
                .createTournamentSatelliteSatellite(aggregateId, tournamentAggregateId, tournamentSatelliteAggregateId,
                        tournamentDto, courseExecutionDto);
        unitOfWorkService.registerChanged(tournamentSatelliteSatellite, unitOfWork);
        return tournamentSatelliteSatelliteFactory.createTournamentSatelliteSatelliteDto(tournamentSatelliteSatellite);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void addParticipant(Integer tournamentAggregateId, UserDto userDto, TournamentDto tournamentDto,
            UnitOfWork unitOfWork) {
        TournamentParticipant tournamentParticipant = new TournamentParticipant(userDto);
        if (tournamentParticipant.getParticipantName().equals("ANONYMOUS")
                || tournamentParticipant.getParticipantUsername().equals("ANONYMOUS")) {
            throw new QuizzesException(QuizzesErrorMessage.USER_IS_ANONYMOUS,
                    tournamentParticipant.getParticipantAggregateId());
        }
        TournamentSatelliteSatellite oldSatellite = loadByTournamentAggregateId(tournamentAggregateId, unitOfWork);
        TournamentSatelliteSatellite newSatellite = tournamentSatelliteSatelliteFactory
                .createTournamentSatelliteSatelliteFromExisting(oldSatellite);

        refreshTournamentInfo(newSatellite, tournamentDto);

        logger.info("Adding participant {} to tournament {}", tournamentParticipant.getParticipantName(),
                tournamentAggregateId);

        newSatellite.addParticipant(tournamentParticipant);

        unitOfWorkService.registerChanged(newSatellite, unitOfWork);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void leaveTournament(Integer tournamentAggregateId, Integer userAggregateId, TournamentDto tournamentDto,
            UnitOfWork unitOfWork) {
        TournamentSatelliteSatellite oldSatellite = loadByTournamentAggregateId(tournamentAggregateId, unitOfWork);
        TournamentSatelliteSatellite newSatellite = tournamentSatelliteSatelliteFactory
                .createTournamentSatelliteSatelliteFromExisting(oldSatellite);
        refreshTournamentInfo(newSatellite, tournamentDto);
        TournamentParticipant participantToRemove = newSatellite.findParticipant(userAggregateId);
        if (participantToRemove == null) {
            throw new QuizzesException(TOURNAMENT_PARTICIPANT_NOT_FOUND, userAggregateId, tournamentAggregateId);
        }
        newSatellite.removeParticipant(participantToRemove);
        unitOfWorkService.registerChanged(newSatellite, unitOfWork);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void solveQuiz(Integer tournamentAggregateId, Integer userAggregateId, Integer answerAggregateId,
            UnitOfWork unitOfWork) {
        TournamentSatelliteSatellite oldSatellite = loadByTournamentAggregateId(tournamentAggregateId, unitOfWork);
        TournamentSatelliteSatellite newSatellite = tournamentSatelliteSatelliteFactory
                .createTournamentSatelliteSatelliteFromExisting(oldSatellite);
        TournamentParticipant participant = newSatellite.findParticipant(userAggregateId);
        if (participant == null) {
            throw new QuizzesException(TOURNAMENT_PARTICIPANT_NOT_FOUND, userAggregateId, tournamentAggregateId);
        }
        participant.answerQuiz();
        unitOfWorkService.registerChanged(newSatellite, unitOfWork);
    }

    /*
     * Refresh of the replicated tournament scalars — used both by the synchronous
     * saga step of UpdateTournament/CancelTournament (by tournament id) and by the
     * UpdateTournamentEvent quad (by tournament id, the event's publisher).
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public TournamentSatelliteSatelliteDto updateTournamentInfoByTournamentId(Integer tournamentAggregateId,
            String startTime, boolean cancelled, Long tournamentVersion, UnitOfWork unitOfWork) {
        TournamentSatelliteSatellite oldSatellite = loadByTournamentAggregateId(tournamentAggregateId, unitOfWork);
        if (oldSatellite.getTournamentVersion() != null && tournamentVersion != null
                && oldSatellite.getTournamentVersion() >= tournamentVersion) {
            return null;
        }
        TournamentSatelliteSatellite newSatellite = tournamentSatelliteSatelliteFactory
                .createTournamentSatelliteSatelliteFromExisting(oldSatellite);
        if (startTime != null) {
            newSatellite.setStartTime(DateHandler.toLocalDateTime(startTime));
        }
        newSatellite.setCancelled(cancelled);
        if (tournamentVersion != null) {
            newSatellite.setTournamentVersion(tournamentVersion);
        }
        unitOfWorkService.registerChanged(newSatellite, unitOfWork);
        return tournamentSatelliteSatelliteFactory.createTournamentSatelliteSatelliteDto(newSatellite);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void deactivateTournamentSatelliteSatellite(Integer tournamentAggregateId, UnitOfWork unitOfWork) {
        TournamentSatelliteSatellite oldSatellite = loadByTournamentAggregateId(tournamentAggregateId, unitOfWork);
        if (oldSatellite.getState() != Aggregate.AggregateState.ACTIVE) {
            return;
        }
        TournamentSatelliteSatellite newSatellite = tournamentSatelliteSatelliteFactory
                .createTournamentSatelliteSatelliteFromExisting(oldSatellite);
        newSatellite.setState(Aggregate.AggregateState.INACTIVE);
        unitOfWorkService.registerChanged(newSatellite, unitOfWork);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void removeTournamentSatelliteSatellite(Integer tournamentAggregateId, UnitOfWork unitOfWork) {
        TournamentSatelliteSatellite oldSatellite = loadByTournamentAggregateId(tournamentAggregateId, unitOfWork);
        TournamentSatelliteSatellite newSatellite = tournamentSatelliteSatelliteFactory
                .createTournamentSatelliteSatelliteFromExisting(oldSatellite);
        newSatellite.remove();
        unitOfWorkService.registerChanged(newSatellite, unitOfWork);
    }

    /******************************* EVENT PROCESSING SERVICES ********************************/

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public TournamentSatelliteSatelliteDto anonymizeUser(Integer satelliteAggregateId, Integer executionAggregateId,
            Integer userAggregateId, String name, String username, Long eventVersion, UnitOfWork unitOfWork) {
        TournamentSatelliteSatellite oldSatellite = (TournamentSatelliteSatellite) unitOfWorkService
                .aggregateLoadAndRegisterRead(satelliteAggregateId, unitOfWork);
        TournamentSatelliteSatellite newSatellite = tournamentSatelliteSatelliteFactory
                .createTournamentSatelliteSatelliteFromExisting(oldSatellite);

        if (!newSatellite.getCourseExecutionAggregateId().equals(executionAggregateId)) {
            return null;
        }

        for (TournamentParticipant tp : newSatellite.getTournamentParticipants()) {
            if (tp.getParticipantAggregateId().equals(userAggregateId)) {
                tp.setParticipantName(name);
                tp.setParticipantUsername(username);
                newSatellite.setCourseExecutionVersion(eventVersion);
                unitOfWorkService.registerChanged(newSatellite, unitOfWork);
            }
        }
        return newSatellite.buildDto();
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public TournamentSatelliteSatelliteDto removeUser(Integer satelliteAggregateId, Integer courseExecutionAggregateId,
            Integer userAggregateId, Long eventVersion, UnitOfWork unitOfWork) {
        TournamentSatelliteSatellite oldSatellite = (TournamentSatelliteSatellite) unitOfWorkService
                .aggregateLoadAndRegisterRead(satelliteAggregateId, unitOfWork);

        TournamentSatelliteSatellite newSatellite = tournamentSatelliteSatelliteFactory
                .createTournamentSatelliteSatelliteFromExisting(oldSatellite);
        TournamentParticipant tournamentParticipant = newSatellite.findParticipant(userAggregateId);
        if (tournamentParticipant != null) {
            tournamentParticipant.setState(Aggregate.AggregateState.DELETED);
            newSatellite.setCourseExecutionVersion(eventVersion);
            unitOfWorkService.registerChanged(newSatellite, unitOfWork);
        }
        return newSatellite.buildDto();
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void updateUserName(Integer satelliteAggregateId, Integer executionAggregateId, Long eventVersion,
            Integer userAggregateId, String name, UnitOfWork unitOfWork) {
        boolean changes = false;
        TournamentSatelliteSatellite oldSatellite = (TournamentSatelliteSatellite) unitOfWorkService
                .aggregateLoadAndRegisterRead(satelliteAggregateId, unitOfWork);
        TournamentSatelliteSatellite newSatellite = tournamentSatelliteSatelliteFactory
                .createTournamentSatelliteSatelliteFromExisting(oldSatellite);

        if (!newSatellite.getCourseExecutionAggregateId().equals(executionAggregateId)) {
            return;
        }

        for (TournamentParticipant tp : newSatellite.getTournamentParticipants()) {
            if (tp.getParticipantAggregateId().equals(userAggregateId)) {
                tp.setParticipantName(name);
                newSatellite.setCourseExecutionVersion(eventVersion);
                changes = true;
            }
        }
        if (changes) {
            unitOfWorkService.registerChanged(newSatellite, unitOfWork);
        }
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public TournamentSatelliteSatelliteDto updateParticipantAnswer(Integer satelliteAggregateId,
            Integer studentAggregateId, Integer quizAnswerAggregateId, Integer questionAggregateId, boolean isCorrect,
            Long eventVersion, UnitOfWork unitOfWork) {
        TournamentSatelliteSatellite oldSatellite = (TournamentSatelliteSatellite) unitOfWorkService
                .aggregateLoadAndRegisterRead(satelliteAggregateId, unitOfWork);
        TournamentParticipant oldParticipant = oldSatellite.findParticipant(studentAggregateId);
        if (oldParticipant != null && oldParticipant.getParticipantAnswer().getQuizAnswerVersion() >= eventVersion) {
            return null;
        }
        TournamentSatelliteSatellite newSatellite = tournamentSatelliteSatelliteFactory
                .createTournamentSatelliteSatelliteFromExisting(oldSatellite);
        TournamentParticipant tournamentParticipant = newSatellite.findParticipant(studentAggregateId);
        if (tournamentParticipant == null) {
            throw new QuizzesException(TOURNAMENT_PARTICIPANT_NOT_FOUND, studentAggregateId,
                    oldSatellite.getTournamentAggregateId());
        }
        /*
         * AFTER_END / IS_CANCELED — checked against the replicated tournament scalars;
         * after the split this check is eventually consistent with the tournament.
         */
        if ((oldSatellite.getStartTime() != null && DateHandler.now().isAfter(oldSatellite.getStartTime()))
                || oldSatellite.isCancelled()) {
            throw new QuizzesException(CANNOT_UPDATE_TOURNAMENT, oldSatellite.getTournamentAggregateId());
        }
        tournamentParticipant.updateAnswerWithQuestion(quizAnswerAggregateId, quizAnswerAggregateId, isCorrect,
                eventVersion);
        unitOfWorkService.registerChanged(newSatellite, unitOfWork);
        return newSatellite.buildDto();
    }

    private void refreshTournamentInfo(TournamentSatelliteSatellite satellite, TournamentDto tournamentDto) {
        if (tournamentDto == null) {
            return;
        }
        if (tournamentDto.getStartTime() != null) {
            satellite.setStartTime(DateHandler.toLocalDateTime(tournamentDto.getStartTime()));
        }
        satellite.setCancelled(tournamentDto.isCancelled());
        if (tournamentDto.getVersion() != null && (satellite.getTournamentVersion() == null
                || tournamentDto.getVersion() > satellite.getTournamentVersion())) {
            satellite.setTournamentVersion(tournamentDto.getVersion());
        }
    }
}
