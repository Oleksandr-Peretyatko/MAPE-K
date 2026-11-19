package org.anonymous.msrefactor.quizzes.sagas.coordination.tournament

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.anonymous.msrefactor.ms.exception.SimulatorException
import org.anonymous.msrefactor.ms.transaction.sagas.aggregate.GenericSagaState
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService
import org.anonymous.msrefactor.ms.utils.DateHandler
import org.anonymous.msrefactor.quizzes.BeanConfigurationSagas
import org.anonymous.msrefactor.quizzes.QuizzesSpockTest
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.CourseExecutionDto
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.sagas.SagaExecution
import org.anonymous.msrefactor.quizzes.microservices.execution.coordination.functionalities.ExecutionFunctionalities
import org.anonymous.msrefactor.quizzes.microservices.topic.aggregate.TopicDto
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.TournamentDto
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto

@DataJpaTest
class CreateTournamentCompensationTest extends QuizzesSpockTest {
    @Autowired
    private SagaUnitOfWorkService unitOfWorkService

    @Autowired
    private ExecutionFunctionalities courseExecutionFunctionalities
    @Autowired
    private ExecutionFunctionalities tournamentFunctionalities

    private CourseExecutionDto courseExecutionDto
    private UserDto userCreatorDto
    private TopicDto topicDto1, topicDto2

    def setup() {
        loadBehaviorScripts()

        courseExecutionDto = createCourseExecution(COURSE_EXECUTION_NAME, COURSE_EXECUTION_TYPE, COURSE_EXECUTION_ACRONYM, COURSE_EXECUTION_ACADEMIC_TERM, TIME_4)

        userCreatorDto = createUser(USER_NAME_1, USER_USERNAME_1, STUDENT_ROLE)
        courseExecutionFunctionalities.addStudent(courseExecutionDto.getAggregateId(), userCreatorDto.getAggregateId())

        topicDto1 = createTopic(courseExecutionDto, TOPIC_NAME_1)
        topicDto2 = createTopic(courseExecutionDto, TOPIC_NAME_2)

        createQuestion(courseExecutionDto, new HashSet<>(Arrays.asList(topicDto1)), TITLE_1, CONTENT_1, OPTION_1, OPTION_2)
        createQuestion(courseExecutionDto, new HashSet<>(Arrays.asList(topicDto2)), TITLE_2, CONTENT_2, OPTION_3, OPTION_4)

        // consumes CSV block 1 (no fault): builds the prerequisite state above with a real tournament creation
        createTournament(TIME_1, TIME_3, 2, userCreatorDto.getAggregateId(), courseExecutionDto.getAggregateId(), [topicDto1.getAggregateId(), topicDto2.getAggregateId()])
    }

    def cleanup() {
        impairmentService.cleanUpCounter()
        impairmentService.cleanDirectory()
    }

    def "saga compensation releases the course execution lock when createTournamentStep fails"() {
        // generateQuizStep acquires CourseExecutionSagaState.READ_COURSE in-process (the execution
        // reads are local since the merge); createTournamentStep depends on it and is where the
        // fault is injected (CSV block 2), so the lock is genuinely held and then genuinely
        // released when the saga aborts.
        when: 'creating a second tournament with a fault injected on createTournamentStep'
        tournamentFunctionalities.createTournament(userCreatorDto.getAggregateId(), courseExecutionDto.getAggregateId(),
            [topicDto1.getAggregateId(), topicDto2.getAggregateId()],
            new TournamentDto(startTime: DateHandler.toISOString(TIME_1), endTime: DateHandler.toISOString(TIME_3), numberOfQuestions: 2))

        then: 'the injected fault surfaces to the caller'
        thrown(SimulatorException)

        and: 'the course execution semantic lock was released back to NOT_IN_SAGA'
        def unitOfWork = unitOfWorkService.createUnitOfWork("TEST")
        def courseExecution = (SagaExecution) unitOfWorkService.aggregateLoadAndRegisterRead(courseExecutionDto.getAggregateId(), unitOfWork)
        courseExecution.sagaState == GenericSagaState.NOT_IN_SAGA
    }

    @TestConfiguration
    static class LocalBeanConfiguration extends BeanConfigurationSagas {}
}
