package org.anonymous.msrefactor.quizzes.microservices.execution.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.anonymous.msrefactor.ms.aggregate.Aggregate;
import org.anonymous.msrefactor.ms.aggregate.AggregateIdGeneratorService;
import org.anonymous.msrefactor.ms.aggregate.ServiceGuard;
import org.anonymous.msrefactor.ms.exception.SimulatorErrorMessage;
import org.anonymous.msrefactor.ms.exception.SimulatorException;
import org.anonymous.msrefactor.ms.utils.DateHandler;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWorkService;
import org.anonymous.msrefactor.quizzes.events.AnonymizeStudentEvent;
import org.anonymous.msrefactor.quizzes.events.DeleteCourseExecutionEvent;
import org.anonymous.msrefactor.quizzes.events.DisenrollStudentFromCourseExecutionEvent;
import org.anonymous.msrefactor.quizzes.events.UpdateStudentNameEvent;
import org.anonymous.msrefactor.quizzes.microservices.exception.QuizzesErrorMessage;
import org.anonymous.msrefactor.quizzes.microservices.exception.QuizzesException;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.*;
import org.anonymous.msrefactor.quizzes.microservices.quiz.aggregate.QuizDto;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.TopicDto;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.anonymous.msrefactor.quizzes.microservices.exception.QuizzesErrorMessage.*;

@Service
public class ExecutionService {
    @Autowired
    private AggregateIdGeneratorService aggregateIdGeneratorService;

    private final CourseExecutionRepository courseExecutionRepository;

    private final CourseExecutionCustomRepository courseExecutionCustomRepository;

    private final UnitOfWorkService<UnitOfWork> unitOfWorkService;

    @Autowired
    private CourseExecutionFactory courseExecutionFactory;

    private static final Logger logger = LoggerFactory.getLogger(ExecutionService.class);

    public ExecutionService(UnitOfWorkService unitOfWorkService, CourseExecutionRepository courseExecutionRepository,
            CourseExecutionCustomRepository courseExecutionCustomRepository) {
        this.unitOfWorkService = unitOfWorkService;
        this.courseExecutionRepository = courseExecutionRepository;
        this.courseExecutionCustomRepository = courseExecutionCustomRepository;
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public CourseExecutionDto getCourseExecutionById(Integer executionAggregateId, UnitOfWork unitOfWorkWork) {
        return courseExecutionFactory.createCourseExecutionDto(
                (Execution) unitOfWorkService.aggregateLoadAndRegisterRead(executionAggregateId, unitOfWorkWork));
    }

    @ServiceGuard("NO_DUPLICATE_COURSE_EXECUTION")
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public CourseExecutionDto createCourseExecution(CourseExecutionDto courseExecutionDto, UnitOfWork unitOfWork) {
        CourseExecutionCourse courseExecutionCourse = new CourseExecutionCourse(courseExecutionDto);

        // NO_DUPLICATE_COURSE_EXECUTION
        Set<Integer> existingIds = courseExecutionCustomRepository.findCourseExecutionIdsOfAllNonDeleted();
        for (Integer id : existingIds) {
            Execution existing = (Execution) unitOfWorkService.aggregateLoadAndRegisterRead(id, unitOfWork);
            if (existing.getAcronym().equals(courseExecutionDto.getAcronym())
                    && existing.getAcademicTerm().equals(courseExecutionDto.getAcademicTerm())) {
                throw new QuizzesException(DUPLICATE_COURSE_EXECUTION, courseExecutionDto.getAcronym(),
                        courseExecutionDto.getAcademicTerm());
            }
        }

        Execution execution = courseExecutionFactory.createCourseExecution(
                aggregateIdGeneratorService.getNewAggregateId(), courseExecutionDto, courseExecutionCourse);

        unitOfWorkService.registerChanged(execution, unitOfWork);
        return courseExecutionFactory.createCourseExecutionDto(execution);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public List<CourseExecutionDto> getAllCourseExecutions(UnitOfWork unitOfWork) {
        return courseExecutionCustomRepository.findCourseExecutionIdsOfAllNonDeleted().stream()
                .map(id -> (Execution) unitOfWorkService.aggregateLoadAndRegisterRead(id, unitOfWork))
                .map(CourseExecutionDto::new)
                .collect(Collectors.toList());
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void removeCourseExecution(Integer executionAggregateId, UnitOfWork unitOfWork) {
        Execution oldExecution = (Execution) unitOfWorkService.aggregateLoadAndRegisterRead(executionAggregateId,
                unitOfWork);
        Execution newExecution = courseExecutionFactory.createCourseExecutionFromExisting(oldExecution);

        newExecution.remove();

        // COURSE_EXECUTION_EXISTS, now intra (merge execution <- tournament): the dissolved
        // DeleteCourseExecutionEvent consumption in tournament becomes a synchronous update
        deactivateTournaments(newExecution);

        unitOfWorkService.registerChanged(newExecution, unitOfWork);
        unitOfWorkService.registerEvent(new DeleteCourseExecutionEvent(newExecution.getAggregateId()), unitOfWork);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void enrollStudent(Integer courseExecutionAggregateId, UserDto userDto, UnitOfWork unitOfWork) {
        Execution oldExecution = (Execution) unitOfWorkService.aggregateLoadAndRegisterRead(courseExecutionAggregateId,
                unitOfWork);

        CourseExecutionStudent courseExecutionStudent = new CourseExecutionStudent(userDto);
        if (!courseExecutionStudent.isActive()) {
            throw new QuizzesException(QuizzesErrorMessage.INACTIVE_USER, courseExecutionStudent.getUserAggregateId());
        }

        if (oldExecution.hasStudent(courseExecutionStudent.getUserAggregateId())) {
            throw new QuizzesException(COURSE_EXECUTION_STUDENT_ALREADY_ENROLLED,
                    courseExecutionStudent.getUserAggregateId(), courseExecutionAggregateId);
        }

        Execution newExecution = courseExecutionFactory.createCourseExecutionFromExisting(oldExecution);
        newExecution.addStudent(courseExecutionStudent);

        unitOfWorkService.registerChanged(newExecution, unitOfWork);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public Set<CourseExecutionDto> getCourseExecutionsByUserId(Integer userAggregateId, UnitOfWork unitOfWork) {
        return courseExecutionRepository.findAll().stream()
                .map(Execution::getAggregateId)
                .map(aggregateId -> (Execution) unitOfWorkService.aggregateLoad(aggregateId, unitOfWork))
                .filter(ce -> ce.hasStudent(userAggregateId))
                .map(courseExecution -> (Execution) unitOfWorkService.registerRead(courseExecution, unitOfWork))
                .map(ce -> courseExecutionFactory.createCourseExecutionDto(ce))
                .collect(Collectors.toSet());
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void removeStudentFromCourseExecution(Integer courseExecutionAggregateId, Integer userAggregateId,
            UnitOfWork unitOfWork) {
        Execution oldExecution = (Execution) unitOfWorkService.aggregateLoadAndRegisterRead(courseExecutionAggregateId,
                unitOfWork);
        Execution newExecution = courseExecutionFactory.createCourseExecutionFromExisting(oldExecution);
        newExecution.removeStudent(userAggregateId);
        // CREATOR_COURSE_EXECUTION / PARTICIPANT_COURSE_EXECUTION, now intra (merge
        // execution <- tournament): the dissolved Disenroll consumption becomes synchronous
        disenrollStudentFromTournaments(newExecution, userAggregateId);
        unitOfWorkService.registerChanged(newExecution, unitOfWork);
        unitOfWorkService.registerEvent(
                new DisenrollStudentFromCourseExecutionEvent(courseExecutionAggregateId, userAggregateId), unitOfWork);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public UserDto getStudentByExecutionIdAndUserId(Integer executionAggregateId, Integer userAggregateId,
            UnitOfWork unitOfWork) {
        Execution execution = (Execution) unitOfWorkService.aggregateLoadAndRegisterRead(executionAggregateId,
                unitOfWork);
        if (!execution.hasStudent(userAggregateId)) {
            throw new QuizzesException(COURSE_EXECUTION_STUDENT_NOT_FOUND, userAggregateId, executionAggregateId);
        }
        return execution.findStudent(userAggregateId).buildDto();
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void anonymizeStudent(Integer executionAggregateId, Integer userAggregateId, UnitOfWork unitOfWork) {
        Execution oldExecution = (Execution) unitOfWorkService.aggregateLoadAndRegisterRead(executionAggregateId,
                unitOfWork);
        if (!oldExecution.hasStudent(userAggregateId)) {
            throw new QuizzesException(COURSE_EXECUTION_STUDENT_NOT_FOUND, userAggregateId, executionAggregateId);
        }
        Execution newExecution = courseExecutionFactory.createCourseExecutionFromExisting(oldExecution);
        newExecution.findStudent(userAggregateId).anonymize();
        // CREATOR_EXISTS / PARTICIPANT_EXISTS, now intra (merge execution <- tournament):
        // the dissolved AnonymizeStudent consumption becomes a synchronous update
        anonymizeStudentInTournaments(newExecution, userAggregateId, "ANONYMOUS", "ANONYMOUS");
        unitOfWorkService.registerChanged(newExecution, unitOfWork);
        unitOfWorkService.registerEvent(
                new AnonymizeStudentEvent(executionAggregateId, "ANONYMOUS", "ANONYMOUS", userAggregateId), unitOfWork);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void updateExecutionStudentName(Integer executionAggregateId, Integer userAggregateId, String name,
            UnitOfWork unitOfWork) {
        Execution oldExecution = (Execution) unitOfWorkService.aggregateLoadAndRegisterRead(executionAggregateId,
                unitOfWork);

        logger.info("Updating student name for user {} in execution {}", userAggregateId, executionAggregateId);

        if (!oldExecution.hasStudent(userAggregateId)) {
            throw new QuizzesException(COURSE_EXECUTION_STUDENT_NOT_FOUND, userAggregateId, executionAggregateId);
        }
        Execution newExecution = courseExecutionFactory.createCourseExecutionFromExisting(oldExecution);
        newExecution.findStudent(userAggregateId).setName(name);
        // CREATOR_EXISTS / PARTICIPANT_EXISTS, now intra (merge execution <- tournament):
        // the dissolved UpdateStudentName consumption becomes a synchronous update
        updateStudentNameInTournaments(newExecution, userAggregateId, name);
        unitOfWorkService.registerChanged(newExecution, unitOfWork);
        unitOfWorkService.registerEvent(new UpdateStudentNameEvent(executionAggregateId, userAggregateId, name),
                unitOfWork);
    }

    /************************************************
     * EVENT PROCESSING
     ************************************************/

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public CourseExecutionDto removeUser(Integer executionAggregateId, Integer userAggregateId, UnitOfWork unitOfWork) {
        logger.info("Removing user by creating DisenrollStudentFromCourseExecutionEvent");
        Execution oldExecution = (Execution) unitOfWorkService.aggregateLoadAndRegisterRead(executionAggregateId,
                unitOfWork);
        Execution newExecution = courseExecutionFactory.createCourseExecutionFromExisting(oldExecution);
        newExecution.findStudent(userAggregateId).setActive(false);
        // CREATOR_EXISTS / PARTICIPANT_EXISTS, now intra (merge execution <- tournament)
        disenrollStudentFromTournaments(newExecution, userAggregateId);
        unitOfWorkService.registerChanged(newExecution, unitOfWork);
        unitOfWorkService.registerEvent(
                new DisenrollStudentFromCourseExecutionEvent(executionAggregateId, userAggregateId), unitOfWork);
        return new CourseExecutionDto(newExecution);
    }


    /*******************************************************************************************************************
     * TOURNAMENT — absorbed from TournamentService (merge execution <- tournament).
     * The wire names keep "tournamentAggregateId"; inside the Execution aggregate the value
     * is the member's tournamentId.
     ******************************************************************************************************************/

    /*
     * Owner lookup for the retained member identity. Read-only; used by functionalities to
     * address locks and forbidden-state checks at the owning root.
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public Integer findExecutionIdByTournamentId(Integer tournamentAggregateId) {
        return courseExecutionCustomRepository.findExecutionIdByTournamentId(tournamentAggregateId)
                .orElseThrow(() -> new SimulatorException(SimulatorErrorMessage.AGGREGATE_NOT_FOUND,
                        tournamentAggregateId));
    }

    private Execution loadExecutionOwningTournament(Integer tournamentAggregateId, UnitOfWork unitOfWork) {
        Integer executionAggregateId = findExecutionIdByTournamentId(tournamentAggregateId);
        return (Execution) unitOfWorkService.aggregateLoadAndRegisterRead(executionAggregateId, unitOfWork);
    }

    /*
     * The dissolved Tournament root rejected mutations of INACTIVE versions through
     * registerChanged; the member keeps that behaviour explicitly.
     */
    private void requireModifiableTournament(Tournament tournament) {
        if (tournament.getState() == Aggregate.AggregateState.INACTIVE) {
            throw new SimulatorException(SimulatorErrorMessage.CANNOT_MODIFY_INACTIVE_AGGREGATE,
                    tournament.getTournamentId());
        }
    }

    // intended for requests from external functionalities
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public TournamentDto getTournamentById(Integer aggregateId, UnitOfWork unitOfWork) {
        Execution execution = loadExecutionOwningTournament(aggregateId, unitOfWork);
        return new TournamentDto(execution.getTournament(aggregateId));
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public TournamentDto createTournament(TournamentDto tournamentDto, UserDto creatorDto,
                                          CourseExecutionDto courseExecutionDto, Set<TopicDto> topicDtos,
                                          QuizDto quizDto, UnitOfWork unitOfWork) {
        Execution oldExecution = (Execution) unitOfWorkService
                .aggregateLoadAndRegisterRead(courseExecutionDto.getAggregateId(), unitOfWork);
        Execution newExecution = courseExecutionFactory.createCourseExecutionFromExisting(oldExecution);

        Tournament tournament = new Tournament(aggregateIdGeneratorService.getNewAggregateId(), tournamentDto,
                creatorDto, topicDtos, quizDto);
        newExecution.addTournament(tournament);

        unitOfWorkService.registerChanged(newExecution, unitOfWork);
        return new TournamentDto(tournament);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void addParticipant(Integer tournamentAggregateId, UserDto userDto, UnitOfWork unitOfWork) {
        TournamentParticipant tournamentParticipant = new TournamentParticipant(userDto);
        Execution oldExecution = loadExecutionOwningTournament(tournamentAggregateId, unitOfWork);
        Execution newExecution = courseExecutionFactory.createCourseExecutionFromExisting(oldExecution);
        // CREATOR_EXISTS / PARTICIPANT_EXISTS are intra now: the participant copies the root's
        // own student record, so a dto read before a concurrent name change cannot go stale
        CourseExecutionStudent student = newExecution.findStudent(tournamentParticipant.getParticipantAggregateId());
        if (student != null) {
            tournamentParticipant.setParticipantName(student.getName());
            tournamentParticipant.setParticipantUsername(student.getUsername());
            tournamentParticipant.setParticipantVersion(student.getUserVersion());
        }
        if (tournamentParticipant.getParticipantName().equals("ANONYMOUS") || tournamentParticipant.getParticipantUsername().equals("ANONYMOUS")) {
            throw new QuizzesException(QuizzesErrorMessage.USER_IS_ANONYMOUS, tournamentParticipant.getParticipantAggregateId());
        }
        Tournament tournament = newExecution.getTournament(tournamentAggregateId);
        requireModifiableTournament(tournament);

        logger.info("Adding participant {} to tournament {}", tournamentParticipant.getParticipantName(), tournamentAggregateId);

        tournament.addParticipant(tournamentParticipant);

        unitOfWorkService.registerChanged(newExecution, unitOfWork);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public TournamentDto updateTournament(TournamentDto tournamentDto, Set<TopicDto> topicDtos, UnitOfWork unitOfWork) {
        Execution oldExecution = loadExecutionOwningTournament(tournamentDto.getAggregateId(), unitOfWork);
        Execution newExecution = courseExecutionFactory.createCourseExecutionFromExisting(oldExecution);
        Tournament tournament = newExecution.getTournament(tournamentDto.getAggregateId());
        requireModifiableTournament(tournament);

        if (tournamentDto.getStartTime() != null ) {
            tournament.setStartTime(DateHandler.toLocalDateTime(tournamentDto.getStartTime()));
        }

        if (tournamentDto.getEndTime() != null ) {
            tournament.setEndTime(DateHandler.toLocalDateTime(tournamentDto.getEndTime()));
        }

        if (tournamentDto.getNumberOfQuestions() != null ) {
            tournament.setNumberOfQuestions(tournamentDto.getNumberOfQuestions());
        }

        if (topicDtos != null && !topicDtos.isEmpty() ) {
            Set<TournamentTopic> tournamentTopics = topicDtos.stream()
                    .map(TournamentTopic::new)
                    .collect(Collectors.toSet());
            tournament.setTournamentTopics(tournamentTopics);
        }

        unitOfWorkService.registerChanged(newExecution, unitOfWork);

        return new TournamentDto(tournament);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public List<TournamentDto> getTournamentsByCourseExecutionId(Integer executionAggregateId, UnitOfWork unitOfWork) {
        Execution execution = (Execution) unitOfWorkService.aggregateLoadAndRegisterRead(executionAggregateId,
                unitOfWork);
        return execution.getTournaments().stream()
                .filter(t -> t.getState() == Aggregate.AggregateState.ACTIVE)
                .map(TournamentDto::new)
                .collect(Collectors.toList());
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public List<TournamentDto> getOpenedTournamentsForCourseExecution(Integer executionAggregateId, UnitOfWork unitOfWork) {
        LocalDateTime now = DateHandler.now();
        Execution execution = (Execution) unitOfWorkService.aggregateLoadAndRegisterRead(executionAggregateId,
                unitOfWork);
        return execution.getTournaments().stream()
                .filter(t -> t.getState() == Aggregate.AggregateState.ACTIVE)
                .filter(t -> now.isBefore(t.getEndTime()))
                .filter(t -> !t.isCancelled())
                .map(TournamentDto::new)
                .collect(Collectors.toList());
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public List<TournamentDto> getClosedTournamentsForCourseExecution(Integer executionAggregateId, UnitOfWork unitOfWork) {
        LocalDateTime now = DateHandler.now();
        Execution execution = (Execution) unitOfWorkService.aggregateLoadAndRegisterRead(executionAggregateId,
                unitOfWork);
        return execution.getTournaments().stream()
                .filter(t -> t.getState() == Aggregate.AggregateState.ACTIVE)
                .filter(t -> now.isAfter(t.getEndTime()))
                .filter(t -> !t.isCancelled())
                .map(TournamentDto::new)
                .collect(Collectors.toList());
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void leaveTournament(Integer tournamentAggregateId, Integer userAggregateId, UnitOfWork unitOfWork) {
        Execution oldExecution = loadExecutionOwningTournament(tournamentAggregateId, unitOfWork);
        Execution newExecution = courseExecutionFactory.createCourseExecutionFromExisting(oldExecution);
        Tournament tournament = newExecution.getTournament(tournamentAggregateId);
        requireModifiableTournament(tournament);
        TournamentParticipant participantToRemove = tournament.findParticipant(userAggregateId);
        if (participantToRemove == null) {
            throw new QuizzesException(TOURNAMENT_PARTICIPANT_NOT_FOUND, userAggregateId, tournamentAggregateId);
        }
        tournament.removeParticipant(participantToRemove);
        unitOfWorkService.registerChanged(newExecution, unitOfWork);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void solveQuiz(Integer tournamentAggregateId, Integer userAggregateId, Integer answerAggregateId, UnitOfWork unitOfWork) {
        Execution oldExecution = loadExecutionOwningTournament(tournamentAggregateId, unitOfWork);
        Execution newExecution = courseExecutionFactory.createCourseExecutionFromExisting(oldExecution);
        Tournament tournament = newExecution.getTournament(tournamentAggregateId);
        requireModifiableTournament(tournament);
        TournamentParticipant participant = tournament.findParticipant(userAggregateId);
        if (participant == null) {
            throw new QuizzesException(TOURNAMENT_PARTICIPANT_NOT_FOUND, userAggregateId, tournamentAggregateId);
        }
        participant.answerQuiz();
        unitOfWorkService.registerChanged(newExecution, unitOfWork);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void cancelTournament(Integer tournamentAggregateId, UnitOfWork unitOfWork) {
        Execution oldExecution = loadExecutionOwningTournament(tournamentAggregateId, unitOfWork);
        Execution newExecution = courseExecutionFactory.createCourseExecutionFromExisting(oldExecution);
        Tournament tournament = newExecution.getTournament(tournamentAggregateId);
        requireModifiableTournament(tournament);
        tournament.cancel();
        unitOfWorkService.registerChanged(newExecution, unitOfWork);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void removeTournament(Integer tournamentAggregateId, UnitOfWork unitOfWork) {
        Execution oldExecution = loadExecutionOwningTournament(tournamentAggregateId, unitOfWork);
        Execution newExecution = courseExecutionFactory.createCourseExecutionFromExisting(oldExecution);
        Tournament tournament = newExecution.getTournament(tournamentAggregateId);
        requireModifiableTournament(tournament);
        tournament.remove();
        unitOfWorkService.registerChanged(newExecution, unitOfWork);
    }

    /************************************ TOURNAMENT EVENT PROCESSING *************************************
     * Consumptions of third-party events (course topics, quiz, quiz answers) survive the merge;
     * the subscriber is now the owning Execution root.
     ******************************************************************************************************/

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public TournamentDto updateTournamentTopic(Integer executionAggregateId, Integer topicAggregateId, String topicName, Long eventVersion, UnitOfWork unitOfWork) {
        Execution oldExecution = (Execution) unitOfWorkService.aggregateLoadAndRegisterRead(executionAggregateId,
                unitOfWork);
        Execution newExecution = courseExecutionFactory.createCourseExecutionFromExisting(oldExecution);
        Tournament changedTournament = null;
        for (Tournament tournament : newExecution.getTournaments()) {
            if (tournament.getState() != Aggregate.AggregateState.ACTIVE) {
                continue;
            }
            TournamentTopic topic = tournament.findTopic(topicAggregateId);
            if (topic == null) {
                continue;
            }
            topic.setTopicName(topicName);
            topic.setTopicVersion(eventVersion);
            changedTournament = tournament;
        }
        if (changedTournament == null) {
            throw new QuizzesException(TOURNAMENT_TOPIC_NOT_FOUND, topicAggregateId, executionAggregateId);
        }
        unitOfWorkService.registerChanged(newExecution, unitOfWork);
        return new TournamentDto(changedTournament);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public TournamentDto removeTournamentTopic(Integer executionAggregateId, Integer topicAggregateId, Long eventVersion, UnitOfWork unitOfWork) {
        // Intentionally disabled, exactly as TournamentService.removeTopic was before the merge:
        // the topic-removal propagation (and the quiz regeneration it triggered) was commented out.
        return null;
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public TournamentDto updateParticipantAnswer(Integer executionAggregateId, Integer studentAggregateId, Integer quizAnswerAggregateId, Integer questionAggregateId, boolean isCorrect, Long eventVersion, UnitOfWork unitOfWork) {
        Execution oldExecution = (Execution) unitOfWorkService.aggregateLoadAndRegisterRead(executionAggregateId,
                unitOfWork);
        Execution newExecution = courseExecutionFactory.createCourseExecutionFromExisting(oldExecution);
        boolean participantFound = false;
        Tournament changedTournament = null;
        for (Tournament tournament : newExecution.getTournaments()) {
            if (tournament.getState() != Aggregate.AggregateState.ACTIVE) {
                continue;
            }
            TournamentParticipant tournamentParticipant = tournament.findParticipant(studentAggregateId);
            if (tournamentParticipant == null) {
                continue;
            }
            participantFound = true;
            TournamentParticipantQuizAnswer participantAnswer = tournamentParticipant.getParticipantAnswer();
            if (participantAnswer.getQuizAnswerAggregateId() != null
                    && !participantAnswer.getQuizAnswerAggregateId().equals(quizAnswerAggregateId)) {
                continue;
            }
            if (participantAnswer.getQuizAnswerVersion() != null
                    && participantAnswer.getQuizAnswerVersion() >= eventVersion) {
                continue;
            }
            /*
                AFTER_END
                    now > this.endTime => p: this.participant | final p.answer
                IS_CANCELED
                    this.canceled => final this.startTime && final this.endTime && final this.numberOfQuestions && final this.tournamentTopics && final this.participants && p: this.participant | final p.answer
            */
            if ((tournament.getStartTime() != null && DateHandler.now().isAfter(tournament.getStartTime()))
                    || tournament.isCancelled()) {
                throw new QuizzesException(CANNOT_UPDATE_TOURNAMENT, tournament.getTournamentId());
            }
            tournamentParticipant.updateAnswerWithQuestion(quizAnswerAggregateId, quizAnswerAggregateId, isCorrect, eventVersion);
            changedTournament = tournament;
        }
        if (!participantFound) {
            throw new QuizzesException(TOURNAMENT_PARTICIPANT_NOT_FOUND, studentAggregateId, executionAggregateId);
        }
        if (changedTournament == null) {
            return null;
        }
        unitOfWorkService.registerChanged(newExecution, unitOfWork);
        return new TournamentDto(changedTournament);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public TournamentDto invalidateQuiz(Integer executionAggregateId, Integer quizAggregateId, Long aggregateVersion, UnitOfWork unitOfWork) {
        // Intentionally disabled, exactly as TournamentService.invalidateQuiz was before the
        // merge: quiz regeneration on invalidation was commented out.
        return null;
    }

    /************************************ SYNCHRONOUS TOURNAMENT MAINTENANCE ******************************
     * Formerly tournament's consumptions of Execution's own events (AnonymizeStudent,
     * UpdateStudentName, DisenrollStudentFromCourseExecution, DeleteCourseExecution). With one
     * root owning both entity sets they run in the same commit — the merge's consistency payoff.
     ******************************************************************************************************/

    private void anonymizeStudentInTournaments(Execution newExecution, Integer userAggregateId, String name, String username) {
        for (Tournament tournament : newExecution.getTournaments()) {
            if (tournament.getState() != Aggregate.AggregateState.ACTIVE) {
                continue;
            }
            if (tournament.getTournamentCreator().getCreatorAggregateId().equals(userAggregateId)) {
                tournament.getTournamentCreator().setCreatorName(name);
                tournament.getTournamentCreator().setCreatorUsername(username);
                tournament.setState(Aggregate.AggregateState.INACTIVE);
            }
            for (TournamentParticipant tp : tournament.getTournamentParticipants()) {
                if (tp.getParticipantAggregateId().equals(userAggregateId)) {
                    tp.setParticipantName(name);
                    tp.setParticipantUsername(username);
                }
            }
        }
    }

    private void updateStudentNameInTournaments(Execution newExecution, Integer userAggregateId, String name) {
        for (Tournament tournament : newExecution.getTournaments()) {
            if (tournament.getState() != Aggregate.AggregateState.ACTIVE) {
                continue;
            }
            if (tournament.getTournamentCreator().getCreatorAggregateId().equals(userAggregateId)) {
                tournament.getTournamentCreator().setCreatorName(name);
            }
            for (TournamentParticipant tp : tournament.getTournamentParticipants()) {
                if (tp.getParticipantAggregateId().equals(userAggregateId)) {
                    tp.setParticipantName(name);
                }
            }
        }
    }

    private void disenrollStudentFromTournaments(Execution newExecution, Integer userAggregateId) {
        for (Tournament tournament : newExecution.getTournaments()) {
            if (tournament.getState() != Aggregate.AggregateState.ACTIVE) {
                continue;
            }
            if (tournament.getTournamentCreator().getCreatorAggregateId().equals(userAggregateId)) {
                tournament.getTournamentCreator().setCreatorState(Aggregate.AggregateState.INACTIVE);
                tournament.setState(Aggregate.AggregateState.INACTIVE);
            }
            TournamentParticipant tournamentParticipant = tournament.findParticipant(userAggregateId);
            if (tournamentParticipant != null) {
                tournamentParticipant.setState(Aggregate.AggregateState.DELETED);
            }
        }
    }

    private void deactivateTournaments(Execution newExecution) {
        for (Tournament tournament : newExecution.getTournaments()) {
            if (tournament.getState() == Aggregate.AggregateState.ACTIVE) {
                tournament.setState(Aggregate.AggregateState.INACTIVE);
            }
        }
    }
}
