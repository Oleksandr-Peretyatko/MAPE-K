package org.anonymous.msrefactor.rt.monitor;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * One command an aggregate accepts, and the chain it triggers.
 *
 * <h3>Why the whole chain and not just the name</h3>
 *
 * <p>A command is the seam between two aggregates, and a refactoring has to move every layer of it at
 * once: the {@code case} in the command handler, the handler method, the service method it delegates
 * to, and every {@code SagaStep} that constructs the command. Miss one and the build still passes —
 * the sender keeps addressing a service that no longer answers, or the handler keeps a case nothing
 * sends.
 *
 * <p>Recording the chain makes both failures visible without running anything: a command with no
 * {@link #sentBy} is a surface nobody calls, and a functionality that sends a command no aggregate
 * handles has lost its receiver.
 *
 * <h3>The command surface is an aggregate's real API</h3>
 *
 * <p>It is not the same as its HTTP surface, and for some aggregates it is the <em>only</em> API:
 * {@code Course} has an empty controller and an empty {@code Functionalities} class, so every
 * operation on it — create, delete, both counters — exists solely as a command another aggregate's
 * saga sends. Counting only workflow classes reports that aggregate as having no behaviour at all.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CommandInfo {

    /** The command class, e.g. {@code GetCourseByIdCommand}. */
    private String name;

    /** {@code CourseCommandHandler.handleGetCourseById} — the {@code case} that accepts it. */
    private String handlerMethod;

    /**
     * {@code CourseService.getCourseById} — the service method the handler delegates to.
     *
     * <p>The behaviour behind the seam. A merge keeps this and drops everything above it; a split has
     * to decide which side it goes to.
     */
    private String serviceMethod;

    /**
     * Every Functionality that constructs this command. Empty means the aggregate exposes an
     * operation nothing invokes.
     */
    private List<CommandCaller> sentBy;

    /** One Functionality that sends a command, and how it uses the target. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CommandCaller {

        /** The Functionality class that constructs the command. */
        private String functionality;

        /** The aggregate that functionality belongs to. */
        private String fromAggregate;

        /**
         * {@code READ} or {@code WRITE}, from the saga's semantic lock; null for a compensation call,
         * which says nothing about how the entity is used.
         */
        private String access;
    }
}
