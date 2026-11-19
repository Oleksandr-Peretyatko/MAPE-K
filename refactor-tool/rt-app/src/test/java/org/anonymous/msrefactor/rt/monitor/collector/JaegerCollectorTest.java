package org.anonymous.msrefactor.rt.monitor.collector;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.anonymous.msrefactor.rt.topology.ComposeTopologyResolver;
import org.anonymous.msrefactor.rt.topology.DeployService;
import org.anonymous.msrefactor.rt.topology.DeployTopology;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Service discovery decides what the whole Monitor phase measures: every name returned here becomes
 * an {@code aggregateMetrics} entry, and from there a hotspot candidate, a QA input and a line in
 * the AI recommender's prompt.
 *
 * <p>Not every OTel {@code service.name} is a deployment unit — the simulator emits a synthetic
 * {@code root} service — so the deployed topology is the allowlist rather than a denylist of names.
 */
class JaegerCollectorTest {

    private static final String JAEGER_URL = "http://jaeger:16686";

    private static final List<String> DEPLOYED = List.of(
            "answer", "course", "execution", "question",
            "quiz", "topic", "tournament", "user");

    private RestTemplate restTemplate;
    private ComposeTopologyResolver topologyResolver;
    private JaegerCollector collector;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        topologyResolver = mock(ComposeTopologyResolver.class);
        collector = new JaegerCollector(restTemplate, topologyResolver);

        ReflectionTestUtils.setField(collector, "jaegerBaseUrl", JAEGER_URL);
        ReflectionTestUtils.setField(collector, "knownServicesSeed", List.of("seed-a", "seed-b"));
        ReflectionTestUtils.setField(collector, "excludedServices",
                List.of("jaeger-all-in-one", "jaeger"));
    }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private void topologyOf(List<String> jaegerNames) {
        List<DeployService> services = jaegerNames.stream()
                .map(n -> new DeployService(n + "-service", n + "-service", n + "-service",
                        n, List.of(), true))
                .toList();
        when(topologyResolver.current()).thenReturn(new DeployTopology(
                "docker-compose.yml", "quizzes:latest", services,
                Instant.EPOCH, DeployTopology.Source.COMPOSE));
    }

    private void jaegerReports(String... names) {
        JaegerCollector.ServicesResponse response = new JaegerCollector.ServicesResponse();
        response.setData(List.of(names));
        when(restTemplate.getForObject(anyString(), any())).thenReturn(response);
    }

    // ─── allowlist mode ───────────────────────────────────────────────────────

    @Test
    @DisplayName("a synthetic name Jaeger reports but compose does not deploy is dropped")
    void syntheticServiceIsNotAService() {
        topologyOf(DEPLOYED);
        jaegerReports("answer", "course", "execution", "question",
                "quiz", "topic", "tournament", "user", "root", "jaeger-all-in-one");

        assertThat(collector.collectServices()).containsExactlyElementsOf(DEPLOYED);
    }

    @Test
    @DisplayName("a service that is deployed but has not yet reported spans is still collected")
    void deployedButSilentServiceSurvives() {
        // buildServiceMetrics seeds an empty entry per discovered name, so a service that produced
        // no traffic in the window must still appear — otherwise it vanishes from the snapshot.
        topologyOf(DEPLOYED);
        jaegerReports("answer", "course");

        assertThat(collector.collectServices()).containsExactlyElementsOf(DEPLOYED);
    }

    @Test
    @DisplayName("the allowlist follows the topology, so a merged-away service stops being queried")
    void topologyChangeIsPickedUp() {
        // After MergeAggregates(course, question) the compose file has no question-service, but
        // Jaeger still holds its spans for the rest of the retention window.
        List<String> afterMerge = List.of(
                "answer", "course", "execution", "quiz", "topic", "tournament", "user");
        topologyOf(afterMerge);
        jaegerReports("answer", "course", "execution", "question",
                "quiz", "topic", "tournament", "user", "root");

        assertThat(collector.collectServices())
                .containsExactlyElementsOf(afterMerge)
                .doesNotContain("question", "root");
    }


    // ─── fallback mode ────────────────────────────────────────────────────────

    @Test
    @DisplayName("with no topology there is no allowlist, so discovery still defines the set")
    void emptyTopologyFallsBackToUnion() {
        // A resolver failure must degrade to the old behaviour, not to a blank snapshot.
        when(topologyResolver.current()).thenReturn(new DeployTopology(
                "docker-compose.yml", "quizzes:latest", List.of(),
                Instant.EPOCH, DeployTopology.Source.FALLBACK));
        jaegerReports("course", "root", "jaeger-all-in-one");

        assertThat(collector.collectServices())
                .containsExactly("seed-a", "seed-b", "course", "root")
                .doesNotContain("jaeger-all-in-one");
    }

    // ─── reachability ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("an unreachable Jaeger yields the topology and flags the failure")
    void unreachableJaegerKeepsTopology() {
        topologyOf(DEPLOYED);
        when(restTemplate.getForObject(anyString(), any()))
                .thenThrow(new RestClientException("connection refused"));

        assertThat(collector.collectServices()).containsExactlyElementsOf(DEPLOYED);
    }

    @Test
    @DisplayName("an empty Jaeger response leaves the topology intact")
    void nullResponseIsTolerated() {
        topologyOf(DEPLOYED);
        when(restTemplate.getForObject(anyString(), any())).thenReturn(null);

        assertThat(collector.collectServices()).containsExactlyElementsOf(DEPLOYED);
    }
}
