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
import org.anonymous.msrefactor.quizzes.events.UpdateTournamentEvent;
import org.anonymous.msrefactor.quizzes.microservices.exception.QuizzesErrorMessage;
import org.anonymous.msrefactor.quizzes.microservices.exception.QuizzesException;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.CourseExecutionDto;
import org.anonymous.msrefactor.quizzes.microservices.quiz.aggregate.QuizDto;
import org.anonymous.msrefactor.quizzes.microservices.topic.aggregate.TopicDto;
import org.anonymous.msrefactor.quizzes.microservices.tournament.aggregate.*;

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
    public TournamentDto createTournament(TournamentDto tournamentDto,
                                          CourseExecutionDto courseExecutionDto, Set<TopicDto> topicDtos,
                                          QuizDto quizDto, UnitOfWork unitOfWork) {

        Integer aggregateId = aggregateIdGeneratorService.getNewAggregateId();

        Tournament tournament = tournamentFactory.createTournament(aggregateId, tournamentDto, courseExecutionDto, topicDtos, quizDto); /* should the skeleton creation be part of the functionality?? */

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

        /*
         * TOURNAMENT_INFO — the satellite keeps replicated copies of startTime and
         * cancelled; this event is their refresh path.
         */
        unitOfWorkService.registerEvent(new UpdateTournamentEvent(newTournament.getAggregateId(),
                DateHandler.toISOString(newTournament.getStartTime()),
                DateHandler.toISOString(newTournament.getEndTime()), newTournament.isCancelled()), unitOfWork);

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
        unitOfWorkService.registerEvent(new UpdateTournamentEvent(newTournament.getAggregateId(),
                DateHandler.toISOString(newTournament.getStartTime()),
                DateHandler.toISOString(newTournament.getEndTime()), newTournament.isCancelled()), unitOfWork);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void removeTournament(Integer tournamentAggregateId, UnitOfWork unitOfWork) {
        Tournament oldTournament = (Tournament) unitOfWorkService.aggregateLoadAndRegisterRead(tournamentAggregateId, unitOfWork);
        Tournament newTournament = tournamentFactory.createTournamentFromExisting(oldTournament);
        newTournament.remove();
        unitOfWorkService.registerChanged(newTournament, unitOfWork);
    }

    /*
     * Called by the tournamentSatellite workflows when anonymisation/disenrollment
     * of the creator deactivates the tournament.
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void deactivateTournament(Integer tournamentAggregateId, UnitOfWork unitOfWork) {
        Tournament oldTournament = (Tournament) unitOfWorkService.aggregateLoadAndRegisterRead(tournamentAggregateId, unitOfWork);
        if (oldTournament.getState() != Aggregate.AggregateState.ACTIVE) {
            return;
        }
        Tournament newTournament = tournamentFactory.createTournamentFromExisting(oldTournament);
        newTournament.setState(Aggregate.AggregateState.INACTIVE);
        unitOfWorkService.registerChanged(newTournament, unitOfWork);
    }


    /******************************************* EVENT PROCESSING SERVICES ********************************************/

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
    public TournamentDto updateTopic(Integer tournamentAggregateId, Integer topicAggregateId, String topicName, Long eventVersion, UnitOfWork unitOfWork) {
        Tournament oldTournament = (Tournament) unitOfWorkService.aggregateLoadAndRegisterRead(tournamentAggregateId, unitOfWork);
        Tournament newTournament = tournamentFactory.createTournamentFromExisting(oldTournament);
        TournamentTopic topic = newTournament.findTopic(topicAggregateId);
        if (topic == null) {
            throw new QuizzesException(TOURNAMENT_TOPIC_NOT_FOUND, topicAggregateId, tournamentAggregateId);
        }
        topic.setTopicName(topicName);
        topic.setTopicVersion(eventVersion);
        unitOfWorkService.registerChanged(newTournament, unitOfWork);

        return new TournamentDto(newTournament);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public TournamentDto removeTopic(Integer tournamentAggregateId, Integer topicAggregateId, Long eventVersion, UnitOfWork unitOfWork) {
//        Tournament oldTournament = (Tournament) unitOfWorkService.aggregateLoadAndRegisterRead(tournamentAggregateId, unitOfWork);
//        TournamentTopic oldTopic = oldTournament.findTopic(topicAggregateId);
//        if (oldTopic != null && oldTopic.getTopicVersion() >= eventVersion) {
//            return null;
//        }
//        Tournament newTournament = tournamentFactory.createTournamentFromExisting(oldTournament);
//        TournamentTopic tournamentTopic  = newTournament.findTopic(topicAggregateId);
//        if (tournamentTopic == null) {
//            throw new QuizzesException(TOURNAMENT_TOPIC_NOT_FOUND, topicAggregateId, tournamentAggregateId);
//        }
//        newTournament.removeTopic(tournamentTopic);
//        QuizDto quizDto = new QuizDto();
//        quizDto.setAggregateId(newTournament.getTournamentQuiz().getQuizAggregateId());
//        quizDto.setAvailableDate(newTournament.getStartTime().toString());
//        quizDto.setConclusionDate(newTournament.getEndTime().toString());
//        quizDto.setResultsDate(newTournament.getEndTime().toString());
//        try {
//            quizService.updateGeneratedQuiz(quizDto, newTournament.getTournamentTopics().stream().filter(t -> t.getState() == Aggregate.AggregateState.ACTIVE).map(TournamentTopic::getTopicAggregateId).collect(Collectors.toSet()), newTournament.getNumberOfQuestions(), unitOfWork);
//        } catch (QuizzesException e) {
//            newTournament.setState(Aggregate.AggregateState.INACTIVE);
//        }
//
//        unitOfWorkService.registerChanged(newTournament, unitOfWork);
//        return new TournamentDto(newTournament);
        return null;
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public TournamentDto invalidateQuiz(Integer tournamentAggregateId, Integer aggregateId, Long aggregateVersion, UnitOfWork unitOfWork) {
//        Tournament oldTournament = (Tournament) unitOfWorkService.aggregateLoadAndRegisterRead(tournamentAggregateId, unitOfWork);
//        Tournament newTournament = tournamentFactory.createTournamentFromExisting(oldTournament);
//        List<Integer> topicsIds = newTournament.getTournamentTopics().stream().map(TournamentTopic::getTopicAggregateId).collect(Collectors.toList());
//
//        QuizDto quizDto = new QuizDto();
//        quizDto.setAvailableDate(newTournament.getStartTime().toString());
//        quizDto.setConclusionDate(newTournament.getEndTime().toString());
//        quizDto.setResultsDate(newTournament.getEndTime().toString());
//
//        QuizDto quizDto1 = null;
//        try {
//            quizDto1 = quizService.generateQuiz(newTournament.getTournamentCourseExecution().getCourseExecutionAggregateId(), quizDto, new ArrayList<>(), newTournament.getNumberOfQuestions(), unitOfWork); // TODO - empty questionDtos
//        } catch (QuizzesException e) {
//            newTournament.setState(Aggregate.AggregateState.INACTIVE);
//        }
//
//        if (quizDto1 != null) {
//            newTournament.getTournamentQuiz().setQuizAggregateId(quizDto1.getAggregateId());
//            newTournament.getTournamentQuiz().setQuizVersion(quizDto1.getVersion());
//            unitOfWorkService.registerChanged(newTournament, unitOfWork);
//        }
//
//        return new TournamentDto(newTournament);
        return null;
    }

}

