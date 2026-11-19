package org.anonymous.msrefactor.rt.monitor.collector;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Creation endpoints, captured with their path variables intact.
 *
 * <p>Pass 5A rewrites every variable to <code>{id}</code> so Locust rows can be matched, which
 * destroys the only thing that says <em>whose</em> scope a resource is created in. And its pattern
 * requires the bare-string form, so the two controllers that write {@code value = "…"} —
 * {@code TournamentController} and {@code ExecutionController} — are invisible to it today.
 */
class PostEndpointCaptureTest {

    private StaticAnalysisCollector collector;

    @BeforeEach
    void setUp() {
        collector = new StaticAnalysisCollector();
    }

    private List<String> pathsOf(String controller) {
        List<String> paths = new ArrayList<>();
        collector.extractPostPaths(controller, paths);
        return paths;
    }

    @Test
    @DisplayName("the bare-string form keeps the variable name")
    void bareStringForm() {
        assertThat(pathsOf("""
                @RestController
                public class TopicController {
                    @PostMapping("/courses/{courseAggregateId}/create")
                    public TopicDto createTopic(@PathVariable Integer courseAggregateId) { }
                }
                """))
                .containsExactly("/courses/{courseAggregateId}/create");
    }

    @Test
    @DisplayName("the value = form is captured too — Pass 5A misses it")
    void valueForm() {
        assertThat(pathsOf("""
                @RestController
                public class TournamentController {
                    @PostMapping(value = "/executions/{executionId}/tournaments/create")
                    public TournamentDto createTournament(@PathVariable Integer executionId) { }

                    @PostMapping(value = "/tournaments/update")
                    public TournamentDto updateTournament() { }
                }
                """))
                .containsExactly("/executions/{executionId}/tournaments/create", "/tournaments/update");
    }

    @Test
    @DisplayName("a repeated path is recorded once, and GET is not a creation scope")
    void deduplicatesAndIgnoresOtherVerbs() {
        assertThat(pathsOf("""
                public class QuizController {
                    @GetMapping("/quizzes/{quizAggregateId}")
                    public QuizDto get() { }

                    @PostMapping("/executions/{courseExecutionId}")
                    public QuizDto create() { }

                    @PostMapping("/executions/{courseExecutionId}")
                    public QuizDto createAgain() { }
                }
                """))
                .containsExactly("/executions/{courseExecutionId}");
    }
}
