package org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.anonymous.msrefactor.ms.aggregate.Aggregate;
import org.anonymous.msrefactor.ms.aggregate.AggregateIdGeneratorService;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWorkService;
import org.anonymous.msrefactor.ms.utils.DateHandler;
import org.anonymous.msrefactor.quizzes.microservices.exception.QuizzesException;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.aggregate.*;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;

import static org.anonymous.msrefactor.ms.exception.SimulatorErrorMessage.AGGREGATE_NOT_FOUND;

@Service
public class TournamentSatelliteService {

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

    private Integer resolveSatelliteId(Integer tournamentAggregateId) {
        return tournamentSatelliteRepository.findTournamentSatelliteIdByTournamentId(tournamentAggregateId)
                .orElseThrow(() -> new QuizzesException(AGGREGATE_NOT_FOUND, tournamentAggregateId));
    }

    private TournamentSatellite loadByTournamentId(Integer tournamentAggregateId, UnitOfWork unitOfWork) {
        Integer satelliteId = resolveSatelliteId(tournamentAggregateId);
        return (TournamentSatellite) unitOfWorkService.aggregateLoadAndRegisterRead(satelliteId, unitOfWork);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public TournamentSatelliteDto getTournamentSatelliteById(Integer aggregateId, UnitOfWork unitOfWork) {
        return tournamentSatelliteFactory.createTournamentSatelliteDto(
                (TournamentSatellite) unitOfWorkService.aggregateLoadAndRegisterRead(aggregateId, unitOfWork));
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public TournamentSatelliteDto findTournamentSatelliteByTournamentId(Integer tournamentAggregateId,
            UnitOfWork unitOfWork) {
        TournamentSatellite tournamentSatellite = loadByTournamentId(tournamentAggregateId, unitOfWork);
        return tournamentSatelliteFactory.createTournamentSatelliteDto(tournamentSatellite);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public TournamentSatelliteDto createTournamentSatellite(Integer tournamentAggregateId, UserDto creatorDto,
            Integer courseExecutionAggregateId, Long courseExecutionVersion, String startTime, boolean cancelled,
            UnitOfWork unitOfWork) {
        Integer aggregateId = aggregateIdGeneratorService.getNewAggregateId();
        TournamentSatellite tournamentSatellite = tournamentSatelliteFactory.createTournamentSatellite(aggregateId,
                tournamentAggregateId, creatorDto, courseExecutionAggregateId, courseExecutionVersion,
                DateHandler.toLocalDateTime(startTime), cancelled);
        unitOfWorkService.registerChanged(tournamentSatellite, unitOfWork);
        return tournamentSatelliteFactory.createTournamentSatelliteDto(tournamentSatellite);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public TournamentSatelliteDto anonymizeUser(Integer satelliteAggregateId, Integer executionAggregateId,
            Integer userAggregateId, String name, String username, Long eventVersion, UnitOfWork unitOfWork) {
        TournamentSatellite oldSatellite = (TournamentSatellite) unitOfWorkService
                .aggregateLoadAndRegisterRead(satelliteAggregateId, unitOfWork);
        if (!oldSatellite.getCourseExecutionAggregateId().equals(executionAggregateId)) {
            return null;
        }
        TournamentSatellite newSatellite = tournamentSatelliteFactory
                .createTournamentSatelliteFromExisting(oldSatellite);

        boolean changes = false;
        if (newSatellite.getTournamentCreator().getCreatorAggregateId().equals(userAggregateId)) {
            newSatellite.getTournamentCreator().setCreatorName(name);
            newSatellite.getTournamentCreator().setCreatorUsername(username);
            newSatellite.setCourseExecutionVersion(eventVersion);
            newSatellite.setState(Aggregate.AggregateState.INACTIVE);
            changes = true;
        }

        if (changes) {
            unitOfWorkService.registerChanged(newSatellite, unitOfWork);
        }
        return tournamentSatelliteFactory.createTournamentSatelliteDto(newSatellite);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void updateUserName(Integer satelliteAggregateId, Integer executionAggregateId, Long eventVersion,
            Integer userAggregateId, String name, UnitOfWork unitOfWork) {
        TournamentSatellite oldSatellite = (TournamentSatellite) unitOfWorkService
                .aggregateLoadAndRegisterRead(satelliteAggregateId, unitOfWork);
        if (!oldSatellite.getCourseExecutionAggregateId().equals(executionAggregateId)) {
            return;
        }
        TournamentSatellite newSatellite = tournamentSatelliteFactory
                .createTournamentSatelliteFromExisting(oldSatellite);
        boolean changes = false;
        if (newSatellite.getTournamentCreator().getCreatorAggregateId().equals(userAggregateId)) {
            newSatellite.getTournamentCreator().setCreatorName(name);
            newSatellite.setCourseExecutionVersion(eventVersion);
            changes = true;
        }
        if (changes) {
            unitOfWorkService.registerChanged(newSatellite, unitOfWork);
        }
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public TournamentSatelliteDto removeUser(Integer satelliteAggregateId, Integer executionAggregateId,
            Integer userAggregateId, Long eventVersion, UnitOfWork unitOfWork) {
        TournamentSatellite oldSatellite = (TournamentSatellite) unitOfWorkService
                .aggregateLoadAndRegisterRead(satelliteAggregateId, unitOfWork);
        TournamentSatellite newSatellite = tournamentSatelliteFactory
                .createTournamentSatelliteFromExisting(oldSatellite);
        boolean changes = false;
        if (newSatellite.getTournamentCreator().getCreatorAggregateId().equals(userAggregateId)) {
            newSatellite.getTournamentCreator().setCreatorState(Aggregate.AggregateState.INACTIVE);
            newSatellite.setState(Aggregate.AggregateState.INACTIVE);
            newSatellite.setCourseExecutionVersion(eventVersion);
            changes = true;
        }
        if (changes) {
            unitOfWorkService.registerChanged(newSatellite, unitOfWork);
        }
        return tournamentSatelliteFactory.createTournamentSatelliteDto(newSatellite);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void updateScalars(Integer tournamentAggregateId, String startTime, Boolean cancelled, UnitOfWork unitOfWork) {
        TournamentSatellite oldSatellite = loadByTournamentId(tournamentAggregateId, unitOfWork);
        TournamentSatellite newSatellite = tournamentSatelliteFactory
                .createTournamentSatelliteFromExisting(oldSatellite);
        if (startTime != null) {
            newSatellite.setStartTime(DateHandler.toLocalDateTime(startTime));
        }
        if (cancelled != null) {
            newSatellite.setCancelled(cancelled);
        }
        unitOfWorkService.registerChanged(newSatellite, unitOfWork);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void removeTournamentSatellite(Integer tournamentAggregateId, UnitOfWork unitOfWork) {
        TournamentSatellite oldSatellite = loadByTournamentId(tournamentAggregateId, unitOfWork);
        TournamentSatellite newSatellite = tournamentSatelliteFactory
                .createTournamentSatelliteFromExisting(oldSatellite);
        newSatellite.remove();
        unitOfWorkService.registerChanged(newSatellite, unitOfWork);
    }
}
