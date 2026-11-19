const BASE = process.env.NEXT_PUBLIC_RT_API_URL ?? "http://localhost:8090"

export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message)
    this.name = "ApiError"
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    headers: { "Content-Type": "application/json", ...(init?.headers ?? {}) },
    ...init,
  })
  if (!res.ok) {
    const text = await res.text().catch(() => "")
    throw new ApiError(res.status, text || res.statusText)
  }
  // 204 No Content
  if (res.status === 204) return undefined as T
  return res.json() as Promise<T>
}

// ---------- Monitor ----------
export const api = {
  monitor: {
    staticVersion: (version: string) => request<StaticVersion>(`/monitor/static/${version}`),
    snapshot: (id: string) => request<MonitorSnapshot>(`/monitor/snapshot/${id}`),
    pipeline: {
      start: (body: { host?: string; windowMinutes?: number; iterationId?: string }) =>
        request<MonitorSnapshot>("/monitor/pipeline/start", { method: "POST", body: JSON.stringify(body) }),
      status: (snapshotId: string) =>
        request<MonitorSnapshot>(`/monitor/pipeline/${snapshotId}/status`),
      stop: (snapshotId: string) =>
        request<void>(`/monitor/pipeline/${snapshotId}/stop`, { method: "POST" }),
    },
    exportUrl: (snapshotId: string) => `${BASE}/monitor/snapshot/${snapshotId}/export`,
  },
  analyze: {
    // qualityPriorities omitted → the backend applies the project default from /config/analysis-config
    run: (body: { snapshotId: string; qualityPriorities?: string[]; iterationId?: string }) =>
      request<AnalysisResult>("/analyze/run", { method: "POST", body: JSON.stringify(body) }),
    getPriorities: (iterationId?: string) =>
      request<string[]>(`/analyze/priorities${iterationId ? `?iterationId=${iterationId}` : ""}`),
    exportUrl: (analysisId: string) => `${BASE}/analyze/${analysisId}/export`,
  },
  execute: {
    // iterationId pins the run's git branch to the MAPE-K iteration; omitting it makes the
    // backend fall back to a random UUID suffix.
    run:        (iterationId?: string) =>
      request<ExecuteResponse>(
        `/execute/run${iterationId ? `?iterationId=${encodeURIComponent(iterationId)}` : ""}`,
        { method: "POST" }),
    testRun:    () => request<ExecuteResponse>("/execute/test-run",   { method: "POST" }),
    rollback:   () => request<ExecuteResponse>("/execute/rollback",   { method: "POST" }),
    // Ghost mode: settles a run parked at AWAITING_VERDICT. The verdict decides the Result of the
    // CLAUDE.md refactor-log entry the backend then writes and commits, so it cannot be revised.
    verdict:    (executeRefactorId: string, verdict: "SUCCESS" | "FAILED", summary?: string) =>
      request<ExecuteResponse>("/execute/verdict", {
        method: "POST",
        body: JSON.stringify({ executeRefactorId, verdict, summary }),
      }),
    status:     () => request<ExecuteResponse | null>("/execute/status"),
    testCommand: () =>
      request<ClaudeCommandTestResult>("/execute/test-claude-command", { method: "POST" }),
    exportUrl: (executeRefactorId: string) => `${BASE}/execute/${executeRefactorId}/export`,
  },
  claude: {
    // Cached server-side for a few minutes; refresh=true re-runs the CLI check.
    status: (refresh = false) => request<ClaudeAuthStatus>(`/claude/status?refresh=${refresh}`),
  },
  knowledge: {
    versions: {
      list:         ()    => request<CodeVersionSummary[]>("/knowledge/versions"),
      tree:         ()    => request<VersionTree>("/knowledge/versions/tree"),
      get:          (id: string) => request<CodeVersionDetail>(`/knowledge/versions/${id}`),
      getIterations:(id: string) => request<LoopIterationSummary[]>(`/knowledge/versions/${id}/iterations`),
      runDiscovery: (id: string) => request<DiscoveryGraph>(`/knowledge/versions/${id}/graph`, { method: "POST" }),
      getDiscovery: (id: string) => request<DiscoveryGraph>(`/knowledge/versions/${id}/graph`),
      /** Static-analysis architecture of this version, grouped by aggregate. Re-reads the source tree — cache it. */
      structure:    (id: string) => request<ProjectStructure>(`/knowledge/versions/${id}/structure`),
    },
    iterations: {
      list:    ()                  => request<LoopIterationSummary[]>("/knowledge/iterations"),
      create:  (name?: string, codeVersionId?: string) => request<LoopIterationSummary>("/knowledge/iterations", { method: "POST", body: JSON.stringify({ name, codeVersionId }) }),
      get:     (id: string)        => request<LoopIterationDetail>(`/knowledge/iterations/${id}`),
      seed:    ()                  => request<{ created: number; message: string }>("/knowledge/iterations/seed", { method: "POST" }),
      linkPlan: (id: string, planId: string) =>
        request<LoopIterationSummary>(`/knowledge/iterations/${id}/plan`, { method: "PATCH", body: JSON.stringify({ planId }) }),
      linkExecute: (id: string, executeRefactorId: string) =>
        request<LoopIterationSummary>(`/knowledge/iterations/${id}/execute`, { method: "PATCH", body: JSON.stringify({ executeRefactorId }) }),
    },
  },
  plan: {
    generate: (body: PlanRequest) =>
      request<PlanResponse>("/plan/generate", { method: "POST", body: JSON.stringify(body) }),
    current: () => request<PlanResponse | null>("/plan/current"),
    get: (planId: string) => request<PlanResponse>(`/plan/${planId}`),
    versions: (planId: string) =>
      request<PlanVersionSummary[]>(`/plan/${planId}/versions`),
    getVersion: (versionId: string) =>
      request<PlanVersion>(`/plan/versions/${versionId}`),
    saveEdit: (planId: string, body: { document: string; note?: string }) =>
      request<PlanVersion>(`/plan/${planId}/versions`, { method: "POST", body: JSON.stringify(body) }),
    approveVersion: (versionId: string, note?: string) =>
      request<PlanVersion>(`/plan/versions/${versionId}/approve`, { method: "POST", body: JSON.stringify({ note }) }),
    rejectVersion: (versionId: string, note?: string) =>
      request<PlanVersion>(`/plan/versions/${versionId}/reject`, { method: "POST", body: JSON.stringify({ note }) }),
    exportUrl: (planId: string) => `${BASE}/plan/${planId}/export`,
  },
  config: {
    settings: () => request<AppSettings>("/config/settings"),
    graph: () => request<ArchGraph>("/config/graph"),
    heuristics: {
      get: () => request<HotspotThresholds>("/config/heuristics"),
      update: (body: HotspotThresholds) =>
        request<HotspotThresholds>("/config/heuristics", { method: "PUT", body: JSON.stringify(body) }),
    },
    constraints: {
      list: () => request<RefactorConstraint[]>("/config/constraints"),
      create: (c: RefactorConstraint) =>
        request<RefactorConstraint>("/config/constraints", { method: "POST", body: JSON.stringify(c) }),
      delete: (id: string) => request<void>(`/config/constraints/${id}`, { method: "DELETE" }),
      context: () => request<ConstraintContext>("/config/constraints/context"),
    },
    analysisConfig: {
      get:   () => request<AnalysisConfig>("/config/analysis-config"),
      update: (body: AnalysisConfig) =>
        request<AnalysisConfig>("/config/analysis-config", { method: "PUT", body: JSON.stringify(body) }),
      reset: () => request<AnalysisConfig>("/config/analysis-config/reset", { method: "PUT" }),
    },
    qualityAttributes: {
      list: () => request<QualityAttributeDef[]>("/config/quality-attributes"),
      create: (name: string, description?: string) =>
        request<QualityAttributeDef>("/config/quality-attributes", { method: "POST", body: JSON.stringify({ name, description: description ?? "" }) }),
      update: (id: string, patch: Partial<{ name: string; description: string; active: boolean }>) =>
        request<QualityAttributeDef>(`/config/quality-attributes/${id}`, { method: "PUT", body: JSON.stringify(patch) }),
      deactivate: (id: string) => request<void>(`/config/quality-attributes/${id}`, { method: "DELETE" }),
    },
    operationScores: {
      get: () => request<Record<string, Record<string, number>>>("/config/operation-scores"),
      update: (scores: Record<string, Record<string, number>>) =>
        request<Record<string, Record<string, number>>>("/config/operation-scores", { method: "PUT", body: JSON.stringify(scores) }),
      reset: () => request<Record<string, Record<string, number>>>("/config/operation-scores/reset", { method: "POST" }),
    },
  },
  quality: {
    getScores: (snapshotId: string) =>
      request<QualityScoreResult>(`/quality/scores/${snapshotId}`),
  },
}

// ---------- Types ----------

/**
 * The refactoring operations the UI exposes.
 *
 * Single source of truth for the UI: the config dropdowns, the hotspot-rule matrix and the
 * operation/QA score matrix all derive their rows from this list, so the frontend cannot drift from
 * the backend the way four separately-maintained arrays did.
 *
 * The replica operations exist in the backend's `OperationType` enum but are disabled tool-wide
 * (`RT_ANALYZE_ENABLED_OPERATIONS` defaults to merge+split), so they are kept out of this list —
 * they still belong to {@link OperationName} because stored candidates/plans from older runs may
 * reference them and must render.
 */
export const REFACTOR_OPERATIONS = [
  "MERGE_AGGREGATES",
  "SPLIT_AGGREGATE",
] as const

/** Every operation name the backend can return, including the disabled replica operations. */
const ALL_OPERATION_NAMES = [...REFACTOR_OPERATIONS, "CREATE_REPLICA", "REMOVE_REPLICA"] as const

export type OperationName = (typeof ALL_OPERATION_NAMES)[number]

/** Human-readable label for each operation. */
export const OPERATION_LABELS: Record<OperationName, string> = {
  MERGE_AGGREGATES: "Merge Aggregates",
  SPLIT_AGGREGATE: "Split Aggregate",
  CREATE_REPLICA: "Create Replica",
  REMOVE_REPLICA: "Remove Replica",
}

export interface QualityAttributeDef {
  id: string
  name: string
  description: string
  sortOrder: number
  active: boolean
  builtIn: boolean
  createdAt: string
}

export interface HotspotThresholds {
  highLatencyP95Ms: number
  highSagaLatencyMs: number
  highCrossSvcCalls: number
  highCompensationRate: number
  highErrorRate: number
  readHeavyRatio: number
  highCouplingM2: number
  lowCohesionM1: number
  highEntityCount: number
  highExternalRels: number
}

export interface MonitorSnapshot {
  snapshotId: string
  collectedAt: string
  windowStart?: string
  windowEnd?: string
  aggregateMetrics?: Record<string, AggregateMetrics>
  sagaMetrics?: Record<string, SagaMetrics>
  locustScenarios?: LocustScenario[]
  staticVersions?: StaticVersion[]
  pipelineStatus?: "RUNNING_STATIC" | "RUNNING_LOCUST" | "RUNNING_JAEGER" | "DONE" | "STOPPED" | "FAILED" | null
  pipelineError?: string | null
}

/**
 * Latency bounds the UI highlights against. Mirrors the backend's `SELF_P95_MS`
 * (QualityMetricThresholds.java) so a service flagged here is one the score also penalises.
 *
 * Both are provisional: they were inherited from the pre-fix `JAEGER_P95_MS`, which measured whole
 * span durations rather than self time. Recalibrate the two together once traces carry real work
 * spans, or the highlight will never fire and the panel will read as uniformly healthy.
 */
export const SELF_P95_MS = { good: 100, bad: 1000 } as const

export interface AggregateMetrics {
  serviceName: string
  totalSpanCount: number
  /** Work spans the latency was derived from; 0 means latency was not measured, not that it is 0 ms. */
  measuredSpanCount: number
  operationFrequency: number
  avgReadLatencyMs: number
  avgWriteLatencyMs: number
  p95LatencyMs: number
  errorRate: number
  bandwidthBytesPerSec: number
  readWriteRatio: number
  latencySource?: "jaeger" | "none"
}

export interface AggregateStepLatency {
  aggregateName: string
  avgStepLatencyMs: number
  p95StepLatencyMs: number
  stepCount: number
}

export interface SagaMetrics {
  workflowName: string
  executionCount: number
  avgTotalLatencyMs: number
  p95TotalLatencyMs: number
  avgCrossServiceCalls: number
  compensationRate: number
  involvedServices: string[]
  stepBreakdown?: Record<string, AggregateStepLatency>
}

export interface LocustScenario {
  scenarioName: string
  sourceFile?: string
  requestCount: number
  failureCount: number
  errorRate: number
  medianResponseMs: number
  avgResponseMs: number
  minResponseMs: number
  maxResponseMs: number
  p95ResponseMs: number
  p99ResponseMs: number
  maxPctResponseMs: number
  requestsPerSec: number
  failuresPerSec: number
  avgContentSizeBytes: number
}

export interface FunctionalityInfo {
  name: string
  ownerService: string
  protocol: 'sagas'
  touchedServices: string[]
  stepCount: number
  /** HTTP method triggering this Functionality ("POST", "GET", …). Null for event-driven. */
  httpMethod?: string
  /** Normalized endpoint path, e.g. "/tournaments/{id}/join". Null for event-driven. */
  httpEndpoint?: string
  /** Locust task name= parameter that exercises this endpoint. Null if not covered. */
  locustTaskName?: string
}

export interface InterServiceDependency {
  targetService: string
  /** σᵢⱼ — value-object entity classes that embed this target's data */
  structuralRefs: number
  /** Event type names this service subscribes to from the target (async) */
  eventTypes?: string[]
  /** Functionality class names that call the target synchronously */
  functionalityNames?: string[]
  /** structuralRefs + eventTypes.length + functionalityNames.length */
  totalWeight: number
}

/** One P1 rule and the state it reads. Classified by footprint, never by name. */
export interface InvariantInfo {
  name: string
  declaredIn?: string
  kind?: "METHOD" | "INLINE"
  /** Member entity classes of the aggregate the rule reads. */
  memberEntities?: string[]
  /** Scalar fields of the aggregate root it reads — the root never moves in a split. */
  rootScalars?: string[]
  /** Compares against getPrev(): cannot span a boundary at all, only be duplicated. */
  readsPrev?: boolean
  /** Reads the aggregate's own state, which lives on the root. */
  readsState?: boolean
}

/**
 * One cross-aggregate rule, with the subscriptions that maintain it.
 *
 * Distinct from the flat `events` on a relation: those are the transport, this says which rule each
 * one serves. Three subscriptions — disenroll, anonymise, rename — maintain a single rule.
 */
export interface InterInvariantInfo {
  name: string
  declaredIn?: string
  /**
   * SERVICE_GUARD: enforced synchronously by a service method that claims it with @ServiceGuard.
   * DECLARED: named in a header block and claimed by nothing at all.
   */
  kind?: "METHOD" | "INLINE" | "SERVICE_GUARD" | "DECLARED"
  /** For SERVICE_GUARD, where it is enforced: "QuizAnswerService.startQuiz". */
  enforcedIn?: string
  /**
   * Rule names from the aggregate's INTER-INVARIANTS header that this method claims, read from its
   * `@InterInvariant` annotation. Empty when the method carries none.
   */
  declaredNames?: string[]
  /** For a DECLARED rule, what the header wrote beside it — usually why it needs no subscription. */
  note?: string
  /** Member entity classes of the aggregate the rule ranges over. */
  memberEntities?: string[]
  rootScalars?: string[]
  readsState?: boolean
  /** Whether registration is wrapped in `if (getState() == ACTIVE)`. */
  guardedByActiveState?: boolean
  /** Whether the rule ranges over a collection (∀) rather than a single reference. */
  iterated?: boolean
  /** Whether registration is itself conditional beyond the ACTIVE guard. */
  conditional?: boolean
  subscriptions?: InterInvariantSubscription[]
  /**
   * Publishers of its subscriptions plus the services its local replicas copy. A superset: the
   * replica half counts every foreign service a class mentions.
   */
  dependsOnServices?: string[]
}

export interface InterInvariantSubscription {
  subscriptionClass: string
  eventType: string
  /** The service publishing the event — the subscription anchor, not necessarily the rule's subject. */
  publisherService?: string
  publisherAggregate?: string
}

export interface ServiceStaticMetrics {
  serviceName: string
  entityCount: number
  internalRelations: number
  externalRelations: Record<string, number>
  m1Contribution: number
  m2Contribution?: number
  entityNames?: string[]
  /** Aggregate roots — the classes extending Aggregate. Absent on older snapshots. */
  rootNames?: string[]
  /** Composition forest: owning class → the classes it holds. Absent on older snapshots. */
  compositions?: Record<string, string[]>
  /** P1 rules with their footprint. Absent on older snapshots — that is "unknown", not "none". */
  invariants?: InvariantInfo[]
  /** The cross-aggregate rules, grouped by the rule rather than by event type. */
  interInvariants?: InterInvariantInfo[]
  /** Per target service, the local classes that carry a reference to it (the replica classes). */
  crossServiceEntities?: Record<string, string[]>
  /** The mirror: per target service, that target's own classes named in this service's source. */
  referencedForeignEntities?: Record<string, string[]>
  eventSubscriptions?: Record<string, string[]>
  functionalityDependencies?: Record<string, string[]>
  /** Merged inter-service dependency graph (all 3 passes). Null in older snapshots. */
  interServiceDependencies?: Record<string, InterServiceDependency>
  /** Σ stepCount of all owned Functionality classes (Pass 4). */
  functionalityStepCount?: number
  /** M1 contribution using only the functionality graph: functionalityStepCount / N². */
  m1FunctionalContribution?: number
  /** M2 contribution using only the functionality graph: Σⱼ σ'ᵢⱼ / (2·Nᵢ·Nⱼ). */
  m2FunctionalContribution?: number
}

export interface StaticVersion {
  version: string
  path?: string
  metrics: {
    m1: number
    m2: number
    m1Functional?: number
    m2Functional?: number
    serviceCount: number
    sourceDir?: string
    services: Record<string, ServiceStaticMetrics>
    functionalities?: FunctionalityInfo[]
  }
}

export interface AnalysisResult {
  analysisId?: string
  snapshotId: string
  analyzedAt: string
  qualityPriorities: string[]
  structuralEvidence: {
    m1: number
    m2: number
    serviceCount: number
    perService: Record<string, ServiceStaticMetrics>
    highCouplingPairs: [string, string][]
    lowCohesionServices: string[]
    highEntityCountServices: string[]
    sourceVersion: string
  }
  runtimeEvidence: {
    perServiceMetrics: Record<string, AggregateMetrics>
    highLatencyServices: string[]
    readHeavyServices: string[]
    highBandwidthServices: string[]
    highSagaLatencyWorkflows: string[]
    sagaMetrics: Record<string, SagaMetrics>
    locustScenarios: LocustScenario[]
    /** Locust metrics per Functionality base name (e.g. "AddParticipant"). Present when linked. */
    functionalityLocustMetrics?: Record<string, LocustScenario>
  }
  consistencyEvidence: {
    violationCount: number
    highCompensationWorkflows: string[]
    avgCompensationRate: number
    hasConsistencySignals: boolean
  }
  crossVersionClues: {
    versionsAnalyzed: string[]
    m1Trend: "IMPROVING" | "DEGRADING" | "STABLE" | "INSUFFICIENT_DATA"
    m2Trend: "IMPROVING" | "DEGRADING" | "STABLE" | "INSUFFICIENT_DATA"
    notes: string[]
  }
  hotspots: Hotspot[]
  candidateOperations: CandidateOperation[]
  activeConstraints?: RefactorConstraint[]
  tentativeRanking: string[]
  analyzePlanBoundary: string
  finalRecommendation: string
  analysisConfig?: AnalysisConfig
  /** Quality attribute scores computed during this analysis. */
  qualityScores?: QualityAttributeScore[]
}

export interface Hotspot {
  type: string
  affectedServices: string[]
  evidence: string
  severity: number
  qualityAttribute?: string
}

export interface CandidateOperation {
  operation: OperationName
  targetService: string
  secondaryTarget?: string
  summary?: string
  whyApplicable: string
  primaryQaTarget: string
  secondaryQaEffects: Record<string, number>
  preconditions: string[]
  risks: string[]
  dependencies: string[]
  affectedEntities?: string[]
  blueprint?: Blueprint
  /** Non-null when an architect constraint blocks this candidate. */
  blockedBy?: string
  /** Advisory notes from non-blocking constraints. */
  warnings?: string[]
  /** Score for the primary QA (SIMPLE_RANK mode only), -2..+2. */
  primaryQaScore?: number
  /** Human-readable explanation of rank position (SIMPLE_RANK mode only). */
  rankReason?: string
  /** Spec-format CLI command derived at serialization time (e.g. "/move-entity tournament TournamentParticipant --to answer"). */
  commandSignature?: string
}

export interface AnalysisConfig {
  hotspotRules: Partial<Record<string, string[]>>
  qaToHotspotTypes?: Partial<Record<string, string[]>>
  /** Ordered QA names (highest first) Analyze uses when a run carries no explicit priorities. */
  defaultQualityPriorities?: string[]
}

export interface RefactorConstraint {
  id?: string
  operation?: OperationName
  targetService?: string
  secondaryTarget?: string
  targetEntity?: string
  reason: string
  blocking: boolean
  createdAt?: string
}

export interface ConstraintServiceContext {
  serviceName: string
  entityNames: string[]
  intraRelationCount: number
  interRelations: Record<string, number>
}

export interface ConstraintContext {
  services: ConstraintServiceContext[]
}

export interface ArchGraphService {
  name: string
  entityCount: number
  entities: string[]
  internalRelations: number
  externalRelations: Record<string, number>
  m1Contribution: number
  invariants: string[]
}

export interface ArchGraph {
  services: ArchGraphService[]
  m1: number
  m2: number
  serviceCount: number
  error?: string
}

export interface AppSettings {
  execute: {
    baseBranch: string
    buildProfiles: string
    projectRoot: string
    composeFile: string
    /** "auto" to derive services from the compose file, or a literal comma-separated list. */
    deployTarget: string
    deployMode: "AUTO" | "EXPLICIT"
    /** Compose services the next deploy will target, resolved from the compose file. */
    deployServices: string[]
    deployTopologySource: "COMPOSE" | "EXPLICIT" | "FALLBACK"
    deployTopologyResolvedAt: string
    gitUserName: string
    gitUserEmail: string
    /**
     * Ghost mode (RT_EXECUTE_GHOST_MODE, boot-time only): a run creates its branch, waits for a
     * manual verdict, and writes only the CLAUDE.md log entry that verdict decides. No mutation,
     * test gate, image build or deploy.
     */
    ghostMode: boolean
  }
  monitor: {
    jaegerBaseUrl: string
    collectionWindowMinutes: number
    /** OTel service names (spring.application.name), derived from the deployed topology. */
    knownServices: string[]
    /** Static yaml seed, used only when topology resolution yields nothing. */
    knownServicesSeed: string[]
    locustTargetHost: string
  }
  analyze: {
    candidateLimit: number
  }
}

export interface AggregateSnapshot {
  aggregateName: string
  serviceName: string
  entities: string[]
  invariants: string[]
  isNew: boolean
}

export interface EntityMove {
  entityName: string
  fromAggregate: string
  toAggregate: string
  rationale?: string
}

export interface InvariantChange {
  invariantName: string
  changeType: string
  fromLocation: string
  toLocation: string
  rationale?: string
}

export interface MetricImpact {
  m1Before?: number;  m1After?: number
  m2Before?: number;  m2After?: number
  entityCountBefore?: string; entityCountAfter?: string
  externalRelsBefore?: number; externalRelsAfter?: number
  p95LatencyMsBefore?: number; p95LatencyMsAfter?: number
  compensationRateBefore?: number; compensationRateAfter?: number
  estimationNote?: string
}

export interface Blueprint {
  summary?: string
  before?: AggregateSnapshot[]
  after?: AggregateSnapshot[]
  entityMoves?: EntityMove[]
  invariantChanges?: InvariantChange[]
  implementationSteps?: string[]
  metricImpact?: MetricImpact
}


// ---------- Plan ----------

export interface PlanRequest {
  operation: OperationName
  targetService: string
  secondaryTarget?: string
  snapshotId?: string
  qualityPriorities: string[]
  preconditionsVerified: boolean
  risksAcknowledged: boolean
  skillInvocation?: string
  blueprint?: CandidateOperation["blueprint"]
  skillPrompt?: string
  iterationId?: string
}

export interface PlanResponse {
  planId: string
  status: string
  operation: OperationName
  targetService: string
  secondaryTarget?: string
  snapshotId?: string
  skillInvocation?: string
  createdAt: string
  blueprint?: CandidateOperation["blueprint"]
  skillPrompt?: string
  document?: string
  skillContent?: string
  enrichedDocument?: string
  /** The active (approved, else latest) plan version this response reflects. */
  activePlanVersionId?: string
  activeVersionNumber?: number
  /** Approval state of the active version: PENDING | APPROVED | REJECTED. */
  approvalState?: string
}

/** Lightweight plan-version row for the version switcher. */
export interface PlanVersionSummary {
  planVersionId: string
  versionNumber: number
  kind: "ORIGINAL" | "ENRICHED" | "USER_EDIT" | string
  model?: string
  approvalState: "PENDING" | "APPROVED" | "REJECTED" | string
  createdAt: string
}

/** A full immutable plan revision. */
export interface PlanVersion {
  planVersionId: string
  planId: string
  versionNumber: number
  kind: "ORIGINAL" | "ENRICHED" | "USER_EDIT" | string
  skillInvocation?: string
  document: string
  model?: string
  promptVersion?: string
  approvalState: "PENDING" | "APPROVED" | "REJECTED" | string
  note?: string
  createdAt: string
  iterationId?: string
  snapshotId?: string
}

export interface PlanSummary {
  planId: string
  operation: OperationName
  targetService: string
  secondaryTarget?: string
  snapshotId?: string
  status: string
  createdAt: string
  /** Canonical CLI command — never mutated after plan generation (e.g. "move-entity tournament TournamentParticipant --to answer"). */
  skillInvocation?: string
}

// ---------- Knowledge / Code Versions ----------

export type CodeVersionStatus = "BASELINE" | "ACTIVE" | "SUPERSEDED" | "FAILED"

export interface CodeVersionSummary {
  versionId: string
  versionNumber: number
  versionLabel: string
  branchName?: string
  gitCommitSha?: string
  imageTag?: string
  status: CodeVersionStatus
  producingExecutionId?: string
  parentVersionId?: string
  createdAt: string
  deployedAt?: string
  notes?: string
  /** Shell commands (bash) that deploy this version by hand. Absent when rt-app never recorded one. */
  deployCommand?: string
  /** QA priority names (highest first) the producing analysis ran with. Absent when unknown (e.g. baseline). */
  qualityPriorities?: string[]
  iterationCount: number
  hasDiscoveryGraph: boolean
}

export interface CodeVersionDetail extends CodeVersionSummary {
  iterations: LoopIterationSummary[]
  discoveryGraphId?: string
}

export interface VersionScoreEntry {
  score: number
  percentage: number
  status: QaStatus
}

export interface VersionTreeNode {
  versionId: string
  versionNumber: number
  versionLabel: string
  parentVersionId?: string
  status: CodeVersionStatus
  branchName?: string
  gitCommitSha?: string
  imageTag?: string
  createdAt: string
  deployedAt?: string
  iterationCount: number
  /** Shell commands (bash) that deploy this version by hand. */
  deployCommand?: string
  /** Refactoring that produced this version. Absent on the baseline. */
  operation?: string
  targetService?: string
  secondaryTarget?: string
  /** Snapshot the scores came from. Absent when the version was never monitored. */
  snapshotId?: string
  /** Attribute name → score. Empty when never monitored. */
  scores: Record<string, VersionScoreEntry>
  /** Attribute name → delta vs parent. Empty at the root or when either side is unmeasured. */
  deltas: Record<string, number>
}

export interface VersionTree {
  nodes: VersionTreeNode[]
  /** Attribute name → versionId scoring highest for it. */
  bestByAttribute: Record<string, string>
}

// ---------- Knowledge / Loop Iterations ----------

export type LoopIterationStatus =
  | "CREATED" | "MONITORING" | "ANALYZED" | "PLANNED"
  | "EXECUTING" | "DEPLOYED" | "FAILED"

export interface LoopIterationSummary {
  iterationId: string
  iterationNumber: number
  name: string
  status: LoopIterationStatus
  snapshotId?: string
  analysisId?: string
  planId?: string
  executeRefactorId?: string
  codeVersionId?: string
  codeVersionLabel?: string
  /** Git branch this iteration's Execute runs work on. */
  branchName?: string
  /** Branch this iteration branched FROM; recovery returns here when its build fails. */
  parentBranchName?: string
  /** Branch outcome, tracked independently of `status`. */
  branchStatus?: BranchStatus
  startedAt: string
  completedAt?: string
  notes?: string
}

export type BranchStatus =
  | "CREATED"
  | "EXECUTING_REFACTOR"
  | "BUILD_SUCCESS"
  | "BUILD_FAILED"

export interface LoopIterationDetail extends LoopIterationSummary {
  snapshot?: MonitorSnapshot
  analysis?: AnalysisResult
  plan?: PlanSummary
  execution?: ExecuteResponse
}

// ---------- Knowledge / Discovery ----------

export interface DiscoveryInvariant {
  name: string
  type: "intra" | "inter"
}

export interface DiscoveryExternalRelation {
  to: string
  via: string
}

export interface DiscoveredAggregate {
  name: string
  entities: string[]
  invariants: DiscoveryInvariant[]
  externalRelations: DiscoveryExternalRelation[]
}

export interface DiscoveredService {
  name: string
  aggregates: DiscoveredAggregate[]
  serviceOperations: string[]
}

// ─── Project structure (static analysis, grouped by aggregate) ────────────────

/** An entity and the entities it contains. */
export interface EntityNode {
  name: string
  /**
   * The foreign aggregate root(s) this class declares a copy of, from its `@Replica` annotation.
   * Comma-separated when it copies more than one; absent for a plain domain entity, and also for a
   * snapshot older than the annotation, where replicas are unknown rather than none.
   */
  replicaOf?: string
  children?: EntityNode[]
}

/**
 * One directed edge between two aggregates, with the three coupling dimensions kept apart:
 * structural references (σ), event subscriptions (async) and functionality calls (sync).
 */
export interface AggregateRelation {
  toAggregate: string
  toService: string
  structuralRefs: number
  /** The local classes behind structuralRefs, named. */
  viaEntities?: string[]
  /** Event types subscribed to across this edge — the "inter" dimension. */
  events?: string[]
  functionalities?: string[]
  readCommands?: string[]
  writeCommands?: string[]
  totalWeight: number
}

/**
 * One command an aggregate accepts, with the chain it triggers and everyone who sends it.
 *
 * The synchronous seam. A refactoring has to move every layer at once — the handler case, the
 * handler method, the service method and every sender — and a half-moved seam still compiles.
 */
export interface CommandInfo {
  name: string
  /** `CourseCommandHandler.handleGetCourseById` */
  handlerMethod?: string
  /** `CourseService.getCourseById` — the behaviour behind the seam. */
  serviceMethod?: string
  /** Absent when nothing sends it: a surface nobody calls. */
  sentBy?: CommandCaller[]
}

export interface CommandCaller {
  functionality: string
  fromAggregate?: string
  /** READ or WRITE; absent for a saga compensation. */
  access?: string
}

/** One command a functionality sends, named in aggregate terms. */
export interface SentCommandRef {
  name: string
  toAggregate?: string
  access?: string
  /** No handler in the target accepts it — the step silently does nothing at runtime. */
  unhandled?: boolean
}

/** One cross-service operation, named as the domain model names it. */
export interface FunctionalityRef {
  /** Class name without the `Functionality(Sagas)` suffix, e.g. `AddParticipant`. */
  operation: string
  className?: string
  protocol?: string
  /** Steps in buildWorkflow() — the length of the distributed interaction. */
  stepCount?: number
  /** The other aggregates this operation reaches. */
  touchedAggregates?: string[]
  httpMethod?: string
  httpEndpoint?: string
  /** The commands this operation sends — the other direction of AggregateStructure.commands. */
  sendsCommands?: SentCommandRef[]
  /** The same commands, partitioned by the step that sends them, in declaration order. */
  steps?: WorkflowStepRef[]
  /** Command sites the parser could not place in any step. Expected absent. */
  looseCommands?: SentCommandRef[]
}

/**
 * One step of a workflow.
 *
 * `index` is where the step is written; `wave` is where it runs. The engine topologically sorts
 * `dependsOn`, so steps sharing a wave have no order between them at all — rendering 1..N alone
 * would assert a sequence the runtime never promised.
 */
export interface WorkflowStepRef {
  /** Declaration order, 1-based. */
  index: number
  name: string
  /** The steps that must complete first. */
  dependsOn?: string[]
  /** Longest dependency chain behind it; 0 waits on nothing. */
  wave: number
  /** False when no `addStep` call names it: constructed, never run. */
  registered?: boolean
  sends?: SentCommandRef[]
  /** What the step sends when the saga aborts past it. */
  compensates?: SentCommandRef[]
}

/**
 * One event moving between two aggregates, seen from one end.
 *
 * The publisher is read from the `registerEvent` call site, not inferred from a subscription's
 * anchor — `publisherAggregate` is absent rather than guessed when no service registers the event.
 */
export interface EventFlow {
  eventType: string
  publisherAggregate?: string
  /** `ServiceClass.method` where the event is registered. */
  publishedIn?: string
  consumerAggregates?: string[]
}

export interface AggregateStructure {
  name: string
  serviceName: string
  entityCount: number
  root: EntityNode
  intraInvariants?: InvariantInfo[]
  /** Cross-aggregate rules maintained by subscription, each with the aggregates at the other end. */
  interInvariants?: InterInvariantInfo[]
  outgoing?: AggregateRelation[]
  incoming?: AggregateRelation[]
  /** Cross-service operations this aggregate owns. Active transactional model only. */
  functionalities?: FunctionalityRef[]
  /** Events this aggregate's service registers, with the aggregates that subscribe. */
  publishes?: EventFlow[]
  /** Events it subscribes to — the flat view of what interInvariants groups by rule. */
  consumes?: EventFlow[]
  /** The commands this aggregate accepts, each with the functionalities that send it. */
  commands?: CommandInfo[]
  /** True when the service holds several roots, so these edges are the service's, not this aggregate's. */
  relationsAreServiceWide?: boolean
}

export interface ProjectStructure {
  versionId: string
  versionLabel: string
  /** Where the analysed source came from: "commit aa11bb22" | "tag v2" | "working tree". */
  resolvedFrom: string
  collectedAt: string
  aggregates: AggregateStructure[]
  /** Per service, entity classes no aggregate root reaches through composition. */
  orphanEntities?: Record<string, string[]>
}

export interface DiscoveryGraph {
  snapshotId: string
  version: string
  discoveredAt: string
  services: DiscoveredService[]
}

// ---------- Execute ----------

export interface ClaudeCommandTestResult {
  success: boolean
  exitCode: number
  claudePath?: string
  output: string
  error?: string
}

export type ClaudeAuthState =
  | "OK" | "NOT_LOGGED_IN" | "BINARY_MISSING" | "UNAVAILABLE" | "ERROR"

export interface ClaudeAuthStatus {
  state: ClaudeAuthState
  message: string
  authMethod: string | null
  account: string | null
  claudePath: string | null
  checkedAt: string
  cached: boolean
}

export type ExecuteStatus =
  | "PENDING" | "MUTATING" | "BUILDING" | "DEPLOYING"
  | "DEPLOYED" | "FAILED" | "ROLLED_BACK"
  // The skill found the operation inapplicable and changed nothing — terminal, but not a failure.
  | "NOT_APPLICABLE"
  // Ghost mode only: the branch exists but nothing was mutated, and the run is parked waiting for
  // a manual SUCCESS/FAILED verdict. Non-terminal — the verdict decides the CLAUDE.md log entry.
  | "AWAITING_VERDICT"

export interface ExecuteResponse {
  executeRefactorId: string
  versionNumber: number
  branchName: string
  operation: string
  targetService: string
  secondaryTarget?: string
  snapshotId?: string
  status: ExecuteStatus
  executeLog: string
  startedAt: string
  completedAt?: string
  gitCommitSha?: string
  imageTag?: string
  planPayload?: string
  /** JSON array of the compose services this version deployed. */
  deployedServices?: string
  /** Iteration created on success to measure the produced version; set only when DEPLOYED. */
  nextIterationId?: string
}

// ---------- Quality Attribute Scores ----------

export type MetricSignalDirection = "higher_is_better" | "lower_is_better"
export type MetricSignalSource = "jaeger" | "locust" | "static" | "derived"
export type QaStatus = "poor" | "warning" | "good" | "excellent"

export interface MetricSignal {
  key: string
  label: string
  rawValue: number | null
  normalizedValue: number | null
  weight: number
  contribution: number
  direction: MetricSignalDirection
  source: MetricSignalSource
  description: string
  estimated: boolean
}

export interface FormulaExplanation {
  shortDescription: string
  formula: string
  interpretation: string
}

export interface QualityAttributeScore {
  attribute: string
  score: number
  percentage: number
  status: QaStatus
  formula: FormulaExplanation
  signals: MetricSignal[]
  hasEstimatedData: boolean
  topDriver?: string
  perEntitySignals?: Record<string, MetricSignal[]>
  perEntityType?: 'service' | 'scenario'
}

export interface QualityScoreResult {
  snapshotId: string
  version: string
  generatedAt: string
  scores: QualityAttributeScore[]
}
