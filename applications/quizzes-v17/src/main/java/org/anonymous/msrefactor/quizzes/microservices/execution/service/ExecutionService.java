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
import org.anonymous.msrefactor.quizzes.events.InvalidateQuizEvent;
import org.anonymous.msrefactor.quizzes.events.DisenrollStudentFromCourseExecutionEvent;
import org.anonymous.msrefactor.quizzes.events.UpdateStudentNameEvent;
import org.anonymous.msrefactor.quizzes.microservices.exception.QuizzesErrorMessage;
import org.anonymous.msrefactor.quizzes.microservices.exception.QuizzesException;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.*;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.QuestionDto;
import org.anonymous.msrefactor.quizzes.microservices.topic.aggregate.TopicDto;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import static org.anonymous.msrefactor.quizzes.microservices.exception.QuizzesErrorMessage.*;
import static org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.QuizType.GENERATED;
import static org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.QuizType.IN_CLASS;

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

        // COURSE_EXECUTION_EXISTS, now intra-aggregate: a removed execution may not keep
        // active quizzes. This replaces the quiz side of DeleteCourseExecutionEvent.
        newExecution.getQuizzes().stream()
                .filter(quiz -> quiz.getState() == Aggregate.AggregateState.ACTIVE)
                .forEach(quiz -> quiz.setState(Aggregate.AggregateState.INACTIVE));

        // COURSE_EXECUTION_EXISTS, tournament side, now intra-aggregate: a removed
        // execution keeps no ACTIVE tournament. This replaces the tournament's
        // DeleteCourseExecutionEvent consumption.
        newExecution.getTournaments().stream()
                .filter(tournament -> tournament.getState() == Aggregate.AggregateState.ACTIVE)
                .forEach(tournament -> tournament.setState(Aggregate.AggregateState.INACTIVE));

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
        // CREATOR_COURSE_EXECUTION / PARTICIPANT_COURSE_EXECUTION, now intra-aggregate:
        // disenrollment reaches the tournaments of this root synchronously, replacing the
        // tournament's DisenrollStudentFromCourseExecutionEvent consumption.
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
        // CREATOR_EXISTS / PARTICIPANT_EXISTS, now intra-aggregate: anonymisation reaches
        // the tournaments of this root synchronously, replacing the tournament's
        // AnonymizeStudentEvent consumption.
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
        // CREATOR_EXISTS / PARTICIPANT_EXISTS, now intra-aggregate: the rename reaches
        // the tournaments of this root synchronously, replacing the tournament's
        // UpdateStudentNameEvent consumption.
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
        // CREATOR_COURSE_EXECUTION / PARTICIPANT_COURSE_EXECUTION, now intra-aggregate —
        // same synchronous propagation as removeStudentFromCourseExecution.
        disenrollStudentFromTournaments(newExecution, userAggregateId);
        unitOfWorkService.registerChanged(newExecution, unitOfWork);
        unitOfWorkService.registerEvent(
                new DisenrollStudentFromCourseExecutionEvent(executionAggregateId, userAggregateId), unitOfWork);
        return new CourseExecutionDto(newExecution);
    }


    /************************************************
     * QUIZ (member entity of the Execution aggregate)
     ************************************************/

    private Execution loadExecutionByQuizId(Integer quizAggregateId, UnitOfWork unitOfWork) {
        Integer executionAggregateId = courseExecutionCustomRepository.findExecutionIdByQuizId(quizAggregateId)
                .orElseThrow(() -> new SimulatorException(SimulatorErrorMessage.AGGREGATE_NOT_FOUND, quizAggregateId));
        return (Execution) unitOfWorkService.aggregateLoadAndRegisterRead(executionAggregateId, unitOfWork);
    }

    private Quiz getQuizOrThrow(Execution execution, Integer quizAggregateId) {
        Quiz quiz = execution.getQuizByQuizId(quizAggregateId);
        if (quiz == null) {
            throw new SimulatorException(SimulatorErrorMessage.AGGREGATE_NOT_FOUND, quizAggregateId);
        }
        return quiz;
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public QuizDto getQuizById(Integer quizAggregateId, UnitOfWork unitOfWork) {
        Execution execution = loadExecutionByQuizId(quizAggregateId, unitOfWork);
        return courseExecutionFactory.createQuizDto(getQuizOrThrow(execution, quizAggregateId));
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public QuizDto startTournamentQuiz(Integer userAggregateId, Integer quizAggregateId, UnitOfWork unitOfWork) {
        /* must add more verifications */
        Execution execution = loadExecutionByQuizId(quizAggregateId, unitOfWork);
        return courseExecutionFactory.createQuizDto(getQuizOrThrow(execution, quizAggregateId));
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public QuizDto generateQuiz(Integer courseExecutionAggregateId, QuizDto quizDto, List<QuestionDto> questionDtos, Integer numberOfQuestions, UnitOfWork unitOfWork) {
        Execution oldExecution = (Execution) unitOfWorkService.aggregateLoadAndRegisterRead(courseExecutionAggregateId, unitOfWork);
        Execution newExecution = courseExecutionFactory.createCourseExecutionFromExisting(oldExecution);

        if (questionDtos.size() < numberOfQuestions) {
            throw new QuizzesException(QuizzesErrorMessage.NOT_ENOUGH_QUESTIONS);
        }

        Set<Integer> questionPositions = new HashSet<>();
        while (questionPositions.size() < numberOfQuestions) {
            questionPositions.add(ThreadLocalRandom.current().nextInt(0, questionDtos.size()));
        }

        Set<QuizQuestion> quizQuestions = questionPositions.stream()
                .map(questionDtos::get)
                .map(QuizQuestion::new)
                .collect(Collectors.toSet());

        Quiz quiz = courseExecutionFactory.createQuiz(aggregateIdGeneratorService.getNewAggregateId(), quizQuestions, quizDto, GENERATED);
        quiz.setTitle("Generated Quiz Title");
        newExecution.addQuiz(quiz);
        unitOfWorkService.registerChanged(newExecution, unitOfWork);
        return courseExecutionFactory.createQuizDto(quiz);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public QuizDto createQuiz(Integer courseExecutionAggregateId, Set<QuestionDto> questions, QuizDto quizDto, UnitOfWork unitOfWork) {
        Execution oldExecution = (Execution) unitOfWorkService.aggregateLoadAndRegisterRead(courseExecutionAggregateId, unitOfWork);
        Execution newExecution = courseExecutionFactory.createCourseExecutionFromExisting(oldExecution);

        Set<QuizQuestion> quizQuestions = questions.stream()
                .map(QuizQuestion::new)
                .collect(Collectors.toSet());

        Quiz quiz = courseExecutionFactory.createQuiz(aggregateIdGeneratorService.getNewAggregateId(), quizQuestions, quizDto, IN_CLASS);
        newExecution.addQuiz(quiz);
        unitOfWorkService.registerChanged(newExecution, unitOfWork);
        return courseExecutionFactory.createQuizDto(quiz);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public QuizDto updateGeneratedQuiz(QuizDto quizDto, Set<Integer> topicsAggregateIds, Integer numberOfQuestions, List<QuestionDto> questionDtos, UnitOfWork unitOfWork) {
        Execution oldExecution = loadExecutionByQuizId(quizDto.getAggregateId(), unitOfWork);
        Execution newExecution = courseExecutionFactory.createCourseExecutionFromExisting(oldExecution);
        Quiz newQuiz = getQuizOrThrow(newExecution, quizDto.getAggregateId());
        newQuiz.update(quizDto);

        if (topicsAggregateIds != null && numberOfQuestions != null) {
            if (questionDtos.size() < numberOfQuestions) {
                throw new QuizzesException(QuizzesErrorMessage.NOT_ENOUGH_QUESTIONS);
            }

            Set<QuizQuestion> quizQuestions = questionDtos.stream()
                    .map(QuizQuestion::new)
                    .collect(Collectors.toSet());

            newQuiz.setQuizQuestions(quizQuestions);
        }

        newQuiz.setTitle("Generated Quiz Title");
        unitOfWorkService.registerChanged(newExecution, unitOfWork);
        return courseExecutionFactory.createQuizDto(newQuiz);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public QuizDto updateQuiz(QuizDto quizDto, Set<QuizQuestion> quizQuestions, UnitOfWork unitOfWork) {
        Execution oldExecution = loadExecutionByQuizId(quizDto.getAggregateId(), unitOfWork);
        Execution newExecution = courseExecutionFactory.createCourseExecutionFromExisting(oldExecution);
        Quiz newQuiz = getQuizOrThrow(newExecution, quizDto.getAggregateId());

        if (quizDto.getTitle() != null) {
            newQuiz.setTitle(quizDto.getTitle());
            unitOfWorkService.registerChanged(newExecution, unitOfWork);
        }

        if (quizDto.getAvailableDate() != null) {
            newQuiz.setAvailableDate(LocalDateTime.parse(quizDto.getAvailableDate()));
        }

        if (quizDto.getConclusionDate() != null) {
            newQuiz.setConclusionDate(LocalDateTime.parse(quizDto.getConclusionDate()));
        }

        if (quizDto.getResultsDate() != null) {
            newQuiz.setResultsDate(LocalDateTime.parse(quizDto.getResultsDate()));
        }

        if (quizQuestions != null && quizQuestions.size() > 0) {
            newQuiz.setQuizQuestions(quizQuestions);
        }

        return courseExecutionFactory.createQuizDto(newQuiz);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public List<QuizDto> getAvailableQuizzes(Integer courseExecutionAggregateId, UnitOfWork unitOfWork) {
        LocalDateTime now = DateHandler.now();
        Execution execution = (Execution) unitOfWorkService.aggregateLoad(courseExecutionAggregateId, unitOfWork);
        execution = (Execution) unitOfWorkService.registerRead(execution, unitOfWork);
        return execution.getQuizzes().stream()
                .filter(quiz -> quiz.getState() != Aggregate.AggregateState.DELETED)
                .filter(quiz -> quiz.getAvailableDate().isAfter(now) && quiz.getConclusionDate().isBefore(now) && quiz.getQuizType() != GENERATED)
                .map(QuizDto::new)
                .collect(Collectors.toList());
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void removeQuiz(Integer quizAggregateId, UnitOfWork unitOfWork) {
        Execution oldExecution = loadExecutionByQuizId(quizAggregateId, unitOfWork);
        Execution newExecution = courseExecutionFactory.createCourseExecutionFromExisting(oldExecution);
        Quiz newQuiz = getQuizOrThrow(newExecution, quizAggregateId);
        newQuiz.setState(Aggregate.AggregateState.DELETED);
        unitOfWorkService.registerChanged(newExecution, unitOfWork);
    }

    /*
     * QUESTION_EXISTS event processing: the subscriber is now the Execution root, so the
     * update is applied to every quiz of this execution that holds the question.
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void updateQuestionInQuizzes(Integer executionAggregateId, Integer questionAggregateId, String title, String content, Long aggregateVersion, UnitOfWork unitOfWork) {
        Execution oldExecution = (Execution) unitOfWorkService.aggregateLoadAndRegisterRead(executionAggregateId, unitOfWork);
        Execution newExecution = courseExecutionFactory.createCourseExecutionFromExisting(oldExecution);

        for (Quiz quiz : newExecution.getQuizzes()) {
            QuizQuestion quizQuestion = quiz.findQuestion(questionAggregateId);
            if (quizQuestion != null) {
                quizQuestion.setTitle(title);
                quizQuestion.setContent(content);
                quizQuestion.setQuestionVersion(aggregateVersion);
            }
        }
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void removeQuizQuestion(Integer executionAggregateId, Integer questionAggregateId, UnitOfWork unitOfWork) {
        Execution oldExecution = (Execution) unitOfWorkService.aggregateLoadAndRegisterRead(executionAggregateId, unitOfWork);
        Execution newExecution = courseExecutionFactory.createCourseExecutionFromExisting(oldExecution);

        for (Quiz quiz : newExecution.getQuizzes()) {
            QuizQuestion quizQuestion = quiz.findQuestion(questionAggregateId);
            if (quizQuestion != null) {
                quiz.setState(Aggregate.AggregateState.INACTIVE);
                quizQuestion.setState(Aggregate.AggregateState.DELETED);
                // QUIZ_EXISTS, tournament side, is intra-aggregate now: the dissolved
                // Tournament aggregate's InvalidateQuizEvent consumption was a no-op
                // (TournamentService.invalidateQuiz was commented out), so no tournament
                // mutation is applied here on purpose. The event is still published for
                // the answer aggregate.
                unitOfWorkService.registerEvent(new InvalidateQuizEvent(quiz.getQuizId()), unitOfWork);
            }
        }
    }

    /************************************************
     * TOURNAMENT (member entity of the Execution aggregate)
     ************************************************/

    private Execution loadExecutionByTournamentId(Integer tournamentAggregateId, UnitOfWork unitOfWork) {
        Integer executionAggregateId = courseExecutionCustomRepository
                .findExecutionIdByTournamentId(tournamentAggregateId)
                .orElseThrow(() -> new SimulatorException(SimulatorErrorMessage.AGGREGATE_NOT_FOUND,
                        tournamentAggregateId));
        return (Execution) unitOfWorkService.aggregateLoadAndRegisterRead(executionAggregateId, unitOfWork);
    }

    private Tournament getTournamentOrThrow(Execution execution, Integer tournamentAggregateId) {
        Tournament tournament = execution.getTournamentByTournamentId(tournamentAggregateId);
        if (tournament == null) {
            throw new SimulatorException(SimulatorErrorMessage.AGGREGATE_NOT_FOUND, tournamentAggregateId);
        }
        return tournament;
    }

    /*
     * The old root lookup refused to mutate an INACTIVE tournament
     * (registerChanged's CANNOT_MODIFY_INACTIVE_AGGREGATE); as a member that check
     * must be made explicitly.
     */
    private void verifyTournamentIsModifiable(Tournament tournament) {
        if (tournament.getState() == Aggregate.AggregateState.INACTIVE) {
            throw new SimulatorException(SimulatorErrorMessage.CANNOT_MODIFY_INACTIVE_AGGREGATE,
                    tournament.getTournamentId());
        }
    }

    /* resolves the owning root of a tournament member — used by workflows to lock M */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public Integer getExecutionIdOfTournament(Integer tournamentAggregateId) {
        return courseExecutionCustomRepository.findExecutionIdByTournamentId(tournamentAggregateId)
                .orElseThrow(() -> new SimulatorException(SimulatorErrorMessage.AGGREGATE_NOT_FOUND,
                        tournamentAggregateId));
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public TournamentDto getTournamentById(Integer tournamentAggregateId, UnitOfWork unitOfWork) {
        Execution execution = loadExecutionByTournamentId(tournamentAggregateId, unitOfWork);
        return courseExecutionFactory.createTournamentDto(getTournamentOrThrow(execution, tournamentAggregateId));
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public TournamentDto createTournament(Integer executionAggregateId, TournamentDto tournamentDto, UserDto creatorDto,
            Set<TopicDto> topicDtos, QuizDto quizDto, UnitOfWork unitOfWork) {
        Execution oldExecution = (Execution) unitOfWorkService.aggregateLoadAndRegisterRead(executionAggregateId,
                unitOfWork);
        Execution newExecution = courseExecutionFactory.createCourseExecutionFromExisting(oldExecution);

        Tournament tournament = courseExecutionFactory.createTournament(
                aggregateIdGeneratorService.getNewAggregateId(), tournamentDto, creatorDto, topicDtos, quizDto);
        newExecution.addTournament(tournament);

        unitOfWorkService.registerChanged(newExecution, unitOfWork);
        return courseExecutionFactory.createTournamentDto(tournament);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void addParticipant(Integer tournamentAggregateId, UserDto userDto, UnitOfWork unitOfWork) {
        TournamentParticipant tournamentParticipant = new TournamentParticipant(userDto);
        if (tournamentParticipant.getParticipantName().equals("ANONYMOUS")
                || tournamentParticipant.getParticipantUsername().equals("ANONYMOUS")) {
            throw new QuizzesException(QuizzesErrorMessage.USER_IS_ANONYMOUS,
                    tournamentParticipant.getParticipantAggregateId());
        }
        Execution oldExecution = loadExecutionByTournamentId(tournamentAggregateId, unitOfWork);
        Execution newExecution = courseExecutionFactory.createCourseExecutionFromExisting(oldExecution);
        Tournament newTournament = getTournamentOrThrow(newExecution, tournamentAggregateId);
        verifyTournamentIsModifiable(newTournament);

        logger.info("Adding participant {} to tournament {}", tournamentParticipant.getParticipantName(),
                tournamentAggregateId);

        newTournament.addParticipant(tournamentParticipant);

        unitOfWorkService.registerChanged(newExecution, unitOfWork);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public TournamentDto updateTournament(TournamentDto tournamentDto, Set<TopicDto> topicDtos, UnitOfWork unitOfWork) {
        Execution oldExecution = loadExecutionByTournamentId(tournamentDto.getAggregateId(), unitOfWork);
        Execution newExecution = courseExecutionFactory.createCourseExecutionFromExisting(oldExecution);
        Tournament newTournament = getTournamentOrThrow(newExecution, tournamentDto.getAggregateId());
        verifyTournamentIsModifiable(newTournament);

        if (tournamentDto.getStartTime() != null) {
            newTournament.setStartTime(DateHandler.toLocalDateTime(tournamentDto.getStartTime()));
        }

        if (tournamentDto.getEndTime() != null) {
            newTournament.setEndTime(DateHandler.toLocalDateTime(tournamentDto.getEndTime()));
        }

        if (tournamentDto.getNumberOfQuestions() != null) {
            newTournament.setNumberOfQuestions(tournamentDto.getNumberOfQuestions());
        }

        if (topicDtos != null && !topicDtos.isEmpty()) {
            Set<TournamentTopic> tournamentTopics = topicDtos.stream()
                    .map(TournamentTopic::new)
                    .collect(Collectors.toSet());
            newTournament.setTournamentTopics(tournamentTopics);
        }

        unitOfWorkService.registerChanged(newExecution, unitOfWork);

        return courseExecutionFactory.createTournamentDto(newTournament);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public List<TournamentDto> getTournamentsByCourseExecutionId(Integer executionAggregateId, UnitOfWork unitOfWork) {
        Execution execution = (Execution) unitOfWorkService.aggregateLoadAndRegisterRead(executionAggregateId,
                unitOfWork);
        return execution.getTournaments().stream()
                .filter(tournament -> tournament.getState() == Aggregate.AggregateState.ACTIVE)
                .map(TournamentDto::new)
                .collect(Collectors.toList());
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public List<TournamentDto> getOpenedTournamentsForCourseExecution(Integer executionAggregateId,
            UnitOfWork unitOfWork) {
        LocalDateTime now = DateHandler.now();
        Execution execution = (Execution) unitOfWorkService.aggregateLoad(executionAggregateId, unitOfWork);
        execution = (Execution) unitOfWorkService.registerRead(execution, unitOfWork);
        return execution.getTournaments().stream()
                .filter(tournament -> tournament.getState() == Aggregate.AggregateState.ACTIVE)
                .filter(t -> now.isBefore(t.getEndTime()))
                .filter(t -> !t.isCancelled())
                .map(TournamentDto::new)
                .collect(Collectors.toList());
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public List<TournamentDto> getClosedTournamentsForCourseExecution(Integer executionAggregateId,
            UnitOfWork unitOfWork) {
        LocalDateTime now = DateHandler.now();
        Execution execution = (Execution) unitOfWorkService.aggregateLoad(executionAggregateId, unitOfWork);
        execution = (Execution) unitOfWorkService.registerRead(execution, unitOfWork);
        return execution.getTournaments().stream()
                .filter(tournament -> tournament.getState() == Aggregate.AggregateState.ACTIVE)
                .filter(t -> now.isAfter(t.getEndTime()))
                .filter(t -> !t.isCancelled())
                .map(TournamentDto::new)
                .collect(Collectors.toList());
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void leaveTournament(Integer tournamentAggregateId, Integer userAggregateId, UnitOfWork unitOfWork) {
        Execution oldExecution = loadExecutionByTournamentId(tournamentAggregateId, unitOfWork);
        Execution newExecution = courseExecutionFactory.createCourseExecutionFromExisting(oldExecution);
        Tournament newTournament = getTournamentOrThrow(newExecution, tournamentAggregateId);
        verifyTournamentIsModifiable(newTournament);
        TournamentParticipant participantToRemove = newTournament.findParticipant(userAggregateId);
        if (participantToRemove == null) {
            throw new QuizzesException(TOURNAMENT_PARTICIPANT_NOT_FOUND, userAggregateId, tournamentAggregateId);
        }
        newTournament.removeParticipant(participantToRemove);
        unitOfWorkService.registerChanged(newExecution, unitOfWork);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void solveQuiz(Integer tournamentAggregateId, Integer userAggregateId, Integer answerAggregateId,
            UnitOfWork unitOfWork) {
        Execution oldExecution = loadExecutionByTournamentId(tournamentAggregateId, unitOfWork);
        Execution newExecution = courseExecutionFactory.createCourseExecutionFromExisting(oldExecution);
        Tournament newTournament = getTournamentOrThrow(newExecution, tournamentAggregateId);
        verifyTournamentIsModifiable(newTournament);
        TournamentParticipant participant = newTournament.findParticipant(userAggregateId);
        if (participant == null) {
            throw new QuizzesException(TOURNAMENT_PARTICIPANT_NOT_FOUND, userAggregateId, tournamentAggregateId);
        }
        participant.answerQuiz();
        unitOfWorkService.registerChanged(newExecution, unitOfWork);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void cancelTournament(Integer tournamentAggregateId, UnitOfWork unitOfWork) {
        Execution oldExecution = loadExecutionByTournamentId(tournamentAggregateId, unitOfWork);
        Execution newExecution = courseExecutionFactory.createCourseExecutionFromExisting(oldExecution);
        Tournament newTournament = getTournamentOrThrow(newExecution, tournamentAggregateId);
        verifyTournamentIsModifiable(newTournament);
        newTournament.cancel();
        unitOfWorkService.registerChanged(newExecution, unitOfWork);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void removeTournament(Integer tournamentAggregateId, UnitOfWork unitOfWork) {
        Execution oldExecution = loadExecutionByTournamentId(tournamentAggregateId, unitOfWork);
        Execution newExecution = courseExecutionFactory.createCourseExecutionFromExisting(oldExecution);
        Tournament newTournament = getTournamentOrThrow(newExecution, tournamentAggregateId);
        verifyTournamentIsModifiable(newTournament);
        // deletion = member state flag; getTournamentByTournamentId skips flagged members
        // and the DELETE invariant (no participants) is verified on the root commit
        newTournament.setState(Aggregate.AggregateState.DELETED);
        unitOfWorkService.registerChanged(newExecution, unitOfWork);
    }

    /*
     * Synchronous replacements for the dissolved Tournament aggregate's consumption of
     * this root's own events (CREATOR_EXISTS, PARTICIPANT_EXISTS,
     * CREATOR_COURSE_EXECUTION, PARTICIPANT_COURSE_EXECUTION). Only ACTIVE tournaments
     * are touched — the old subscriptions were registered only while the tournament
     * aggregate was ACTIVE.
     */

    private void anonymizeStudentInTournaments(Execution newExecution, Integer userAggregateId, String name,
            String username) {
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

    /*
     * TOPIC_EXISTS event processing: the subscriber is now the Execution root, so the
     * update is applied to every tournament of this execution that holds the topic.
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void updateTopicInTournaments(Integer executionAggregateId, Integer topicAggregateId, String topicName,
            Long eventVersion, UnitOfWork unitOfWork) {
        Execution oldExecution = (Execution) unitOfWorkService.aggregateLoadAndRegisterRead(executionAggregateId,
                unitOfWork);
        Execution newExecution = courseExecutionFactory.createCourseExecutionFromExisting(oldExecution);

        boolean found = false;
        for (Tournament tournament : newExecution.getTournaments()) {
            if (tournament.getState() != Aggregate.AggregateState.ACTIVE) {
                continue;
            }
            TournamentTopic topic = tournament.findTopic(topicAggregateId);
            if (topic != null) {
                topic.setTopicName(topicName);
                topic.setTopicVersion(eventVersion);
                found = true;
            }
        }
        if (!found) {
            throw new QuizzesException(TOURNAMENT_TOPIC_NOT_FOUND, topicAggregateId, executionAggregateId);
        }
        unitOfWorkService.registerChanged(newExecution, unitOfWork);
    }

    /*
     * TOPIC_EXISTS on DeleteTopicEvent: the dissolved aggregate's removeTopic service
     * body was commented out (a no-op returning null); preserved as the same no-op.
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public TournamentDto removeTopicInTournaments(Integer executionAggregateId, Integer topicAggregateId,
            Long eventVersion, UnitOfWork unitOfWork) {
        return null;
    }

    /*
     * QUIZ_ANSWER_EXISTS event processing: applied to every tournament of this execution
     * whose participant matches the answering student and the publishing quiz answer —
     * the same pair the old per-tournament subscription matched on.
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void updateParticipantAnswer(Integer executionAggregateId, Integer studentAggregateId,
            Integer quizAnswerAggregateId, Integer questionAggregateId, boolean isCorrect, Long eventVersion,
            UnitOfWork unitOfWork) {
        Execution oldExecution = (Execution) unitOfWorkService.aggregateLoadAndRegisterRead(executionAggregateId,
                unitOfWork);
        Execution newExecution = courseExecutionFactory.createCourseExecutionFromExisting(oldExecution);

        boolean changed = false;
        for (Tournament tournament : newExecution.getTournaments()) {
            if (tournament.getState() != Aggregate.AggregateState.ACTIVE) {
                continue;
            }
            TournamentParticipant participant = tournament.findParticipant(studentAggregateId);
            if (participant == null
                    || participant.getParticipantAnswer().getQuizAnswerAggregateId() == null
                    || !participant.getParticipantAnswer().getQuizAnswerAggregateId().equals(quizAnswerAggregateId)) {
                continue;
            }
            if (participant.getParticipantAnswer().getQuizAnswerVersion() >= eventVersion) {
                continue;
            }
            /*
             * AFTER_END / IS_CANCELED — the answer is final once the tournament started
             * or was cancelled.
             */
            if ((tournament.getStartTime() != null && DateHandler.now().isAfter(tournament.getStartTime()))
                    || tournament.isCancelled()) {
                throw new QuizzesException(CANNOT_UPDATE_TOURNAMENT, tournament.getTournamentId());
            }
            participant.updateAnswerWithQuestion(quizAnswerAggregateId, quizAnswerAggregateId, isCorrect, eventVersion);
            changed = true;
        }
        if (changed) {
            unitOfWorkService.registerChanged(newExecution, unitOfWork);
        }
    }

}
