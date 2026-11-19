package org.anonymous.msrefactor.rt.monitor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The normalised structural signals the quality scores are built from, computed from the raw
 * counts a {@link StaticMetrics} snapshot already carries.
 *
 * <p>Every signal here lies in {@code [0, 1]} (or is a count whose threshold is documented at the
 * consumer) and its best value is <b>reachable</b> by a real aggregate — the property the raw
 * Mancoridis M1/M2 lack (Briand, Morasca &amp; Basili 1996, <i>normalization</i>). M1 and M2 stay on
 * the snapshot as the literature metrics; these are what the scores read.
 *
 * <h3>Per service</h3>
 * <ul>
 *   <li><b>structuralCohesion</b> — {@code μᵢ / 2(Nᵢ−1)}, capped at 1; a single-entity service is
 *       1. A DDD aggregate is a tree under its root, so a tree with back-references (two JPA
 *       annotations per edge) is the maximum, not the {@code Nᵢ²} directed-with-self-loops graph
 *       the original Aᵢ assumes.</li>
 *   <li><b>functionalCohesion</b> — mean over the functionalities that touch the service of the
 *       fraction of its entities they use (Mono2Micro-style). Needs
 *       {@link FunctionalityInfo#getTouchedEntities()}; null when no functionality resolves.</li>
 *   <li><b>clusterFactor</b> — TurboMQ's {@code 2μᵢ / (2μᵢ + Σⱼ(σᵢⱼ + σⱼᵢ))} (Mitchell &amp;
 *       Mancoridis 2006): 1 when nothing crosses the boundary.</li>
 *   <li><b>localStepRatio</b> — of the workflow steps of the functionalities this service owns,
 *       the fraction that send no command to another service. A merge turns cross steps local; a
 *       split does the opposite.</li>
 *   <li><b>replicaDensity</b> — declared replica classes per entity.</li>
 *   <li><b>eventEdgeDensity</b> — cross-service event subscriptions per entity.</li>
 *   <li><b>couplingDensity</b> — references in and out over the most this service could hold
 *       (each of its entities naming every other service, each foreign entity naming it).</li>
 *   <li><b>avgDistributedSteps</b> — mean step count of the owned functionalities that leave the
 *       service; 0 when none does.</li>
 * </ul>
 *
 * <h3>System</h3>
 * Entity-weighted means of the per-service values, so that removing or adding a service changes
 * the score only through what changed inside the aggregates involved. {@code couplingDensity} is
 * {@code Σσ / ((t'−1)·ΣN)}; {@code localStepRatio} is step-weighted; {@code avgDistributedSteps} is
 * functionality-weighted; {@code m2Max} is the value M2 would take if every entity referenced every
 * other service once, the real ceiling of that formula.
 */
public final class StructuralSignals {

    private StructuralSignals() {}

    // ─── Per-service primitives ───────────────────────────────────────────────

    /** {@code μ / 2(N−1)} capped at 1; 1 for a single entity; null when N ≤ 0. */
    public static Double structuralCohesion(int entityCount, int internalRelations) {
        if (entityCount <= 0) return null;
        if (entityCount == 1) return 1.0;
        return Math.min(1.0, internalRelations / (2.0 * (entityCount - 1)));
    }

    /**
     * TurboMQ cluster factor. A service with neither internal relations nor external references
     * is whole and isolated: 1 when it is a single entity, 0 when several unrelated ones.
     */
    public static Double clusterFactor(int entityCount, int internalRelations, int externalRefs) {
        if (entityCount <= 0) return null;
        if (internalRelations == 0 && externalRefs == 0) return entityCount == 1 ? 1.0 : 0.0;
        return (2.0 * internalRelations) / (2.0 * internalRelations + externalRefs);
    }

    /** Outgoing references of a service: Σⱼ σᵢⱼ. */
    public static int outgoingRefs(ServiceStaticMetrics s) {
        if (s.getExternalRelations() == null) return 0;
        return s.getExternalRelations().values().stream().mapToInt(Integer::intValue).sum();
    }

    /** Incoming references of a service: Σⱼ σⱼᵢ. */
    public static int incomingRefs(String name, Collection<ServiceStaticMetrics> all) {
        int in = 0;
        for (ServiceStaticMetrics o : all) {
            if (name.equals(o.getServiceName()) || o.getExternalRelations() == null) continue;
            in += o.getExternalRelations().getOrDefault(name, 0);
        }
        return in;
    }

    /** Distinct declared replica classes; falls back to cross-service entity names; null if unknown. */
    public static Integer replicaCount(ServiceStaticMetrics s) {
        if (s.getReplicaEntities() != null) return distinct(s.getReplicaEntities().values());
        if (s.getCrossServiceEntities() != null) return distinct(s.getCrossServiceEntities().values());
        return null;
    }

    /** Cross-service event subscriptions; a missing map means none. */
    public static int eventEdges(ServiceStaticMetrics s) {
        if (s.getEventSubscriptions() == null) return 0;
        return s.getEventSubscriptions().values().stream().mapToInt(List::size).sum();
    }

    private static int distinct(Collection<List<String>> lists) {
        Set<String> names = new LinkedHashSet<>();
        lists.forEach(names::addAll);
        return names.size();
    }

    // ─── Functionality-derived primitives ────────────────────────────────────

    /** Steps of a functionality that send at least one command to a service other than its owner. */
    public static int crossSteps(FunctionalityInfo f) {
        String owner = f.getOwnerService();
        if (f.getSteps() != null && !f.getSteps().isEmpty()) {
            int cross = 0;
            for (FunctionalityInfo.WorkflowStep step : f.getSteps()) {
                if (step.getCommands() == null) continue;
                boolean leaves = step.getCommands().stream()
                        .anyMatch(c -> c.getTargetService() != null && !c.getTargetService().equals(owner));
                if (leaves) cross++;
            }
            return Math.min(cross, Math.max(f.getStepCount(), cross));
        }
        // Older snapshots: no per-step view. Every foreign service touched costs at least one step.
        int foreign = 0;
        if (f.getTouchedServices() != null) {
            for (String t : f.getTouchedServices()) if (!t.equals(owner)) foreign++;
        }
        return Math.min(foreign, f.getStepCount());
    }

    /** One (local, total) step pair per owner service. */
    private record StepTally(int local, int total, int distributedFuncs, int distributedSteps) {
        StepTally add(FunctionalityInfo f) {
            int cross = crossSteps(f);
            int steps = Math.max(f.getStepCount(), cross);
            boolean distributed = cross > 0;
            return new StepTally(local + (steps - cross), total + steps,
                    distributedFuncs + (distributed ? 1 : 0),
                    distributedSteps + (distributed ? steps : 0));
        }
        static StepTally empty() { return new StepTally(0, 0, 0, 0); }
        Double localRatio() { return total == 0 ? null : (double) local / total; }
        Double avgDistributed() {
            return total == 0 ? null : distributedFuncs == 0 ? 0.0 : (double) distributedSteps / distributedFuncs;
        }
    }

    // ─── Enrichment ───────────────────────────────────────────────────────────

    /**
     * Fills every signal that is null on the snapshot and can be computed from what it carries.
     * Values already present are kept, so a collector-computed snapshot round-trips unchanged and
     * a pre-signal snapshot is scored on the same definitions.
     */
    public static StaticMetrics enrich(StaticMetrics sm) {
        if (sm == null || sm.getServices() == null || sm.getServices().isEmpty()) return sm;

        Map<String, ServiceStaticMetrics> services = sm.getServices();
        List<FunctionalityInfo> funcs = sm.getFunctionalities() == null ? List.of() : sm.getFunctionalities();
        // A directory with no entities is not a service: it must not widen any denominator.
        int t = (int) services.values().stream().filter(s -> s.getEntityCount() > 0).count();
        int totalN = services.values().stream().mapToInt(ServiceStaticMetrics::getEntityCount).sum();

        Map<String, StepTally> tallies = new LinkedHashMap<>();
        Map<String, List<Double>> functionalFractions = new LinkedHashMap<>();
        StepTally system = StepTally.empty();
        for (FunctionalityInfo f : funcs) {
            if (f.getOwnerService() != null && services.containsKey(f.getOwnerService())) {
                tallies.merge(f.getOwnerService(), StepTally.empty().add(f), (a, b) -> a.add(f));
                system = system.add(f);
            }
            if (f.getTouchedEntities() != null) {
                f.getTouchedEntities().forEach((svc, ents) -> {
                    ServiceStaticMetrics s = services.get(svc);
                    if (s == null || s.getEntityCount() <= 0 || ents == null || ents.isEmpty()) return;
                    double frac = Math.min(1.0, (double) ents.size() / s.getEntityCount());
                    functionalFractions.computeIfAbsent(svc, k -> new ArrayList<>()).add(frac);
                });
            }
        }

        Map<String, ServiceStaticMetrics> enriched = new LinkedHashMap<>();
        int totalRefs = 0;
        for (Map.Entry<String, ServiceStaticMetrics> e : services.entrySet()) {
            String name = e.getKey();
            ServiceStaticMetrics s = e.getValue();
            int n = s.getEntityCount();
            int out = outgoingRefs(s);
            int in = incomingRefs(name, services.values());
            totalRefs += out;
            int otherN = totalN - n;

            ServiceStaticMetrics.ServiceStaticMetricsBuilder b = s.toBuilder();
            if (s.getStructuralCohesion() == null)
                b.structuralCohesion(structuralCohesion(n, s.getInternalRelations()));
            if (s.getClusterFactor() == null)
                b.clusterFactor(clusterFactor(n, s.getInternalRelations(), out + in));
            if (s.getReplicaDensity() == null && n > 0) {
                Integer replicas = replicaCount(s);
                if (replicas != null) b.replicaDensity((double) replicas / n);
            }
            if (s.getEventEdgeDensity() == null && n > 0)
                b.eventEdgeDensity((double) eventEdges(s) / n);
            if (s.getCouplingDensity() == null && n > 0) {
                int max = n * (t - 1) + otherN;
                b.couplingDensity(max == 0 ? 0.0 : Math.min(1.0, (double) (out + in) / max));
            }
            StepTally tally = tallies.get(name);
            if (s.getLocalStepRatio() == null && tally != null) b.localStepRatio(tally.localRatio());
            if (s.getAvgDistributedSteps() == null && tally != null) b.avgDistributedSteps(tally.avgDistributed());
            List<Double> fracs = functionalFractions.get(name);
            if (s.getFunctionalCohesion() == null && fracs != null && !fracs.isEmpty())
                b.functionalCohesion(fracs.stream().mapToDouble(Double::doubleValue).average().orElse(0.0));
            enriched.put(name, b.build());
        }

        StaticMetrics.StaticMetricsBuilder sb = sm.toBuilder().services(enriched);
        if (sm.getStructuralCohesion() == null)
            sb.structuralCohesion(entityWeighted(enriched.values(), ServiceStaticMetrics::getStructuralCohesion));
        if (sm.getFunctionalCohesion() == null)
            sb.functionalCohesion(entityWeighted(enriched.values(), ServiceStaticMetrics::getFunctionalCohesion));
        if (sm.getClusterFactor() == null)
            sb.clusterFactor(entityWeighted(enriched.values(), ServiceStaticMetrics::getClusterFactor));
        if (sm.getReplicaDensity() == null)
            sb.replicaDensity(entityWeighted(enriched.values(), ServiceStaticMetrics::getReplicaDensity));
        if (sm.getEventEdgeDensity() == null)
            sb.eventEdgeDensity(entityWeighted(enriched.values(), ServiceStaticMetrics::getEventEdgeDensity));
        if (sm.getCouplingDensity() == null) {
            double max = (double) (t - 1) * totalN;
            sb.couplingDensity(t <= 1 || max == 0 ? 0.0 : Math.min(1.0, totalRefs / max));
        }
        if (sm.getLocalStepRatio() == null) sb.localStepRatio(system.localRatio());
        if (sm.getAvgDistributedSteps() == null) sb.avgDistributedSteps(system.avgDistributed());
        if (sm.getM2Max() == null) sb.m2Max(m2Max(enriched.values()));
        return sb.build();
    }

    /** Entity-weighted mean over the services whose value is known; null when none is. */
    public static Double entityWeighted(Collection<ServiceStaticMetrics> services,
                                        java.util.function.Function<ServiceStaticMetrics, Double> get) {
        double wSum = 0, vSum = 0;
        for (ServiceStaticMetrics s : services) {
            Double v = get.apply(s);
            if (v == null || s.getEntityCount() <= 0) continue;
            wSum += s.getEntityCount();
            vSum += v * s.getEntityCount();
        }
        return wSum == 0 ? null : vSum / wSum;
    }

    /** The M2 the Mancoridis formula yields when every entity references every other service once. */
    public static Double m2Max(Collection<ServiceStaticMetrics> services) {
        List<ServiceStaticMetrics> list = new ArrayList<>(services);
        int t = list.size();
        if (t <= 1) return 0.0;
        double sum = 0;
        for (int i = 0; i < t; i++) {
            for (int j = i + 1; j < t; j++) {
                int ni = list.get(i).getEntityCount(), nj = list.get(j).getEntityCount();
                if (ni > 0 && nj > 0) sum += (double) (ni + nj) / (2.0 * ni * nj);
            }
        }
        return sum / (t * (t - 1) / 2.0);
    }
}
