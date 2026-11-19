package org.anonymous.msrefactor.quizzes.sagas.coordination.tournament

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService
import org.anonymous.msrefactor.quizzes.BeanConfigurationSagas
import org.anonymous.msrefactor.quizzes.QuizzesSpockTest
import org.anonymous.msrefactor.quizzes.microservices.quiz.service.QuizService
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.CourseExecutionDto
import org.anonymous.msrefactor.quizzes.microservices.execution.coordination.functionalities.ExecutionFunctionalities
import org.anonymous.msrefactor.quizzes.microservices.question.aggregate.QuestionDto
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.TopicDto
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.TournamentDto
import org.anonymous.msrefactor.quizzes.microservices.execution.notification.handling.CourseExecutionEventHandling
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto

@DataJpaTest
class AnonymizeStudentAndSolveQuizTest extends QuizzesSpockTest {
    @Autowired
    private SagaUnitOfWorkService unitOfWorkService

    @Autowired
    private QuizService quizService

    @Autowired
    private ExecutionFunctionalities courseExecutionFunctionalities
    @Autowired
    private ExecutionFunctionalities tournamentFunctionalities

    @Autowired
    private CourseExecutionEventHandling tournamentEventHandling

    private CourseExecutionDto courseExecutionDto
    private UserDto userCreatorDto, userDto
    private TopicDto topicDto1, topicDto2, topicDto3
    private QuestionDto questionDto1, questionDto2, questionDto3
    private TournamentDto tournamentDto

    def setup() {
        given: 'a course execution'
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
    }

    def cleanup() {

    }

    def 'sequential solve quiz and anonymize user'() {
        
        given: 'a quiz is solved for a user'
        tournamentFunctionalities.addParticipant(tournamentDto.getAggregateId(), courseExecutionDto.getAggregateId(), userDto.getAggregateId())
        tournamentFunctionalities.solveQuiz(tournamentDto.aggregateId, userDto.getAggregateId())

        when: 'the user is anonymized after starting the quiz'
        courseExecutionFunctionalities.anonymizeStudent(courseExecutionDto.getAggregateId(), userDto.getAggregateId())
        Thread.yield() // merge execution <- tournament: propagation is synchronous; no tournament-side handling to force

        then: 'the user is anonymized in the course execution'
        def courseExecutionResult = courseExecutionFunctionalities.getCourseExecutionByAggregateId(courseExecutionDto.getAggregateId())
        courseExecutionResult.getStudents().find{ it.aggregateId == userDto.getAggregateId() }.name == ANONYMOUS

        and: 'the quiz is still started for the anonymized user'
        def unitOfWork = unitOfWorkService.createUnitOfWork("getQuizAnswerDtoByQuizIdAndUserId")
        def quizAnswerResult = quizService.getQuizAnswerDtoByQuizIdAndUserId(tournamentDto.quiz.aggregateId, userDto.getAggregateId(), unitOfWork)
        quizAnswerResult.getStudentName() == userDto.getName()
    }

    @TestConfiguration
    static class LocalBeanConfiguration extends BeanConfigurationSagas {}
}