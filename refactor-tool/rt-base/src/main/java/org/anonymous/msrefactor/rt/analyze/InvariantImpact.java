package org.anonymous.msrefactor.rt.analyze;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Classification of how invariants in the source aggregate are affected by a refactoring operation.
 * Used for SPLIT_AGGREGATE candidates.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class InvariantImpact {

    /** Invariants that remain in the current aggregate and are unaffected. */
    private List<String> staysLocal;

    /** Invariants that can follow the entity to its new location. */
    private List<String> movesWithEntity;

    /**
     * Invariants that will span two aggregates after the operation — these become
     * inter-aggregate and require event-driven or SAGA enforcement.
     */
    private List<String> becomesCrossBoundary;


    /** Invariants that will need SAGA coordination or compensation after the operation. */
    private List<String> requiresSagaOrCompensation;

    /**
     * Cross-aggregate rules whose footprint moves to the satellite entirely, so their subscriptions
     * move with it.
     *
     * <p>Kept apart from {@link #movesWithEntity} because the work is different in kind: the
     * satellite has to grow its own {@code getEventSubscriptions()}, event handler, polling and
     * {@code ByEvent} method. Nothing is redesigned — the rule was already eventual — but nothing is
     * free either.
     */
    private List<String> subscriptionsMoveToSatellite;

    /**
     * Cross-aggregate rules that range over the satellite while their subscription anchor — the
     * entity holding the publisher's aggregate id and version — stays in the core.
     *
     * <p>These are not {@link #becomesCrossBoundary}: the rule is already cross-boundary. The
     * satellite simply cannot subscribe to anything until that anchor is replicated into it, which
     * makes a {@code CREATE_REPLICA} a prerequisite of the split rather than a follow-up.
     */
    private List<String> subscriptionsNeedAnchorReplica;

    /**
     * Invariants severe enough to be called out as a warning: so many of them span both partitions
     * that the split is likely to need preparatory work.
     *
     * <p>Named for what it does. It was {@code blocksCandidate}, but nothing has ever blocked a
     * candidate on it — its one consumer adds a sentence to {@code risks}, and the operation is
     * still emitted and still ranked the same. Whether a majority of straddling invariants
     * <em>should</em> suppress the candidate is a product decision, not a naming one.
     */
    private List<String> blockingWarnings;
}
