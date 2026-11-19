package org.anonymous.msrefactor.rt.monitor;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Merged inter-service dependency edge from service A to service B.
 *
 * <p>Combines three orthogonal coupling dimensions collected by {@code StaticAnalysisCollector}:
 * <ul>
 *   <li><b>structuralRefs</b> (Pass 2) — value-object entity classes in A that embed B's aggregate data (σᵢⱼ)</li>
 *   <li><b>eventTypes</b> (Pass 3) — lifecycle events published by B that A subscribes to (async)</li>
 *   <li><b>functionalityNames</b> (Pass 4) — A's Functionality classes that call B synchronously via CommandGateway</li>
 * </ul>
 *
 * <p>{@link #totalWeight} = structuralRefs + eventTypes.size + functionalityNames.size.
 * A higher weight indicates stronger overall coupling between A and B.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class InterServiceDependency {

    /** The service being depended on. */
    private String targetService;

    /** σᵢⱼ — number of entity/value-object classes in the source service that reference this target. */
    private int structuralRefs;

    /** Event type simple names (e.g. {@code "DeleteCourseExecutionEvent"}) this service subscribes to from the target. */
    private List<String> eventTypes;

    /** Functionality class names (e.g. {@code "CreateQuizFunctionalitySagas"}) that call this target service synchronously. */
    private List<String> functionalityNames;

    /** Aggregate coupling weight: structuralRefs + eventTypes.size() + functionalityNames.size(). */
    private int totalWeight;

    /**
     * Command classes this service sends to the target that only <b>read</b> it, e.g.
     * {@code GetCourseExecutionByIdCommand}.
     *
     * <p>Classified by the saga step's semantic lock ({@code READ_*}) where one exists, falling back
     * to the command's verb where a step declares none. Saga compensations
     * ({@code NOT_IN_SAGA}) appear in neither list — they say nothing about how the entity is used.
     *
     * <p>These are the calls a {@code CreateReplica} would eliminate, and their names identify the
     * entity to replicate. Null on snapshots taken before this field existed — treat as unknown,
     * never as "none".
     */
    private List<String> readCommands;

    /**
     * Command classes this service sends to the target that <b>write</b> it.
     *
     * <p>A non-empty list disqualifies {@code CreateReplica} on this edge: the write still has to
     * reach the owner, so a local copy would be a second source of truth. That is an ownership
     * problem, not a locality one.
     */
    private List<String> writeCommands;
}
