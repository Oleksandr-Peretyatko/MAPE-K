package org.anonymous.msrefactor.rt.quality;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.anonymous.msrefactor.rt.analyze.QualityAttribute;
import org.anonymous.msrefactor.rt.monitor.FunctionalityInfo;
import org.anonymous.msrefactor.rt.monitor.AggregateMetrics;
import org.anonymous.msrefactor.rt.monitor.LocustScenarioMetrics;
import org.anonymous.msrefactor.rt.monitor.ServiceStaticMetrics;
import org.anonymous.msrefactor.rt.monitor.StaticMetrics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The acceptance cases of the metric redesign: a merge or a split must move the static scores,
 * and in the direction the operation's intent predicts. Each case is a before/after pair of
 * snapshots built the way the collector would see the code after the operation.
 *
 * <p>Fixture: four services. {@code quiz} (3 entities) owns {@code question} (3 entities) — every
 * workflow that touches one touches the other and each holds a replica of the other.
 * {@code tournament} (6 entities) is used by two disjoint groups of workflows, three entities each.
 * {@code user} (1 entity) stands alone.
 */
class MetricSensitivityTest {

    private QualityAttributeCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new QualityAttributeCalculator(new MetricNormalizer());
    }

    /** No saga metrics: the production method takes them as its 4th argument. */
    private List<QualityAttributeScore> calculateAll(Map<String, AggregateMetrics> jaeger,
                                                     List<LocustScenarioMetrics> locust, StaticMetrics statics) {
        return calculator.calculateAll(jaeger, locust, statics, Map.of());
    }

    @Test
    @DisplayName("merging an owned pair whose workflows always use both keeps cohesion and raises coupling, consistency and autonomy")
    void mergeOwnedPair_improvesStaticScores() {
        Map<QualityAttribute, Double> before = scores(baseline());
        Map<QualityAttribute, Double> after = scores(mergeQuizQuestion());

        // The merged aggregate is as cohesive as its parts were. The system mean can still drift
        // by a hair: two perfectly cohesive aggregates became one, so the less cohesive
        // tournament weighs more in the average — a composition effect, not a loss.
        ServiceStaticMetrics merged = org.anonymous.msrefactor.rt.monitor.StructuralSignals
                .enrich(mergeQuizQuestion()).getServices().get("quiz");
        assertThat(merged.getFunctionalCohesion()).isEqualTo(1.0);
        assertThat(merged.getClusterFactor()).isEqualTo(1.0);
        assertThat(after.get(QualityAttribute.COHESION)).isGreaterThan(before.get(QualityAttribute.COHESION) - 0.02);
        assertThat(after.get(QualityAttribute.COUPLING)).isGreaterThan(before.get(QualityAttribute.COUPLING) + 0.03);
        assertThat(after.get(QualityAttribute.CONSISTENCY)).isGreaterThan(before.get(QualityAttribute.CONSISTENCY) + 0.03);
        assertThat(after.get(QualityAttribute.AUTONOMY)).isGreaterThan(before.get(QualityAttribute.AUTONOMY) + 0.03);
    }

    @Test
    @DisplayName("merging a pair whose workflows never use both lowers cohesion even though coupling improves")
    void mergeUnrelatedPair_lowersCohesion() {
        Map<QualityAttribute, Double> before = scores(baseline());
        Map<QualityAttribute, Double> after = scores(mergeTournamentUser());

        assertThat(after.get(QualityAttribute.COHESION)).isLessThan(before.get(QualityAttribute.COHESION));
    }

    @Test
    @DisplayName("splitting an aggregate along disjoint use raises cohesion and lowers coupling and autonomy")
    void splitByDisjointUse_tradesCouplingForCohesion() {
        Map<QualityAttribute, Double> before = scores(baseline());
        Map<QualityAttribute, Double> after = scores(splitTournament());

        assertThat(after.get(QualityAttribute.COHESION)).isGreaterThan(before.get(QualityAttribute.COHESION) + 0.03);
        assertThat(after.get(QualityAttribute.COUPLING)).isLessThan(before.get(QualityAttribute.COUPLING));
        assertThat(after.get(QualityAttribute.AUTONOMY)).isLessThan(before.get(QualityAttribute.AUTONOMY));
    }

    @Test
    @DisplayName("a directory with no entities does not change any static score")
    void emptyServiceDirectory_isNeutral() {
        StaticMetrics padded = baseline();
        padded.getServices().put("exception", ServiceStaticMetrics.builder()
                .serviceName("exception").entityCount(0).externalRelations(Map.of()).build());
        Map<QualityAttribute, Double> before = scores(baseline());
        Map<QualityAttribute, Double> after = scores(padded);

        for (QualityAttribute qa : List.of(QualityAttribute.COHESION, QualityAttribute.COUPLING,
                QualityAttribute.CONSISTENCY, QualityAttribute.AUTONOMY)) {
            assertThat(after.get(qa)).isEqualTo(before.get(qa));
        }
    }

    @Test
    @DisplayName("a snapshot without the signal fields is scored on the same definitions, flagged as estimated where runtime is missing")
    void legacySnapshot_isScoredFromRawCounts() {
        StaticMetrics legacy = baseline();
        legacy.setFunctionalities(null);   // pre-Pass-4 snapshot: no workflow data at all
        Map<QualityAttribute, Double> s = scores(legacy);
        assertThat(s.get(QualityAttribute.COHESION)).isBetween(0.0, 1.0);
        assertThat(s.get(QualityAttribute.COUPLING)).isBetween(0.0, 1.0);
        QualityAttributeScore cohesion = calculateAll(Map.of(), List.of(), legacy).stream()
                .filter(x -> x.getAttribute() == QualityAttribute.COHESION).findFirst().orElseThrow();
        assertThat(cohesion.getSignals()).anyMatch(sig -> "functional_cohesion".equals(sig.getKey())
                && sig.getNormalizedValue() == null);
    }

    // ─── Scenarios ───────────────────────────────────────────────────────────

    private StaticMetrics baseline() {
        Map<String, ServiceStaticMetrics> services = new LinkedHashMap<>();
        services.put("quiz", svc("quiz", 3, 4, List.of("Quiz", "QuizOption", "QuizQuestion"),
                Map.of("question", 1), Map.of("Question", List.of("QuizQuestion"))));
        services.put("question", svc("question", 3, 4, List.of("Question", "Option", "QuestionQuiz"),
                Map.of("quiz", 1), Map.of("Quiz", List.of("QuestionQuiz"))));
        services.put("tournament", svc("tournament", 6, 10,
                List.of("Tournament", "TournamentA1", "TournamentA2", "TournamentB0", "TournamentB1", "TournamentB2"),
                Map.of(), Map.of()));
        services.put("user", svc("user", 1, 0, List.of("User"), Map.of(), Map.of()));

        List<FunctionalityInfo> funcs = new ArrayList<>();
        // quiz ↔ question: every workflow uses both aggregates fully, through one cross step.
        funcs.add(func("CreateQuiz", "quiz", 3, List.of("quiz", "question"), 1,
                Map.of("quiz", List.of("Quiz", "QuizOption", "QuizQuestion"), "question", List.of("Question", "Option", "QuestionQuiz"))));
        funcs.add(func("AnswerQuestion", "question", 3, List.of("question", "quiz"), 1,
                Map.of("question", List.of("Question", "Option", "QuestionQuiz"), "quiz", List.of("Quiz", "QuizOption", "QuizQuestion"))));
        // tournament: two disjoint groups of workflows, each using half of the aggregate.
        funcs.add(func("JoinTournament", "tournament", 2, List.of("tournament"), 0,
                Map.of("tournament", List.of("Tournament", "TournamentA1", "TournamentA2"))));
        funcs.add(func("ScoreTournament", "tournament", 2, List.of("tournament"), 0,
                Map.of("tournament", List.of("TournamentB0", "TournamentB1", "TournamentB2"))));
        funcs.add(func("GetUser", "user", 1, List.of("user"), 0, Map.of("user", List.of("User"))));
        return StaticMetrics.builder().services(services).serviceCount(4).functionalities(funcs).build();
    }

    /** quiz absorbs question: replicas gone, the root held by one relation, the cross step local. */
    private StaticMetrics mergeQuizQuestion() {
        StaticMetrics base = baseline();
        Map<String, ServiceStaticMetrics> services = new LinkedHashMap<>(base.getServices());
        services.remove("question");
        services.put("quiz", svc("quiz", 4, 8, List.of("Quiz", "QuizOption", "Question", "Option"),
                Map.of(), Map.of()));
        List<FunctionalityInfo> funcs = new ArrayList<>();
        funcs.add(func("CreateQuiz", "quiz", 3, List.of("quiz"), 0,
                Map.of("quiz", List.of("Quiz", "QuizOption", "Question", "Option"))));
        funcs.add(func("AnswerQuestion", "quiz", 3, List.of("quiz"), 0,
                Map.of("quiz", List.of("Quiz", "QuizOption", "Question", "Option"))));
        base.getFunctionalities().stream().filter(f -> f.getOwnerService().equals("tournament")
                || f.getOwnerService().equals("user")).forEach(funcs::add);
        return StaticMetrics.builder().services(services).serviceCount(3).functionalities(funcs).build();
    }

    /** tournament absorbs user: nothing used them together, so the merged aggregate is used in parts. */
    private StaticMetrics mergeTournamentUser() {
        StaticMetrics base = baseline();
        Map<String, ServiceStaticMetrics> services = new LinkedHashMap<>(base.getServices());
        services.remove("user");
        services.put("tournament", svc("tournament", 7, 12,
                List.of("Tournament", "TournamentA1", "TournamentA2", "TournamentB0", "TournamentB1", "TournamentB2", "User"),
                Map.of(), Map.of()));
        List<FunctionalityInfo> funcs = new ArrayList<>();
        base.getFunctionalities().stream().filter(f -> !f.getOwnerService().equals("user")).forEach(funcs::add);
        funcs.add(func("GetUser", "tournament", 1, List.of("tournament"), 0, Map.of("tournament", List.of("User"))));
        return StaticMetrics.builder().services(services).serviceCount(3).functionalities(funcs).build();
    }

    /** tournament split into the two halves its workflows use: one reference each way, one new service. */
    private StaticMetrics splitTournament() {
        StaticMetrics base = baseline();
        Map<String, ServiceStaticMetrics> services = new LinkedHashMap<>(base.getServices());
        services.put("tournament", svc("tournament", 3, 4, List.of("Tournament", "TournamentA1", "TournamentA2"),
                Map.of("scoring", 1), Map.of("Scoring", List.of("TournamentA2"))));
        services.put("scoring", svc("scoring", 3, 4, List.of("Scoring", "TournamentB1", "TournamentB2"),
                Map.of("tournament", 1), Map.of("Tournament", List.of("TournamentB2"))));
        List<FunctionalityInfo> funcs = new ArrayList<>();
        base.getFunctionalities().stream().filter(f -> !f.getOwnerService().equals("tournament")).forEach(funcs::add);
        funcs.add(func("JoinTournament", "tournament", 2, List.of("tournament"), 0,
                Map.of("tournament", List.of("Tournament", "TournamentA1", "TournamentA2"))));
        funcs.add(func("ScoreTournament", "scoring", 3, List.of("scoring", "tournament"), 1,
                Map.of("scoring", List.of("Scoring", "TournamentB1", "TournamentB2"), "tournament", List.of("Tournament"))));
        return StaticMetrics.builder().services(services).serviceCount(5).functionalities(funcs).build();
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private Map<QualityAttribute, Double> scores(StaticMetrics sm) {
        Map<QualityAttribute, Double> out = new LinkedHashMap<>();
        calculateAll(Map.of(), List.of(), sm).forEach(s -> out.put(s.getAttribute(), s.getScore()));
        return out;
    }

    private static ServiceStaticMetrics svc(String name, int n, int mu, List<String> entities,
                                            Map<String, Integer> ext, Map<String, List<String>> replicas) {
        return ServiceStaticMetrics.builder()
                .serviceName(name).entityCount(n).internalRelations(mu).entityNames(entities)
                .externalRelations(ext).replicaEntities(replicas)
                .eventSubscriptions(ext.isEmpty() ? null
                        : Map.of(ext.keySet().iterator().next(), List.of("UpdateEvent")))
                .build();
    }

    /** A workflow of {@code steps} steps, the first {@code crossSteps} of which send to another service. */
    private static FunctionalityInfo func(String name, String owner, int steps, List<String> touched,
                                          int crossSteps, Map<String, List<String>> touchedEntities) {
        List<FunctionalityInfo.WorkflowStep> workflow = new ArrayList<>();
        String foreign = touched.stream().filter(t -> !t.equals(owner)).findFirst().orElse(owner);
        for (int i = 1; i <= steps; i++) {
            String target = i <= crossSteps ? foreign : owner;
            workflow.add(FunctionalityInfo.WorkflowStep.builder().index(i).name("s" + i)
                    .dependsOn(List.of()).registered(true)
                    .commands(List.of(FunctionalityInfo.SentCommand.builder()
                            .name("Cmd" + i).targetService(target).access("write").build()))
                    .build());
        }
        return FunctionalityInfo.builder()
                .name(name + "FunctionalitySagas").ownerService(owner)
                .touchedServices(touched).stepCount(steps).steps(workflow)
                .touchedEntities(touchedEntities).build();
    }
}
