package org.anonymous.msrefactor.rt.analyze;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * A point-in-time view of an aggregate's composition: its entities and invariants.
 * Used in {@link RefactorBlueprint} to show the before/after state of a refactoring.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AggregateSnapshot {

    /** Aggregate class name, e.g. {@code "Tournament"}. */
    private String aggregateName;

    /** Owning service directory name, e.g. {@code "tournament"}. */
    private String serviceName;

    /** Entity class names belonging to this aggregate. */
    private List<String> entities;

    /** Invariant method names declared inside this aggregate's classes. */
    private List<String> invariants;

    /** True when this aggregate does not yet exist and is proposed by the blueprint. */
    private boolean isNew;
}
