package org.anonymous.msrefactor.rt.analyze.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.anonymous.msrefactor.rt.config.AnalysisThresholdsConfig;
import org.anonymous.msrefactor.rt.plan.dto.PlanRequest;
import org.anonymous.msrefactor.rt.plan.service.PlanService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A disabled operation can still arrive after generation filters it, from a direct or stale
 * Plan request; Plan must refuse it before anything is written.
 */
class DisabledOperationGuardsTest {

    private static ObjectMapper sharedMapper() {
        // As AppConfig builds it: unknown fields are ignored and an unknown operation reads as null.
        return new ObjectMapper()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL);
    }

    @Test
    @DisplayName("Plan refuses an operation that is not enabled, before writing anything")
    void planRejectsDisabledOperation() {
        PlanService planService = new PlanService(
                null, null, null, sharedMapper(), null, null, null,
                new AnalysisThresholdsConfig());
        PlanRequest req = new PlanRequest("CREATE_REPLICA", "tournament", "user", null, List.of(),
                true, true, "create-replica tournament User --to tournament", null, null, null);

        assertThatThrownBy(() -> planService.generate(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CREATE_REPLICA")
                .hasMessageContaining("not enabled");
    }
}
