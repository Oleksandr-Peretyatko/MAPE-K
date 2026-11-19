package org.anonymous.msrefactor.rt.monitor.collector;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reading the publisher side of an event, which the collector never did before.
 *
 * <p>Everywhere else the publisher is inferred from a subscription's <i>anchor</i> — a different
 * question, and one that gets {@code UpdateStudentNameEvent} wrong: it is anchored on the course
 * execution and so attributed to {@code execution} whether or not that is where it is registered.
 */
class EventPublicationTest {

    private StaticAnalysisCollector collector;

    @BeforeEach
    void setUp() {
        collector = new StaticAnalysisCollector();
    }

    @Test
    @DisplayName("the publication is found when it spans two lines")
    void findsPublicationsSplitAcrossLines() {
        // 5 of the 12 call sites in the reference application look like this. A line-oriented search
        // reports those five events as never published — plausible enough to be believed, and wrong.
        Map<String, String> published = collector.publishedEvents(Map.of("ExecutionService", """
                @Service
                public class ExecutionService {
                    public void removeStudentFromCourseExecution(Integer id, UnitOfWork unitOfWork) {
                        unitOfWorkService.registerEvent(
                                new DisenrollStudentFromCourseExecutionEvent(id, userId), unitOfWork);
                    }
                }
                """));

        assertThat(published)
                .containsEntry("DisenrollStudentFromCourseExecutionEvent",
                        "ExecutionService.removeStudentFromCourseExecution");
    }

    @Test
    @DisplayName("each publication is attributed to the method it sits in")
    void attributesEachEventToItsMethod() {
        Map<String, String> published = collector.publishedEvents(Map.of("ExecutionService", """
                @Service
                public class ExecutionService {
                    public void removeCourseExecution(Integer id, UnitOfWork unitOfWork) {
                        unitOfWorkService.registerEvent(new DeleteCourseExecutionEvent(id), unitOfWork);
                    }

                    public void anonymizeStudent(Integer id, UnitOfWork unitOfWork) {
                        unitOfWorkService.registerEvent(
                                new AnonymizeStudentEvent(id, "ANONYMOUS", "ANONYMOUS", userId), unitOfWork);
                    }
                }
                """));

        assertThat(published).containsExactlyInAnyOrderEntriesOf(Map.of(
                "DeleteCourseExecutionEvent", "ExecutionService.removeCourseExecution",
                "AnonymizeStudentEvent", "ExecutionService.anonymizeStudent"));
    }

    @Test
    @DisplayName("an event registered from two methods is attributed to the first")
    void keepsTheFirstOfSeveralCallSites() {
        // ExecutionService really does publish DisenrollStudentFromCourseExecutionEvent twice. Both
        // answers agree on the aggregate, which is the question this feeds.
        Map<String, String> published = collector.publishedEvents(Map.of("ExecutionService", """
                public class ExecutionService {
                    public void removeStudent(Integer id, UnitOfWork unitOfWork) {
                        unitOfWorkService.registerEvent(new DisenrollStudentFromCourseExecutionEvent(id), unitOfWork);
                    }

                    public void removeUser(Integer id, UnitOfWork unitOfWork) {
                        unitOfWorkService.registerEvent(new DisenrollStudentFromCourseExecutionEvent(id), unitOfWork);
                    }
                }
                """));

        assertThat(published).containsExactly(Map.entry(
                "DisenrollStudentFromCourseExecutionEvent", "ExecutionService.removeStudent"));
    }

    @Test
    @DisplayName("a class that registers nothing publishes nothing")
    void aServiceWithNoPublicationIsEmpty() {
        Map<String, String> published = collector.publishedEvents(Map.of("CourseService", """
                @Service
                public class CourseService {
                    public CourseDto getCourse(Integer id, UnitOfWork unitOfWork) {
                        return courseFactory.createCourseDto(load(id, unitOfWork));
                    }
                }
                """));

        assertThat(published).isEmpty();
    }

    @Test
    @DisplayName("a publication outside any recognised method keeps the class alone")
    void fallsBackToTheClassWhenTheMethodIsUnknown() {
        // Best effort by design: attributing it to a guessed method would be worse than saying less.
        Map<String, String> published = collector.publishedEvents(Map.of("OddService", """
                public class OddService {
                    { unitOfWorkService.registerEvent(new DeleteUserEvent(id), unitOfWork); }
                }
                """));

        assertThat(published).containsEntry("DeleteUserEvent", "OddService");
    }
}
