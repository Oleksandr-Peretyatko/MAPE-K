package org.anonymous.msrefactor.rt.monitor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.anonymous.msrefactor.rt.execute.config.ExecuteConfig;
import org.anonymous.msrefactor.rt.execute.persistence.ExecuteRefactorEntity;
import org.anonymous.msrefactor.rt.execute.persistence.ExecuteRefactorRepository;
import org.anonymous.msrefactor.rt.knowledge.persistence.CodeVersionEntity;
import org.anonymous.msrefactor.rt.knowledge.persistence.LoopIterationEntity;
import org.anonymous.msrefactor.rt.knowledge.persistence.LoopIterationRepository;
import org.anonymous.msrefactor.rt.knowledge.service.CodeVersionService;
import org.anonymous.msrefactor.rt.knowledge.service.ProjectStructureService;
import org.anonymous.msrefactor.rt.monitor.StaticMetrics;
import org.anonymous.msrefactor.rt.monitor.VersionAnalysisResult;
import org.anonymous.msrefactor.rt.monitor.web.dto.JaegerCollectionRequest;
import org.anonymous.msrefactor.rt.topology.ComposeTopologyResolver;
import org.anonymous.msrefactor.rt.topology.DeployService;
import org.anonymous.msrefactor.rt.topology.DeployTopology;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Monitor measures the iteration's bound version (falling back to the DB's deployed record) with
 * the topology it was deployed with — never the topology cache or the shared working tree, which
 * reflected whatever branch was checked out and made a snapshot of v1 (question merged into
 * course) still list question.
 */
class MeasuredVersionResolverTest {

    private static final List<String> V0_SERVICES = List.of("course-service", "question-service", "quiz-service");
    private static final List<String> V1_SERVICES = List.of("course-service", "quiz-service");

    private CodeVersionService codeVersionService;
    private LoopIterationRepository iterations;
    private ExecuteRefactorRepository executions;
    private ProjectStructureService structure;
    private ComposeTopologyResolver topologyResolver;
    private MeasuredVersionResolver resolver;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private final Instant deployedAt = Instant.parse("2026-09-30T11:51:42Z");
    private CodeVersionEntity v1;

    private static DeployTopology topology(List<String> names) {
        return new DeployTopology("compose.yml", "quizzes:latest",
                names.stream().map(n -> new DeployService(n, n, n, n.replace("-service", ""), List.of(8080), true)).toList(),
                Instant.now(), DeployTopology.Source.COMPOSE);
    }

    @BeforeEach
    void setUp() throws Exception {
        codeVersionService = mock(CodeVersionService.class);
        iterations = mock(LoopIterationRepository.class);
        executions = mock(ExecuteRefactorRepository.class);
        structure = mock(ProjectStructureService.class);
        topologyResolver = mock(ComposeTopologyResolver.class);
        // The working tree is on the pre-merge branch: the resolver would still see question.
        when(topologyResolver.current()).thenReturn(topology(V0_SERVICES));
        when(topologyResolver.resolve(anyString(), anyBoolean(), any())).thenReturn(topology(V0_SERVICES));

        ExecuteConfig config = new ExecuteConfig();
        config.setDeployTarget("auto");
        resolver = new MeasuredVersionResolver(codeVersionService, iterations, executions, structure,
                topologyResolver, config, mapper);

        v1 = CodeVersionEntity.builder().versionId("cv-1").versionLabel("v1").versionNumber(1)
                .gitCommitSha("cc33dd44").producingExecutionId("exec-1").deployedAt(deployedAt).build();
        // Serialized exactly as ExecuteService.recordTopology stores it, derived getters included.
        ExecuteRefactorEntity exec = ExecuteRefactorEntity.builder().executeRefactorId("exec-1")
                .deployedTopology(mapper.writeValueAsString(topology(V1_SERVICES)))
                .deployedServices(mapper.writeValueAsString(V1_SERVICES)).build();
        when(executions.findById("exec-1")).thenReturn(Optional.of(exec));
    }

    @Test
    @DisplayName("the deployed version's recorded topology is used, not the stale resolver cache")
    void usesRecordedTopologyOfDeployedVersion() {
        when(codeVersionService.findActiveOrBaseline()).thenReturn(Optional.of(v1));

        MeasuredVersionResolver.Measured m = resolver.resolve(null);

        assertThat(m.label()).isEqualTo("v1");
        assertThat(m.topology().serviceNames()).containsExactlyElementsOf(V1_SERVICES);
        assertThat(m.topology().jaegerServiceNames()).doesNotContain("question");
        assertThat(m.deployedAt()).isEqualTo(deployedAt);
        verify(topologyResolver, never()).current();
    }

    @Test
    @DisplayName("names-only record still works when the full topology is missing")
    void fallsBackToRecordedServiceNames() throws Exception {
        when(codeVersionService.findActiveOrBaseline()).thenReturn(Optional.of(v1));
        when(executions.findById("exec-1")).thenReturn(Optional.of(ExecuteRefactorEntity.builder()
                .executeRefactorId("exec-1").deployedServices(mapper.writeValueAsString(V1_SERVICES)).build()));

        assertThat(resolver.resolve(null).topology().jaegerServiceNames()).containsExactly("course", "quiz");
    }

    @Test
    @DisplayName("the baseline has no deploy record, so the topology is resolved fresh — never from cache")
    void baselineResolvesFresh() {
        CodeVersionEntity baseline = CodeVersionEntity.builder().versionId("cv-0").versionLabel("baseline").build();
        when(codeVersionService.findActiveOrBaseline()).thenReturn(Optional.of(baseline));

        resolver.resolve(null);

        verify(topologyResolver).resolve(eq("auto"), eq(true), any());
        verify(topologyResolver, never()).current();
    }

    @Test
    @DisplayName("static metrics come from the version's own source and are stored as 'current'")
    void staticFromVersionSource() {
        when(codeVersionService.findActiveOrBaseline()).thenReturn(Optional.of(v1));
        VersionAnalysisResult byCommit = VersionAnalysisResult.builder()
                .version("v1").path("git:cc33dd44").metrics(StaticMetrics.builder().build()).build();
        when(structure.resolveSource(v1)).thenReturn(new ProjectStructureService.Resolved(byCommit, "commit cc33dd44"));

        VersionAnalysisResult r = resolver.staticVersion(resolver.resolve(null)).orElseThrow();

        assertThat(r.getVersion()).isEqualTo("current");
        assertThat(r.getPath()).contains("v1").contains("commit cc33dd44");
        assertThat(r.getMetrics()).isSameAs(byCommit.getMetrics());
    }

    @Test
    @DisplayName("the iteration's bound version is measured even when the DB records another as deployed")
    void measuresIterationBoundVersionOverDbActive() {
        CodeVersionEntity baseline = CodeVersionEntity.builder().versionId("cv-0").versionLabel("baseline").build();
        // v18 stayed ACTIVE from an old campaign while the operator manually redeployed the
        // baseline: the DB's record must not override the version the iteration was created from.
        when(codeVersionService.findActiveOrBaseline()).thenReturn(Optional.of(v1));
        when(iterations.findById("iter-2")).thenReturn(Optional.of(LoopIterationEntity.builder()
                .iterationId("iter-2").codeVersionId("cv-0").build()));
        when(codeVersionService.findById("cv-0")).thenReturn(Optional.of(baseline));

        assertThat(resolver.resolve("iter-2").label()).isEqualTo("baseline");
    }

    @Test
    @DisplayName("an iteration with no bound version falls back to the DB's deployed record")
    void unboundIterationFallsBackToActive() {
        when(codeVersionService.findActiveOrBaseline()).thenReturn(Optional.of(v1));
        when(iterations.findById("iter-3")).thenReturn(Optional.of(LoopIterationEntity.builder()
                .iterationId("iter-3").build()));

        assertThat(resolver.resolve("iter-3").label()).isEqualTo("v1");
    }

    // ─── Jaeger window ───────────────────────────────────────────────────────

    @Test
    @DisplayName("the Jaeger window starts at the deploy when the deploy is inside it")
    void jaegerWindowClampedToDeploy() {
        Instant recentDeploy = Instant.now().minus(Duration.ofMinutes(5));

        JaegerCollectionRequest req = MonitorPipelineService.jaegerWindow(15, recentDeploy, "snap");

        assertThat(req.getWindowStartMs()).isEqualTo(recentDeploy.toEpochMilli());
        assertThat(req.getWindowEndMs()).isGreaterThan(req.getWindowStartMs());
    }

    @Test
    @DisplayName("an older deploy leaves the configured window untouched")
    void jaegerWindowUnclampedForOldDeploy() {
        Instant oldDeploy = Instant.now().minus(Duration.ofHours(3));

        JaegerCollectionRequest req = MonitorPipelineService.jaegerWindow(15, oldDeploy, "snap");

        long spanMs = req.getWindowEndMs() - req.getWindowStartMs();
        assertThat(spanMs).isBetween(Duration.ofMinutes(15).toMillis() - 1_000, Duration.ofMinutes(15).toMillis() + 1_000);
    }
}
