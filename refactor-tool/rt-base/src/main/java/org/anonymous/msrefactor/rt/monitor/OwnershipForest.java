package org.anonymous.msrefactor.rt.monitor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Which aggregate owns which — the precondition {@code MergeAggregates} is defined on.
 *
 * <p>Coupling says a merge is <em>worth</em> doing; ownership is what makes it <em>possible</em>. An
 * aggregate {@code A} owns {@code B} when every {@code B} belongs to exactly one {@code A} for its
 * whole life, and three independent signals decide it:
 *
 * <table>
 *   <tr><td><b>O1</b></td><td>{@code B}'s root holds a <b>single-valued</b> replica of {@code A}</td></tr>
 *   <tr><td><b>O2</b></td><td>{@code B} is created in {@code A}'s scope — a creation path carries
 *       {@code A}'s id as a path variable</td></tr>
 *   <tr><td><b>O3</b></td><td>a parent-scoped collection query exists —
 *       {@code Find<B>sBy<A>IdCommand}</td></tr>
 * </table>
 *
 * <p>The rule is <b>O1 and at least one of O2/O3</b>, and O1 is evaluated first: it decides which
 * pairs the other two are ever asked about. O2's match is deliberately loose
 * ({@code courseExecutionId} contains {@code execution}), which is safe only under that gate — the
 * three signals must not be collapsed into an unordered "any two of three".
 *
 * <p>This reproduces the classifier the execution skill runs by hand
 * ({@code refactor-merge-aggregate/SKILL.md}, <i>Ownership Classifier</i>), so that Analyze proposes
 * only pairs the operation can actually accept. The two must not drift.
 *
 * <p>Pure: it takes the maps a snapshot already carries and returns verdicts. What to do with a
 * verdict — propose, block, warn — is the caller's policy.
 */
public final class OwnershipForest {

    private OwnershipForest() {}

    /** One aggregate's ownership inputs, assembled by the caller from a {@link ServiceStaticMetrics}. */
    public record Unit(
            String service,
            String root,
            /** {@link ServiceStaticMetrics#getSingleValuedMembers()} for this root; null = unknown. */
            List<String> singleValuedMembers,
            /** {@link ServiceStaticMetrics#getReplicaEntities()}: foreign root → local classes; null = unknown. */
            Map<String, List<String>> replicaEntities,
            /** {@link ServiceStaticMetrics#getPostEndpoints()} — raw paths, variable names intact. */
            List<String> postEndpoints,
            /** The command class names this service handles. */
            List<String> commandNames) {
    }

    public enum Verdict {
        /** One candidate satisfies O1 plus O2 or O3, alone. */
        OWNED,
        /** An O1 link exists but the full rule fails, or two candidates tie — ownership is ambiguous. */
        AMBIGUOUS,
        /** No aggregate holds a single-valued replica of another: the pair has no ownership path. */
        NONE,
        /** The snapshot carries no cardinality or replica data — the question could not be asked. */
        NO_DATA
    }

    public record Signals(boolean o1, boolean o2, boolean o3, String viaMember) {
        public int count() {
            return (o1 ? 1 : 0) + (o2 ? 1 : 0) + (o3 ? 1 : 0);
        }
    }

    /**
     * A candidate ownership edge, owner → child, with the evidence behind it.
     *
     * <p>{@code rivalRoots} names the other O1 candidates, which is what an {@code AMBIGUOUS} verdict
     * needs to be explainable: {@code QuizAnswer} has three of them.
     */
    public record Edge(
            String ownerService, String ownerRoot,
            String childService, String childRoot,
            Signals signals, Verdict verdict, List<String> rivalRoots) {

        public boolean owned() {
            return verdict == Verdict.OWNED;
        }
    }

    /**
     * The owner of every unit, keyed by child root. Every unit gets an entry: a root with no owner
     * carries a {@code NONE}, {@code AMBIGUOUS} or {@code NO_DATA} verdict and no owner names.
     */
    public static Map<String, Edge> owners(Collection<Unit> units) {
        Map<String, Edge> out = new LinkedHashMap<>();
        if (units == null) return out;
        for (Unit child : units) {
            out.put(child.root(), ownerOf(child, units));
        }
        return out;
    }

    /** The winning edge for one child — the same computation {@link #owners} runs over all of them. */
    public static Edge ownerOf(Unit child, Collection<Unit> units) {
        if (!hasOwnershipData(units)) {
            return noOwner(child, Verdict.NO_DATA, List.of());
        }

        List<Unit> candidates = new ArrayList<>();
        for (Unit owner : units) {
            if (owner.root().equals(child.root())) continue;
            if (o1(owner, child)) candidates.add(owner);
        }
        if (candidates.isEmpty()) return noOwner(child, Verdict.NONE, List.of());

        List<String> rivals = candidates.stream().map(Unit::root).toList();

        Unit best = null;
        Signals bestSignals = null;
        boolean tied = false;
        for (Unit owner : candidates) {
            Signals s = signalsFor(owner, child);
            if (!s.o2() && !s.o3()) continue;                 // O1 alone is not ownership
            if (bestSignals == null || s.count() > bestSignals.count()) {
                best = owner;
                bestSignals = s;
                tied = false;
            } else if (s.count() == bestSignals.count()) {
                tied = true;
            }
        }

        if (bestSignals == null || tied) {
            return noOwner(child, Verdict.AMBIGUOUS, rivals);
        }
        return new Edge(best.service(), best.root(), child.service(), child.root(),
                bestSignals, Verdict.OWNED, rivals);
    }

    /**
     * {@code [X, parent(X), …]} — the structural chain, starting with {@code X} itself.
     *
     * <p>Follows <b>O1 alone</b>, because the chain is structure rather than a verdict: {@code Quiz}
     * hangs under {@code Execution} whether or not the full rule confirms it. Where a root has
     * several O1 candidates the one with the most signals wins, and a genuine tie ends the chain.
     */
    public static List<String> ownerChain(String root, Collection<Unit> units) {
        List<String> chain = new ArrayList<>();
        if (root == null || units == null) return chain;
        Set<String> seen = new LinkedHashSet<>();
        String current = root;
        while (current != null && seen.add(current)) {
            chain.add(current);
            current = o1Parent(current, units);
        }
        return chain;
    }

    /** The deepest root common to both chains, or empty when the two sit in different trees. */
    public static Optional<String> lowestCommonAncestor(String a, String b, Collection<Unit> units) {
        List<String> chainB = ownerChain(b, units);
        for (String node : ownerChain(a, units)) {
            if (chainB.contains(node)) return Optional.of(node);
        }
        return Optional.empty();
    }

    /**
     * Whether the snapshot can answer the question at all.
     *
     * <p>Asked of the whole collection, not of one unit: a root that holds nothing single-valued —
     * {@code Course}, {@code User} — is a root, not missing data, and must come out as
     * {@link Verdict#NONE}. Only a snapshot where <em>no</em> service carries the field predates the
     * collection and is genuinely unanswerable.
     */
    public static boolean hasOwnershipData(Collection<Unit> units) {
        if (units == null) return false;
        for (Unit u : units) {
            if (u.singleValuedMembers() != null && u.replicaEntities() != null) return true;
        }
        return false;
    }

    // ─── Signals ─────────────────────────────────────────────────────────────────

    static Signals signalsFor(Unit owner, Unit child) {
        String via = o1Member(owner, child);
        return new Signals(via != null, o2(owner, child), o3(owner, child), via);
    }

    /** O1 — the child's root holds a single-valued member that declares itself a replica of the owner. */
    static boolean o1(Unit owner, Unit child) {
        return o1Member(owner, child) != null;
    }

    private static String o1Member(Unit owner, Unit child) {
        if (child.singleValuedMembers() == null || child.replicaEntities() == null) return null;
        List<String> replicasOfOwner = child.replicaEntities().get(owner.root());
        if (replicasOfOwner == null) return null;
        for (String member : child.singleValuedMembers()) {
            if (replicasOfOwner.contains(member)) return member;
        }
        return null;
    }

    /**
     * O2 — the child is created inside the owner's scope: one of its own POST paths carries a
     * variable named after the owner's root, as in
     * {@code /courses/{courseAggregateId}/questions/create}.
     */
    static boolean o2(Unit owner, Unit child) {
        if (child.postEndpoints() == null) return false;
        String root = owner.root().toLowerCase();
        for (String path : child.postEndpoints()) {
            for (String variable : pathVariables(path)) {
                if (variable.toLowerCase().contains(root)) return true;
            }
        }
        return false;
    }

    /**
     * O3 — a parent-scoped collection query: {@code FindTopicsByCourseIdCommand},
     * {@code GetTournamentsByCourseExecutionIdCommand}.
     *
     * <p>The {@code \w*} after {@code By} is what lets {@code …ByCourseExecutionId} count for
     * {@code Execution}; requiring the root to be followed immediately by {@code Id} or
     * {@code AggregateId} is what stops that same name counting for {@code Course}.
     */
    static boolean o3(Unit owner, Unit child) {
        if (child.commandNames() == null) return false;
        Pattern scoped = Pattern.compile(
                "^(?:Find|Get)\\w*By\\w*" + Pattern.quote(owner.root()) + "(?:Aggregate)?Id");
        for (String command : child.commandNames()) {
            if (command != null && scoped.matcher(command).find()) return true;
        }
        return false;
    }

    // ─── Internals ───────────────────────────────────────────────────────────────

    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{([^}]+)}");

    private static List<String> pathVariables(String path) {
        List<String> names = new ArrayList<>();
        if (path == null) return names;
        PATH_VARIABLE.matcher(path).results().forEach(m -> names.add(m.group(1)));
        return names;
    }

    private static Edge noOwner(Unit child, Verdict verdict, List<String> rivals) {
        return new Edge(null, null, child.service(), child.root(),
                new Signals(false, false, false, null), verdict, rivals);
    }

    private static String o1Parent(String root, Collection<Unit> units) {
        Unit child = unitOf(root, units);
        if (child == null) return null;

        String best = null;
        int bestCount = -1;
        boolean tied = false;
        for (Unit owner : units) {
            if (owner.root().equals(root) || !o1(owner, child)) continue;
            int count = signalsFor(owner, child).count();
            if (count > bestCount) {
                best = owner.root();
                bestCount = count;
                tied = false;
            } else if (count == bestCount) {
                tied = true;
            }
        }
        return tied ? null : best;
    }

    private static Unit unitOf(String root, Collection<Unit> units) {
        for (Unit u : units) {
            if (u.root().equals(root)) return u;
        }
        return null;
    }
}
