package org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.service;

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
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.aggregate.*;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;

import static org.anonymous.msrefactor.ms.exception.SimulatorErrorMessage.AGGREGATE_NOT_FOUND;
import static org.anonymous.msrefactor.quizzes.microservices.exception.QuizzesErrorMessage.CANNOT_UPDATE_TOURNAMENT;
import static org.anonymous.msrefactor.quizzes.microservices.exception.QuizzesErrorMessage.TOURNAMENT_PARTICIPANT_NOT_FOUND;

@Service
public class TournamentSatelliteService {
    private static final Logger logger = LoggerFactory.getLogger(TournamentSatelliteService.class);

    @Autowired
    private AggregateIdGeneratorService aggregateIdGeneratorService;

    private final UnitOfWorkService<UnitOfWork> unitOfWorkService;

    private final TournamentSatelliteCustomRepository tournamentSatelliteRepository;

    @Autowired
    private TournamentSatelliteFactory tournamentSatelliteFactory;

    public TournamentSatelliteService(UnitOfWorkService unitOfWorkService,
            TournamentSatelliteCustomRepository tournamentSatelliteRepository) {
        this.unitOfWorkService = unitOfWorkService;
        this.tournamentSatelliteRepository = tournamentSatelliteRepository;
    }

    /*
     * The satellite resolves its own root from the id the caller carries — the
     * core's tournamentAggregateId (boundary rule: external ids do not change).
     */
    private TournamentSatellite loadByTournamentAggregateId(Integer tournamentAggregateId, UnitOfWork unitOfWork) {
        Integer satelliteAggregateId = tournamentSatelliteRepository
                .findTournamentSatelliteIdByTournamentId(tournamentAggregateId)
                .orElseThrow(() -> new SimulatorException(AGGREGATE_NOT_FOUND, tournamentAggregateId));
        return (TournamentSatellite) unitOfWorkService.aggregateLoadAndRegisterRead(satelliteAggregateId, unitOfWork);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public TournamentSatelliteDto getTournamentSatelliteById(Integer aggregateId, UnitOfWork unitOfWork) {
        return tournamentSatelliteFactory.createTournamentSatelliteDto(
                (TournamentSatellite) unitOfWorkService.aggregateLoadAndRegisterRead(aggregateId, unitOfWork));
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public TournamentSatelliteDto findTournamentSatelliteByTournamentId(Integer tournamentAggregateId,
            UnitOfWork unitOfWork) {
        return tournamentSatelliteFactory
                .createTournamentSatelliteDto(loadByTournamentAggregateId(tournamentAggregateId, unitOfWork));
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public TournamentSatelliteDto createTournamentSatellite(Integer tournamentAggregateId, TournamentDto tournamentDto,
            UserDto creatorDto, CourseExecutionDto courseExecutionDto, UnitOfWork unitOfWork) {
        Integer aggregateId = aggregateIdGeneratorService.getNewAggregateId();
        TournamentSatellite tournamentSatellite = tournamentSatelliteFactory.createTournamentSatellite(aggregateId,
                tournamentAggregateId, tournamentDto, creatorDto, courseExecutionDto);
        unitOfWorkService.registerChanged(tournamentSatellite, unitOfWork);
        return tournamentSatelliteFactory.createTournamentSatelliteDto(tournamentSatellite);
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
        TournamentSatellite oldSatellite = loadByTournamentAggregateId(tournamentAggregateId, unitOfWork);
        TournamentSatellite newSatellite = tournamentSatelliteFactory
                .createTournamentSatelliteFromExisting(oldSatellite);

        refreshTournamentInfo(newSatellite, tournamentDto);

        logger.info("Adding participant {} to tournament {}", tournamentParticipant.getParticipantName(),
                tournamentAggregateId);

        newSatellite.addParticipant(tournamentParticipant);

        unitOfWorkService.registerChanged(newSatellite, unitOfWork);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void leaveTournament(Integer tournamentAggregateId, Integer userAggregateId, TournamentDto tournamentDto,
            UnitOfWork unitOfWork) {
        TournamentSatellite oldSatellite = loadByTournamentAggregateId(tournamentAggregateId, unitOfWork);
        TournamentSatellite newSatellite = tournamentSatelliteFactory
                .createTournamentSatelliteFromExisting(oldSatellite);
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
        TournamentSatellite oldSatellite = loadByTournamentAggregateId(tournamentAggregateId, unitOfWork);
        TournamentSatellite newSatellite = tournamentSatelliteFactory
                .createTournamentSatelliteFromExisting(oldSatellite);
        TournamentParticipant participant = newSatellite.findParticipant(userAggregateId);
        if (participant == null) {
            throw new QuizzesException(TOURNAMENT_PARTICIPANT_NOT_FOUND, userAggregateId, tournamentAggregateId);
        }
        participant.answerQuiz();
        unitOfWorkService.registerChanged(newSatellite, unitOfWork);
    }

    /*
     * Refresh of the replicated core scalars — used both by the synchronous saga
     * step of UpdateTournament/CancelTournament (by tournament id) and by the
     * UpdateTournamentEvent quad (by satellite id).
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public TournamentSatelliteDto updateTournamentInfoByTournamentId(Integer tournamentAggregateId, String startTime,
            boolean cancelled, Long tournamentVersion, UnitOfWork unitOfWork) {
        TournamentSatellite oldSatellite = loadByTournamentAggregateId(tournamentAggregateId, unitOfWork);
        return updateTournamentInfo(oldSatellite, startTime, cancelled, tournamentVersion, unitOfWork);
    }

    private TournamentSatelliteDto updateTournamentInfo(TournamentSatellite oldSatellite, String startTime,
            boolean cancelled, Long tournamentVersion, UnitOfWork unitOfWork) {
        if (oldSatellite.getTournamentVersion() != null && tournamentVersion != null
                && oldSatellite.getTournamentVersion() >= tournamentVersion) {
            return null;
        }
        TournamentSatellite newSatellite = tournamentSatelliteFactory
                .createTournamentSatelliteFromExisting(oldSatellite);
        if (startTime != null) {
            newSatellite.setStartTime(DateHandler.toLocalDateTime(startTime));
        }
        newSatellite.setCancelled(cancelled);
        if (tournamentVersion != null) {
            newSatellite.setTournamentVersion(tournamentVersion);
        }
        unitOfWorkService.registerChanged(newSatellite, unitOfWork);
        return tournamentSatelliteFactory.createTournamentSatelliteDto(newSatellite);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void deactivateTournamentSatellite(Integer tournamentAggregateId, UnitOfWork unitOfWork) {
        TournamentSatellite oldSatellite = loadByTournamentAggregateId(tournamentAggregateId, unitOfWork);
        if (oldSatellite.getState() != Aggregate.AggregateState.ACTIVE) {
            return;
        }
        TournamentSatellite newSatellite = tournamentSatelliteFactory
                .createTournamentSatelliteFromExisting(oldSatellite);
        newSatellite.setState(Aggregate.AggregateState.INACTIVE);
        unitOfWorkService.registerChanged(newSatellite, unitOfWork);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void removeTournamentSatellite(Integer tournamentAggregateId, UnitOfWork unitOfWork) {
        TournamentSatellite oldSatellite = loadByTournamentAggregateId(tournamentAggregateId, unitOfWork);
        TournamentSatellite newSatellite = tournamentSatelliteFactory
                .createTournamentSatelliteFromExisting(oldSatellite);
        newSatellite.remove();
        unitOfWorkService.registerChanged(newSatellite, unitOfWork);
    }

    /******************************* EVENT PROCESSING SERVICES ********************************/

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public TournamentSatelliteDto anonymizeUser(Integer satelliteAggregateId, Integer executionAggregateId,
            Integer userAggregateId, String name, String username, Long eventVersion, UnitOfWork unitOfWork) {
        TournamentSatellite oldSatellite = (TournamentSatellite) unitOfWorkService
                .aggregateLoadAndRegisterRead(satelliteAggregateId, unitOfWork);
        TournamentSatellite newSatellite = tournamentSatelliteFactory
                .createTournamentSatelliteFromExisting(oldSatellite);

        if (!newSatellite.getCourseExecutionAggregateId().equals(executionAggregateId)) {
            return null;
        }

        if (newSatellite.getTournamentCreator().getCreatorAggregateId().equals(userAggregateId)) {
            newSatellite.getTournamentCreator().setCreatorName(name);
            newSatellite.getTournamentCreator().setCreatorUsername(username);
            newSatellite.setCourseExecutionVersion(eventVersion);
            newSatellite.setState(Aggregate.AggregateState.INACTIVE);
            unitOfWorkService.registerChanged(newSatellite, unitOfWork);
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
    public TournamentSatelliteDto removeUser(Integer satelliteAggregateId, Integer courseExecutionAggregateId,
            Integer userAggregateId, Long eventVersion, UnitOfWork unitOfWork) {
        TournamentSatellite oldSatellite = (TournamentSatellite) unitOfWorkService
                .aggregateLoadAndRegisterRead(satelliteAggregateId, unitOfWork);

        TournamentSatellite newSatellite = tournamentSatelliteFactory
                .createTournamentSatelliteFromExisting(oldSatellite);
        if (newSatellite.getTournamentCreator().getCreatorAggregateId().equals(userAggregateId)) {
            newSatellite.getTournamentCreator().setCreatorState(Aggregate.AggregateState.INACTIVE);
            newSatellite.setState(Aggregate.AggregateState.INACTIVE);
            newSatellite.setCourseExecutionVersion(eventVersion);
            unitOfWorkService.registerChanged(newSatellite, unitOfWork);
        }

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
        TournamentSatellite oldSatellite = (TournamentSatellite) unitOfWorkService
                .aggregateLoadAndRegisterRead(satelliteAggregateId, unitOfWork);
        TournamentSatellite newSatellite = tournamentSatelliteFactory
                .createTournamentSatelliteFromExisting(oldSatellite);

        if (!newSatellite.getCourseExecutionAggregateId().equals(executionAggregateId)) {
            return;
        }

        if (newSatellite.getTournamentCreator().getCreatorAggregateId().equals(userAggregateId)) {
            newSatellite.getTournamentCreator().setCreatorName(name);
            newSatellite.setCourseExecutionVersion(eventVersion);
            changes = true;
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
    public TournamentSatelliteDto updateParticipantAnswer(Integer satelliteAggregateId, Integer studentAggregateId,
            Integer quizAnswerAggregateId, Integer questionAggregateId, boolean isCorrect, Long eventVersion,
            UnitOfWork unitOfWork) {
        TournamentSatellite oldSatellite = (TournamentSatellite) unitOfWorkService
                .aggregateLoadAndRegisterRead(satelliteAggregateId, unitOfWork);
        TournamentParticipant oldParticipant = oldSatellite.findParticipant(studentAggregateId);
        if (oldParticipant != null && oldParticipant.getParticipantAnswer().getQuizAnswerVersion() >= eventVersion) {
            return null;
        }
        TournamentSatellite newSatellite = tournamentSatelliteFactory
                .createTournamentSatelliteFromExisting(oldSatellite);
        TournamentParticipant tournamentParticipant = newSatellite.findParticipant(studentAggregateId);
        if (tournamentParticipant == null) {
            throw new QuizzesException(TOURNAMENT_PARTICIPANT_NOT_FOUND, studentAggregateId,
                    oldSatellite.getTournamentAggregateId());
        }
        /*
         * AFTER_END / IS_CANCELED — checked against the replicated core scalars; after
         * the split this check is eventually consistent with the core.
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

    private void refreshTournamentInfo(TournamentSatellite satellite, TournamentDto tournamentDto) {
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
