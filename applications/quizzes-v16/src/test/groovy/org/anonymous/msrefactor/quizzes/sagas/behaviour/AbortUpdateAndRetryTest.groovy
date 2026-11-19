package org.anonymous.msrefactor.quizzes.sagas.behaviour

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.anonymous.msrefactor.ms.messaging.local.LocalCommandGateway
import org.anonymous.msrefactor.ms.monitoring.TraceService
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService
import org.anonymous.msrefactor.ms.utils.DateHandler
import org.anonymous.msrefactor.quizzes.BeanConfigurationSagas
import org.anonymous.msrefactor.quizzes.QuizzesSpockTest
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.CourseExecutionDto
import org.anonymous.msrefactor.quizzes.microservices.execution.coordination.functionalities.ExecutionFunctionalities
import org.anonymous.msrefactor.quizzes.microservices.question.aggregate.QuestionDto
import org.anonymous.msrefactor.quizzes.microservices.topic.aggregate.TopicDto
import org.anonymous.msrefactor.quizzes.microservices.topic.service.TopicService
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.TournamentDto
import org.anonymous.msrefactor.quizzes.microservices.execution.coordination.sagas.UpdateTournamentFunctionalitySagas
import org.anonymous.msrefactor.quizzes.microservices.execution.service.ExecutionService
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto

@DataJpaTest
class AbortUpdateAndRetryTest extends QuizzesSpockTest {
    @Autowired
    private SagaUnitOfWorkService unitOfWorkService

    @Autowired
    private ExecutionFunctionalities courseExecutionFunctionalities
    @Autowired
    private ExecutionFunctionalities quizFunctionalities
    @Autowired
    private ExecutionFunctionalities tournamentFunctionalities

    @Autowired
    private ExecutionService tournamentService
    @Autowired
    private TopicService topicService

    @Autowired
    public TraceService traceService

    @Autowired
    private LocalCommandGateway commandGateway;

    private CourseExecutionDto courseExecutionDto
    private UserDto userCreatorDto, userDto
    private TopicDto topicDto1, topicDto2, topicDto3
    private QuestionDto questionDto1, questionDto2, questionDto3
    private TournamentDto tournamentDto

    def unitOfWork1

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

        and: 'a unit of work'
        def functionalityName1 = UpdateTournamentFunctionalitySagas.class.getSimpleName()
        unitOfWork1 = unitOfWorkService.createUnitOfWork(functionalityName1)
    }

    def cleanup() {
        impairmentService.cleanUpCounter()
    }

    def 'update tournament fault compensate retry'() {
        given: 'a clear report'
        impairmentService.cleanReportFile()
        assert tournamentDto.numberOfQuestions == 2

        and: 
        tournamentDto.setStartTime(DateHandler.toISOString(TIME_2))
        tournamentDto.setEndTime(DateHandler.toISOString(TIME_4))
        tournamentDto.setNumberOfQuestions(3)
        def topicsAggregateIds = [topicDto1.getAggregateId(), topicDto2.getAggregateId(), topicDto3.getAggregateId()].toSet()

        when: 'start update tournament'
        def updateTournamentFunctionality = new UpdateTournamentFunctionalitySagas(unitOfWorkService, tournamentService, tournamentDto, topicsAggregateIds, unitOfWork1, commandGateway)
        updateTournamentFunctionality.executeUntilStep("updateTournamentStep",unitOfWork1)

        then: 'assert the tournament is updated'
        def updatedTournamentDto = tournamentFunctionalities.findTournament(tournamentDto.getAggregateId())
        updatedTournamentDto != null
        updatedTournamentDto.startTime == DateHandler.toISOString(TIME_2)
        updatedTournamentDto.endTime == DateHandler.toISOString(TIME_4)
        updatedTournamentDto.numberOfQuestions == 3
        updatedTournamentDto.topics*.aggregateId.toSet() == [topicDto1.getAggregateId(), topicDto2.getAggregateId(), topicDto3.getAggregateId()].toSet()


        when: 'remove finishes with an error'
        try {
            updateTournamentFunctionality.resumeWorkflow(unitOfWork1)
        } catch (Exception e) {
            println e.message
        }


        then: 'assert the tournament is compensated'
        def updatedTournamentDto1 = tournamentFunctionalities.findTournament(tournamentDto.getAggregateId())
        assert updatedTournamentDto1 != null
        assert updatedTournamentDto1.numberOfQuestions == 2
        assert updatedTournamentDto1.topics*.aggregateId.toSet() == [topicDto1.getAggregateId(), topicDto2.getAggregateId()].toSet()

        when: 'retry'
        def retries = impairmentService.getRetryValue("UpdateTournamentFunctionalitySagas")
        println "\u001B[34mRetries: $retries\u001B[0m"

        boolean success = false
        while (retries > 0 && !success) {
            try {
                def retryUnitOfWork = unitOfWorkService.createUnitOfWork(UpdateTournamentFunctionalitySagas.class.getSimpleName())
                def retryFunctionality = new UpdateTournamentFunctionalitySagas(unitOfWorkService, tournamentService, tournamentDto, topicsAggregateIds, retryUnitOfWork, commandGateway)
                retryFunctionality.executeWorkflow(retryUnitOfWork)
                success = true  // If no exception, mark as successful
            } catch (Exception e) {
                retries--
            }
        }
        
        then: 'The tournament is updated again'
        def quizDto = quizFunctionalities.findQuiz(updatedTournamentDto.quiz.aggregateId)
        assert quizDto.availableDate == DateHandler.toISOString(TIME_2)
        assert quizDto.conclusionDate == DateHandler.toISOString(TIME_4)
        assert quizDto.questionDtos.size() == 3

        cleanup: 'remove all generated artifacts after test execution'
        traceService.endRootSpan()
        // traceService.spanFlush()
        impairmentService.cleanDirectory()
    }

    @TestConfiguration
    static class LocalBeanConfiguration extends BeanConfigurationSagas {}
}