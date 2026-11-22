package org.anonymous.msrefactor.rt.analyze.selector;

import org.springframework.stereotype.Component;
import org.anonymous.msrefactor.rt.analyze.OperationType;
import org.anonymous.msrefactor.rt.analyze.ValidationStep;

import java.util.List;

/**
 * Generates a concrete validation plan (ordered list of {@link ValidationStep}) for each
 * refactoring operation type. Steps are templates with entity and service names interpolated.
 */
@Component
public class ValidationPlanBuilder {

    public List<ValidationStep> buildFor(OperationType op, String targetService, String entityName) {
        if (op == null) return List.of();
        return switch (op) {
            case SPLIT_AGGREGATE   -> splitAggregatePlan(targetService);
            case CREATE_REPLICA    -> createReplicaPlan(targetService, entityName);
            case MERGE_AGGREGATES  -> mergeAggregatesPlan(targetService);
            default                -> genericPlan(targetService);
        };
    }

    private List<ValidationStep> splitAggregatePlan(String service) {
        String svc = service != null ? service : "service";
        return List.of(
                step("STATIC_ANALYSIS",
                        "Run `mvn install` in simulator/ then `mvn clean test` in rt-app",
                        "BUILD SUCCESS, 0 compilation errors"),
                step("UNIT_TEST",
                        "Verify both resulting aggregates can be independently instantiated in unit tests",
                        "0 test failures"),
                step("INTEGRATION_TEST",
                        "Run `mvn clean -Ptest-sagas test`",
                        "All SAGA flows pass with the new aggregate boundary"),
                step("INVARIANT_TEST",
                        "Verify that every invariant in the original aggregate is either preserved or explicitly migrated",
                        "No invariant violations in invariant smoke test"),
                // Not "check M1 improved". M1 is computed per service and both aggregates of a split
                // stay in the same service, so the split cannot raise it — the only movement to
                // expect is a small dip from the satellite's new root class. Asking for an
                // improvement asks for something the operation cannot deliver.
                step("STATIC_ANALYSIS",
                        "Re-run static analysis and compare M1/M2 for " + svc + " against the pre-split snapshot",
                        "M1 and M2 are unchanged, or the only movement is explained by the satellite's "
                        + "new root class — both aggregates stay in " + svc + ", so a same-service "
                        + "split does not move a per-service metric"),
                step("LOCUST",
                        "Re-run Locust and monitor for performance regressions",
                        "No p95 regression > 10%"));
    }

    private List<ValidationStep> createReplicaPlan(String service, String entity) {
        String ent = entity != null ? entity : "entity";
        String svc = service != null ? service : "service";
        return List.of(
                step("STATIC_ANALYSIS",
                        "Run `mvn install` in simulator/ then `mvn clean test` in rt-app",
                        "BUILD SUCCESS, 0 compilation errors"),
                step("UNIT_TEST",
                        "Verify that " + svc + " reads from the local replica and NOT from the remote service in unit tests",
                        "No remote calls in read path unit tests"),
                step("INTEGRATION_TEST",
                        "Run `mvn clean -Ptest-sagas test`",
                        "All SAGA flows pass"),
                step("INVARIANT_TEST",
                        "Confirm that the replica of " + ent + " is never used as authoritative source for writes",
                        "No write operations target the replica object"),
                step("LOCUST",
                        "Re-run Locust scenario and measure p95 latency for the read-heavy endpoint",
                        "p95 latency reduced; staleness within the configured window"),
                step("JAEGER",
                        "Verify that cross-service read calls to the source service are eliminated from hot path",
                        "No synchronous reads to source service on the hot path"));
    }

    private List<ValidationStep> mergeAggregatesPlan(String service) {
        return List.of(
                step("STATIC_ANALYSIS", "Run `mvn install` and `mvn clean test`",
                        "BUILD SUCCESS"),
                step("INTEGRATION_TEST", "Run `mvn clean -Ptest-sagas test`",
                        "All flows pass"),
                step("INVARIANT_TEST", "Verify converted intra-invariants are enforced synchronously",
                        "No invariant exceptions after merge"),
                step("LOCUST", "Re-run Locust and verify p95 improvement",
                        "p95 reduced for the merged service pair"),
                step("STATIC_ANALYSIS", "Re-run static analysis; check M2 decrease",
                        "M2 lower after merge"));
    }

    /** Fallback — currently only REMOVE_REPLICA lands here. */
    private List<ValidationStep> genericPlan(String service) {
        return List.of(
                step("STATIC_ANALYSIS", "Run `mvn install` and `mvn clean test`",
                        "BUILD SUCCESS"),
                step("INTEGRATION_TEST", "Run `mvn clean -Ptest-sagas test`",
                        "All flows pass"),
                step("LOCUST", "Re-run Locust scenario after applying the refactoring",
                        "No regressions in p95 latency or error rate"));
    }

    private static ValidationStep step(String type, String description, String expectedOutcome) {
        return ValidationStep.builder()
                .type(type)
                .description(description)
                .expectedOutcome(expectedOutcome)
                .build();
    }
}
