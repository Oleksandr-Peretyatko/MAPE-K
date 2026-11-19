package org.anonymous.msrefactor.rt.analyze.selector;

import org.anonymous.msrefactor.rt.knowledge.DiscoveryGraph;
import org.anonymous.msrefactor.rt.monitor.ServiceStaticMetrics;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The name a {@code SPLIT_AGGREGATE} gives its satellite, and the bounds any proposed name must keep.
 *
 * <p>The name is not a label. It becomes the {@code --new-aggregate} argument, and from there the
 * satellite's class, package directory, service, Spring profile, container and database. Every file
 * the split creates carries it twice — directory and class — so its length is paid twice on the
 * deepest path, {@code 165 + 2·length} characters from the repository root, and a Windows checkout
 * cannot create a path over 260. v6 of the evaluation split {@code Tournament} into
 * {@code TournamentCreatorParticipantParticipantQuizAnswerCluster}, a name built from the moved
 * entities: 23 of the files it committed silently do not exist in a Windows clone of the branch.
 *
 * <p>So the default is deliberately plain and predictable, {@code <Aggr>Satellite}, numbered when
 * taken. The split skill applies the same bounds and the same fallback to whatever it is handed, so
 * the pipeline and the skill always agree on the name. An architect may still supply a better one.
 */
public final class SatelliteNaming {

    /**
     * {@code <Aggr>Satellite}, then {@code <Aggr>Satellite2}, {@code …3} until it clashes with none of
     * {@code taken} (compared case-insensitively — a package directory is the lower-first form).
     */
    public static String defaultName(String aggregate, Collection<String> taken) {
        String base = ServiceNaming.cap(aggregate) + "Satellite";
        Set<String> lower = lowercase(taken);
        String name = base;
        for (int n = 2; lower.contains(name.toLowerCase(Locale.ROOT)); n++) {
            name = base + n;
        }
        return name;
    }

    /**
     * Every service, aggregate and entity name the application already uses — what a new satellite
     * must not collide with. Either argument may be {@code null}.
     */
    public static Set<String> takenNames(Map<String, ServiceStaticMetrics> perService, DiscoveryGraph graph) {
        Set<String> taken = new HashSet<>();
        if (perService != null) {
            perService.forEach((service, sm) -> {
                taken.add(service);
                if (sm == null) return;
                taken.add(sm.getServiceName());
                addAll(taken, sm.getEntityNames());
                addAll(taken, sm.getRootNames());
            });
        }
        if (graph != null && graph.getServices() != null) {
            for (DiscoveryGraph.DiscoveredService s : graph.getServices()) {
                taken.add(s.getName());
                if (s.getAggregates() == null) continue;
                for (DiscoveryGraph.DiscoveredAggregate a : s.getAggregates()) {
                    taken.add(a.getName());
                    addAll(taken, a.getEntities());
                }
            }
        }
        taken.remove(null);
        return taken;
    }

    private static void addAll(Set<String> into, List<String> names) {
        if (names != null) into.addAll(names);
    }

    private static Set<String> lowercase(Collection<String> names) {
        if (names == null) return Set.of();
        return names.stream().filter(Objects::nonNull)
                .map(n -> n.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }

    private SatelliteNaming() {}
}
