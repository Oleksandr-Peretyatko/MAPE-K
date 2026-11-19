package org.anonymous.msrefactor.quizzes.sagas.coordination.answer

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.anonymous.msrefactor.ms.aggregate.Aggregate
import org.anonymous.msrefactor.ms.notification.EventRepository
import org.anonymous.msrefactor.ms.utils.DateHandler
import org.anonymous.msrefactor.quizzes.BeanConfigurationSagas
import org.anonymous.msrefactor.quizzes.QuizzesSpockTest
import org.anonymous.msrefactor.quizzes.events.DeleteCourseExecutionEvent
import org.anonymous.msrefactor.quizzes.events.DeleteQuestionEvent
import org.anonymous.msrefactor.quizzes.microservices.quiz.aggregate.QuizRepository
import org.anonymous.msrefactor.quizzes.microservices.quiz.notification.handling.QuizEventHandling
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.CourseExecutionDto
import org.anonymous.msrefactor.quizzes.microservices.execution.coordination.functionalities.ExecutionFunctionalities
import org.anonymous.msrefactor.quizzes.microservices.question.aggregate.QuestionDto
import org.anonymous.msrefactor.quizzes.microservices.quiz.aggregate.QuizDto
import org.anonymous.msrefactor.quizzes.microservices.quiz.coordination.functionalities.QuizFunctionalities
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.TopicDto
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto

// Adapted by the merge quiz <- answer: the quiz answer is a member of the Quiz root, so the
// subscriber of every event below is the quiz. The former InvalidateQuizEvent consumption became
// the synchronous flagging inside QuizService.removeQuizQuestion (QUIZ_EXISTS is intra now), so
// that scenario is driven through the DeleteQuestionEvent that used to trigger the invalidation.
@DataJpaTest
class QuizAnswerEventHandlingTest extends QuizzesSpockTest {

    @Autowired
    private ExecutionFunctionalities courseExecutionFunctionalities
    @Autowired
    private QuizFunctionalities quizFunctionalities
    @Autowired
    private QuizEventHandling quizEventHandling
    @Autowired
    private QuizRepository quizRepository
    @Autowired
    private EventRepository eventRepository

    private CourseExecutionDto courseExecutionDto
    private UserDto userDto
    private TopicDto topicDto
    private QuestionDto questionDto
    private QuizDto quizDto

    def setup() {
        courseExecutionDto = createCourseExecution(COURSE_EXECUTION_NAME, COURSE_EXECUTION_TYPE, COURSE_EXECUTION_ACRONYM, COURSE_EXECUTION_ACADEMIC_TERM, TIME_4)
        userDto = createUser(USER_NAME_1, USER_USERNAME_1, STUDENT_ROLE)
        courseExecutionFunctionalities.addStudent(courseExecutionDto.getAggregateId(), userDto.getAggregateId())
        topicDto = createTopic(courseExecutionDto, TOPIC_NAME_1)
        questionDto = createQuestion(courseExecutionDto, new HashSet<>(Arrays.asList(topicDto)), TITLE_1, CONTENT_1, OPTION_1, OPTION_2)

        def quizDtoInput = new QuizDto()
        quizDtoInput.setTitle(TITLE_1)
        quizDtoInput.setAvailableDate(DateHandler.toISOString(TIME_1))
        quizDtoInput.setConclusionDate(DateHandler.toISOString(TIME_2))
        quizDtoInput.setResultsDate(DateHandler.toISOString(TIME_3))
        quizDtoInput.setQuestionDtos(Arrays.asList(questionDto))
        quizDto = quizFunctionalities.createQuiz(courseExecutionDto.getAggregateId(), quizDtoInput)

        quizFunctionalities.startQuiz(quizDto.getAggregateId(), courseExecutionDto.getAggregateId(), userDto.getAggregateId())
    }

    def cleanup() {}

    private def latestQuizAnswer() {
        return quizRepository.findLatestQuiz().get().getQuizAnswers().iterator().next()
    }

    def "AnonymizeStudentEvent updates the student name in the quiz answer"() {
        when:
        courseExecutionFunctionalities.anonymizeStudent(courseExecutionDto.getAggregateId(), userDto.getAggregateId())
        quizEventHandling.handleAnonymizeStudentEvents()

        then:
        latestQuizAnswer().getStudent().getName() == ANONYMOUS
    }

    def "DeleteQuestionEvent invalidates the quiz and removes the quiz answer"() {
        given: "a DeleteQuestionEvent published for the quiz's question"
        def questionVersion = quizRepository.findLatestQuiz().get().getQuizQuestions().iterator().next().getQuestionVersion()
        def event = new DeleteQuestionEvent(questionDto.getAggregateId())
        event.setPublisherAggregateVersion(questionVersion + 1)
        event.setPublished(true)
        eventRepository.save(event)

        when:
        quizEventHandling.handleRemoveQuestionEvent()

        then:
        latestQuizAnswer().getState() == Aggregate.AggregateState.DELETED
    }

    def "DeleteCourseExecutionEvent removes the quiz answer"() {
        given: "a DeleteCourseExecutionEvent published for the course execution"
        def executionVersion = quizRepository.findLatestQuiz().get().getQuizCourseExecution().getCourseExecutionVersion()
        def event = new DeleteCourseExecutionEvent(courseExecutionDto.getAggregateId())
        event.setPublisherAggregateVersion(executionVersion + 1)
        event.setPublished(true)
        eventRepository.save(event)

        when:
        quizEventHandling.handleRemoveCourseExecutionEvents()

        then:
        latestQuizAnswer().getState() == Aggregate.AggregateState.DELETED
    }

    @TestConfiguration
    static class LocalBeanConfiguration extends BeanConfigurationSagas {}
}
