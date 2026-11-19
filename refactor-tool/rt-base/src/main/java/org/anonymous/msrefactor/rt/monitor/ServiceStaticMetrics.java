package org.anonymous.msrefactor.rt.monitor;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Static structural metrics for a single microservice, derived from source-code analysis.
 *
 * <p>Used as input for the M1 (intra-service cohesion) and M2 (inter-service coupling)
 * formulas from Mancoridis et al. [47], as cited in the refactoring evaluation framework.
 *
 * <h3>Definitions</h3>
 * <ul>
 *   <li>{@link #entityCount} — Nᵢ: number of entity/value-object classes inside the service</li>
 *   <li>{@link #internalRelations} — μᵢ: number of relations between entities within the service
 *       (detected via {@code @OneToOne} / {@code @OneToMany} / {@code @ManyToOne} JPA annotations)</li>
 *   <li>{@link #externalRelations} — σᵢⱼ per target service: value-object classes in this service
 *       that embed a reference to another service's aggregate</li>
 * </ul>
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ServiceStaticMetrics {

    /** Service name (matches the microservices package subdirectory name). */
    private String serviceName;

    /** Nᵢ — count of entity/value-object classes in this service's aggregate package. */
    private int entityCount;

    /** μᵢ — count of intra-service JPA relations (@OneToOne / @OneToMany / @ManyToOne). */
    private int internalRelations;

    /**
     * The composition graph of this service's aggregates: owning class → the classes it contains.
     *
     * <p>Derived from the {@code mappedBy} side of {@code @OneToOne} / {@code @OneToMany}: the class
     * that declares {@code mappedBy} is the parent, the field's type is the child. The other side of
     * every pair is a plain back-reference.
     *
     * <p>This is what makes a proposed cut checkable. {@link #internalRelations} counts the same
     * relations but keeps only the total, and a total cannot say whether moving an entity would
     * orphan its children.
     *
     * <p>Null on snapshots taken before this field existed; treat absence as "unknown", not "the
     * aggregate has no structure".
     */
    private Map<String, List<String>> compositions;

    /**
     * The subset of {@link #compositions} whose member is held <b>one-to-one</b>: owning class → its
     * single-valued members. These are the ownership candidates.
     *
     * <p>The cardinality lives on the owning side. {@code QuestionTopic} holds a single
     * {@code topicAggregateId} like any replica, yet {@code Question} declares
     * {@code @OneToMany Set<QuestionTopic>}: reading the member class cannot tell the two apart,
     * reading the root can. {@link #compositions} keeps the same edges and drops the distinction, so
     * on its own an N:M relation reads as ownership and a merge gets proposed for a pair no single
     * root could contain.
     *
     * <p>Null on snapshots taken before this field existed; treat absence as "unknown", not "this
     * root holds nothing single-valued".
     */
    private Map<String, List<String>> singleValuedMembers;

    /**
     * σᵢⱼ per target service — number of value-object classes in this service that
     * embed a reference to each other service.
     * Key = target service name, Value = relation count.
     */
    private Map<String, Integer> externalRelations;

    /**
     * This service's contribution to M1: μᵢ / Nᵢ².
     * Zero when entityCount == 0 to avoid division by zero.
     */
    private double m1Contribution;

    /**
     * This service's contribution to M2: Σⱼ≠ᵢ (σᵢⱼ + σⱼᵢ) / (2·Nᵢ·Nⱼ).
     * Uses the same symmetric sigma as the system-level computeM2 formula.
     * Zero when entityCount == 0.
     */
    private double m2Contribution;

    /** Class names of the detected entities (same set used to compute {@link #entityCount}). */
    private List<String> entityNames;

    /**
     * The subset of {@link #entityNames} that extends {@code Aggregate} — this service's aggregate
     * roots.
     *
     * <p>Needed because a service's name does not reliably give its root: {@code answer}'s root is
     * {@code QuizAnswer}, not {@code Answer}. Deriving the root by capitalising the service name
     * silently picks the wrong class, and falling back to "the first entity" picks whatever the
     * directory listing happened to return — which is how a replica class was once proposed as the
     * thing to replicate.
     *
     * <p>Null on snapshots taken before this field existed; callers must treat absence as "unknown"
     * rather than "none".
     */
    private List<String> rootNames;

    /**
     * The P1 intra-invariants of this service's aggregates, each with the state it reads.
     *
     * <p>Collected by walking {@code verifyInvariants()} rather than by matching the {@code
     * invariant*} name, which is a convention of two aggregates and misses four real rules
     * elsewhere. See {@link InvariantInfo}.
     *
     * <p>Null on snapshots taken before this field existed; callers must treat absence as "unknown"
     * rather than "this aggregate has no invariants".
     */
    private List<InvariantInfo> invariants;

    /**
     * The cross-aggregate rules this service's aggregates maintain, each with the subscriptions that
     * keep it true and the state it ranges over.
     *
     * <p>Collected by walking {@code getEventSubscriptions()}, which is the choke point for the
     * "inter" dimension exactly as {@code verifyInvariants()} is for the "intra" one. Distinct from
     * {@link #eventSubscriptions}, which records the same subscriptions as bare transport — event
     * types per publisher, with no indication of which rule each one serves. See
     * {@link InterInvariantInfo}.
     *
     * <p>Null on snapshots taken before this field existed; callers must treat absence as "unknown"
     * rather than "this aggregate has no cross-aggregate rules".
     */
    private List<InterInvariantInfo> interInvariants;

    /**
     * Per-target-service list of local entity names that reference that service.
     * Key = target service name, Value = entity class names in THIS service that carry that reference.
     * Provides entity-level traceability beyond the aggregate count in {@link #externalRelations}.
     */
    private Map<String, List<String>> crossServiceEntities;

    /**
     * Per foreign aggregate root, the local classes that declare themselves a copy of it — read from
     * their {@code @Replica} annotation.
     *
     * <p>Distinct from {@link #crossServiceEntities}, which records every foreign service a class
     * <em>mentions</em>. The two disagree, and conflating them produced a false answer:
     * {@code CourseExecutionStudent} copies {@code User} and merely names {@code TournamentParticipant}
     * in an {@code equals}, yet was reported as a replica of {@code tournament}.
     *
     * <p>Null when the analysed version carries no {@code @Replica} at all — a revision older than the
     * convention, whose replicas are unknown rather than absent. An empty map means the version uses
     * the annotation and this service has no replica.
     */
    private Map<String, List<String>> replicaEntities;

    /**
     * The mirror of {@link #crossServiceEntities}: per-target-service list of <b>that target's</b>
     * entity class names which appear in THIS service's source.
     *
     * <p>The two are easy to confuse and mean opposite things. For {@code tournament → execution},
     * {@code crossServiceEntities} holds {@code TournamentCourseExecution} (a tournament class),
     * while this holds {@code Execution} (an execution class). Only the latter can be the operand of
     * a {@code CreateReplica} — copying a class the consumer already owns is meaningless.
     *
     * <p>Null on snapshots taken before this field existed; treat absence as "unknown", not "none".
     */
    private Map<String, List<String>> referencedForeignEntities;

    /**
     * Per-publisher-service list of event types this service subscribes to.
     * Key = publisher service name, Value = event type simple names (e.g. "DeleteCourseExecutionEvent").
     * Populated by Pass 3 (event subscription scan of {@code events/subscribe/} directories).
     * Encodes the behavioral coupling dimension — which services' lifecycle events this service reacts to.
     */
    private Map<String, List<String>> eventSubscriptions;

    /**
     * The events this service publishes: event type → the {@code ServiceClass.method} that registers
     * it.
     *
     * <p>The counterpart of {@link #eventSubscriptions}, and the only place the publisher side is
     * <em>read</em>. The publisher named on a subscription is inferred from its anchor, which answers
     * a different question: {@code UpdateStudentNameEvent} is anchored on the course execution, so
     * that inference attributes it to {@code execution} whether or not that is where it is registered.
     *
     * <p>Null when the analysed version has no {@code service/} directories to scan.
     */
    private Map<String, String> publishedEvents;

    /**
     * The commands this service's {@code CommandHandler} accepts, each with the service method it
     * delegates to.
     *
     * <p>The aggregate's real API across the boundary, and for {@code Course} the only one it has —
     * its controller and its {@code Functionalities} class are both empty, so create, delete and both
     * counters exist solely as commands another aggregate's saga sends.
     *
     * <p>Null when the service has no {@code messaging/} package to read.
     */
    private List<CommandInfo> handledCommands;

    /**
     * The {@code @PostMapping} paths this service's controllers declare, <b>unnormalised</b>.
     *
     * <p>The path variable's name is the signal: {@code /courses/{courseAggregateId}/questions/create}
     * says a Question is created inside a Course's scope, which is what separates a real parent from
     * a class merely referenced by id. {@link FunctionalityInfo#getHttpEndpoint()} rewrites every
     * variable to <code>{id}</code> so Locust rows can be matched, and therefore cannot answer this.
     *
     * <p>Null on snapshots taken before this field existed; treat absence as "unknown", not "none".
     */
    private List<String> postEndpoints;

    /**
     * Synchronous operational dependencies via Functionality steps (Pass 4).
     * Key = target service name, Value = Functionality class names (e.g. "CreateQuizFunctionalitySagas")
     * that call that target via {@code commandGateway.send()} in {@code buildWorkflow()}.
     */
    private Map<String, List<String>> functionalityDependencies;

    /**
     * Merged inter-service dependency graph combining all three passes.
     * Key = target service name. Each entry unifies Pass 2 (structural refs), Pass 3 (event subscriptions),
     * and Pass 4 (functionality dependencies) into a single {@link InterServiceDependency} edge.
     * Computed after all passes complete; null when not yet collected.
     */
    private Map<String, InterServiceDependency> interServiceDependencies;

    // ─── Functionality-graph metrics (Pass 4 only) ────────────────────────────

    /**
     * μ'ᵢ — sum of {@link FunctionalityInfo#getStepCount()} across all Functionality classes
     * owned by this service. Used as the intra-service signal for {@link #m1FunctionalContribution}.
     */
    private int functionalityStepCount;

    /**
     * M1 contribution using the functionality graph only: μ'ᵢ / Nᵢ².
     * {@code functionalityStepCount / (entityCount²)}.  Zero when entityCount == 0.
     */
    private double m1FunctionalContribution;

    /**
     * M2 contribution using the functionality graph only.
     * σ'ᵢⱼ = number of Functionality classes owned by this service that call service j.
     * Contribution = Σⱼ≠ᵢ (σ'ᵢⱼ + σ'ⱼᵢ) / (2·Nᵢ·Nⱼ).
     */
    private double m2FunctionalContribution;

    // ─── Normalised structural signals (see StructuralSignals) ───────────────
    // Null on snapshots taken before these existed; StructuralSignals.enrich fills them from the
    // raw counts, so a consumer never has to tell the two apart.

    /** {@code μᵢ / 2(Nᵢ−1)} capped at 1; 1 for a single entity. The tree-normalised M1. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Double structuralCohesion;
    /**
     * Mean, over the functionalities that touch this service, of the fraction of its entities each
     * one uses. Null when no functionality resolves to any of its entities.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Double functionalCohesion;
    /** TurboMQ cluster factor {@code 2μᵢ / (2μᵢ + Σⱼ(σᵢⱼ + σⱼᵢ))}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Double clusterFactor;
    /** Fraction of the owned workflows' steps that send no command to another service. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Double localStepRatio;
    /** Declared replica classes per entity. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Double replicaDensity;
    /** Cross-service event subscriptions per entity. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Double eventEdgeDensity;
    /** References in and out over the most this service could hold. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Double couplingDensity;
    /** Mean step count of the owned functionalities that leave the service; 0 when none does. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Double avgDistributedSteps;
}
