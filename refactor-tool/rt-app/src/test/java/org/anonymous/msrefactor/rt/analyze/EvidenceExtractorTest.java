package org.anonymous.msrefactor.rt.analyze;

import org.junit.jupiter.api.Test;
import org.anonymous.msrefactor.rt.analyze.detector.HotspotDetector;
import org.anonymous.msrefactor.rt.analyze.detector.PerServiceQaAnalyzer;
import org.anonymous.msrefactor.rt.analyze.extractor.EvidenceExtractor;
import org.anonymous.msrefactor.rt.quality.MetricNormalizer;
import org.anonymous.msrefactor.rt.monitor.MonitorSnapshot;
import org.anonymous.msrefactor.rt.monitor.ServiceStaticMetrics;
import org.anonymous.msrefactor.rt.monitor.StaticMetrics;
import org.anonymous.msrefactor.rt.monitor.VersionAnalysisResult;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests 1 & 2: sparse snapshot yields no hotspots;
 * structurally coupled services trigger TIGHT_COUPLING hotspot.
 */
class EvidenceExtractorTest {

    private final EvidenceExtractor    extractor  = new EvidenceExtractor();
    private final HotspotDetector      detector   =
            new HotspotDetector(new PerServiceQaAnalyzer(new MetricNormalizer()));

    // ─── Test 1: sparse snapshot ──────────────────────────────────────────────

    @Test
    void sparseSnapshot_producesNoHotspotsAndEmptyEvidence() {
        MonitorSnapshot snap = MonitorSnapshot.builder()
                .snapshotId("test-sparse")
                .collectedAt(Instant.now())
                .aggregateMetrics(null)
                .sagaMetrics(Collections.emptyMap())
                .staticVersions(null)
                .consistencyViolationCount(0)
                .build();

        HotspotThresholds   thresholds  = HotspotThresholds.defaults();
        StructuralEvidence  structural  = extractor.extractStructural(snap, thresholds);
        RuntimeEvidence     runtime     = extractor.extractRuntime(snap, thresholds);
        ConsistencyEvidence consistency = extractor.extractConsistency(snap, thresholds);

        // No evidence populated
        assertThat(structural.getHighCouplingPairs()).isEmpty();
        assertThat(structural.getLowCohesionServices()).isEmpty();
        assertThat(structural.getHighEntityCountServices()).isEmpty();
        assertThat(runtime.getHighLatencyServices()).isEmpty();
        assertThat(runtime.getReadHeavyServices()).isEmpty();
        assertThat(consistency.getViolationCount()).isEqualTo(0);
        assertThat(consistency.getHighCompensationWorkflows()).isEmpty();
        assertThat(consistency.isHasConsistencySignals()).isFalse();

        // No hotspots
        List<Hotspot> hotspots = detector.detect(structural, runtime, consistency, thresholds);
        assertThat(hotspots).isEmpty();
    }

    // ─── Test 2: structurally coupled services → TIGHT_COUPLING hotspot ──────

    @Test
    void tightlyCoupledServices_producesTightCouplingHotspot() {
        // execution → course: 5 external refs; m2=0.65 (high coupling) triggers TIGHT_COUPLING
        ServiceStaticMetrics executionSsm = ServiceStaticMetrics.builder()
                .serviceName("execution")
                .entityCount(5)
                .internalRelations(3)
                .externalRelations(Map.of("course", 5))
                .m1Contribution(0.12)
                .m2Contribution(0.65)
                .build();

        ServiceStaticMetrics courseSsm = ServiceStaticMetrics.builder()
                .serviceName("course")
                .entityCount(4)
                .internalRelations(2)
                .externalRelations(Map.of("execution", 4))
                .m1Contribution(0.13)
                .m2Contribution(0.65)
                .build();

        StaticMetrics sm = StaticMetrics.builder()
                .services(Map.of("execution", executionSsm, "course", courseSsm))
                .m1(0.12)
                .m2(0.45)
                .serviceCount(2)
                .sourceDir("/test")
                .build();

        VersionAnalysisResult version = VersionAnalysisResult.builder()
                .version("v1")
                .path("/test/v1/src/main/java")
                .metrics(sm)
                .build();

        MonitorSnapshot snap = MonitorSnapshot.builder()
                .snapshotId("test-coupling")
                .collectedAt(Instant.now())
                .staticVersions(List.of(version))
                .aggregateMetrics(Collections.emptyMap())
                .sagaMetrics(Collections.emptyMap())
                .consistencyViolationCount(0)
                .build();

        HotspotThresholds   thresholds  = HotspotThresholds.defaults();
        StructuralEvidence  structural  = extractor.extractStructural(snap, thresholds);
        RuntimeEvidence     runtime     = extractor.extractRuntime(snap, thresholds);
        ConsistencyEvidence consistency = extractor.extractConsistency(snap, thresholds);

        // Evidence
        assertThat(structural.getHighCouplingPairs()).isNotEmpty();
        assertThat(structural.getHighCouplingPairs())
                .anyMatch(pair -> (pair[0].equals("execution") && pair[1].equals("course"))
                               || (pair[0].equals("course") && pair[1].equals("execution")));

        // Hotspot — per-service analysis emits one hotspot per service, not one per pair
        List<Hotspot> hotspots = detector.detect(structural, runtime, consistency, thresholds);
        assertThat(hotspots).isNotEmpty();
        assertThat(hotspots)
                .anyMatch(h -> h.getType() == HotspotType.TIGHT_COUPLING
                        && (h.getAffectedServices().contains("execution")
                                || h.getAffectedServices().contains("course")));
    }

    // ─── Test 2b: TIGHT_COUPLING hotspot → MergeAggregates candidate ─────────

    @Test
    void tightlyCoupledServices_generatesMergeAggregatesCandidate() {
        ServiceStaticMetrics executionSsm = ServiceStaticMetrics.builder()
                .serviceName("execution")
                .entityCount(5)
                .internalRelations(3)
                .externalRelations(Map.of("course", 5))
                .m1Contribution(0.12)
                .m2Contribution(0.65)
                .build();

        ServiceStaticMetrics courseSsm = ServiceStaticMetrics.builder()
                .serviceName("course")
                .entityCount(4)
                .internalRelations(2)
                .externalRelations(Map.of("execution", 4))
                .m1Contribution(0.13)
                .m2Contribution(0.65)
                .build();

        StaticMetrics sm = StaticMetrics.builder()
                .services(Map.of("execution", executionSsm, "course", courseSsm))
                .m1(0.12).m2(0.65).serviceCount(2).sourceDir("/test")
                .build();

        VersionAnalysisResult version = VersionAnalysisResult.builder()
                .version("v1").path("/test").metrics(sm).build();

        MonitorSnapshot snap = MonitorSnapshot.builder()
                .snapshotId("test-coupling-ops")
                .collectedAt(Instant.now())
                .staticVersions(List.of(version))
                .aggregateMetrics(Collections.emptyMap())
                .sagaMetrics(Collections.emptyMap())
                .consistencyViolationCount(0)
                .build();

        org.anonymous.msrefactor.rt.analyze.generator.CandidateGenerator generator =
                new org.anonymous.msrefactor.rt.analyze.generator.CandidateGenerator(
                        new org.anonymous.msrefactor.rt.analyze.selector.AggregatePartitionAnalyzer(),
                        new org.anonymous.msrefactor.rt.analyze.selector.ReplicaCandidateAnalyzer(),
                        new org.anonymous.msrefactor.rt.analyze.selector.InvariantImpactAnalyzer(),
                        new org.anonymous.msrefactor.rt.analyze.selector.ValidationPlanBuilder());

        HotspotThresholds   thresholds  = HotspotThresholds.defaults();
        StructuralEvidence  structural  = extractor.extractStructural(snap, thresholds);
        RuntimeEvidence     runtime     = extractor.extractRuntime(snap, thresholds);
        ConsistencyEvidence consistency = extractor.extractConsistency(snap, thresholds);
        List<Hotspot>       hotspots    = detector.detect(structural, runtime, consistency, thresholds);

        List<CandidateOperation> candidates = generator.generate(hotspots, structural, runtime, consistency, AnalysisConfig.defaults(), null);

        assertThat(candidates).isNotEmpty();
        assertThat(candidates)
                .anyMatch(c -> c.getOperation() == OperationType.MERGE_AGGREGATES);
    }

    // ─── Cross-version trends ─────────────────────────────────────────────────

    @Test
    void singleVersion_crossVersionTrendIsInsufficientData() {
        MonitorSnapshot snap = MonitorSnapshot.builder()
                .snapshotId("test-single-ver")
                .collectedAt(Instant.now())
                .staticVersions(List.of(VersionAnalysisResult.builder()
                        .version("v1").metrics(StaticMetrics.builder()
                                .m1(0.1).m2(0.2).serviceCount(3)
                                .services(Collections.emptyMap()).sourceDir("/t").build())
                        .build()))
                .build();

        CrossVersionClues clues = extractor.extractCrossVersion(snap);
        assertThat(clues.getM1Trend()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(clues.getM2Trend()).isEqualTo("INSUFFICIENT_DATA");
    }

    @Test
    void twoVersions_improvingM1Trend() {
        VersionAnalysisResult v1 = VersionAnalysisResult.builder().version("v1")
                .metrics(StaticMetrics.builder().m1(0.05).m2(0.40)
                        .serviceCount(3).services(Collections.emptyMap()).sourceDir("/t").build())
                .build();
        VersionAnalysisResult v2 = VersionAnalysisResult.builder().version("v2")
                .metrics(StaticMetrics.builder().m1(0.15).m2(0.25)
                        .serviceCount(3).services(Collections.emptyMap()).sourceDir("/t").build())
                .build();

        MonitorSnapshot snap = MonitorSnapshot.builder()
                .snapshotId("test-two-ver")
                .collectedAt(Instant.now())
                .staticVersions(List.of(v1, v2))
                .build();

        CrossVersionClues clues = extractor.extractCrossVersion(snap);
        assertThat(clues.getM1Trend()).isEqualTo("IMPROVING");  // M1 increased: better cohesion
        assertThat(clues.getM2Trend()).isEqualTo("IMPROVING");  // M2 decreased: lower coupling
    }
}
