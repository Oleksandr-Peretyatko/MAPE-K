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
import org.anonymous.msrefactor.quizzes.microservices.exception.QuizzesException;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.CourseExecutionDto;
import org.anonymous.msrefactor.quizzes.microservices.tournament.aggregate.TournamentDto;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.aggregate.*;

import static org.anonymous.msrefactor.ms.exception.SimulatorErrorMessage.AGGREGATE_NOT_FOUND;

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

        if (changes) {
            unitOfWorkService.registerChanged(newSatellite, unitOfWork);
        }
    }

}
