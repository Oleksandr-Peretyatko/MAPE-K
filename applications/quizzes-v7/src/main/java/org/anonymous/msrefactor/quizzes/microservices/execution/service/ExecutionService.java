package org.anonymous.msrefactor.quizzes.microservices.execution.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.anonymous.msrefactor.ms.aggregate.Aggregate;
import org.anonymous.msrefactor.ms.aggregate.AggregateIdGeneratorService;
import org.anonymous.msrefactor.ms.exception.SimulatorException;
import org.anonymous.msrefactor.ms.aggregate.ServiceGuard;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWorkService;
import org.anonymous.msrefactor.ms.utils.DateHandler;
import org.anonymous.msrefactor.quizzes.events.AnonymizeStudentEvent;
import org.anonymous.msrefactor.quizzes.events.DeleteCourseExecutionEvent;
import org.anonymous.msrefactor.quizzes.events.DisenrollStudentFromCourseExecutionEvent;
import org.anonymous.msrefactor.quizzes.events.InvalidateQuizEvent;
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
        // COURSE_EXECUTION_EXISTS used to be an inter-invariant maintained by the Quiz aggregate;
        // now intra-aggregate: invalidating this execution invalidates its quizzes in one unit of work.
        for (Quiz quiz : newExecution.getQuizzes()) {
            quiz.setState(Aggregate.AggregateState.INACTIVE);
        }
        // Same conversion for the former Tournament aggregate (merge execution <- tournament): the
        // DeleteCourseExecution event consumption that set a tournament INACTIVE is now synchronous.
        for (Tournament tournament : newExecution.getTournaments()) {
            tournament.setState(Aggregate.AggregateState.INACTIVE);
        }

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
        unitOfWorkService.registerChanged(newExecution, unitOfWork);
        unitOfWorkService.registerEvent(
                new DisenrollStudentFromCourseExecutionEvent(executionAggregateId, userAggregateId), unitOfWork);
        return new CourseExecutionDto(newExecution);
    }

    /************************************************
     * QUIZ (merged from the former Quiz aggregate)
     ************************************************/

    // Load the quiz member for read, mirroring the old Quiz aggregate load: only an ACTIVE quiz is
    // visible; anything else (no execution, deleted execution, non-ACTIVE member) is AGGREGATE_NOT_FOUND.
    private Quiz loadActiveQuizForRead(Integer quizAggregateId, UnitOfWork unitOfWork) {
        Integer executionId = courseExecutionCustomRepository.findExecutionIdByQuizId(quizAggregateId)
                .orElseThrow(() -> new SimulatorException(org.anonymous.msrefactor.ms.exception.SimulatorErrorMessage.AGGREGATE_NOT_FOUND, quizAggregateId));
        Execution execution = (Execution) unitOfWorkService.aggregateLoadAndRegisterRead(executionId, unitOfWork);
        Quiz quiz = execution.findQuiz(quizAggregateId);
        if (quiz == null || quiz.getState() != Aggregate.AggregateState.ACTIVE) {
            throw new SimulatorException(org.anonymous.msrefactor.ms.exception.SimulatorErrorMessage.AGGREGATE_NOT_FOUND, quizAggregateId);
        }
        return quiz;
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public QuizDto getQuizById(Integer quizAggregateId, UnitOfWork unitOfWork) {
        return new QuizDto(loadActiveQuizForRead(quizAggregateId, unitOfWork));
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public QuizDto startTournamentQuiz(Integer userAggregateId, Integer quizAggregateId, UnitOfWork unitOfWork) {
        /* must add more verifications */
        return new QuizDto(loadActiveQuizForRead(quizAggregateId, unitOfWork));
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public QuizDto generateQuiz(CourseExecutionDto courseExecutionDto, QuizDto quizDto, List<QuestionDto> questionDtos, Integer numberOfQuestions, UnitOfWork unitOfWork) {
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

        Execution oldExecution = (Execution) unitOfWorkService.aggregateLoadAndRegisterRead(courseExecutionDto.getAggregateId(), unitOfWork);
        Execution newExecution = courseExecutionFactory.createCourseExecutionFromExisting(oldExecution);
        Integer quizId = aggregateIdGeneratorService.getNewAggregateId();
        Quiz quiz = new Quiz(quizId, quizQuestions, quizDto, GENERATED);
        quiz.setTitle("Generated Quiz Title");
        newExecution.addQuiz(quiz);
        unitOfWorkService.registerChanged(newExecution, unitOfWork);
        return new QuizDto(quiz);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public QuizDto createQuiz(Integer courseExecutionId, Set<QuestionDto> questions, QuizDto quizDto, UnitOfWork unitOfWork) {
        Set<QuizQuestion> quizQuestions = questions.stream()
                .map(QuizQuestion::new)
                .collect(Collectors.toSet());

        Execution oldExecution = (Execution) unitOfWorkService.aggregateLoadAndRegisterRead(courseExecutionId, unitOfWork);
        Execution newExecution = courseExecutionFactory.createCourseExecutionFromExisting(oldExecution);
        Integer quizId = aggregateIdGeneratorService.getNewAggregateId();
        Quiz quiz = new Quiz(quizId, quizQuestions, quizDto, IN_CLASS);
        newExecution.addQuiz(quiz);
        unitOfWorkService.registerChanged(newExecution, unitOfWork);
        return new QuizDto(quiz);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public QuizDto updateGeneratedQuiz(QuizDto quizDto, Set<Integer> topicsAggregateIds, Integer numberOfQuestions, List<QuestionDto> questionDtos, UnitOfWork unitOfWork) {
        Integer executionId = courseExecutionCustomRepository.findExecutionIdByQuizId(quizDto.getAggregateId())
                .orElseThrow(() -> new SimulatorException(org.anonymous.msrefactor.ms.exception.SimulatorErrorMessage.AGGREGATE_NOT_FOUND, quizDto.getAggregateId()));
        Execution oldExecution = (Execution) unitOfWorkService.aggregateLoadAndRegisterRead(executionId, unitOfWork);
        Execution newExecution = courseExecutionFactory.createCourseExecutionFromExisting(oldExecution);
        Quiz quiz = newExecution.getQuiz(quizDto.getAggregateId());
        quiz.update(quizDto);

        if (topicsAggregateIds != null && numberOfQuestions != null) {
            if (questionDtos.size() < numberOfQuestions) {
                throw new QuizzesException(QuizzesErrorMessage.NOT_ENOUGH_QUESTIONS);
            }

            Set<QuizQuestion> quizQuestions = questionDtos.stream()
                    .map(QuizQuestion::new)
                    .collect(Collectors.toSet());

            quiz.setQuizQuestions(quizQuestions);
        }

        quiz.setTitle("Generated Quiz Title");
        unitOfWorkService.registerChanged(newExecution, unitOfWork);
        return new QuizDto(quiz);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public QuizDto updateQuiz(QuizDto quizDto, Set<QuizQuestion> quizQuestions, UnitOfWork unitOfWork) {
        Integer executionId = courseExecutionCustomRepository.findExecutionIdByQuizId(quizDto.getAggregateId())
                .orElseThrow(() -> new SimulatorException(org.anonymous.msrefactor.ms.exception.SimulatorErrorMessage.AGGREGATE_NOT_FOUND, quizDto.getAggregateId()));
        Execution oldExecution = (Execution) unitOfWorkService.aggregateLoadAndRegisterRead(executionId, unitOfWork);
        Execution newExecution = courseExecutionFactory.createCourseExecutionFromExisting(oldExecution);
        Quiz quiz = newExecution.getQuiz(quizDto.getAggregateId());

        if (quizDto.getTitle() != null) {
            quiz.setTitle(quizDto.getTitle());
            unitOfWorkService.registerChanged(newExecution, unitOfWork);
        }

        if (quizDto.getAvailableDate() != null) {
            quiz.setAvailableDate(LocalDateTime.parse(quizDto.getAvailableDate()));
        }

        if (quizDto.getConclusionDate() != null) {
            quiz.setConclusionDate(LocalDateTime.parse(quizDto.getConclusionDate()));
        }

        if (quizDto.getResultsDate() != null) {
            quiz.setResultsDate(LocalDateTime.parse(quizDto.getResultsDate()));
        }

        if (quizQuestions != null && quizQuestions.size() > 0) {
            quiz.setQuizQuestions(quizQuestions);
        }

        return new QuizDto(quiz);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public List<QuizDto> getAvailableQuizzes(Integer courseExecutionAggregateId, UnitOfWork unitOfWork) {
        LocalDateTime now = DateHandler.now();
        Execution execution = (Execution) unitOfWorkService.aggregateLoadAndRegisterRead(courseExecutionAggregateId, unitOfWork);
        return execution.getQuizzes().stream()
                .filter(quiz -> quiz.getState() == Aggregate.AggregateState.ACTIVE)
                .filter(quiz -> quiz.getAvailableDate().isAfter(now) && quiz.getConclusionDate().isBefore(now) && quiz.getQuizType() != GENERATED)
                .map(QuizDto::new)
                .collect(Collectors.toList());
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void removeQuiz(Integer quizAggregateId, UnitOfWork unitOfWork) {
        Integer executionId = courseExecutionCustomRepository.findExecutionIdByQuizId(quizAggregateId)
                .orElseThrow(() -> new SimulatorException(org.anonymous.msrefactor.ms.exception.SimulatorErrorMessage.AGGREGATE_NOT_FOUND, quizAggregateId));
        Execution oldExecution = (Execution) unitOfWorkService.aggregateLoadAndRegisterRead(executionId, unitOfWork);
        Execution newExecution = courseExecutionFactory.createCourseExecutionFromExisting(oldExecution);
        Quiz quiz = newExecution.getQuiz(quizAggregateId);
        quiz.setState(Aggregate.AggregateState.DELETED);
        unitOfWorkService.registerChanged(newExecution, unitOfWork);
    }

    /* Event processing merged from Quiz: QUESTION_EXISTS */

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void updateQuestion(Integer executionAggregateId, Integer questionAggregateId, String title, String content, Long aggregateVersion, UnitOfWork unitOfWork) {
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
                unitOfWorkService.registerEvent(new InvalidateQuizEvent(quiz.getQuizId()), unitOfWork);
            }
        }
    }

    /************************************************
     * TOURNAMENT (merged from the former Tournament aggregate)
     ************************************************/

    // Member-id -> root-id lookup (layer 2.2). Mirrors the former Tournament aggregate load:
    // an unknown tournament id is AGGREGATE_NOT_FOUND.
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public Integer getExecutionIdByTournamentId(Integer tournamentAggregateId) {
        return courseExecutionCustomRepository.findExecutionIdByTournamentId(tournamentAggregateId)
                .orElseThrow(() -> new SimulatorException(org.anonymous.msrefactor.ms.exception.SimulatorErrorMessage.AGGREGATE_NOT_FOUND, tournamentAggregateId));
    }

    // Load the tournament member for read, mirroring the old Tournament aggregate load
    // (findNonDeletedSagaAggregate): visible unless DELETED — an INACTIVE tournament still loads.
    private Tournament loadTournamentForRead(Integer tournamentAggregateId, UnitOfWork unitOfWork) {
        Integer executionId = getExecutionIdByTournamentId(tournamentAggregateId);
        Execution execution = (Execution) unitOfWorkService.aggregateLoadAndRegisterRead(executionId, unitOfWork);
        Tournament tournament = execution.findTournament(tournamentAggregateId);
        if (tournament == null || tournament.getState() == Aggregate.AggregateState.DELETED) {
            throw new SimulatorException(org.anonymous.msrefactor.ms.exception.SimulatorErrorMessage.AGGREGATE_NOT_FOUND, tournamentAggregateId);
        }
        return tournament;
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public TournamentDto getTournamentById(Integer tournamentAggregateId, UnitOfWork unitOfWork) {
        return new TournamentDto(loadTournamentForRead(tournamentAggregateId, unitOfWork));
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public TournamentDto createTournament(TournamentDto tournamentDto, UserDto creatorDto,
            CourseExecutionDto courseExecutionDto, Set<TopicDto> topicDtos, QuizDto quizDto, UnitOfWork unitOfWork) {
        Execution oldExecution = (Execution) unitOfWorkService
                .aggregateLoadAndRegisterRead(courseExecutionDto.getAggregateId(), unitOfWork);
        Execution newExecution = courseExecutionFactory.createCourseExecutionFromExisting(oldExecution);

        Integer tournamentId = aggregateIdGeneratorService.getNewAggregateId();
        Tournament tournament = new Tournament(tournamentId, tournamentDto, creatorDto, topicDtos, quizDto);
        newExecution.addTournament(tournament);

        unitOfWorkService.registerChanged(newExecution, unitOfWork);
        return new TournamentDto(tournament);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public TournamentDto updateTournament(TournamentDto tournamentDto, Set<TopicDto> topicDtos,
            UnitOfWork unitOfWork) {
        Integer executionId = getExecutionIdByTournamentId(tournamentDto.getAggregateId());
        Execution oldExecution = (Execution) unitOfWorkService.aggregateLoadAndRegisterRead(executionId, unitOfWork);
        Execution newExecution = courseExecutionFactory.createCourseExecutionFromExisting(oldExecution);
        Tournament tournament = newExecution.findTournament(tournamentDto.getAggregateId());
        if (tournament == null) {
            throw new SimulatorException(org.anonymous.msrefactor.ms.exception.SimulatorErrorMessage.AGGREGATE_NOT_FOUND, tournamentDto.getAggregateId());
        }

        if (tournamentDto.getStartTime() != null) {
            tournament.setStartTime(DateHandler.toLocalDateTime(tournamentDto.getStartTime()));
        }

        if (tournamentDto.getEndTime() != null) {
            tournament.setEndTime(DateHandler.toLocalDateTime(tournamentDto.getEndTime()));
        }

        if (tournamentDto.getNumberOfQuestions() != null) {
            tournament.setNumberOfQuestions(tournamentDto.getNumberOfQuestions());
        }

        if (topicDtos != null && !topicDtos.isEmpty()) {
            Set<TournamentTopic> tournamentTopics = topicDtos.stream()
                    .map(TournamentTopic::new)
                    .collect(Collectors.toSet());
            tournament.setTournamentTopics(tournamentTopics);
        }

        unitOfWorkService.registerChanged(newExecution, unitOfWork);

        return new TournamentDto(tournament);
    }

    /*
     * The former ForSaga query also hid tournaments whose own sagaState was set; a member has no
     * saga state of its own any more (the lock lives on the owning execution root), so the listing
     * filters on member state only.
     */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public List<TournamentDto> getTournamentsByCourseExecutionId(Integer executionAggregateId,
            UnitOfWork unitOfWork) {
        Execution execution = (Execution) unitOfWorkService.aggregateLoadAndRegisterRead(executionAggregateId,
                unitOfWork);
        return execution.getTournaments().stream()
                .filter(t -> t.getState() == Aggregate.AggregateState.ACTIVE)
                .map(TournamentDto::new)
                .collect(Collectors.toList());
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public List<TournamentDto> getOpenedTournamentsForCourseExecution(Integer executionAggregateId,
            UnitOfWork unitOfWork) {
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
    public List<TournamentDto> getClosedTournamentsForCourseExecution(Integer executionAggregateId,
            UnitOfWork unitOfWork) {
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
    public void cancelTournament(Integer tournamentAggregateId, UnitOfWork unitOfWork) {
        Integer executionId = getExecutionIdByTournamentId(tournamentAggregateId);
        Execution oldExecution = (Execution) unitOfWorkService.aggregateLoadAndRegisterRead(executionId, unitOfWork);
        Execution newExecution = courseExecutionFactory.createCourseExecutionFromExisting(oldExecution);
        Tournament tournament = newExecution.findTournament(tournamentAggregateId);
        tournament.cancel();
        unitOfWorkService.registerChanged(newExecution, unitOfWork);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void removeTournament(Integer tournamentAggregateId, UnitOfWork unitOfWork) {
        Integer executionId = getExecutionIdByTournamentId(tournamentAggregateId);
        Execution oldExecution = (Execution) unitOfWorkService.aggregateLoadAndRegisterRead(executionId, unitOfWork);
        Execution newExecution = courseExecutionFactory.createCourseExecutionFromExisting(oldExecution);
        Tournament tournament = newExecution.findTournament(tournamentAggregateId);
        // the member keeps its row and is flagged: the satellites still hold its identifier
        tournament.setState(Aggregate.AggregateState.DELETED);
        unitOfWorkService.registerChanged(newExecution, unitOfWork);
    }

    /* Event-processing branch of the former Tournament aggregate: the creator scalar. Invoked by
     * command from the tournamentSatellite service's sagas. */

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public TournamentDto anonymizeTournamentUser(Integer tournamentAggregateId, Integer executionAggregateId,
            Integer userAggregateId, String name, String username, Long eventVersion, UnitOfWork unitOfWork) {
        Integer executionId = getExecutionIdByTournamentId(tournamentAggregateId);
        if (!executionId.equals(executionAggregateId)) {
            return null;
        }
        Execution oldExecution = (Execution) unitOfWorkService.aggregateLoadAndRegisterRead(executionId, unitOfWork);
        Tournament oldTournament = oldExecution.findTournament(tournamentAggregateId);

        if (oldTournament.getCreatorAggregateId() != null
                && oldTournament.getCreatorAggregateId().equals(userAggregateId)) {
            Execution newExecution = courseExecutionFactory.createCourseExecutionFromExisting(oldExecution);
            Tournament newTournament = newExecution.findTournament(tournamentAggregateId);
            newTournament.setState(Aggregate.AggregateState.INACTIVE);
            unitOfWorkService.registerChanged(newExecution, unitOfWork);
            return new TournamentDto(newTournament);
        }
        return new TournamentDto(oldTournament);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public TournamentDto removeTournamentUser(Integer tournamentAggregateId, Integer courseExecutionAggregateId,
            Integer userAggregateId, Long eventVersion, UnitOfWork unitOfWork) {
        Integer executionId = getExecutionIdByTournamentId(tournamentAggregateId);
        Execution oldExecution = (Execution) unitOfWorkService.aggregateLoadAndRegisterRead(executionId, unitOfWork);
        Tournament oldTournament = oldExecution.findTournament(tournamentAggregateId);

        if (oldTournament.getCreatorAggregateId() != null
                && oldTournament.getCreatorAggregateId().equals(userAggregateId)) {
            Execution newExecution = courseExecutionFactory.createCourseExecutionFromExisting(oldExecution);
            Tournament newTournament = newExecution.findTournament(tournamentAggregateId);
            newTournament.setState(Aggregate.AggregateState.INACTIVE);
            unitOfWorkService.registerChanged(newExecution, unitOfWork);
            return new TournamentDto(newTournament);
        }
        return new TournamentDto(oldTournament);
    }

    /* Event processing merged from Tournament: TOPIC_EXISTS. The subscriber is now the execution
     * root, so a topic event fans out over all its tournament members holding that topic. */

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public TournamentDto updateTopicInTournaments(Integer executionAggregateId, Integer topicAggregateId,
            String topicName, Long eventVersion, UnitOfWork unitOfWork) {
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
        return null;
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public TournamentDto removeTopicFromTournaments(Integer executionAggregateId, Integer topicAggregateId,
            Long eventVersion, UnitOfWork unitOfWork) {
        // preserved no-op: the former TournamentService.removeTopic did nothing
        return null;
    }

}
