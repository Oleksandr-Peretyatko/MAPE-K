package org.anonymous.msrefactor.rt.analyze.selector;

import org.springframework.stereotype.Component;
import org.anonymous.msrefactor.rt.analyze.InvariantImpact;
import org.anonymous.msrefactor.rt.knowledge.DiscoveryGraph;
import org.anonymous.msrefactor.rt.monitor.InterInvariantInfo;
import org.anonymous.msrefactor.rt.monitor.InvariantInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Classifies how each invariant of the source aggregate is affected by a SPLIT_AGGREGATE.
 *
 * <p>Classification is by <b>footprint</b> — what the rule actually reads, as extracted by the
 * collector. It used to be by name substring ({@code invariantName.contains(entityName)}), which
 * matched nothing in practice: {@code invariantUniqueParticipant} does not contain
 * {@code TournamentParticipant}, so every rule was reported as staying in the core, including the
 * ones guarding entities that had just moved out.
 *
 * <p>A {@link DiscoveryGraph}, when present, only refines the intra/inter distinction. It is
 * produced by an explicit REST call and never by the Monitor→Analyze pipeline, so in practice
 * {@code graphInvariants} is null and the classification rests entirely on the footprint.
 */
@Component
public class InvariantImpactAnalyzer {

    /**
     * Classifies invariant impact for a SPLIT_AGGREGATE operation.
     *
     * <p>Cross-aggregate rules are classified in their own buckets rather than reusing the intra
     * ones. {@code becomesCrossBoundary} would be meaningless for a rule that is already eventual;
     * what a cut does to it is move its subscriptions, or leave them without an anchor.
     *
     * @param coreEntities      entities staying in the original (core) aggregate
     * @param satelliteEntities entities moving to the new satellite aggregate
     * @param invariants        the aggregate's intra-invariants, each with the state it reads
     * @param interInvariants   its cross-aggregate rules; {@code DECLARED} ones are ignored, having
     *                          no subscription to move and no footprint to place
     * @param graphInvariants   invariants from the DiscoveryGraph (usually null — see above)
     */
    public InvariantImpact analyzeForSplit(
            List<String> coreEntities,
            List<String> satelliteEntities,
            List<InvariantInfo> invariants,
            List<InterInvariantInfo> interInvariants,
            List<DiscoveryGraph.DiscoveredInvariant> graphInvariants) {

        List<String> subscriptionsMove = new ArrayList<>();
        List<String> needsAnchorReplica = new ArrayList<>();
        classifyInterInvariants(satelliteEntities, interInvariants, subscriptionsMove, needsAnchorReplica);

        if (invariants == null || invariants.isEmpty()) {
            return InvariantImpact.builder()
                    .staysLocal(List.of()).movesWithEntity(List.of())
                    .becomesCrossBoundary(List.of())
                    .requiresSagaOrCompensation(List.of()).blockingWarnings(List.of())
                    .subscriptionsMoveToSatellite(subscriptionsMove)
                    .subscriptionsNeedAnchorReplica(needsAnchorReplica)
                    .build();
        }

        List<String> staysLocal = new ArrayList<>();
        List<String> movesWithEntity = new ArrayList<>();
        List<String> becomesCrossBoundary = new ArrayList<>();
        List<String> requiresSaga = new ArrayList<>();
        List<String> blocks = new ArrayList<>();

        for (InvariantInfo inv : invariants) {
            String name = inv.getName();
            List<String> reads = inv.getMemberEntities() == null ? List.of() : inv.getMemberEntities();

            boolean touchesSat = reads.stream().anyMatch(satelliteEntities::contains);
            // The root stays with the core, so reading any root scalar — or the aggregate's own
            // state — is itself a read of the core side. Missing this is what makes an invariant
            // look like it travels with its collection when it actually straddles the cut:
            // ParticipantsEnrolledBeforeStarTime reads TournamentParticipant *and* this.startTime.
            boolean readsRoot = inv.isReadsState()
                    || (inv.getRootScalars() != null && !inv.getRootScalars().isEmpty());
            boolean touchesCore = readsRoot || reads.stream().anyMatch(coreEntities::contains);

            if (touchesCore && touchesSat) {
                // getPrev() compares against this aggregate's own previous version. After a split
                // each side has its own version chain, so such a rule cannot span the boundary at
                // all — it has to be duplicated, which is a redesign, not a re-homing.
                String graphType = resolveGraphType(name, graphInvariants);
                if (inv.isReadsPrev() || "inter".equals(graphType)) {
                    requiresSaga.add(name);
                } else {
                    becomesCrossBoundary.add(name);
                }
            } else if (touchesSat) {
                movesWithEntity.add(name);
            } else {
                staysLocal.add(name);
            }
        }

        if (becomesCrossBoundary.size() > Math.max(invariants.size() / 2, 1)) {
            blocks.addAll(becomesCrossBoundary);
        }

        return InvariantImpact.builder()
                .staysLocal(staysLocal)
                .movesWithEntity(movesWithEntity)
                .becomesCrossBoundary(becomesCrossBoundary)
                .requiresSagaOrCompensation(requiresSaga)
                .blockingWarnings(blocks.isEmpty() ? List.of() : blocks)
                .subscriptionsMoveToSatellite(subscriptionsMove)
                .subscriptionsNeedAnchorReplica(needsAnchorReplica)
                .build();
    }

    /**
     * A cross-aggregate rule the cut does not touch keeps its subscriptions where they are; one whose
     * footprint moves takes them along; one that reads both sides loses its anchor.
     *
     * <p>"Both sides" includes the root, because the root stays with the core — the same reason an
     * intra rule over one collection can still straddle a cut that moves only that collection.
     */
    private void classifyInterInvariants(List<String> satelliteEntities,
                                         List<InterInvariantInfo> interInvariants,
                                         List<String> subscriptionsMove,
                                         List<String> needsAnchorReplica) {
        if (interInvariants == null) return;
        for (InterInvariantInfo inter : interInvariants) {
            // Only a rule with subscriptions has anything for the cut to re-home; a DECLARED one has
            // no implementation and a SERVICE_GUARD is enforced synchronously in a service method.
            if (!inter.isBackedBySubscription()) continue;

            List<String> reads = inter.getMemberEntities() == null ? List.of() : inter.getMemberEntities();
            if (reads.stream().noneMatch(satelliteEntities::contains)) continue;

            boolean readsCore = reads.stream().anyMatch(e -> !satelliteEntities.contains(e))
                    || inter.isReadsState()
                    || (inter.getRootScalars() != null && !inter.getRootScalars().isEmpty());
            if (readsCore) needsAnchorReplica.add(inter.getName());
            else subscriptionsMove.add(inter.getName());
        }
    }

    private String resolveGraphType(String invName,
                                    List<DiscoveryGraph.DiscoveredInvariant> graphInvariants) {
        if (graphInvariants == null) return null;
        return graphInvariants.stream()
                .filter(gi -> gi.getName() != null && gi.getName().equals(invName))
                .map(DiscoveryGraph.DiscoveredInvariant::getType)
                .findFirst()
                .orElse(null);
    }
}
