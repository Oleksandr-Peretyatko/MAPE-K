package org.anonymous.msrefactor.quizzes.sagas.behaviour

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.anonymous.msrefactor.ms.messaging.local.LocalCommandGateway
import org.anonymous.msrefactor.ms.monitoring.TraceService
import org.anonymous.msrefactor.ms.notification.EventService
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService
import org.anonymous.msrefactor.ms.utils.DateHandler
import org.anonymous.msrefactor.quizzes.BeanConfigurationSagas
import org.anonymous.msrefactor.quizzes.QuizzesSpockTest
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.CourseExecutionDto
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.CourseExecutionFactory
import org.anonymous.msrefactor.quizzes.microservices.execution.coordination.functionalities.ExecutionFunctionalities
import org.anonymous.msrefactor.quizzes.microservices.execution.service.ExecutionService
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.QuestionDto
import org.anonymous.msrefactor.quizzes.microservices.topic.aggregate.TopicDto
import org.anonymous.msrefactor.quizzes.microservices.topic.service.TopicService
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.TournamentDto
import org.anonymous.msrefactor.quizzes.microservices.execution.coordination.sagas.CreateTournamentFunctionalitySagas
import org.anonymous.msrefactor.quizzes.microservices.execution.notification.handling.CourseExecutionEventHandling
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto

@DataJpaTest
class CreateTournamentRecoveryTest extends QuizzesSpockTest {
    public static final String UPDATED_NAME = "UpdatedName"

    @Autowired
    private SagaUnitOfWorkService unitOfWorkService

    @Autowired
    private ExecutionService courseExecutionService
    @Autowired
    private ExecutionService tournamentService
    @Autowired
    private CourseExecutionFactory courseExecutionFactory

    @Autowired
    private ExecutionFunctionalities courseExecutionFunctionalities
    @Autowired
    private ExecutionFunctionalities tournamentFunctionalities
    @Autowired
    private ExecutionFunctionalities quizFunctionalities

    @Autowired
    private EventService eventService

    @Autowired
    public TraceService traceService

    @Autowired
    private TopicService topicService

    @Autowired
    private ExecutionService quizService

    @Autowired
    private CourseExecutionEventHandling tournamentEventHandling

    @Autowired
    private LocalCommandGateway commandGateway

    private CourseExecutionDto courseExecutionDto
    private UserDto userCreatorDto, userDto
    private TopicDto topicDto1, topicDto2, topicDto3
    private QuestionDto questionDto1, questionDto2, questionDto3
    private TournamentDto tournamentDto

    def unitOfWork1, unitOfWork2

    def setup() {
        given: 'load a behavior specification'
        loadBehaviorScripts()
        traceService.startRootSpan()

        and: 'a course execution'
        courseExecutionDto = createCourseExecution(COURSE_EXECUTION_NAME, COURSE_EXECUTION_TYPE, COURSE_EXECUTION_ACRONYM, COURSE_EXECUTION_ACADEMIC_TERM, TIME_4)

        and: 'a user to enroll in the course execution'
        userCreatorDto = createUser(USER_NAME_1, USER_USERNAME_1, STUDENT_ROLE)
        courseExecutionFunctionalities.addStudent(courseExecutionDto.getAggregateId(), userCreatorDto.getAggregateId())

        and: 'another user to enroll in the course execution'
        userDto = createUser(USER_NAME_2, USER_USERNAME_2, STUDENT_ROLE)
        courseExecutionFunctionalities.addStudent(courseExecutionDto.aggregateId, userDto.aggregateId)

        and: 'three topics'
        topicDto1 = createTopic(courseExecutionDto, TOPIC_NAME_1)
        topicDto2 = createTopic(courseExecutionDto, TOPIC_NAME_2)
        topicDto3 = createTopic(courseExecutionDto, TOPIC_NAME_3)

        and: 'three questions'
        questionDto1 = createQuestion(courseExecutionDto, new HashSet<>(Arrays.asList(topicDto1)), TITLE_1, CONTENT_1, OPTION_1, OPTION_2)
        questionDto2 = createQuestion(courseExecutionDto, new HashSet<>(Arrays.asList(topicDto2)), TITLE_2, CONTENT_2, OPTION_3, OPTION_4)
        questionDto3 = createQuestion(courseExecutionDto, new HashSet<>(Arrays.asList(topicDto3)), TITLE_3, CONTENT_3, OPTION_1, OPTION_3)

        and: 'a tournament '
        tournamentDto = new TournamentDto(startTime: DateHandler.toISOString(TIME_1), endTime: DateHandler.toISOString(TIME_3), numberOfQuestions: 2)
    }

    def cleanup() {
        impairmentService.cleanUpCounter()
    }

    def 'Check Quiz existence'() {
        given: 'a clear report'
        impairmentService.cleanReportFile()
        
        and: 'create unit of works for the creation of a tournament'
        def functionalityName1 = CreateTournamentFunctionalitySagas.class.getSimpleName()
        def unitOfWork1 = unitOfWorkService.createUnitOfWork(functionalityName1)

        and: 'the create tournament functionality'
        def createTournamentFunctionality = new CreateTournamentFunctionalitySagas(
                unitOfWorkService, userCreatorDto.getAggregateId(),
             courseExecutionDto.getAggregateId(), [topicDto1.getAggregateId(), topicDto2.getAggregateId()], tournamentDto, unitOfWork1, commandGateway)
        

        when: 'execute until the step that generates the quiz'
        createTournamentFunctionality.executeUntilStep("generateQuizStep", unitOfWork1)
        then: 'a quiz is created'
        boolean exceptionThrown = false
        try {
            createTournamentFunctionality.resumeWorkflow(unitOfWork1)
        } catch (Exception e) {
            exceptionThrown = true
        }

        traceService.endRootSpan()
        traceService.spanFlush()
        impairmentService.cleanDirectory()
    }

    @TestConfiguration
    static class LocalBeanConfiguration extends BeanConfigurationSagas {}
}
