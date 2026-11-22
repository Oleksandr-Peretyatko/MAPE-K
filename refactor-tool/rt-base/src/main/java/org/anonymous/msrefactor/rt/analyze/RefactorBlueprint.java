package org.anonymous.msrefactor.rt.analyze;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Concrete execution blueprint for a {@link CandidateOperation}.
 *
 * <p>Describes the refactoring in terms the developer can act on immediately:
 * which aggregates exist before and after, which entities move, which invariants change,
 * and a sequenced list of implementation steps.
 *
 * <p>Populated by {@code CandidateGenerator} when static structural data is available.
 * Null when no static analysis has been run for the target service.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RefactorBlueprint {

    /** One-sentence description of the proposed change. */
    private String summary;

    /** Current state — one entry per aggregate involved in the operation. */
    private List<AggregateSnapshot> before;

    /** Proposed state — one or more aggregate snapshots after applying the operation. */
    private List<AggregateSnapshot> after;

    /** Explicit entity relocations (empty for operations that do not move entities). */
    private List<EntityMove> entityMoves;

    /** How each affected invariant changes under this operation. */
    private List<InvariantChange> invariantChanges;

    /** Ordered list of developer actions to implement this operation. */
    private List<String> implementationSteps;

    /** Estimated before/after metric impact. Null when insufficient evidence is available. */
    private MetricImpact metricImpact;
}
