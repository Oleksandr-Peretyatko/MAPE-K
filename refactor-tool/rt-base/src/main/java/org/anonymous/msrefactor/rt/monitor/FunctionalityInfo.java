package org.anonymous.msrefactor.rt.monitor;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Metadata for a single Functionality class found in the source tree.
 *
 * <p>Populated by Pass 4 of {@code StaticAnalysisCollector}. Each instance corresponds
 * to one {@code *FunctionalitySagas.java} file.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FunctionalityInfo {

    /** Simple class name, e.g. {@code "CreateQuizFunctionalitySagas"}. */
    private String name;

    /** Service that owns this Functionality (matches microservices package subdirectory). */
    private String ownerService;

    /** Concurrency protocol, always {@code "sagas"}: the only transactional model the stack runs. */
    private String protocol;

    /** All services referenced via {@code ServiceMapping.XYZ.getServiceName()} in {@code buildWorkflow()}. */
    private List<String> touchedServices;

    /** Number of {@code SagaStep} / {@code Step} instantiations found in {@code buildWorkflow()}. */
    private int stepCount;

    /**
     * HTTP method (uppercase) of the controller endpoint that triggers this Functionality
     * (e.g. {@code "POST"}, {@code "GET"}).
     * Null for event-driven functionalities that have no direct HTTP endpoint (Pass 5A).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String httpMethod;

    /**
     * Normalized HTTP path pattern that triggers this Functionality.
     * Path variables are replaced with {@code {id}} (e.g. {@code "/tournaments/{id}/join"}).
     * Null for event-driven functionalities (Pass 5A).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String httpEndpoint;

    /**
     * The Locust task {@code name=} parameter that exercises this endpoint
     * (e.g. {@code "Add participant"}).
     * Null when no Locust scenario covers this Functionality (Pass 5B).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String locustTaskName;

    /**
     * The commands this Functionality constructs, with the service each is addressed to.
     *
     * <p>{@link #touchedServices} says which services the workflow reaches; this says through which
     * seam. A refactoring that moves an aggregate has to re-point or collapse exactly these call
     * sites, and there is no other way to enumerate them: the per-service command totals on
     * {@link InterServiceDependency} aggregate the sender away.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<SentCommand> commands;

    /**
     * The workflow parsed into its steps, in declaration order, each with the commands it sends.
     *
     * <p>{@link #commands} is the same set of call sites flattened; this partitions them by the step
     * that owns them and adds the dependency edges, which is what actually orders them — the engine
     * topologically sorts the DAG, so two steps that do not depend on each other have no defined
     * order between them at all.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<WorkflowStep> steps;

    /**
     * Command sites that fall inside no step and no compensation.
     *
     * <p>Expected empty. A non-empty list means the step parser did not recognise a shape the source
     * uses, and saying so beats a step view that quietly holds fewer call sites than
     * {@link #commands} does.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<SentCommand> looseCommands;

    /**
     * Per service, the entity classes this functionality reaches: those its own source names (by
     * class or DTO) plus those named in the body of every service method its commands delegate to.
     *
     * <p>The input of functional cohesion — what makes "these entities belong together" a claim
     * about use rather than about JPA annotations. A heuristic: a service method that reaches a
     * member only through a repository query is missed, and a name mentioned in a comment is
     * counted. Null when nothing resolved or on snapshots taken before the field existed.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Map<String, List<String>> touchedEntities;

    /** One step of a workflow, with everything that fixes its place in the order. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class WorkflowStep {

        /** Declaration order in {@code buildWorkflow()}, 1-based. Not the execution order. */
        private int index;

        /** The name the step is constructed with — the same string the abort path compensates until. */
        private String name;

        /** Step names from the third constructor argument; empty when the step has no dependency. */
        private List<String> dependsOn;

        /**
         * Longest dependency chain behind this step: 0 for a step that waits on nothing.
         *
         * <p>Steps sharing a wave can run in any order relative to each other, which is exactly what
         * numbering them 1..N would hide.
         */
        private int wave;

        /**
         * Whether a {@code workflow.addStep(...)} call names this step.
         *
         * <p>A step that is constructed and never added compiles, never runs, and is reported
         * nowhere else.
         */
        private boolean registered;

        /** Commands sent from the step body. */
        private List<SentCommand> commands;

        /**
         * Commands sent from this step's {@code registerCompensation} body; null when it registers
         * none.
         *
         * <p>Mostly the bare {@code Command} base class, which carries no entity and exists to
         * release the semantic lock — a construction site a refactoring has to re-point like any
         * other, and one {@code commands} deliberately excludes.
         */
        private List<SentCommand> compensation;
    }

    /** One command construction site inside a Functionality's workflow. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SentCommand {

        private String name;

        /** The service the command is addressed to, from its {@code ServiceMapping} argument. */
        private String targetService;

        /** {@code READ} or {@code WRITE}; null for a saga compensation. */
        private String access;
    }
}
