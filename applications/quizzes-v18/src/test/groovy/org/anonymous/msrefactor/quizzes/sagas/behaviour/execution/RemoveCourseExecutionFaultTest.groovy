package org.anonymous.msrefactor.quizzes.sagas.behaviour.execution

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.anonymous.msrefactor.ms.exception.SimulatorException
import org.anonymous.msrefactor.quizzes.BeanConfigurationSagas
import org.anonymous.msrefactor.quizzes.QuizzesSpockTest
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.CourseExecutionDto
import org.anonymous.msrefactor.quizzes.microservices.execution.coordination.functionalities.ExecutionFunctionalities

@DataJpaTest
class RemoveCourseExecutionFaultTest extends QuizzesSpockTest {
    @Autowired
    private ExecutionFunctionalities courseExecutionFunctionalities

    private CourseExecutionDto courseExecutionDto
    private CourseExecutionDto courseExecutionDto2

    def setup() {
        loadBehaviorScripts()
        // Two executions for the same course so the removal is domain-valid
        courseExecutionDto = createCourseExecution(COURSE_EXECUTION_NAME, COURSE_EXECUTION_TYPE, COURSE_EXECUTION_ACRONYM, COURSE_EXECUTION_ACADEMIC_TERM, TIME_4)
        courseExecutionDto2 = createCourseExecution(COURSE_EXECUTION_NAME, COURSE_EXECUTION_TYPE, COURSE_EXECUTION_ACRONYM + '_2', COURSE_EXECUTION_ACADEMIC_TERM, TIME_4)
    }

    def cleanup() {
        impairmentService.cleanDirectory()
    }

    def "course execution still exists when getCourseExecutionStep fails"() {
        when:
        courseExecutionFunctionalities.removeCourseExecution(courseExecutionDto.getAggregateId())

        then:
        thrown(SimulatorException)

        and: 'the course execution is still listed'
        def executions = courseExecutionFunctionalities.getCourseExecutions()
        executions.find { it.aggregateId == courseExecutionDto.aggregateId } != null
    }

    def "course execution still exists when removeCourseExecutionStep fails"() {
        when:
        courseExecutionFunctionalities.removeCourseExecution(courseExecutionDto.getAggregateId())

        then:
        thrown(SimulatorException)

        and: 'the course execution is still listed'
        def executions = courseExecutionFunctionalities.getCourseExecutions()
        executions.find { it.aggregateId == courseExecutionDto.aggregateId } != null
    }

    @TestConfiguration
    static class LocalBeanConfiguration extends BeanConfigurationSagas {}
}
