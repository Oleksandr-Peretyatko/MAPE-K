package org.anonymous.msrefactor.rt.analyze;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single validation action to execute after applying a refactoring operation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ValidationStep {

    /**
     * Category of the validation:
     * STATIC_ANALYSIS, UNIT_TEST, INTEGRATION_TEST, INVARIANT_TEST, LOCUST, JAEGER
     */
    private String type;

    /** Concrete description of what to check or run. */
    private String description;

    /** The result that should be observed if the refactoring was applied correctly. */
    private String expectedOutcome;
}
