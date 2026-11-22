package org.anonymous.msrefactor.quizzes.sagas.behaviour

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.anonymous.msrefactor.ms.exception.SimulatorErrorMessage
import org.anonymous.msrefactor.ms.exception.SimulatorException
import org.anonymous.msrefactor.ms.monitoring.TraceService
import org.anonymous.msrefactor.ms.notification.EventService
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService
import org.anonymous.msrefactor.quizzes.BeanConfigurationSagas
import org.anonymous.msrefactor.quizzes.QuizzesSpockTest
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.CourseExecutionDto
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.CourseExecutionFactory
import org.anonymous.msrefactor.quizzes.microservices.execution.coordination.functionalities.ExecutionFunctionalities
import org.anonymous.msrefactor.quizzes.microservices.execution.coordination.sagas.UpdateStudentNameFunctionalitySagas
import org.anonymous.msrefactor.quizzes.microservices.execution.service.ExecutionService
import org.anonymous.msrefactor.quizzes.microservices.question.aggregate.QuestionDto
import org.anonymous.msrefactor.quizzes.microservices.topic.aggregate.TopicDto
import org.anonymous.msrefactor.quizzes.microservices.tournament.aggregate.TournamentDto
import org.anonymous.msrefactor.quizzes.microservices.tournament.coordination.functionalities.TournamentFunctionalities
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.coordination.sagas.AddParticipantFunctionalitySagas
import org.anonymous.msrefactor.quizzes.microservices.tournament.notification.handling.TournamentEventHandling
import org.anonymous.msrefactor.quizzes.microservices.tournament.service.TournamentService
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.coordination.functionalities.TournamentSatelliteFunctionalities
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.notification.handling.TournamentSatelliteEventHandling
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.coordination.functionalities.TournamentSatelliteSatelliteFunctionalities
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.notification.handling.TournamentSatelliteSatelliteEventHandling

@DataJpaTest
class HandleEventBehaviour extends QuizzesSpockTest {
    @Autowired
    private TournamentSatelliteSatelliteEventHandling tournamentSatelliteSatelliteEventHandling
    @Autowired
    private TournamentSatelliteSatelliteFunctionalities tournamentSatelliteSatelliteFunctionalities
    @Autowired
    private TournamentSatelliteFunctionalities tournamentSatelliteFunctionalities
    @Autowired
    private TournamentSatelliteEventHandling tournamentSatelliteEventHandling
    public static final String UPDATED_NAME = "UpdatedName"

    @Autowired
    private SagaUnitOfWorkService unitOfWorkService

    @Autowired
    private ExecutionService courseExecutionService
    @Autowired
    private TournamentService tournamentService
    @Autowired
    private CourseExecutionFactory courseExecutionFactory

    @Autowired
    private ExecutionFunctionalities courseExecutionFunctionalities
    @Autowired
    private TournamentFunctionalities tournamentFunctionalities

    @Autowired
    private EventService eventService


    @Autowired
    public TraceService traceService

    @Autowired
    private TournamentEventHandling tournamentEventHandling

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

        and: 'a tournament where the first user is the creator'
        tournamentDto = createTournament(TIME_1, TIME_3, 2, userCreatorDto.getAggregateId(),  courseExecutionDto.getAggregateId(), [topicDto1.getAggregateId(),topicDto2.getAggregateId()])

        and: 'two units of work'
        def functionalityName1 = UpdateStudentNameFunctionalitySagas.class.getSimpleName()
        def functionalityName2 = AddParticipantFunctionalitySagas.class.getSimpleName()
        unitOfWork1 = unitOfWorkService.createUnitOfWork(functionalityName1)
        unitOfWork2 = unitOfWorkService.createUnitOfWork(functionalityName2)
    }

    def cleanup() {
        impairmentService.cleanUpCounter()
    }

    def 'sequential - add creator: update; add: fails because when creator is added tournament have not process the event yet' () {
        given: 'a clear report'
        impairmentService.cleanReportFile()

        and: 'creator name is updated'
        def updateNameDto = new UserDto()
        updateNameDto.setName(UPDATED_NAME)
        courseExecutionFunctionalities.updateStudentName(courseExecutionDto.getAggregateId(), userCreatorDto.getAggregateId(), updateNameDto)

        when: 'event is finally processed it updates the creator name'
        tournamentSatelliteEventHandling.handleUpdateStudentNameEvent()
        tournamentSatelliteSatelliteEventHandling.handleUpdateStudentNameEvent()
        then: ''
        thrown(SimulatorException)

        when: 'add creator as participant where the creator in tournament still has the old name'
        tournamentSatelliteSatelliteFunctionalities.addParticipant(tournamentDto.getAggregateId(), courseExecutionDto.getAggregateId(), userCreatorDto.getAggregateId())
        

        then: 'fails because invariant breaks'
        def error = thrown(SimulatorException)
        error.errorMessage == SimulatorErrorMessage.INVARIANT_BREAK
        
        and: 'the creator is participant with updated name'
        def tournamentDtoResult2 = tournamentFunctionalities.findTournament(tournamentDto.getAggregateId())
        tournamentDtoResult2.getParticipants().size() == 0

        cleanup: 'remove all generated artifacts after test execution'
        traceService.endRootSpan()
        traceService.spanFlush()
        impairmentService.cleanDirectory()
    }

    @TestConfiguration
    static class LocalBeanConfiguration extends BeanConfigurationSagas {}
}