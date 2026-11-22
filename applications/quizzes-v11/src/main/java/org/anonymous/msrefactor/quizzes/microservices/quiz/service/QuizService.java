package org.anonymous.msrefactor.quizzes.microservices.quiz.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.anonymous.msrefactor.ms.aggregate.Aggregate;
import org.anonymous.msrefactor.ms.aggregate.AggregateIdGeneratorService;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWorkService;
import org.anonymous.msrefactor.ms.aggregate.ServiceGuard;
import org.anonymous.msrefactor.ms.exception.SimulatorException;
import org.anonymous.msrefactor.ms.utils.DateHandler;
import org.anonymous.msrefactor.quizzes.events.InvalidateQuizEvent;
import org.anonymous.msrefactor.quizzes.events.QuizAnswerQuestionAnswerEvent;
import org.anonymous.msrefactor.quizzes.microservices.exception.QuizzesErrorMessage;
import org.anonymous.msrefactor.quizzes.microservices.exception.QuizzesException;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.CourseExecutionDto;
import org.anonymous.msrefactor.quizzes.microservices.question.aggregate.QuestionDto;
import org.anonymous.msrefactor.quizzes.microservices.quiz.aggregate.*;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import static org.anonymous.msrefactor.ms.exception.SimulatorErrorMessage.AGGREGATE_NOT_FOUND;
import static org.anonymous.msrefactor.quizzes.microservices.quiz.aggregate.QuizType.GENERATED;
import static org.anonymous.msrefactor.quizzes.microservices.quiz.aggregate.QuizType.IN_CLASS;

@Service
public class QuizService {
    @Autowired
    private AggregateIdGeneratorService aggregateIdGeneratorService;

    private final QuizRepository quizRepository;
    
    private final UnitOfWorkService<UnitOfWork> unitOfWorkService;

    @Autowired
    private QuizFactory quizFactory;
    
    public QuizService(UnitOfWorkService unitOfWorkService, QuizRepository quizRepository) {
        this.unitOfWorkService = unitOfWorkService;
        this.quizRepository = quizRepository;
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public QuizDto getQuizById(Integer aggregateId, UnitOfWork unitOfWork) {
        return quizFactory.createQuizDto((Quiz) unitOfWorkService.aggregateLoadAndRegisterRead(aggregateId, unitOfWork));
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public QuizDto generateQuiz(CourseExecutionDto courseExecutionDto, QuizDto quizDto, List<QuestionDto> questionDtos, Integer numberOfQuestions, UnitOfWork unitOfWork) {
        Integer aggregateId = aggregateIdGeneratorService.getNewAggregateId();
        QuizCourseExecution quizCourseExecution = new QuizCourseExecution(courseExecutionDto);

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


        Quiz quiz = quizFactory.createQuiz(aggregateId, quizCourseExecution, quizQuestions, quizDto, GENERATED);
        quiz.setTitle("Generated Quiz Title");
        unitOfWorkService.registerChanged(quiz, unitOfWork);
        return quizFactory.createQuizDto(quiz);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public QuizDto startTournamentQuiz(Integer userAggregateId, Integer quizAggregateId, UnitOfWork unitOfWork) {
        /* must add more verifications */
        Quiz oldQuiz = (Quiz) unitOfWorkService.aggregateLoadAndRegisterRead(quizAggregateId, unitOfWork);
        return quizFactory.createQuizDto(oldQuiz);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public QuizDto createQuiz(QuizCourseExecution quizCourseExecution, Set<QuestionDto> questions, QuizDto quizDto, UnitOfWork unitOfWork) {
        Integer aggregateId = aggregateIdGeneratorService.getNewAggregateId();

        Set<QuizQuestion> quizQuestions = questions.stream()
                .map(QuizQuestion::new)
                .collect(Collectors.toSet());

        Quiz quiz = quizFactory.createQuiz(aggregateId, quizCourseExecution, quizQuestions, quizDto, IN_CLASS);
        unitOfWorkService.registerChanged(quiz, unitOfWork);
        return quizFactory.createQuizDto(quiz);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public QuizDto updateGeneratedQuiz(QuizDto quizDto, Set<Integer> topicsAggregateIds, Integer numberOfQuestions, List<QuestionDto> questionDtos, UnitOfWork unitOfWork) {
        Quiz oldQuiz = (Quiz) unitOfWorkService.aggregateLoadAndRegisterRead(quizDto.getAggregateId(), unitOfWork);
        Quiz newQuiz = quizFactory.createQuizFromExisting(oldQuiz);
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
        unitOfWorkService.registerChanged(newQuiz, unitOfWork);
        return quizFactory.createQuizDto(newQuiz);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public QuizDto updateQuiz(QuizDto quizDto, Set<QuizQuestion> quizQuestions, UnitOfWork unitOfWork) {
        Quiz oldQuiz = (Quiz) unitOfWorkService.aggregateLoadAndRegisterRead(quizDto.getAggregateId(), unitOfWork);
        Quiz newQuiz = quizFactory.createQuizFromExisting(oldQuiz);


        if (quizDto.getTitle() != null) {
            newQuiz.setTitle(quizDto.getTitle());
            unitOfWorkService.registerChanged(newQuiz, unitOfWork);
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

        return quizFactory.createQuizDto(newQuiz);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public List<QuizDto> getAvailableQuizzes(Integer courseExecutionAggregateId, UnitOfWork unitOfWork) {
        LocalDateTime now = DateHandler.now();
       return quizRepository.findAllQuizIdsByCourseExecution(courseExecutionAggregateId).stream()
               .map(id -> (Quiz) unitOfWorkService.aggregateLoad(id, unitOfWork))
               .filter(quiz -> quiz.getAvailableDate().isAfter(now) && quiz.getConclusionDate().isBefore(now) && quiz.getQuizType() != GENERATED)
               .map(quiz -> (Quiz) unitOfWorkService.registerRead(quiz, unitOfWork))
               .map(QuizDto::new)
               .collect(Collectors.toList());
    }

    /************************************************ EVENT PROCESSING ************************************************/

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public QuizDto removeCourseExecution(Integer quizAggregateId, Integer courseExecutionId, Long aggregateVersion, UnitOfWork unitOfWork) {
        Quiz oldQuiz = (Quiz) unitOfWorkService.aggregateLoadAndRegisterRead(quizAggregateId, unitOfWork);
        Quiz newQuiz = quizFactory.createQuizFromExisting(oldQuiz);
        
        if (newQuiz.getQuizCourseExecution().getCourseExecutionAggregateId().equals(courseExecutionId)) {
            newQuiz.setState(Aggregate.AggregateState.INACTIVE);
            // merge quiz <- answer: the member answers' COURSE_EXECUTION_EXISTS rule is enforced
            // here too — their execution is the quiz's own (COURSE_EXECUTION_SAME_QUIZ_COURSE_EXECUTION)
            newQuiz.getQuizAnswers().forEach(quizAnswer -> quizAnswer.setState(Aggregate.AggregateState.DELETED));
            unitOfWorkService.registerChanged(newQuiz, unitOfWork);
            return new QuizDto(newQuiz);
        }
        
        return null;
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void updateQuestion(Integer quizAggregateId, Integer questionAggregateId, String title, String content, Long aggregateVersion, UnitOfWork unitOfWork) {
        Quiz oldQuiz = (Quiz) unitOfWorkService.aggregateLoadAndRegisterRead(quizAggregateId, unitOfWork);
        Quiz newQuiz = quizFactory.createQuizFromExisting(oldQuiz);

        QuizQuestion quizQuestion = newQuiz.findQuestion(questionAggregateId);

        if (quizQuestion != null) {
            quizQuestion.setTitle(title);
            quizQuestion.setContent(content);
            quizQuestion.setQuestionVersion(aggregateVersion);
        }
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void removeQuizQuestion(Integer quizAggregateId, Integer questionAggregateId, UnitOfWork unitOfWork) {
        Quiz oldQuiz = (Quiz) unitOfWorkService.aggregateLoadAndRegisterRead(quizAggregateId, unitOfWork);
        Quiz newQuiz = quizFactory.createQuizFromExisting(oldQuiz);

        QuizQuestion quizQuestion = newQuiz.findQuestion(questionAggregateId);

        if (quizQuestion != null) {
            newQuiz.setState(Aggregate.AggregateState.INACTIVE);
            quizQuestion.setState(Aggregate.AggregateState.DELETED);
            unitOfWorkService.registerEvent(new InvalidateQuizEvent(newQuiz.getAggregateId()), unitOfWork);
            // merge quiz <- answer: QuizAnswer's QUIZ_EXISTS rule was an InvalidateQuizEvent
            // subscription; the invalidated quiz now flags its member answers synchronously.
            // A separate copy is registered so the quiz's own row keeps the state it kept before
            // (the InvalidateQuizEvent remains what external consumers observe).
            Quiz invalidatedQuiz = quizFactory.createQuizFromExisting(oldQuiz);
            boolean anyAnswerFlagged = false;
            for (QuizAnswer quizAnswer : invalidatedQuiz.getQuizAnswers()) {
                if (quizAnswer.getState() != Aggregate.AggregateState.DELETED) {
                    quizAnswer.setState(Aggregate.AggregateState.DELETED);
                    anyAnswerFlagged = true;
                }
            }
            if (anyAnswerFlagged) {
                unitOfWorkService.registerChanged(invalidatedQuiz, unitOfWork);
            }
        }
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void removeQuiz(Integer quizAggregateId, UnitOfWork unitOfWork) {
        Quiz oldQuiz = (Quiz) unitOfWorkService.aggregateLoadAndRegisterRead(quizAggregateId, unitOfWork);
        Quiz newQuiz = quizFactory.createQuizFromExisting(oldQuiz);
        newQuiz.remove();
        unitOfWorkService.registerChanged(newQuiz, unitOfWork);
    }

    /************************************************
     * QUIZ ANSWERS (absorbed from QuizAnswerService, merge quiz <- answer)
     * Wire names keep <aggrB>AggregateId; inside the aggregate the identity is quizAnswerId.
     ************************************************/

    private Quiz loadQuizOwningAnswer(Integer quizAnswerId, UnitOfWork unitOfWork) {
        Integer quizAggregateId = quizRepository.findQuizIdsByQuizAnswerId(quizAnswerId).stream().findFirst()
                .orElseThrow(() -> new SimulatorException(AGGREGATE_NOT_FOUND, quizAnswerId));
        return (Quiz) unitOfWorkService.aggregateLoadAndRegisterRead(quizAggregateId, unitOfWork);
    }

    private QuizAnswer findQuizAnswerOfStudent(Quiz quiz, Integer userAggregateId) {
        QuizAnswer quizAnswer = quiz.getQuizAnswers().stream()
                .filter(answer -> answer.getStudent().getStudentAggregateId().equals(userAggregateId))
                .findFirst()
                .orElseThrow(() -> new QuizzesException(QuizzesErrorMessage.NO_USER_ANSWER_FOR_QUIZ,
                        quiz.getAggregateId(), userAggregateId));

        if (quizAnswer.getState() == Aggregate.AggregateState.DELETED) {
            throw new QuizzesException(QuizzesErrorMessage.QUIZ_ANSWER_DELETED, quizAnswer.getQuizAnswerId());
        }

        return quizAnswer;
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public QuizAnswerDto getQuizAnswerDtoByQuizIdAndUserId(Integer quizAggregateId, Integer userAggregateId,
            UnitOfWork unitOfWork) {
        Quiz quiz = (Quiz) unitOfWorkService.aggregateLoadAndRegisterRead(quizAggregateId, unitOfWork);
        return new QuizAnswerDto(findQuizAnswerOfStudent(quiz, userAggregateId));
    }

    // UNIQUE_QUIZ_ANSWER_PER_STUDENT is declared by Quiz: the rule is about the quiz having one
    // answer per student. It was a Layer 2 guard over a remote repository; it is now a
    // navigation over the owning root, checked in the same transaction.
    @ServiceGuard({ "COURSE_EXECUTION_SAME_QUIZ_COURSE_EXECUTION", "UNIQUE_QUIZ_ANSWER_PER_STUDENT" })
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public QuizAnswerDto startQuiz(Integer quizAggregateId, Integer courseExecutionAggregateId, UserDto userDto,
            UnitOfWork unitOfWork) {
        Quiz oldQuiz = (Quiz) unitOfWorkService.aggregateLoadAndRegisterRead(quizAggregateId, unitOfWork);

        // COURSE_EXECUTION_SAME_QUIZ_COURSE_EXECUTION
        if (!courseExecutionAggregateId.equals(oldQuiz.getQuizCourseExecution().getCourseExecutionAggregateId())) {
            throw new QuizzesException(QuizzesErrorMessage.QUIZ_DOES_NOT_BELONG_TO_COURSE_EXECUTION, quizAggregateId,
                    courseExecutionAggregateId);
        }

        // UNIQUE_QUIZ_ANSWER_PER_STUDENT (Layer 2 guard)
        if (oldQuiz.findQuizAnswerByStudent(userDto.getAggregateId()) != null) {
            throw new QuizzesException(QuizzesErrorMessage.QUIZ_ALREADY_STARTED_BY_STUDENT, userDto.getAggregateId(),
                    quizAggregateId);
        }

        Quiz newQuiz = quizFactory.createQuizFromExisting(oldQuiz);
        // QUESTIONS_ANSWER_QUESTIONS_BELONG_TO_QUIZ because questions come from the quiz
        QuizAnswer quizAnswer = new QuizAnswer(aggregateIdGeneratorService.getNewAggregateId(),
                new AnswerCourseExecution(newQuiz.getQuizCourseExecution().getCourseExecutionAggregateId(),
                        newQuiz.getQuizCourseExecution().getCourseExecutionVersion()),
                new AnswerStudent(userDto), new AnsweredQuiz(newQuiz));
        quizAnswer.setAnswerDate(DateHandler.now());
        newQuiz.addQuizAnswer(quizAnswer);

        unitOfWorkService.registerChanged(newQuiz, unitOfWork);
        return new QuizAnswerDto(quizAnswer);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void answerQuestion(Integer quizAggregateId, Integer userAggregateId, QuestionAnswerDto userAnswerDto,
            QuestionDto questionDto, UnitOfWork unitOfWork) {
        Quiz oldQuiz = (Quiz) unitOfWorkService.aggregateLoadAndRegisterRead(quizAggregateId, unitOfWork);
        Quiz newQuiz = quizFactory.createQuizFromExisting(oldQuiz);
        QuizAnswer quizAnswer = findQuizAnswerOfStudent(newQuiz, userAggregateId);

        QuestionAnswer questionAnswer = new QuestionAnswer(userAnswerDto, questionDto);
        quizAnswer.addQuestionAnswer(questionAnswer);
        unitOfWorkService.registerChanged(newQuiz, unitOfWork);
        // the event keeps publishing the retained member identifier so the tournament
        // subscription (TournamentSubscribesAnswerQuestion) keeps matching
        unitOfWorkService.registerEvent(new QuizAnswerQuestionAnswerEvent(quizAnswer.getQuizAnswerId(),
                questionAnswer.getQuestionAggregateId(), quizAggregateId, userAggregateId,
                questionAnswer.isCorrect()), unitOfWork);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void concludeQuiz(Integer quizAggregateId, Integer userAggregateId, UnitOfWork unitOfWork) {
        Quiz oldQuiz = (Quiz) unitOfWorkService.aggregateLoadAndRegisterRead(quizAggregateId, unitOfWork);
        Quiz newQuiz = quizFactory.createQuizFromExisting(oldQuiz);
        QuizAnswer quizAnswer = findQuizAnswerOfStudent(newQuiz, userAggregateId);

        quizAnswer.setCompleted(true);
        unitOfWorkService.registerChanged(newQuiz, unitOfWork);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void removeQuizAnswer(Integer quizAnswerAggregateId, UnitOfWork unitOfWork) {
        Quiz oldQuiz = loadQuizOwningAnswer(quizAnswerAggregateId, unitOfWork);
        Quiz newQuiz = quizFactory.createQuizFromExisting(oldQuiz);
        QuizAnswer quizAnswer = newQuiz.findQuizAnswerById(quizAnswerAggregateId);
        if (quizAnswer == null || quizAnswer.getState() == Aggregate.AggregateState.DELETED) {
            // the old root lookup only found non-deleted aggregates
            throw new SimulatorException(AGGREGATE_NOT_FOUND, quizAnswerAggregateId);
        }
        quizAnswer.setState(Aggregate.AggregateState.DELETED);
        unitOfWorkService.registerChanged(newQuiz, unitOfWork);
    }

    /************************************************
     * EVENT PROCESSING (absorbed from QuizAnswerService — the subscriber is now the Quiz root)
     ************************************************/

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void updateStudentNameInQuizAnswers(Integer quizAggregateId, Integer executionAggregateId,
            Long eventVersion, Integer userAggregateId, String name, UnitOfWork unitOfWork) {
        Quiz oldQuiz = (Quiz) unitOfWorkService.aggregateLoadAndRegisterRead(quizAggregateId, unitOfWork);
        Quiz newQuiz = quizFactory.createQuizFromExisting(oldQuiz);

        boolean changed = false;
        for (QuizAnswer quizAnswer : newQuiz.getQuizAnswers()) {
            if (quizAnswer.getState() != Aggregate.AggregateState.DELETED
                    && quizAnswer.getAnswerCourseExecution().getCourseExecutionAggregateId().equals(executionAggregateId)
                    && quizAnswer.getStudent().getStudentAggregateId().equals(userAggregateId)) {
                quizAnswer.getStudent().setName(name);
                quizAnswer.getAnswerCourseExecution().setCourseExecutionVersion(eventVersion);
                changed = true;
            }
        }
        if (changed) {
            unitOfWorkService.registerChanged(newQuiz, unitOfWork);
        }
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void removeStudentFromQuizAnswers(Integer quizAggregateId, Integer userAggregateId, Long eventVersion,
            UnitOfWork unitOfWork) {
        Quiz oldQuiz = (Quiz) unitOfWorkService.aggregateLoadAndRegisterRead(quizAggregateId, unitOfWork);
        Quiz newQuiz = quizFactory.createQuizFromExisting(oldQuiz);

        boolean changed = false;
        for (QuizAnswer quizAnswer : newQuiz.getQuizAnswers()) {
            if (quizAnswer.getState() == Aggregate.AggregateState.ACTIVE
                    && quizAnswer.getStudent().getStudentAggregateId().equals(userAggregateId)) {
                quizAnswer.getStudent().setStudentState(Aggregate.AggregateState.DELETED);
                quizAnswer.setState(Aggregate.AggregateState.INACTIVE);
                quizAnswer.getAnswerCourseExecution().setCourseExecutionVersion(eventVersion);
                changed = true;
            }
        }
        if (changed) {
            unitOfWorkService.registerChanged(newQuiz, unitOfWork);
        }
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void removeQuestionFromQuizAnswer(Integer quizAnswerAggregateId, Integer questionAggregateId,
            Long eventVersion, UnitOfWork unitOfWork) {
        Quiz oldQuiz = loadQuizOwningAnswer(quizAnswerAggregateId, unitOfWork);
        Quiz newQuiz = quizFactory.createQuizFromExisting(oldQuiz);
        QuizAnswer quizAnswer = newQuiz.findQuizAnswerById(quizAnswerAggregateId);
        if (quizAnswer == null) {
            return;
        }
        QuestionAnswer questionAnswer = quizAnswer.findQuestionAnswer(questionAggregateId);
        if (questionAnswer == null) {
            return;
        }
        questionAnswer.setState(Aggregate.AggregateState.DELETED);
        quizAnswer.setState(Aggregate.AggregateState.INACTIVE);
        unitOfWorkService.registerChanged(newQuiz, unitOfWork);
    }

}
