# Hotspot Detection Threshold Configuration

## Problem

Analysis detection thresholds (latency limits, coupling ratios, entity counts, etc.) were hardcoded as static constants in `AnalysisHeuristics.java`. Different projects operate at different scales — a threshold calibrated for a fast synchronous API is meaningless for a batch system. Users had no way to tune sensitivity without changing code.

## Solution

The 10 detection thresholds are now stored per-project in the database and editable through a dialog in the Analyze phase UI. Severity normalization denominators remain static (they affect score magnitude only, not detection).

---

## Threshold Reference

| Key | Default | Hotspot type triggered |
|-----|---------|------------------------|
| `highLatencyP95Ms` | 1000.0 ms | HIGH_LATENCY (service) |
| `highSagaLatencyMs` | 2000.0 ms | HIGH_LATENCY (SAGA) |
| `highCrossSvcCalls` | 3 | HIGH_LATENCY (SAGA cross-svc) |
| `highCompensationRate` | 0.10 | HIGH_COMPENSATION |
| `highErrorRate` | 0.05 | HIGH_ERROR_RATE |
| `readHeavyRatio` | 5.0 | READ_HEAVY |
| `highCouplingM2` | 0.30 | TIGHT_COUPLING |
| `lowCohesionM1` | 0.35 | LOW_COHESION — read against the service's **functional cohesion** (else its cluster factor), both in [0,1] with a reachable 1; the name is kept for configuration compatibility, the raw `μ/N²` M1 contribution is no longer compared |
| `highEntityCount` | 5 | HIGH_ENTITY_COUNT |
| `highExternalRels` | 2 | TIGHT_COUPLING |

---

## Architecture

### Storage

`project_config` table (singleton row, id=1) gains a nullable `thresholds_json TEXT` column. Null = defaults. A `HotspotThresholds` Java record is serialized/deserialized by Jackson.

### Backend files changed

| File | Change |
|------|--------|
| `rt-base/.../analyze/HotspotThresholds.java` | **New** — record with `defaults()` factory |
| `rt-app/.../monitor/persistence/ProjectConfigEntity.java` | +`thresholds_json` column |
| `rt-app/.../monitor/ProjectConfig.java` | +`AtomicReference<HotspotThresholds>` cache, `getThresholds()`, `updateThresholds()` |
| `rt-app/.../config/web/ConfigController.java` | +`GET /config/heuristics`, `PUT /config/heuristics` |
| `rt-app/.../analyze/detector/HotspotDetector.java` | `detect()` accepts `HotspotThresholds t`; uses `t.field()` instead of static constants |
| `rt-app/.../analyze/extractor/EvidenceExtractor.java` | `extractStructural/Runtime/Consistency()` each accept `HotspotThresholds t` |
| `rt-app/.../analyze/service/AnalyzeService.java` | Injects `ProjectConfig`; reads `projectConfig.getThresholds()` and passes to extractor/detector |

### Frontend files changed

| File | Change |
|------|--------|
| `lib/api.ts` | +`HotspotThresholds` interface, +`api.config.heuristics.{get, update}` |
| `components/config/ThresholdsEditor.tsx` | **New** — dialog with Runtime/Structural field groups, save + reset buttons |
| `components/phases/AnalyzePhase.tsx` | +`Settings2` icon button (opens `ThresholdsEditor`), +`ThresholdsEditor` dialog mount |

---

## API

```
GET  /config/heuristics
     → 200 HotspotThresholds (current values; defaults if never saved)

PUT  /config/heuristics
     body: HotspotThresholds
     → 200 HotspotThresholds (persisted)
     → 400 if any value is negative, or rate fields > 1.0
```

---

## Persistence lifecycle

1. **First startup** — `project_config` row is created with `thresholds_json = null`; `ProjectConfig.init()` leaves the `AtomicReference` at `HotspotThresholds.defaults()`.
2. **User edits** — `PUT /config/heuristics` serializes the record to JSON, writes `thresholds_json`, updates the `AtomicReference` in-place.
3. **Restart** — `@PostConstruct` loads the row and deserializes `thresholds_json`; any deserialization failure falls back to defaults with a warning log.
4. **Analysis run** — `AnalyzeService.analyze()` calls `projectConfig.getThresholds()` once at the start of each pipeline invocation.
