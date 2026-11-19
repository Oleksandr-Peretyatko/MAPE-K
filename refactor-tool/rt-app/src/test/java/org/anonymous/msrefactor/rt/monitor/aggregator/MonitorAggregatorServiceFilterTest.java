package org.anonymous.msrefactor.rt.monitor.aggregator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.anonymous.msrefactor.rt.monitor.AggregateMetrics;
import org.anonymous.msrefactor.rt.monitor.web.dto.JaegerCollectionResult;
import org.anonymous.msrefactor.rt.monitor.collector.JaegerCollector.SpanSummary;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which names end up in {@code aggregateMetrics} decides what the rest of the loop treats as a
 * service — {@code discoveredServices}, hotspot candidates, QA inputs.
 *
 * <p>The keys come from the <b>spans</b>, not from the discovered-service list: Jaeger is queried
 * per service but returns whole traces, and every span is labelled with its own producer. So a
 * trace fetched for {@code quiz} also carries the simulator's synthetic {@code root} span. Filtering
 * service <i>discovery</i> alone never removed it — this is where it has to happen.
 */
class MonitorAggregatorServiceFilterTest {

    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final Instant AT = Instant.parse("2026-08-14T10:55:41Z");

    private final MonitorAggregator aggregator = newAggregator();

    private static MonitorAggregator newAggregator() {
        MonitorAggregator a = new MonitorAggregator();
        // @Value field; unset in a plain instantiation.
        ReflectionTestUtils.setField(a, "sagaRootSuffixes",
                List.of("FunctionalitySagas", "Functionality"));
        return a;
    }

    private static SpanSummary span(String service, String operation, long durationUs) {
        return SpanSummary.builder()
                .traceId("t1")
                .spanId(service + "-" + operation)
                .operationName(operation)
                .serviceName(service)
                .durationUs(durationUs)
                .build();
    }

    /** The scaffolding span TraceManager opens per service; stays open for the whole functionality. */
    private static SpanSummary rootSpan(String service, long durationUs) {
        return SpanSummary.builder()
                .traceId("t1")
                .spanId(service + "-root")
                .operationName(service + "-root7")
                .serviceName(service)
                .durationUs(durationUs)
                .isServiceRoot(true)
                .build();
    }

    private static SpanSummary child(String service, String operation, String parentSpanId,
                                     long durationUs) {
        return SpanSummary.builder()
                .traceId("t1")
                .spanId(service + "-" + operation)
                .parentSpanId(parentSpanId)
                .operationName(operation)
                .serviceName(service)
                .durationUs(durationUs)
                .build();
    }

    private JaegerCollectionResult aggregate(List<SpanSummary> spans, List<String> services) {
        return aggregator.aggregateJaeger(spans, services, WINDOW, AT, (int) WINDOW.toMinutes());
    }

    @Test
    @DisplayName("a span from a service that is not deployed does not become a service")
    void syntheticServiceIsDropped() {
        // `root` is emitted by TraceManager's second TracerProvider purely to parent each service's
        // own root span. It has no compose block, no database and no source to refactor.
        List<SpanSummary> spans = List.of(
                span("quiz", "CreateQuiz", 1_000),
                span("course", "GetCourseById", 2_000),
                span("root", "root7", 29_711_000));

        JaegerCollectionResult snapshot = aggregate(spans, List.of("quiz", "course"));

        assertThat(snapshot.getAggregateMetrics()).containsOnlyKeys("quiz", "course");
    }

    @Test
    @DisplayName("dropping it takes its latency out of the per-service numbers with it")
    void syntheticLatencyDoesNotLeak() {
        // The master root span stays open for a whole functionality, so it dwarfs real operations.
        // Left in, it defined the max() that QualityAttributeCalculator reads as the PERFORMANCE p95.
        List<SpanSummary> spans = List.of(
                span("quiz", "CreateQuiz", 1_000),
                span("root", "root7", 29_711_000));

        JaegerCollectionResult snapshot = aggregate(spans, List.of("quiz"));

        double maxP95 = snapshot.getAggregateMetrics().values().stream()
                .mapToDouble(m -> m.getP95LatencyMs()).max().orElseThrow();
        assertThat(maxP95).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a deployed service that produced no spans still reports, so it cannot vanish")
    void silentServiceIsRetained() {
        JaegerCollectionResult snapshot = aggregate(
                List.of(span("quiz", "CreateQuiz", 1_000)),
                List.of("quiz", "topic"));

        assertThat(snapshot.getAggregateMetrics()).containsOnlyKeys("quiz", "topic");
        assertThat(snapshot.getAggregateMetrics().get("topic").getTotalSpanCount()).isZero();
    }

    @Test
    @DisplayName("with no known services nothing is filtered — a failed resolve must degrade, not erase")
    void emptyServiceListDisablesFiltering() {
        List<SpanSummary> spans = List.of(
                span("quiz", "CreateQuiz", 1_000),
                span("root", "root7", 29_711_000));

        JaegerCollectionResult snapshot = aggregate(spans, List.of());

        assertThat(snapshot.getAggregateMetrics()).containsOnlyKeys("quiz", "root");
    }

    // ─── latency is work, not scaffolding ─────────────────────────────────────

    @Test
    @DisplayName("a service root span is excluded from latency but still counted as traffic")
    void serviceRootDoesNotDefineLatency() {
        // The bug this replaces: TraceManager's per-service root span stays open for the whole
        // functionality, so percentiling over it reported workflow duration once per service —
        // every service showed ~29.7 s within 1 ms of the others.
        JaegerCollectionResult snapshot = aggregate(
                List.of(rootSpan("quiz", 29_711_000), span("quiz", "CreateQuiz", 12_000)),
                List.of("quiz"));

        AggregateMetrics quiz = snapshot.getAggregateMetrics().get("quiz");
        assertThat(quiz.getP95LatencyMs()).isEqualTo(12.0);
        assertThat(quiz.getTotalSpanCount()).isEqualTo(2);
        assertThat(quiz.getMeasuredSpanCount()).isEqualTo(1);
        assertThat(quiz.getLatencySource()).isEqualTo("jaeger");
    }

    @Test
    @DisplayName("self time excludes what the span was blocked on downstream")
    void selfTimeExcludesChildren() {
        // A caller that waits 70 ms on a callee did 30 ms of its own work. Charging it the full
        // 100 ms would blame the caller for its dependency's slowness.
        JaegerCollectionResult snapshot = aggregate(
                List.of(span("quiz", "CreateQuiz", 100_000),
                        child("question", "GetQuestion", "quiz-CreateQuiz", 70_000)),
                List.of("quiz", "question"));

        assertThat(snapshot.getAggregateMetrics().get("quiz").getP95LatencyMs()).isEqualTo(30.0);
        assertThat(snapshot.getAggregateMetrics().get("question").getP95LatencyMs()).isEqualTo(70.0);
    }

    @Test
    @DisplayName("only scaffolding means latency was not measured, not that it is zero")
    void noWorkSpansReportsNotMeasured() {
        // This is the state the live traces are actually in, and 0 ms would normalise to a perfect
        // score — absent telemetry must not read as the best possible result.
        JaegerCollectionResult snapshot = aggregate(
                List.of(rootSpan("quiz", 29_711_000)), List.of("quiz"));

        AggregateMetrics quiz = snapshot.getAggregateMetrics().get("quiz");
        assertThat(quiz.getMeasuredSpanCount()).isZero();
        assertThat(quiz.getTotalSpanCount()).isEqualTo(1);
        assertThat(quiz.getLatencySource()).isEqualTo("none");
    }

    @Test
    @DisplayName("concurrent children cannot drive self time negative")
    void selfTimeClampsAtZero() {
        JaegerCollectionResult snapshot = aggregate(
                List.of(span("quiz", "CreateQuiz", 50_000),
                        child("question", "A", "quiz-CreateQuiz", 40_000),
                        child("topic", "B", "quiz-CreateQuiz", 40_000)),
                List.of("quiz", "question", "topic"));

        assertThat(snapshot.getAggregateMetrics().get("quiz").getP95LatencyMs()).isZero();
    }

    @Test
    @DisplayName("saga metrics still see the whole trace, including spans from filtered services")
    void sagaMetricsUseUnfilteredSpans() {
        // buildSagaMetrics works off the raw span list and walks parent links, so restricting the
        // per-service map must not remove spans from the trace it reasons over.
        List<SpanSummary> spans = List.of(
                span("root", "root7", 29_711_000),
                SpanSummary.builder().traceId("t1").spanId("f1").operationName("CreateQuizFunctionality::1")
                        .serviceName("quiz").durationUs(5_000).isFunctionalitySpan(true).build());

        JaegerCollectionResult snapshot = aggregate(spans, List.of("quiz"));

        assertThat(snapshot.getAggregateMetrics()).containsOnlyKeys("quiz");
        assertThat(snapshot.getSagaMetrics()).isNotEmpty();
    }

    // ─── Monolith expansion ──────────────────────────────────────────────────────

    @Test
    @DisplayName("a merged microservice is not split back into the aggregate it absorbed")
    void mergedServiceIsNotExpanded() {
        // After merging question into course, course-service runs both aggregates' steps.
        List<SpanSummary> spans = List.of(
                span("course", "CreateQuestionStep", 20_000),
                span("course", "GetCourseByIdStep", 10_000),
                span("quiz", "CreateQuizStep", 30_000));

        JaegerCollectionResult snap = aggregate(spans, List.of("course", "quiz"));

        assertThat(snap.getAggregateMetrics()).containsOnlyKeys("course", "quiz");
    }

    @Test
    @DisplayName("a monolith deployment is still expanded into per-aggregate entries")
    void monolithIsExpanded() {
        List<SpanSummary> spans = List.of(
                span("quizzes", "CreateQuestionStep", 20_000),
                span("quizzes", "GetCourseByIdStep", 10_000));

        JaegerCollectionResult snap = aggregate(spans, List.of("quizzes"));

        assertThat(snap.getAggregateMetrics()).containsOnlyKeys("question-service", "course-service");
    }
}
