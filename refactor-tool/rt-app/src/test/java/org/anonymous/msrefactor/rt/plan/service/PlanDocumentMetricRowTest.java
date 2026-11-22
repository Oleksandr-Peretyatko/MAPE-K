package org.anonymous.msrefactor.rt.plan.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.anonymous.msrefactor.rt.analyze.MetricImpact;
import org.anonymous.msrefactor.rt.analyze.RefactorBlueprint;
import org.anonymous.msrefactor.rt.plan.dto.PlanRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How the plan document renders a metric whose "after" value is deliberately absent.
 *
 * <p>Analyze no longer predicts M1, M2 or p95 with fixed multipliers: it reports the measurement and
 * leaves the prediction blank. This document is the only consumer of {@link MetricImpact}, so the
 * rendering of that blank is what the reader actually sees — it has to read as "not claimed", not
 * make the row vanish and take the measured baseline with it.
 */
class PlanDocumentMetricRowTest {

    private final PlanDocumentGenerator generator = new PlanDocumentGenerator();

    private String render(MetricImpact mi) {
        RefactorBlueprint bp = RefactorBlueprint.builder()
                .summary("split Tournament")
                .metricImpact(mi)
                .build();
        PlanRequest req = new PlanRequest("SPLIT_AGGREGATE", "tournament", null, "snap-1",
                List.of(), true, true, null, bp, null, null);
        return generator.generate(req, null);
    }

    @Test
    @DisplayName("a measured 'before' with no prediction keeps its row, with a dash and a neutral arrow")
    void beforeWithoutAfterStillRenders() {
        String doc = render(MetricImpact.builder()
                .m1Before(0.122)
                .m1After(null)
                .estimationNote("M1 is per service; this split does not move it.")
                .build());

        // 0[.,]122 — the generator formats with String.format and no explicit locale, so the decimal
        // separator follows the host's. Not this phase's problem; the point here is the row shape.
        assertThat(doc).containsPattern("\\| M1 cohesion \\| 0[.,]122 \\| — \\| → \\|");
        assertThat(doc).contains("M1 is per service");
    }

    @Test
    @DisplayName("a metric with neither value is omitted rather than shown as two dashes")
    void rowWithNeitherValueIsOmitted() {
        String doc = render(MetricImpact.builder().m1Before(0.122).build());

        assertThat(doc).doesNotContain("p95 latency");
        assertThat(doc).doesNotContain("Compensation rate");
    }
}
