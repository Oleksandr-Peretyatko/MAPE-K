package org.anonymous.msrefactor.rt.analyze.selector;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.anonymous.msrefactor.rt.analyze.StructuralEvidence;
import org.anonymous.msrefactor.rt.monitor.CommandInfo;
import org.anonymous.msrefactor.rt.monitor.CompositionForest;
import org.anonymous.msrefactor.rt.monitor.OwnershipForest;
import org.anonymous.msrefactor.rt.monitor.ServiceStaticMetrics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Whether a pair of services may be merged at all, and in which direction.
 *
 * <p>Coupling decides which merges are <em>worth</em> proposing; ownership decides which are
 * <em>possible</em>. Without this filter Analyze proposes pairs the operation refuses at execution
 * time — {@code question + topic}, whose relation is N:M, or anything paired with {@code user},
 * which owns nothing and is owned by nothing.
 *
 * <p>Thin on purpose, like {@link ConstraintAwareSelector}: {@link OwnershipForest} holds the rules,
 * this class adapts a snapshot to them and phrases the verdict. The caller keeps the policy.
 */
@Slf4j
@Component
public class OwnershipSelector {

    /** The ownership picture of one snapshot, computed once per analysis. */
    public record Index(List<OwnershipForest.Unit> units,
                        Map<String, OwnershipForest.Edge> ownerByRoot,
                        Map<String, String> serviceByRoot) {

        public boolean hasData() {
            return OwnershipForest.hasOwnershipData(units);
        }
    }

    /** An ordered merge pair: {@code owner} survives, {@code child} dissolves into it. */
    public record Pair(String owner, String child, OwnershipForest.Edge edge) {}

    /** Builds the index from a snapshot's per-service metrics. Never null; may be empty. */
    public Index index(StructuralEvidence structural) {
        List<OwnershipForest.Unit> units = new ArrayList<>();
        Map<String, String> serviceByRoot = new LinkedHashMap<>();

        Map<String, ServiceStaticMetrics> perService =
                structural == null ? null : structural.getPerService();
        if (perService != null) {
            perService.forEach((service, sm) -> {
                for (String root : rootsOf(sm)) {
                    units.add(unitFor(service, root, sm));
                    serviceByRoot.putIfAbsent(root, service);
                }
            });
        }
        return new Index(units, OwnershipForest.owners(units), serviceByRoot);
    }

    /**
     * The ordered pair for {@code (a, b)} when one of them owns the other, in whichever direction —
     * the operation is agnostic to the order it was asked in, so a hotspot on the child still yields
     * the owner as the surviving service.
     */
    public Optional<Pair> mergeable(Index ix, String a, String b) {
        if (ix == null || a == null || b == null || a.equals(b)) return Optional.empty();
        return ownedEdgeBetween(ix, a, b)
                .map(e -> new Pair(e.ownerService(), e.childService(), e));
    }

    /** Every confirmed ownership edge in the snapshot — the pairs a merge may name. */
    public List<OwnershipForest.Edge> ownedEdges(Index ix) {
        if (ix == null) return List.of();
        return ix.ownerByRoot().values().stream().filter(OwnershipForest.Edge::owned).toList();
    }

    /**
     * Why this pair cannot be merged, in one sentence naming the evidence.
     *
     * <p>Never "no reason": a pair reaches this method only because coupling proposed it, and an
     * architect who sees it disappear is owed the cause.
     */
    public String blockReason(Index ix, String a, String b) {
        if (ix == null || !ix.hasData()) {
            return "ownership could not be checked — this snapshot carries no cardinality data; "
                 + "re-run Monitor › Static";
        }
        Optional<Step> next = nextStep(ix, a, b);
        if (next.isPresent()) {
            Step s = next.get();
            if (!s.feasible()) {
                // Name why that hop is not an edge. When the hop *is* this pair, that cause is the
                // whole answer; when it lies above the pair, it is what the pair is waiting on.
                String why = ambiguityBetween(ix, s.owner(), s.child());
                boolean hopIsThisPair = (s.owner().equals(a) && s.child().equals(b))
                                     || (s.owner().equals(b) && s.child().equals(a));
                if (why != null) {
                    return hopIsThisPair ? why
                            : String.format("%s and %s are not parent and child, and their next step "
                                    + "%s is itself blocked: %s", a, b, s.notation(), why);
                }
                return String.format("%s and %s are not parent and child, and the step toward their "
                        + "common owner — %s — is itself not an ownership edge, so this pair is not "
                        + "reachable today", a, b, s.notation());
            }
            return String.format("%s and %s are not parent and child — the next step toward their "
                    + "common owner is %s, and this pair is re-evaluated once it has run",
                    a, b, s.notation());
        }
        String ambiguous = ambiguityBetween(ix, a, b);
        if (ambiguous != null) return ambiguous;
        return String.format("no ownership relation between %s and %s — neither holds a single-valued "
                + "replica of the other, so no single root could contain both", a, b);
    }

    /** One hop of a chain, in the {@code owner<-child} notation the prerequisite channel uses. */
    public record Step(String owner, String child, boolean feasible) {
        public String notation() {
            return owner + "<-" + child;
        }
    }

    /**
     * The <b>single</b> merge to run next for a pair that is not itself an ownership edge.
     *
     * <p>Deliberately one hop, not the whole chain. The loop applies one operation per iteration and
     * re-analyses the result, so the rest of the chain is recomputed from the snapshot the first hop
     * produced — where it may have become a plain ownership edge, or may no longer exist at all as a
     * pair. Emitting four steps would ask the architect to commit to a path across three snapshots
     * that do not exist yet.
     *
     * <p>Covers both shapes: siblings, where the hop is taken from {@code a} toward the common owner,
     * and ancestor/descendant, where {@code a} <em>is</em> the common owner and the hop is taken from
     * {@code b}. {@link Step#feasible()} is false when that hop is not an ownership edge either —
     * {@code course<-execution}, whose link exists but whose rule fails.
     */
    public Optional<Step> nextStep(Index ix, String a, String b) {
        if (ix == null || a == null || b == null || a.equals(b)) return Optional.empty();
        String rootA = rootOf(ix, a);
        String rootB = rootOf(ix, b);
        if (rootA == null || rootB == null) return Optional.empty();
        if (ownedEdgeBetween(ix, a, b).isPresent()) return Optional.empty();   // already a candidate

        Optional<String> lca = OwnershipForest.lowestCommonAncestor(rootA, rootB, ix.units());
        if (lca.isEmpty()) return Optional.empty();                            // different trees
        String ancestor = lca.get();

        // Whoever is not the common owner is the one that has to move first.
        String climber = ancestor.equals(rootA) ? rootB : rootA;
        if (climber.equals(ancestor)) return Optional.empty();

        List<String> chain = OwnershipForest.ownerChain(climber, ix.units());
        if (chain.size() < 2) return Optional.empty();
        String child = chain.get(0);
        String parent = chain.get(1);

        OwnershipForest.Edge edge = ix.ownerByRoot().get(child);
        boolean feasible = edge != null && edge.owned() && parent.equals(edge.ownerRoot());
        return Optional.of(new Step(serviceOf(ix, parent), serviceOf(ix, child), feasible));
    }

    /**
     * The next step as a one-element {@code dependencies} list, or empty when there is none.
     *
     * <p>A list because that is the field's shape; one element because one operation runs per
     * iteration — see {@link #nextStep}.
     */
    public List<String> chainFor(Index ix, String a, String b) {
        return nextStep(ix, a, b).map(s -> List.of(s.notation())).orElse(List.of());
    }

    // ─── Internals ───────────────────────────────────────────────────────────────

    private Optional<OwnershipForest.Edge> ownedEdgeBetween(Index ix, String a, String b) {
        for (OwnershipForest.Edge e : ownedEdges(ix)) {
            boolean forward  = a.equals(e.ownerService()) && b.equals(e.childService());
            boolean backward = b.equals(e.ownerService()) && a.equals(e.childService());
            if (forward || backward) return Optional.of(e);
        }
        return Optional.empty();
    }

    /** The "an O1 link exists but the rule fails" case, phrased with its signal count. */
    private String ambiguityBetween(Index ix, String a, String b) {
        for (String[] pair : new String[][]{{a, b}, {b, a}}) {
            String childRoot = rootOf(ix, pair[1]);
            if (childRoot == null) continue;
            OwnershipForest.Edge e = ix.ownerByRoot().get(childRoot);
            if (e == null || e.verdict() != OwnershipForest.Verdict.AMBIGUOUS) continue;
            if (!e.rivalRoots().contains(rootOf(ix, pair[0]))) continue;
            return String.format("ownership of %s by %s is ambiguous — the single-valued link exists "
                    + "(%s) but %s is created and queried independently of it",
                    pair[1], pair[0], String.join(", ", e.rivalRoots()), pair[1]);
        }
        return null;
    }

    private String rootOf(Index ix, String service) {
        return ix.units().stream()
                .filter(u -> u.service().equals(service))
                .map(OwnershipForest.Unit::root)
                .findFirst().orElse(null);
    }

    private String serviceOf(Index ix, String root) {
        return ix.serviceByRoot().getOrDefault(root, root.toLowerCase());
    }

    private List<String> rootsOf(ServiceStaticMetrics sm) {
        if (sm.getRootNames() != null && !sm.getRootNames().isEmpty()) return sm.getRootNames();
        String resolved = CompositionForest.resolveRoot(sm.getCompositions(), sm.getRootNames());
        return resolved == null ? List.of() : List.of(resolved);
    }

    private OwnershipForest.Unit unitFor(String service, String root, ServiceStaticMetrics sm) {
        // Null means the snapshot never collected the field; an absent key means this root holds
        // nothing single-valued, which is a fact about the root and not missing data.
        List<String> singleValued = sm.getSingleValuedMembers() == null
                ? null
                : sm.getSingleValuedMembers().getOrDefault(root, List.of());
        List<String> commands = sm.getHandledCommands() == null ? List.of()
                : sm.getHandledCommands().stream()
                        .map(CommandInfo::getName).filter(Objects::nonNull).toList();
        return new OwnershipForest.Unit(service, root, singleValued,
                sm.getReplicaEntities(), sm.getPostEndpoints(), commands);
    }
}
