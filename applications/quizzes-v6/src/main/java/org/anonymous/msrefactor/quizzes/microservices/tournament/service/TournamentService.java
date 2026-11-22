package org.anonymous.msrefactor.quizzes.microservices.tournament.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.anonymous.msrefactor.ms.aggregate.Aggregate;
import org.anonymous.msrefactor.ms.aggregate.AggregateIdGeneratorService;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWorkService;
import org.anonymous.msrefactor.ms.utils.DateHandler;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.CourseExecutionDto;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.QuizDto;
import org.anonymous.msrefactor.quizzes.microservices.topic.aggregate.TopicDto;
import org.anonymous.msrefactor.quizzes.microservices.tournament.aggregate.*;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.anonymous.msrefactor.quizzes.microservices.exception.QuizzesErrorMessage.*;

@Service
public class TournamentService {
    private static final Logger logger = LoggerFactory.getLogger(TournamentService.class);

    @Autowired
    private AggregateIdGeneratorService aggregateIdGeneratorService;

    private final UnitOfWorkService<UnitOfWork> unitOfWorkService;

    private final TournamentCustomRepository tournamentRepository;

    @Autowired
    private TournamentFactory tournamentFactory;

    public TournamentService(UnitOfWorkService unitOfWorkService, TournamentCustomRepository tournamentRepository) {
        this.unitOfWorkService = unitOfWorkService;
        this.tournamentRepository = tournamentRepository;
    }

    // intended for requests from external functionalities
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public TournamentDto getTournamentById(Integer aggregateId, UnitOfWork unitOfWork) {
        return tournamentFactory.createTournamentDto((Tournament) unitOfWorkService.aggregateLoadAndRegisterRead(aggregateId, unitOfWork));
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public TournamentDto createTournament(TournamentDto tournamentDto, UserDto creatorDto,
                                          CourseExecutionDto courseExecutionDto, Set<TopicDto> topicDtos,
                                          QuizDto quizDto, UnitOfWork unitOfWork) {

        Integer aggregateId = aggregateIdGeneratorService.getNewAggregateId();

        Tournament tournament = tournamentFactory.createTournament(aggregateId, tournamentDto, creatorDto, courseExecutionDto, topicDtos, quizDto);

        unitOfWorkService.registerChanged(tournament, unitOfWork);
        return tournamentFactory.createTournamentDto(tournament);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public TournamentDto updateTournament(TournamentDto tournamentDto, Set<TopicDto> topicDtos, UnitOfWork unitOfWork) {
        Tournament oldTournament = (Tournament) unitOfWorkService.aggregateLoadAndRegisterRead(tournamentDto.getAggregateId(), unitOfWork);
        Tournament newTournament = tournamentFactory.createTournamentFromExisting(oldTournament);

        if (tournamentDto.getStartTime() != null ) {
            newTournament.setStartTime(DateHandler.toLocalDateTime(tournamentDto.getStartTime()));
        }

        if (tournamentDto.getEndTime() != null ) {
            newTournament.setEndTime(DateHandler.toLocalDateTime(tournamentDto.getEndTime()));
        }

        if (tournamentDto.getNumberOfQuestions() != null ) {
            newTournament.setNumberOfQuestions(tournamentDto.getNumberOfQuestions());
        }

        if (topicDtos != null && !topicDtos.isEmpty() ) {
            Set<TournamentTopic> tournamentTopics = topicDtos.stream()
                    .map(TournamentTopic::new)
                    .collect(Collectors.toSet());
            newTournament.setTournamentTopics(tournamentTopics);
        }

        unitOfWorkService.registerChanged(newTournament, unitOfWork);

        return tournamentFactory.createTournamentDto(newTournament);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public List<TournamentDto> getTournamentsByCourseExecutionId(Integer executionAggregateId, UnitOfWork unitOfWork) {
        return tournamentRepository.findAllRelevantTournamentIds(executionAggregateId).stream()
                .map(aggregateId -> (Tournament) unitOfWorkService.aggregateLoadAndRegisterRead(aggregateId, unitOfWork))
                .map(TournamentDto::new)
                .collect(Collectors.toList());

    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public List<TournamentDto> getOpenedTournamentsForCourseExecution(Integer executionAggregateId, UnitOfWork unitOfWork) {
        LocalDateTime now = DateHandler.now();
        return tournamentRepository.findAllRelevantTournamentIds(executionAggregateId).stream()
                .map(aggregateId -> (Tournament) unitOfWorkService.aggregateLoad(aggregateId, unitOfWork))
                .filter(t -> now.isBefore(t.getEndTime()))
                .filter(t -> !t.isCancelled())
                .map(tournament -> (Tournament) unitOfWorkService.registerRead(tournament, unitOfWork))
                .map(TournamentDto::new)
                .collect(Collectors.toList());
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public List<TournamentDto> getClosedTournamentsForCourseExecution(Integer executionAggregateId, UnitOfWork unitOfWork) {
        LocalDateTime now = DateHandler.now();
        return tournamentRepository.findAllRelevantTournamentIds(executionAggregateId).stream()
                .map(aggregateId -> (Tournament) unitOfWorkService.aggregateLoad(aggregateId, unitOfWork))
                .filter(t -> now.isAfter(t.getEndTime()))
                .filter(t -> !t.isCancelled())
                .map(tournament -> (Tournament) unitOfWorkService.registerRead(tournament, unitOfWork))
                .map(TournamentDto::new)
                .collect(Collectors.toList());
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void cancelTournament(Integer tournamentAggregateId, UnitOfWork unitOfWork) {
        Tournament oldTournament = (Tournament) unitOfWorkService.aggregateLoadAndRegisterRead(tournamentAggregateId, unitOfWork);
        Tournament newTournament = tournamentFactory.createTournamentFromExisting(oldTournament);
        newTournament.cancel();
        unitOfWorkService.registerChanged(newTournament, unitOfWork);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void removeTournament(Integer tournamentAggregateId, UnitOfWork unitOfWork) {
        Tournament oldTournament = (Tournament) unitOfWorkService.aggregateLoadAndRegisterRead(tournamentAggregateId, unitOfWork);
        Tournament newTournament = tournamentFactory.createTournamentFromExisting(oldTournament);
        newTournament.remove();
        unitOfWorkService.registerChanged(newTournament, unitOfWork);
    }


    /******************************************* EVENT PROCESSING SERVICES ********************************************/

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public TournamentDto anonymizeUser(Integer tournamentAggregateId, Integer executionAggregateId, Integer userAggregateId, String name, String username, Long eventVersion, UnitOfWork unitOfWork) {
        Tournament oldTournament = (Tournament) unitOfWorkService.aggregateLoadAndRegisterRead(tournamentAggregateId, unitOfWork);

        if (!oldTournament.getTournamentCourseExecution().getCourseExecutionAggregateId().equals(executionAggregateId)) {
            return null;
        }

        if (oldTournament.getCreatorAggregateId() != null
                && oldTournament.getCreatorAggregateId().equals(userAggregateId)) {
            Tournament newTournament = tournamentFactory.createTournamentFromExisting(oldTournament);
            newTournament.getTournamentCourseExecution().setCourseExecutionVersion(eventVersion);
            newTournament.setState(Aggregate.AggregateState.INACTIVE);
            unitOfWorkService.registerChanged(newTournament, unitOfWork);
            return new TournamentDto(newTournament);
        }
        return new TournamentDto(oldTournament);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public TournamentDto removeCourseExecution(Integer tournamentAggregateId, Integer courseExecutionId, Long eventVersion, UnitOfWork unitOfWork) {
        Tournament oldTournament = (Tournament) unitOfWorkService.aggregateLoadAndRegisterRead(tournamentAggregateId, unitOfWork);
        if (oldTournament.getTournamentCourseExecution() != null && oldTournament.getTournamentCourseExecution().getCourseExecutionVersion() >= eventVersion) {
            return null;
        }

        Tournament newTournament = tournamentFactory.createTournamentFromExisting(oldTournament);
        if (newTournament.getTournamentCourseExecution().getCourseExecutionAggregateId().equals(courseExecutionId)) {
            newTournament.setState(Aggregate.AggregateState.INACTIVE);
            unitOfWorkService.registerChanged(newTournament, unitOfWork);
        }
        return new TournamentDto(newTournament);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public TournamentDto removeUser(Integer tournamentAggregateId, Integer courseExecutionAggregateId, Integer userAggregateId, Long eventVersion, UnitOfWork unitOfWork) {
        Tournament oldTournament = (Tournament) unitOfWorkService.aggregateLoadAndRegisterRead(tournamentAggregateId, unitOfWork);

        if (oldTournament.getCreatorAggregateId() != null
                && oldTournament.getCreatorAggregateId().equals(userAggregateId)) {
            Tournament newTournament = tournamentFactory.createTournamentFromExisting(oldTournament);
            newTournament.setState(Aggregate.AggregateState.INACTIVE);
            newTournament.getTournamentCourseExecution().setCourseExecutionVersion(eventVersion);
            unitOfWorkService.registerChanged(newTournament, unitOfWork);
            return new TournamentDto(newTournament);
        }
        return new TournamentDto(oldTournament);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public TournamentDto updateTopic(Integer tournamentAggregateId, Integer topicAggregateId, String topicName, Long eventVersion, UnitOfWork unitOfWork) {
        Tournament oldTournament = (Tournament) unitOfWorkService.aggregateLoadAndRegisterRead(tournamentAggregateId, unitOfWork);
        Tournament newTournament = tournamentFactory.createTournamentFromExisting(oldTournament);
        TournamentTopic topic = newTournament.findTopic(topicAggregateId);
        if (topic == null) {
            throw new org.anonymous.msrefactor.quizzes.microservices.exception.QuizzesException(TOURNAMENT_TOPIC_NOT_FOUND, topicAggregateId, tournamentAggregateId);
        }
        topic.setTopicName(topicName);
        topic.setTopicVersion(eventVersion);
        unitOfWorkService.registerChanged(newTournament, unitOfWork);

        return new TournamentDto(newTournament);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public TournamentDto removeTopic(Integer tournamentAggregateId, Integer topicAggregateId, Long eventVersion, UnitOfWork unitOfWork) {
        return null;
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public TournamentDto invalidateQuiz(Integer tournamentAggregateId, Integer aggregateId, Long aggregateVersion, UnitOfWork unitOfWork) {
        return null;
    }
}
