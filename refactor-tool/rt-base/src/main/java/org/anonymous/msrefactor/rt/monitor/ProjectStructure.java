package org.anonymous.msrefactor.rt.monitor;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The architecture of one code version, arranged the way a domain modeller reads it: by aggregate.
 *
 * <p>Every field here is already collected by {@code StaticAnalysisCollector} — the composition
 * forest, the invariant footprints, the replica classes, the merged dependency edges. What was
 * missing was a shape: the collector reports <b>per service</b>, and a service is not an aggregate.
 * This regroups the same facts around the aggregate root, which is the unit an architect actually
 * splits, merges and reasons about.
 *
 * <h3>Both halves of consistency, kept apart</h3>
 *
 * <p>{@link AggregateStructure#intraInvariants} are the rules an aggregate checks on itself;
 * {@link AggregateStructure#interInvariants} are the ones it maintains across a boundary by reacting
 * to events. An aggregate can have none of the first and several of the second — {@code QuizAnswer}
 * does — and reporting only the first makes it read as unconstrained when the opposite is true.
 *
 * <p>The association between a rule and its subscriptions is read from the code, not guessed from
 * event names: each method called from {@code getEventSubscriptions()} registers the subscriptions
 * of exactly one rule. {@link AggregateRelation#events} still carries the flat event types, which is
 * the transport rather than the rule.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProjectStructure {

    private String versionId;
    private String versionLabel;

    /**
     * Where the analysed source came from, in words: {@code "commit aa11bb22"}, {@code "tag v2"} or
     * {@code "working tree"}.
     *
     * <p>Shown in the UI because the same version label can resolve differently depending on what
     * exists in the repository, and a structure the reader cannot place is a structure they cannot
     * trust.
     */
    private String resolvedFrom;

    private Instant collectedAt;

    private List<AggregateStructure> aggregates;

    /**
     * Per service, the entity classes no aggregate root reaches through composition.
     *
     * <p>Not padding. An entity outside the forest is either a collector miss or a class with no
     * owner, and both are worth seeing; dropping it would make the view quietly incomplete.
     */
    private Map<String, List<String>> orphanEntities;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AggregateStructure {

        /** The aggregate root class — {@code Tournament}, {@code QuizAnswer}, … */
        private String name;

        private String serviceName;

        /** Entities in this aggregate, root included. */
        private int entityCount;

        /** The composition forest of this aggregate, nested, starting at the root. */
        private EntityNode root;

        /** The P1 rules this aggregate enforces, each with the state it reads. */
        private List<InvariantInfo> intraInvariants;

        /**
         * The cross-aggregate rules this aggregate maintains, each with the subscriptions that keep
         * it true and the aggregates at the other end.
         */
        private List<InterInvariantInfo> interInvariants;

        private List<AggregateRelation> outgoing;
        private List<AggregateRelation> incoming;

        /**
         * The cross-service operations this aggregate owns, one per Functionality class.
         *
         * <p>Only the active transactional model (sagas) is scanned.
         */
        private List<FunctionalityRef> functionalities;

        /**
         * Events published by this aggregate's service, with the aggregates that subscribe to them.
         *
         * <p>Read from the {@code registerEvent} call site, unlike the publisher named on an
         * {@link AggregateRelation}, which is inferred from the subscription's anchor. The two can
         * disagree, and when they do the anchor is the one that is guessing.
         */
        private List<EventFlow> publishes;

        /**
         * Events this aggregate subscribes to, with the aggregate that publishes each.
         *
         * <p>The flat view of what {@link InterInvariantInfo#getSubscriptions()} groups by rule. Both
         * are wanted: the rule view answers "what does this maintain", this one answers "who does it
         * depend on".
         */
        private List<EventFlow> consumes;

        /**
         * The commands this aggregate accepts, each with the service method behind it and every
         * Functionality that sends it.
         *
         * <p>The synchronous seam, and the layer map a refactoring has to move whole: {@code case} →
         * handler method → service method → senders. A command with no sender is a surface nothing
         * calls; a sender with no command is a call with no receiver. Both are invisible to the
         * compiler and both appear here.
         */
        private List<CommandInfo> commands;

        /**
         * True when the owning service holds more than one aggregate root.
         *
         * <p>The collector measures coupling per service, so in that case the relations below belong
         * to the service and are repeated on each of its aggregates. Saying so is cheaper than
         * letting the reader assume a precision that is not there.
         */
        private boolean relationsAreServiceWide;
    }

    /** One cross-service operation, as the source declares it. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FunctionalityRef {

        /**
         * The operation, with the {@code FunctionalitySagas} suffix removed:
         * {@code AddParticipant}. That is how the domain model names it, so it is what a reader
         * compares against.
         */
        private String operation;

        /** The class it was read from, e.g. {@code AddParticipantFunctionalitySagas}. */
        private String className;

        /** Always {@code sagas}: the only transactional model the stack runs. */
        private String protocol;

        /** Steps in {@code buildWorkflow()} — the length of the distributed interaction. */
        private int stepCount;

        /**
         * The other aggregates this operation reaches, translated from the services its workflow
         * calls. A service with several roots contributes nothing, since no single root would be right.
         */
        private List<String> touchedAggregates;

        private String httpMethod;
        private String httpEndpoint;

        /**
         * The commands this operation sends, with the aggregate each is addressed to.
         *
         * <p>The other direction of {@link AggregateStructure#commands}. Together they say, for any
         * proposed move, which call sites collapse into local calls and which merely change target.
         */
        private List<SentCommandRef> sendsCommands;

        /**
         * The workflow broken into its steps, in declaration order.
         *
         * <p>{@link #sendsCommands} answers "what does this operation touch"; this answers "in what
         * order, and what may be reordered". The distinction is not cosmetic: the engine
         * topologically sorts {@link WorkflowStepRef#dependsOn}, so steps in the same
         * {@link WorkflowStepRef#wave} have no order between them and a refactoring is free to move
         * them past each other — while one that crosses a wave boundary is not.
         */
        private List<WorkflowStepRef> steps;

        /**
         * Command sites the step parser could not place in any step. Expected absent; present means
         * the parser missed a shape, not that the source has none.
         */
        private List<SentCommandRef> looseCommands;
    }

    /** One step of a Functionality's workflow, named in aggregate terms. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class WorkflowStepRef {

        /** Declaration order, 1-based — where the reader finds it in the file, not when it runs. */
        private int index;

        private String name;

        /** The steps that must complete first. */
        private List<String> dependsOn;

        /** Longest dependency chain behind this step; steps sharing a wave are unordered. */
        private int wave;

        /** False when no {@code addStep} call names it: constructed, never run. */
        private boolean registered;

        private List<SentCommandRef> sends;

        /** What the step's compensation sends when the saga aborts past it. */
        private List<SentCommandRef> compensates;
    }

    /** One command a Functionality sends, named in aggregate terms. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SentCommandRef {

        private String name;

        /** The aggregate that handles it; the service name when it has no single root. */
        private String toAggregate;

        /** {@code READ} or {@code WRITE}; null for a saga compensation. */
        private String access;

        /**
         * True when no handler in the target service accepts this command.
         *
         * <p>Nothing catches this otherwise: the command still compiles, the gateway still sends it,
         * and the receiving handler's {@code default} arm logs a warning and returns null, so the
         * saga step silently does nothing. It is what a half-finished refactoring leaves behind —
         * the sender re-pointed and the handler not moved, or the reverse.
         */
        private boolean unhandled;
    }

    /** One event moving between two aggregates, seen from one end. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class EventFlow {

        private String eventType;

        /** On a {@code consumes} entry, the aggregate that registers the event; null when unknown. */
        private String publisherAggregate;

        /** {@code ServiceClass.method} where the event is registered; null when not found. */
        private String publishedIn;

        /** On a {@code publishes} entry, the aggregates that subscribe to it. */
        private List<String> consumerAggregates;
    }

    /** An entity and the entities it contains. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class EntityNode {

        private String name;

        /**
         * The foreign aggregate root(s) this class declares a copy of, from its {@code @Replica}
         * annotation — {@code TournamentCourseExecution} carries {@code Execution}. Comma-separated
         * when it copies more than one. Null for a plain domain entity of this aggregate.
         *
         * <p>Names a root rather than a service because that is what the annotation names, and
         * because a root survives a service rename. It was previously derived from "which foreign
         * services does this class mention", which labelled {@code CourseExecutionStudent} — a copy
         * of {@code User} — as a replica of {@code tournament}.
         */
        private String replicaOf;

        private List<EntityNode> children;
    }

    /**
     * One directed edge between two aggregates, carrying all three coupling dimensions separately.
     *
     * <p>Mirrors {@link InterServiceDependency}, which already merges them, plus {@link #viaEntities}
     * so the reader can see <em>which</em> local classes produce the structural count rather than
     * only how many.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AggregateRelation {

        private String toAggregate;
        private String toService;

        /** σ — local classes that embed the target's data. */
        private int structuralRefs;

        /** The classes behind {@link #structuralRefs}, named. */
        private List<String> viaEntities;

        /** Event types published by the target that this side subscribes to — the async dimension. */
        private List<String> events;

        /** Functionality classes that call the target synchronously through the command gateway. */
        private List<String> functionalities;

        private List<String> readCommands;
        private List<String> writeCommands;

        private int totalWeight;
    }
}
