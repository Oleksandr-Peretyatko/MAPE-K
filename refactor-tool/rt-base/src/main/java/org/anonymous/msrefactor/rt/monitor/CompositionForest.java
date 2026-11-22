package org.anonymous.msrefactor.rt.monitor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Walks the composition graph of {@link ServiceStaticMetrics#getCompositions()}.
 *
 * <h3>Why this is one place and not two</h3>
 *
 * <p>Two callers need to know which entities travel together: the partitioner, which builds cut
 * candidates out of whole subtrees so a cut cannot orphan a child, and the structure view, which
 * renders the same containment as a tree. If each walked the graph its own way, the tool would end
 * up with two answers to "what is inside this aggregate" — and the one the architect reads on screen
 * would be the one nobody tested.
 *
 * <p>Deliberately pure: it takes the map and the candidate roots, and knows nothing about
 * {@code rootNames} fallbacks or about how a cut is scored. Those stay with the callers.
 */
public final class CompositionForest {

    private CompositionForest() {}

    /** One node of the nested view: an entity and the entities it contains. */
    public record Node(String name, List<Node> children) {}

    /**
     * The aggregate root: the composition parent that no other class contains.
     *
     * @param compositions  owning class → the classes it holds
     * @param fallbackRoots consulted when the map names no such parent — typically
     *                      {@link ServiceStaticMetrics#getRootNames()}
     * @return the root, or null when neither source names one
     */
    public static String resolveRoot(Map<String, List<String>> compositions,
                                     Collection<String> fallbackRoots) {
        if (compositions != null && !compositions.isEmpty()) {
            Set<String> children = new LinkedHashSet<>();
            compositions.values().forEach(children::addAll);
            for (String parent : compositions.keySet()) {
                if (!children.contains(parent)) return parent;
            }
        }
        return fallbackRoots == null || fallbackRoots.isEmpty()
                ? null : fallbackRoots.iterator().next();
    }

    /**
     * Every entity contained by {@code node}, including {@code node} itself, flattened.
     *
     * <p>Cycle-guarded: a class that contains itself transitively is a collector bug, not a reason to
     * hang.
     */
    public static List<String> subtree(String node, Map<String, List<String>> compositions) {
        List<String> out = new ArrayList<>();
        collect(node, compositions, out);
        return out;
    }

    /** The same walk, keeping the containment shape instead of flattening it. */
    public static Node tree(String node, Map<String, List<String>> compositions) {
        return build(node, compositions, new LinkedHashSet<>());
    }

    /** The direct members of {@code root}, each closed over its own descendants. */
    public static List<List<String>> units(String root, Map<String, List<String>> compositions) {
        List<List<String>> units = new ArrayList<>();
        for (String member : children(root, compositions)) {
            units.add(subtree(member, compositions));
        }
        return units;
    }

    private static void collect(String node, Map<String, List<String>> compositions, List<String> into) {
        if (into.contains(node)) return;
        into.add(node);
        for (String child : children(node, compositions)) {
            collect(child, compositions, into);
        }
    }

    /**
     * Each entity appears once, under the first parent that claims it.
     *
     * <p>Skipping an already-seen child rather than emitting it as a leaf is what keeps {@link #tree}
     * and {@link #subtree} describing the same set: a cycle would otherwise render the same class
     * twice on screen while the flat walk counted it once.
     */
    private static Node build(String node, Map<String, List<String>> compositions, Set<String> seen) {
        seen.add(node);
        List<Node> kids = new ArrayList<>();
        for (String child : children(node, compositions)) {
            if (seen.contains(child)) continue;
            kids.add(build(child, compositions, seen));
        }
        return new Node(node, kids);
    }

    private static List<String> children(String node, Map<String, List<String>> compositions) {
        if (compositions == null) return List.of();
        return compositions.getOrDefault(node, List.of());
    }
}
