package org.anonymous.msrefactor.quizzes.sagas.coordination.answer

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.anonymous.msrefactor.ms.exception.SimulatorException
import org.anonymous.msrefactor.ms.transaction.sagas.aggregate.GenericSagaState
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService
import org.anonymous.msrefactor.ms.utils.DateHandler
import org.anonymous.msrefactor.quizzes.BeanConfigurationSagas
import org.anonymous.msrefactor.quizzes.QuizzesSpockTest
import org.anonymous.msrefactor.quizzes.microservices.answer.coordination.functionalities.QuizAnswerFunctionalities
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.CourseExecutionDto
import org.anonymous.msrefactor.quizzes.microservices.execution.coordination.functionalities.ExecutionFunctionalities
import org.anonymous.msrefactor.quizzes.microservices.question.aggregate.QuestionDto
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.QuizDto
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.sagas.SagaExecution
import org.anonymous.msrefactor.quizzes.microservices.topic.aggregate.TopicDto
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto

@DataJpaTest
class StartQuizCompensationTest extends QuizzesSpockTest {
    @Autowired
    private SagaUnitOfWorkService unitOfWorkService

    @Autowired
    private ExecutionFunctionalities courseExecutionFunctionalities
    @Autowired
    private ExecutionFunctionalities quizFunctionalities
    @Autowired
    private QuizAnswerFunctionalities quizAnswerFunctionalities

    private CourseExecutionDto courseExecutionDto
    private UserDto userDto1, userDto2
    private QuizDto quizDto

    def setup() {
        loadBehaviorScripts()

        courseExecutionDto = createCourseExecution(COURSE_EXECUTION_NAME, COURSE_EXECUTION_TYPE, COURSE_EXECUTION_ACRONYM, COURSE_EXECUTION_ACADEMIC_TERM, TIME_4)

        userDto1 = createUser(USER_NAME_1, USER_USERNAME_1, STUDENT_ROLE)
        courseExecutionFunctionalities.addStudent(courseExecutionDto.getAggregateId(), userDto1.getAggregateId())

        userDto2 = createUser(USER_NAME_2, USER_USERNAME_2, STUDENT_ROLE)
        courseExecutionFunctionalities.addStudent(courseExecutionDto.getAggregateId(), userDto2.getAggregateId())

        def topicDto = createTopic(courseExecutionDto, TOPIC_NAME_1)
        QuestionDto questionDto = createQuestion(courseExecutionDto, new HashSet<>(Arrays.asList(topicDto)), TITLE_1, CONTENT_1, OPTION_1, OPTION_2)

        def quizDtoInput = new QuizDto()
        quizDtoInput.setTitle(TITLE_1)
        quizDtoInput.setAvailableDate(DateHandler.toISOString(TIME_1))
        quizDtoInput.setConclusionDate(DateHandler.toISOString(TIME_2))
        quizDtoInput.setResultsDate(DateHandler.toISOString(TIME_3))
        quizDtoInput.setQuestionDtos(Arrays.asList(questionDto))
        quizDto = quizFunctionalities.createQuiz(courseExecutionDto.getAggregateId(), quizDtoInput)

        // consumes CSV block 1 (no fault): first student starts the quiz successfully
        quizAnswerFunctionalities.startQuiz(quizDto.getAggregateId(), courseExecutionDto.getAggregateId(), userDto1.getAggregateId())
    }

    def cleanup() {
        impairmentService.cleanUpCounter()
        impairmentService.cleanDirectory()
    }

    def "saga compensation releases the quiz lock when startQuizStep fails"() {
        // getQuizStep reads the quiz (merged into the execution aggregate) and is a root step; startQuizStep depends on
        // both getQuizStep and getUserStep and is where the fault is injected (CSV block 2). Under the
        // old fixed two-pass ExecutionPlan, startQuizStep's fault check ran synchronously in
        // registration order in the same pass that merely registered getQuizStep's/getUserStep's
        // futures, racing their real async bodies. The topological worklist fix gates startQuizStep's
        // fault check on both dependencies' real completion.
        when: 'a second student starts the same quiz with a fault injected on startQuizStep'
        quizAnswerFunctionalities.startQuiz(quizDto.getAggregateId(), courseExecutionDto.getAggregateId(), userDto2.getAggregateId())

        then: 'the injected fault surfaces to the caller'
        thrown(SimulatorException)

        and: 'the execution aggregate that owns the quiz is not left locked'
        def unitOfWork = unitOfWorkService.createUnitOfWork("TEST")
        def quizExecution = (SagaExecution) unitOfWorkService.aggregateLoadAndRegisterRead(courseExecutionDto.getAggregateId(), unitOfWork)
        quizExecution.sagaState == GenericSagaState.NOT_IN_SAGA
    }

    @TestConfiguration
    static class LocalBeanConfiguration extends BeanConfigurationSagas {}
}
