package org.anonymous.msrefactor.rt.analyze.selector;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.anonymous.msrefactor.rt.analyze.AlternativeCandidate;
import org.anonymous.msrefactor.rt.analyze.OperationType;
import org.anonymous.msrefactor.rt.knowledge.DiscoveryGraph;
import org.anonymous.msrefactor.rt.monitor.CompositionForest;
import org.anonymous.msrefactor.rt.monitor.InterInvariantInfo;
import org.anonymous.msrefactor.rt.monitor.InvariantInfo;
import org.anonymous.msrefactor.rt.monitor.ServiceStaticMetrics;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Chooses how to divide an aggregate for a {@code SPLIT_AGGREGATE}, by the <b>cost</b> of the cut.
 *
 * <h3>What cost means</h3>
 *
 * <p>Creating the satellite costs the same whichever entities go in it — around nine artefacts, the
 * steps of {@code new-aggregate}. What varies between cuts is the invariants: a rule that ends up
 * reading both sides can no longer be a method on either, and has to become an event-synchronised
 * replica or a saga. So the price of a cut is the number of invariants it splits, and the cheapest
 * cut is the one that splits none — entities move, no rule is rewritten.
 *
 * <h3>Cross-aggregate rules count too, and not in the same way</h3>
 *
 * <p>An inter-invariant is already eventual, so a cut never has to redesign it — but it does have to
 * <b>re-home its subscriptions</b>, and that is not free:
 *
 * <ul>
 *   <li>The rule's whole footprint moves to the satellite: the subscriptions go with it, and the
 *       satellite needs its own {@code getEventSubscriptions()}, handler, polling and
 *       {@code ByEvent} method. Mechanical, but roughly five artefacts per rule.</li>
 *   <li>The rule reads both sides: the satellite cannot even subscribe, because the entity carrying
 *       the publisher's id and version stays in the core. {@code Tournament.CreatorExists} is
 *       anchored on {@code TournamentCourseExecution} while ranging over the creator and the
 *       participants — a cut that moves those needs a {@code CREATE_REPLICA} of the anchor first.
 *       That is a prerequisite operation, not a line of code.</li>
 * </ul>
 *
 * <p>{@link Cut#cost} counts every rule the cut disturbs, of either kind, with no invented weights.
 * Severity is expressed by the ordering instead: between two cuts that disturb the same number of
 * rules, the one that redesigns or re-anchors fewer of them wins.</p>
 *
 * <p>Only rules backed by a subscription count. A {@code DECLARED} rule is a header comment nothing
 * implements and a {@code SERVICE_GUARD} is enforced synchronously inside a service method: neither
 * has transport for the cut to re-home, and neither has a footprint to place on either side.</p>
 *
 * <p>The previous criterion grouped entities by which external service their replicas pointed at and
 * took the largest group. That is not a measure of anything a split costs. On the reference
 * application it chose the most expensive cut available — four straddling invariants — and one that
 * could not be executed at all, because it moved a parent and left its {@code cascade = ALL} child
 * behind.
 *
 * <h3>Why the units are whole subtrees</h3>
 *
 * <p>Candidate cuts are built from the composition forest, one unit per direct member of the root,
 * each unit carrying its own descendants. Structural validity is then a property of the
 * construction rather than a check that can fail: a cut cannot orphan a child, because a child is
 * never separable from its parent.
 *
 * <p>That relies on the composition graph being a forest — no relation between siblings. The
 * collector verifies it and warns if it is ever untrue.
 *
 * <h3>Viability comes before cost</h3>
 *
 * <p>A satellite made only of declared replicas is not an expensive cut, it is not an aggregate: it
 * would hold no state of its own. That is decided first, by {@link #isViable}, and a cut that fails it
 * is not ranked at all — with no viable cut, {@code partition} returns {@code null}. Cost then orders
 * what survives, and a replica the cut carries across counts there, because the satellite inherits the
 * quad that keeps the copy fresh.
 */
@Slf4j
@Component
public class AggregatePartitionAnalyzer {

    /** Optional — present only in the Spring context; null in plain unit tests (partition then unfiltered). */
    @Autowired(required = false)
    private ConstraintAwareSelector constraintAwareSelector;

    /**
     * Above this many direct members the subset enumeration is skipped in favour of single units.
     * No aggregate in the reference application has more than five, but 2ⁿ without a ceiling is a
     * time bomb in an application that does.
     */
    private static final int MAX_UNITS_FOR_EXHAUSTIVE = 12;

    /**
     * @param takenNames every service, aggregate and entity name already in use — the satellite's
     *                   name is numbered past them ({@link SatelliteNaming#defaultName})
     */
    public PartitionResult partition(String service, ServiceStaticMetrics sm, DiscoveryGraph graph,
                                     Collection<String> takenNames) {
        if (sm == null || sm.getEntityNames() == null || sm.getEntityNames().size() < 2) return null;

        Map<String, List<String>> compositions = sm.getCompositions();
        if (compositions == null || compositions.isEmpty()) {
            log.debug("[partition] {} — no composition data; nothing to cut along", service);
            return null;
        }

        String root = resolveRoot(sm, compositions);
        if (root == null) return null;

        List<List<String>> units = buildUnits(root, compositions);
        if (units.size() < 2) {
            // One unit means the only possible satellite is the whole aggregate, leaving no core.
            log.debug("[partition] {} — {} unit(s); no cut leaves both sides populated",
                    service, units.size());
            return null;
        }

        List<InvariantInfo> invariants = sm.getInvariants() == null ? List.of() : sm.getInvariants();
        List<InterInvariantInfo> interInvariants = enforcedInterInvariants(sm);
        List<String> allEntities = sm.getEntityNames();
        Set<String> declaredReplicas = declaredReplicas(sm);

        List<Cut> viable = enumerate(units).stream()
                .map(sat -> score(sat, invariants, interInvariants, declaredReplicas))
                .filter(cut -> isViable(cut, units, declaredReplicas))
                .toList();

        if (viable.isEmpty()) {
            // Structural inviability, not an architect's constraint: returning null is what keeps the
            // two apart. Every unit is a copy of another aggregate, so no cut leaves the satellite any
            // state of its own — `execution` in the reference application.
            log.debug("[partition] {} — no viable satellite: every unit is a pure replica ({}); "
                            + "SPLIT_AGGREGATE not emitted",
                    service, pureReplicaDetail(units, declaredReplicas));
            return null;
        }

        List<Cut> ranked = viable.stream()
                .filter(cut -> isAllowed(cut, service))
                // Total disturbance first, then how much of it is redesign rather than re-homing,
                // then the larger satellite. The severity tie-break is inert for an aggregate with
                // no inter-invariants, where severity equals cost.
                .sorted(Comparator.comparingInt(Cut::cost)
                        .thenComparingInt(Cut::severity)
                        .thenComparing(Comparator.comparingInt((Cut c) -> c.satellite().size()).reversed()))
                .toList();

        if (ranked.isEmpty()) {
            return new PartitionResult(allEntities, List.of(), null, List.of(), 0,
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                    true, blockReasons(units, service));
        }

        Cut best = ranked.get(0);
        List<String> core = allEntities.stream()
                .filter(e -> !best.satellite().contains(e))
                .collect(Collectors.toList());
        if (core.isEmpty()) return null;

        List<AlternativeCandidate> alternatives = ranked.stream().skip(1).limit(4)
                .map(c -> AlternativeCandidate.builder()
                        .operation("SPLIT_AGGREGATE")
                        // The members, not a name: every alternative would get the same default name.
                        .target(String.join(",", c.satellite()))
                        .score(0.0)
                        .reasonRejectedOrRankedLower(String.format(
                                "%d rule(s) would be disturbed by the cut versus %d for the chosen "
                                        + "partition%s",
                                c.cost(), best.cost(), disturbedDetail(c)))
                        .build())
                .collect(Collectors.toList());

        log.debug("[partition] {} → satellite {} at cost {} (straddling={}, subscriptions moved={}, "
                        + "anchors split={}, replicas moved={}, replicas shared={}, {} alternatives)",
                service, best.satellite(), best.cost(), best.straddling().size(),
                best.movedSubscriptions().size(), best.splitAnchors().size(),
                best.replicasMoved().size(), best.replicasShared().size(), alternatives.size());

        return new PartitionResult(core, best.satellite(), SatelliteNaming.defaultName(service, takenNames),
                alternatives, best.cost(), best.straddling(), best.temporal(),
                best.movedSubscriptions(), best.splitAnchors(),
                best.replicasMoved(), best.replicasShared(), false, List.of());
    }

    /** The named rules behind a rejected cut's number, so the alternative says what it would cost. */
    private String disturbedDetail(Cut cut) {
        List<String> parts = new ArrayList<>();
        if (!cut.straddling().isEmpty()) {
            parts.add("straddling: " + String.join(", ", cut.straddling()));
        }
        if (!cut.splitAnchors().isEmpty()) {
            parts.add("anchor replicated for: " + String.join(", ", cut.splitAnchors()));
        }
        if (!cut.movedSubscriptions().isEmpty()) {
            parts.add("subscriptions moved: " + String.join(", ", cut.movedSubscriptions()));
        }
        if (!cut.replicasMoved().isEmpty()) {
            parts.add("replicas moved: " + String.join(", ", cut.replicasMoved()));
        }
        if (!cut.replicasShared().isEmpty()) {
            parts.add("replicas both sides read: " + String.join(", ", cut.replicasShared()));
        }
        return parts.isEmpty() ? "" : " — " + String.join("; ", parts);
    }

    /**
     * The cross-aggregate rules a cut can actually disturb: the ones backed by a subscription.
     *
     * <p>A {@code DECLARED} rule is a line in the aggregate's header nothing implements, and a
     * {@code SERVICE_GUARD} is enforced synchronously in a service method. Neither has transport the
     * cut has to re-home, so neither costs anything; counting them would charge a partition for a
     * comment, or for code that does not move.
     */
    private List<InterInvariantInfo> enforcedInterInvariants(ServiceStaticMetrics sm) {
        if (sm.getInterInvariants() == null) return List.of();
        return sm.getInterInvariants().stream()
                .filter(InterInvariantInfo::isBackedBySubscription)
                .toList();
    }

    // ─── Building the units ─────────────────────────────────────────────────────

    /**
     * The composition parent that nothing else contains.
     *
     * <p>The walk itself lives in {@link CompositionForest}, shared with the structure view: what
     * counts as "inside this aggregate" has to be one answer, and the one an architect reads on
     * screen has to be the one this partitioner cuts along.
     */
    private String resolveRoot(ServiceStaticMetrics sm, Map<String, List<String>> compositions) {
        return CompositionForest.resolveRoot(compositions, sm.getRootNames());
    }

    /** One unit per direct member of the root, each closed over its own descendants. */
    private List<List<String>> buildUnits(String root, Map<String, List<String>> compositions) {
        return CompositionForest.units(root, compositions);
    }

    /** Every non-empty proper subset of the units, as a flat entity list. */
    private List<List<String>> enumerate(List<List<String>> units) {
        List<List<String>> out = new ArrayList<>();
        if (units.size() > MAX_UNITS_FOR_EXHAUSTIVE) {
            log.warn("[partition] {} units exceeds the exhaustive ceiling of {}; evaluating single "
                   + "units only, so a cheaper combination may be missed",
                    units.size(), MAX_UNITS_FOR_EXHAUSTIVE);
            units.forEach(u -> out.add(new ArrayList<>(u)));
            return out;
        }
        int total = 1 << units.size();
        for (int mask = 1; mask < total - 1; mask++) {         // skip empty and all-units
            List<String> combined = new ArrayList<>();
            for (int i = 0; i < units.size(); i++) {
                if ((mask & (1 << i)) != 0) combined.addAll(units.get(i));
            }
            out.add(combined);
        }
        return out;
    }

    // ─── Scoring ────────────────────────────────────────────────────────────────

    /**
     * An invariant straddles when it reads something inside the satellite and something outside.
     * "Outside" includes every root scalar and the aggregate's own state, because the root stays
     * with the core — that is the part most easily missed, and the reason a rule over one collection
     * can still be split by a cut that moves only that collection.
     */
    private Cut score(List<String> satellite, List<InvariantInfo> invariants,
                      List<InterInvariantInfo> interInvariants, Set<String> declaredReplicas) {
        List<String> straddling = new ArrayList<>();
        List<String> temporal = new ArrayList<>();
        // What a rule still reaching into the core reads. A replica named here cannot simply move
        // with the cut, because the core goes on reasoning over that copy.
        Set<String> readsOfStraddlingRules = new LinkedHashSet<>();

        for (InvariantInfo inv : invariants) {
            List<String> reads = inv.getMemberEntities() == null ? List.of() : inv.getMemberEntities();
            boolean touchesSat = reads.stream().anyMatch(satellite::contains);
            if (!touchesSat) continue;

            boolean needsCore = reads.stream().anyMatch(e -> !satellite.contains(e))
                    || inv.isReadsState()
                    || (inv.getRootScalars() != null && !inv.getRootScalars().isEmpty());
            if (!needsCore) continue;

            straddling.add(inv.getName());
            readsOfStraddlingRules.addAll(reads);
            // Counted separately, not as extra cost: a rule comparing against getPrev() cannot span
            // the boundary at all, because each side would keep its own version chain. It has to be
            // duplicated, which is a redesign the plan should name rather than bury in a number.
            if (inv.isReadsPrev()) temporal.add(inv.getName());
        }

        List<String> movedSubscriptions = new ArrayList<>();
        List<String> splitAnchors = new ArrayList<>();

        for (InterInvariantInfo inter : interInvariants) {
            List<String> reads = inter.getMemberEntities() == null ? List.of() : inter.getMemberEntities();
            boolean touchesSat = reads.stream().anyMatch(satellite::contains);
            // A rule the cut leaves entirely in the core keeps its subscriptions where they are.
            if (!touchesSat) continue;

            boolean needsCore = reads.stream().anyMatch(e -> !satellite.contains(e))
                    || inter.isReadsState()
                    || (inter.getRootScalars() != null && !inter.getRootScalars().isEmpty());
            // Straddling here does not mean redesign — the rule is eventual already. It means the
            // satellite has no anchor to subscribe with, because the entity holding the publisher's
            // id and version stayed behind. Resolving it needs a CREATE_REPLICA before the split.
            if (needsCore) {
                splitAnchors.add(inter.getName());
                readsOfStraddlingRules.addAll(reads);
            } else {
                movedSubscriptions.add(inter.getName());
            }
        }

        // Every declared copy the cut carries across. Each one obliges the satellite to own the quad
        // that keeps it fresh — subscription, handler, @Scheduled, process…Event — and nothing else in
        // this class prices that: movedSubscriptions counts only @InterInvariant rules, and a plain
        // refresh subscription carries no annotation (`answer` has five subscriptions and three rules).
        List<String> replicasMoved = satellite.stream()
                .filter(declaredReplicas::contains)
                .collect(Collectors.toList());

        // The copy the core still reads. It has to be duplicated — two refresh paths, two lag
        // windows — or one side re-pointed to a remote read, which is a CREATE_REPLICA before the
        // split. Severity, not extra cost: the artefacts are already counted above.
        List<String> replicasShared = replicasMoved.stream()
                .filter(readsOfStraddlingRules::contains)
                .collect(Collectors.toList());

        return new Cut(satellite, straddling, temporal, movedSubscriptions, splitAnchors,
                replicasMoved, replicasShared);
    }

    /**
     * Whether the cut leaves the satellite any state of its own.
     *
     * <p>A satellite made only of declared copies is not an aggregate — it is a cache with a service
     * around it, and the skill refuses it as a degenerate split. That is a <b>binary</b> property, not
     * an expensive one: a cut with a high cost is still a candidate an architect may approve, while
     * this one has nothing to approve.
     *
     * <p><b>A unit is authoritative when it is not a declared replica, or when it owns a subtree.</b>
     * The second half is what makes the predicate usable: {@code TournamentParticipant} is
     * {@code @Replica("User")} and yet carries {@code enrollTime} and owns
     * {@code TournamentParticipantQuizAnswer} — it is the satellite of the one clearly feasible split
     * in the reference application, so "not annotated" alone would reject the good case.
     *
     * <p><b>Owning a subtree is a proxy</b> for carrying state of its own, which a pure cache does not.
     * The literal criterion is a field no {@code process…Event} writes, and it is not computable yet:
     * {@code ServiceStaticMetrics} records entities, not their fields. Known limit of the proxy: a
     * replica whose children are also replicas is judged authoritative by structure rather than by data.
     */
    private boolean isViable(Cut cut, List<List<String>> units, Set<String> declaredReplicas) {
        if (declaredReplicas.isEmpty()) return true;   // nothing declared: every unit is authoritative
        return units.stream()
                .filter(unit -> !unit.isEmpty() && cut.satellite().contains(unit.get(0)))
                .anyMatch(unit -> authoritative(unit, declaredReplicas));
    }

    /** A unit carries state of its own when it is not a declared copy, or when it owns children. */
    private boolean authoritative(List<String> unit, Set<String> declaredReplicas) {
        return unit.size() > 1 || !declaredReplicas.contains(unit.get(0));
    }

    /** Every class this service declares as a copy of another aggregate, from {@code @Replica}. */
    private Set<String> declaredReplicas(ServiceStaticMetrics sm) {
        if (sm.getReplicaEntities() == null) return Set.of();
        return sm.getReplicaEntities().values().stream()
                .filter(java.util.Objects::nonNull)
                .flatMap(List::stream)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** Names the units behind a structural refusal, so the log says which copies of what. */
    private String pureReplicaDetail(List<List<String>> units, Set<String> declaredReplicas) {
        return units.stream()
                .filter(unit -> !unit.isEmpty() && !authoritative(unit, declaredReplicas))
                .map(unit -> unit.get(0))
                .collect(Collectors.joining(", "));
    }

    private boolean isAllowed(Cut cut, String service) {
        if (constraintAwareSelector == null || !constraintAwareSelector.enabled()) return true;
        return cut.satellite().stream().noneMatch(e -> constraintAwareSelector.isBlocked(
                OperationType.SPLIT_AGGREGATE.name(), service, null, List.of(e)));
    }

    private List<String> blockReasons(List<List<String>> units, String service) {
        if (constraintAwareSelector == null || !constraintAwareSelector.enabled()) return List.of();
        return units.stream().flatMap(List::stream)
                .flatMap(e -> constraintAwareSelector.blockingReasons(
                        OperationType.SPLIT_AGGREGATE.name(), service, null, List.of(e)).stream())
                .distinct().collect(Collectors.toList());
    }

    // ─── Result types ───────────────────────────────────────────────────────────

    private record Cut(List<String> satellite, List<String> straddling, List<String> temporal,
                       List<String> movedSubscriptions, List<String> splitAnchors,
                       List<String> replicasMoved, List<String> replicasShared) {

        /**
         * Every rule the cut disturbs, of either kind, plus every copy it carries across.
         *
         * <p>A replica belongs in the same currency as a rule: moving it obliges the satellite to own
         * the four pieces that keep it fresh. Between two cuts that disturb the same rules, the one
         * that moves fewer copies is the cheaper one.
         */
        int cost() {
            return straddling.size() + movedSubscriptions.size() + splitAnchors.size()
                    + replicasMoved.size();
        }

        /**
         * The part of {@link #cost()} that is not mechanical: an intra rule to redesign, or a
         * subscription with no anchor left on its side. Breaks ties between equally disturbing cuts
         * in favour of the one whose work is scaffolding.
         */
        int severity() {
            return straddling.size() + splitAnchors.size() + replicasShared.size();
        }
    }

    public record PartitionResult(
            List<String> coreEntities,
            List<String> satelliteEntities,
            String satelliteName,
            List<AlternativeCandidate> alternativePartitions,
            /**
             * Every rule the cut disturbs: intra-invariants it splits, plus cross-aggregate rules
             * whose subscriptions have to be re-homed. 0 means entities move and nothing else does.
             */
            int cost,
            List<String> straddlingInvariants,
            /** Straddling rules that compare against {@code getPrev()} and must be duplicated. */
            List<String> temporalInvariants,
            /**
             * Cross-aggregate rules that move to the satellite whole: their subscriptions, handler,
             * polling and {@code ByEvent} method go with them. Mechanical work, no redesign.
             */
            List<String> interInvariantsMovingToSatellite,
            /**
             * Cross-aggregate rules the cut leaves without an anchor: they range over the satellite
             * while the entity carrying the publisher's id and version stays in the core, so the
             * satellite needs a {@code CREATE_REPLICA} of that anchor before it can subscribe.
             */
            List<String> interInvariantsSplittingAnchor,
            /**
             * The declared copies the cut carries into the satellite. Each one takes its refresh quad
             * with it: the satellite needs its own {@code getEventSubscriptions()}, handler,
             * {@code @Scheduled} and {@code process…Event} to keep the copy current.
             */
            List<String> replicasMoved,
            /**
             * The copies the core still reads through a straddling rule. They cannot simply move:
             * either both sides keep a copy — two refresh paths, two lag windows — or one side
             * re-points to a remote read, which is a {@code CREATE_REPLICA} before the split.
             */
            List<String> replicasShared,
            boolean allBlocked,
            List<String> blockReasons
    ) {}
}
