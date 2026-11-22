#!/usr/bin/env python3
"""
Static structural analysis for the quizzes microservices application.

Computes M1 (intra-service cohesion) and M2 (inter-service coupling) from the
Java source tree without compilation, using regex-based heuristics.

Formulas (Mancoridis et al. [47]):
  M1 = (1/t') * sum_i (mu_i / N_i^2)
  M2 = 1/(t'*(t'-1)/2) * sum_{i!=j} sigma_ij / (2*N_i*N_j)

Where:
  t'        = number of services
  N_i       = entity/value-object class count in service i
  mu_i      = internal JPA relations (@OneToOne / @OneToMany) in service i
  sigma_ij  = value-object classes in service i that reference service j

Usage:
  python analyze.py
  python analyze.py --source-dir /path/to/quizzes/src/main/java
  python analyze.py --json
  python analyze.py --source-dir ... --json
"""

import argparse
import json
import os
import re
import sys
from pathlib import Path

# Ensure Unicode output works on Windows terminals
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

# ─── Configuration ────────────────────────────────────────────────────────────

MICROSERVICES_SUBPATH = (
    "org/anonymous/msrefactor/quizzes/microservices"
)

# Keywords in class names that signal a reference to a given service.
# Order matters: more specific entries first (e.g. CourseExecution before Course).
SERVICE_KEYWORDS = {
    "execution":  ["CourseExecution", "Execution"],
    "course":     ["Course"],
    "user":       ["Student", "Creator", "Participant", "User"],
    "topic":      ["Topic"],
    "question":   ["Question"],
    "quiz":       ["Quiz"],
    "answer":     ["Answer"],
    "tournament": ["Tournament"],
}

EXCLUDED_SUFFIXES = {"Repository", "Factory", "Dto", "State", "Type"}
EXCLUDED_PREFIXES = {"Saga"}

RE_ABSTRACT        = re.compile(r'\babstract\s+class\b')
# Parity with StaticAnalysisCollector: @ManyToOne counts, an abstract class that carries @Entity
# (every aggregate root: the Saga subclass lives in sagas/) is an entity, enums/interfaces and
# @Embeddable/@MappedSuperclass types are not.
RE_JPA_REL         = re.compile(r'@OneToOne|@OneToMany|@ManyToOne')
RE_NON_ENTITY      = re.compile(r'@Embeddable|@MappedSuperclass')
RE_AGGREGATE_ROOT  = re.compile(r'\bextends\s+Aggregate\b')
RE_REPLICA         = re.compile(r'@Replica\b')
RE_AGGREGATE_ID    = re.compile(r'(\w+?)AggregateIds?\b')
RE_COMMAND_SITE    = re.compile(r'new\s+(\w+Command)\s*\(\s*unitOfWork\s*,\s*ServiceMapping\.([A-Z_]+)\.getServiceName\(\)')
RE_HANDLER_CASE    = re.compile(r'case\s+(\w+Command)\s+\w+\s*->\s*(\w+)\s*\(')
RE_DELEGATION      = re.compile(r'(\w+Service)\.(\w+)\s*\(')
RE_SERVICE_MAPPING = re.compile(r'ServiceMapping\.([A-Z_]+)\.getServiceName\(\)')
RE_STEP            = re.compile(r'\bnew\s+(?:Saga)?Step\s*\(')
# Pass 5A: @PostMapping("/path") on controller methods
RE_CONTROLLER_MAPPING  = re.compile(r'@(Post|Get|Put|Delete|Patch)Mapping\s*\(\s*"([^"]+)"\s*\)')
RE_SERVICE_METHOD_CALL = re.compile(r'\.([a-z][a-zA-Z0-9_]*)\s*\(')
# Pass 5B: self.client.post("/path", ..., name="Task") in Locust .py files
RE_LOCUST_CLIENT_CALL = re.compile(
    r"self\.client\.(post|get|put|delete|patch)\s*\([^\"']*(?:f)?[\"']"
    r"([^\"'{}]+(?:\{[^}]*\}[^\"'{}]*)*)[\"'].*?name\s*=\s*[\"']([^\"']+)[\"']",
    re.DOTALL | re.IGNORECASE,
)

# Maps ServiceMapping enum names to service directory names (matches ServiceMapping.java values).
ENUM_TO_SERVICE: dict[str, str] = {
    "TOURNAMENT": "tournament",
    "QUIZ":       "quiz",
    "USER":       "user",
    "EXECUTION":  "execution",
    "COURSE":     "course",
    "QUESTION":   "question",
    "TOPIC":      "topic",
    "ANSWER":     "answer",
}

# ─── Analysis ─────────────────────────────────────────────────────────────────

def is_excluded(class_name: str) -> bool:
    for s in EXCLUDED_SUFFIXES:
        if class_name.endswith(s):
            return True
    for p in EXCLUDED_PREFIXES:
        if class_name.startswith(p):
            return True
    return False


def zero_service(service_name: str) -> dict:
    return {
        "serviceName": service_name,
        "entityCount": 0,
        "internalRelations": 0,
        "externalRelations": {},
        "m1Contribution": 0.0,
        "entities": [],
    }


def analyse_service(service_name: str, service_dir: Path) -> dict:
    aggregate_dir = service_dir / "aggregate"
    if not aggregate_dir.is_dir():
        return zero_service(service_name)

    # Only direct .java files — skip the sagas/ subdirectory
    java_files = [f for f in aggregate_dir.iterdir()
                  if f.is_file() and f.suffix == ".java"]

    entity_names = []
    root_names: list[str] = []
    replica_names: list[str] = []
    contents: dict[str, str] = {}
    internal_relations = 0
    external_relations: dict[str, int] = {}

    for f in sorted(java_files):
        class_name = f.stem
        if is_excluded(class_name):
            continue

        try:
            content = f.read_text(encoding="utf-8")
        except OSError:
            continue

        if RE_ABSTRACT.search(content) and "@Entity" not in content:
            continue
        if RE_NON_ENTITY.search(content) and "@Entity" not in content:
            continue
        if re.search(r'\b(?:enum|interface|@interface)\s+' + re.escape(class_name) + r'\b', content):
            continue

        entity_names.append(class_name)
        contents[class_name] = content
        internal_relations += len(RE_JPA_REL.findall(content))
        if RE_AGGREGATE_ROOT.search(content):
            root_names.append(class_name)
        if RE_REPLICA.search(content):
            replica_names.append(class_name)

        # External relations: which service does this class reference?
        for target_svc, keywords in SERVICE_KEYWORDS.items():
            if target_svc == service_name:
                continue
            for kw in keywords:
                if kw in class_name:
                    external_relations[target_svc] = external_relations.get(target_svc, 0) + 1
                    break  # count this class once per target service

    n = len(entity_names)
    m1_contribution = internal_relations / (n * n) if n > 0 else 0.0

    return {
        "serviceName": service_name,
        "entityCount": n,
        "internalRelations": internal_relations,
        "externalRelations": external_relations,
        "m1Contribution": m1_contribution,
        "entities": entity_names,
        "entityNames": entity_names,
        "rootNames": root_names,
        "replicaEntities": replica_names,
        "eventSubscriptionCount": _count_subscriptions(service_dir),
        "_contents": contents,
    }


def _count_subscriptions(service_dir: Path) -> int:
    """Pass 3 parity: one subscription per *Subscribes*.java under notification/subscribe/ (or events/subscribe/)."""
    for parts in (("notification", "subscribe"), ("events", "subscribe")):
        sub_dir = service_dir.joinpath(*parts)
        if sub_dir.is_dir():
            return sum(1 for f in sub_dir.glob("*.java") if "Subscribes" in f.stem)
    return 0


def refine_external_relations(services: dict) -> None:
    """
    Pass 2 parity: an entity of A references B when its source names an entity of B as a
    standalone identifier, or declares a ``<prefix>AggregateId`` field whose prefix resolves to B.
    Replaces the class-name keyword heuristic, which cannot see id-only references.
    """
    entity_owner = {}
    root_owner = {}
    for name, svc in services.items():
        for e in svc["entityNames"]:
            entity_owner[e] = name
        for r in svc.get("rootNames", []):
            root_owner[r.lower()] = name
    for name, svc in services.items():
        ext: dict[str, int] = {}
        cross: dict[str, list] = {}
        for entity, content in svc.get("_contents", {}).items():
            hit: set[str] = set()
            for other, owner in entity_owner.items():
                if owner != name and re.search(r'\b' + re.escape(other) + r'\b', content):
                    hit.add(owner)
            for m in RE_AGGREGATE_ID.finditer(content):
                prefix = m.group(1).lower()
                for root, owner in root_owner.items():
                    if owner != name and prefix.endswith(root):
                        hit.add(owner)
                for owner, keywords in SERVICE_KEYWORDS.items():
                    if owner != name and owner in services and any(prefix.endswith(k.lower()) for k in keywords):
                        hit.add(owner)
            for owner in hit:
                ext[owner] = ext.get(owner, 0) + 1
                cross.setdefault(owner, []).append(entity)
        svc["externalRelations"] = ext
        svc["crossServiceEntities"] = cross


def func_sigma(svc: dict, target: str) -> int:
    """σ'ᵢⱼ — number of Functionality classes owned by svc that call target (Pass 4)."""
    deps = svc.get("functionalityDependencies", {})
    return len(deps.get(target, []))


def compute_metrics(services: dict) -> tuple[float, float]:
    """Return (M1, M2) for the full application."""
    t = len(services)
    if t == 0:
        return 0.0, 0.0

    # M1
    m1 = sum(s["m1Contribution"] for s in services.values()) / t

    # M2
    if t <= 1:
        return m1, 0.0

    denom_pairs = t * (t - 1) / 2.0
    service_list = list(services.values())
    sigma_sum = 0.0
    for i in range(len(service_list)):
        for j in range(i + 1, len(service_list)):
            si, sj = service_list[i], service_list[j]
            sigma = (si["externalRelations"].get(sj["serviceName"], 0) +
                     sj["externalRelations"].get(si["serviceName"], 0))
            ni, nj = si["entityCount"], sj["entityCount"]
            if sigma > 0 and ni > 0 and nj > 0:
                sigma_sum += sigma / (2.0 * ni * nj)

    m2 = sigma_sum / denom_pairs
    return m1, m2


def compute_functional_metrics(services: dict, all_functionalities: list) -> tuple[float, float]:
    """
    Compute M1_func and M2_func using ONLY the functionality graph (Pass 4).

    M1_func: μ'ᵢ = Σ stepCount of owned Functionalities  →  (1/t') · Σᵢ μ'ᵢ/Nᵢ²
    M2_func: σ'ᵢⱼ = |functionalityDependencies[j]|        →  Mancoridis M2 formula

    Side-effects: adds 'functionalityStepCount', 'm1FunctionalContribution',
    and 'm2FunctionalContribution' keys to each service dict in-place.
    """
    t = len(services)
    if t == 0:
        return 0.0, 0.0

    # Step counts per ownerService
    step_sums: dict[str, int] = {}
    for fi in all_functionalities:
        step_sums[fi["ownerService"]] = step_sums.get(fi["ownerService"], 0) + fi["stepCount"]

    # m1FunctionalContribution per service
    for svc in services.values():
        steps = step_sums.get(svc["serviceName"], 0)
        n = svc["entityCount"]
        svc["functionalityStepCount"] = steps
        svc["m1FunctionalContribution"] = steps / (n * n) if n > 0 else 0.0

    m1_func = sum(s["m1FunctionalContribution"] for s in services.values()) / t

    if t <= 1:
        for svc in services.values():
            svc["m2FunctionalContribution"] = 0.0
        return m1_func, 0.0

    # m2FunctionalContribution per service
    service_list = list(services.values())
    for i, si in enumerate(service_list):
        ni = si["entityCount"]
        if ni == 0:
            si["m2FunctionalContribution"] = 0.0
            continue
        acc = 0.0
        for j, sj in enumerate(service_list):
            if i == j:
                continue
            nj = sj["entityCount"]
            if nj == 0:
                continue
            sigma = func_sigma(si, sj["serviceName"]) + func_sigma(sj, si["serviceName"])
            if sigma > 0:
                acc += sigma / (2.0 * ni * nj)
        si["m2FunctionalContribution"] = acc

    # System-level M2_func
    denom_pairs = t * (t - 1) / 2.0
    sigma_sum = 0.0
    for i in range(len(service_list)):
        for j in range(i + 1, len(service_list)):
            si, sj = service_list[i], service_list[j]
            sigma = func_sigma(si, sj["serviceName"]) + func_sigma(sj, si["serviceName"])
            ni, nj = si["entityCount"], sj["entityCount"]
            if sigma > 0 and ni > 0 and nj > 0:
                sigma_sum += sigma / (2.0 * ni * nj)
    m2_func = sigma_sum / denom_pairs
    return m1_func, m2_func


def collect_functionality_deps(service_name: str, service_dir: Path,
                               known_services: set) -> tuple[dict, list]:
    """
    Pass 4: scan coordination/sagas/ for Functionality classes.

    Returns:
      deps  — {target_service: [func_class_names]}  (for functionalityDependencies)
      funcs — list of FunctionalityInfo dicts
    """
    deps: dict[str, list] = {}
    funcs: list[dict] = []
    coord_dir = service_dir / "coordination"
    if not coord_dir.is_dir():
        return deps, funcs

    for protocol in ("sagas",):
        proto_dir = coord_dir / protocol
        if not proto_dir.is_dir():
            continue
        for f in sorted(proto_dir.glob("*.java")):
            try:
                content = f.read_text(encoding="utf-8")
            except OSError:
                continue
            touched: list[str] = []
            seen: set[str] = set()
            for m in RE_SERVICE_MAPPING.finditer(content):
                svc = ENUM_TO_SERVICE.get(m.group(1))
                if svc and svc in known_services and svc not in seen:
                    touched.append(svc)
                    seen.add(svc)
            if not touched:
                continue
            steps = len(RE_STEP.findall(content))
            funcs.append({
                "name":           f.stem,
                "ownerService":   service_name,
                "protocol":       protocol,
                "touchedServices": touched,
                "stepCount":      steps,
            })
            for target in touched:
                if target != service_name:
                    deps.setdefault(target, []).append(f.stem)
    return deps, funcs


def collect_controller_map(ms_root: Path) -> dict[str, str]:
    """
    Pass 5A: scan coordination/webapi/*Controller.java for each service.
    Returns { "POST:/tournaments/{id}/join": "AddParticipant" }.
    """
    result: dict[str, str] = {}
    for service_dir in ms_root.iterdir():
        if not service_dir.is_dir():
            continue
        webapi_dir = service_dir / "coordination" / "webapi"
        if not webapi_dir.is_dir():
            continue
        for f in webapi_dir.glob("*Controller.java"):
            try:
                content = f.read_text(encoding="utf-8")
            except OSError:
                continue
            for m in RE_CONTROLLER_MAPPING.finditer(content):
                http_method = m.group(1).upper()
                norm_path = re.sub(r'\{[^}]+\}', '{id}', m.group(2))
                remainder = content[m.end():m.end() + 600]
                body_m = RE_SERVICE_METHOD_CALL.search(remainder)
                if body_m:
                    method_name = body_m.group(1)
                    base = method_name[0].upper() + method_name[1:]
                    result.setdefault(f"{http_method}:{norm_path}", base)
    return result


def collect_locust_task_map(locust_dir: Path) -> dict[str, str]:
    """
    Pass 5B: scan *.py files in locust_dir for self.client.METHOD("path", ..., name="Task").
    Returns { "POST:/tournaments/{id}/join": "Add participant" }.
    """
    result: dict[str, str] = {}
    if not locust_dir.is_dir():
        return result
    for f in locust_dir.rglob("*.py"):
        try:
            content = f.read_text(encoding="utf-8")
        except OSError:
            continue
        for m in RE_LOCUST_CLIENT_CALL.finditer(content):
            http_method = m.group(1).upper()
            norm_path = re.sub(r'\{[^}]+\}', '{id}', m.group(2))
            task_name = m.group(3)
            result.setdefault(f"{http_method}:{norm_path}", task_name)
    return result


def _functionality_base(class_name: str) -> str:
    for suffix in ("FunctionalitySagas", "Functionality"):
        if class_name.endswith(suffix):
            return class_name[: -len(suffix)]
    return class_name


def enrich_functionalities(funcs: list[dict], endpoint_map: dict, locust_map: dict) -> list[dict]:
    """Pass 5: add httpMethod, httpEndpoint, locustTaskName to each FunctionalityInfo dict."""
    if not endpoint_map:
        return funcs
    base_to_key: dict[str, str] = {}
    for key, base in endpoint_map.items():
        base_to_key.setdefault(base, key)
    enriched = []
    for fi in funcs:
        base = _functionality_base(fi["name"])
        key = base_to_key.get(base)
        if key:
            parts = key.split(":", 1)
            fi = dict(fi)
            fi["httpMethod"] = parts[0]
            fi["httpEndpoint"] = parts[1] if len(parts) > 1 else None
            fi["locustTaskName"] = locust_map.get(key)
        enriched.append(fi)
    return enriched


def run_analysis(source_dir: Path, locust_dir: Path | None = None) -> dict:
    ms_root = source_dir / MICROSERVICES_SUBPATH
    if not ms_root.is_dir():
        print(f"ERROR: microservices directory not found: {ms_root}", file=sys.stderr)
        sys.exit(1)

    service_dirs = sorted(d for d in ms_root.iterdir() if d.is_dir())
    if not service_dirs:
        print(f"ERROR: no service directories found under {ms_root}", file=sys.stderr)
        sys.exit(1)

    services = {}
    for sd in service_dirs:
        name = sd.name
        services[name] = analyse_service(name, sd)
    # A directory with no entities (exception/, a service whose aggregate/ is missing) is not a
    # service: counting it inflates t' and shrinks every per-pair denominator.
    services = {n: s for n, s in services.items() if s["entityCount"] > 0}
    refine_external_relations(services)

    m1, m2 = compute_metrics(services)

    # Pass 4: Functionality mapper
    known_services = set(services.keys())
    all_functionalities: list[dict] = []
    for sd in service_dirs:
        name = sd.name
        if name not in services:
            continue
        func_deps, funcs = collect_functionality_deps(name, sd, known_services)
        if func_deps:
            services[name]["functionalityDependencies"] = func_deps
        all_functionalities.extend(funcs)

    # Merge all three passes → interServiceDependencies
    for svc in services.values():
        all_targets = (set(svc.get("externalRelations", {}))
                     | set(svc.get("eventSubscriptions", {}))
                     | set(svc.get("functionalityDependencies", {})))
        if not all_targets:
            continue
        merged: dict = {}
        for target in sorted(all_targets):
            s  = svc.get("externalRelations", {}).get(target, 0)
            ev = svc.get("eventSubscriptions", {}).get(target, [])
            fn = svc.get("functionalityDependencies", {}).get(target, [])
            merged[target] = {
                "targetService":      target,
                "structuralRefs":     s,
                "eventTypes":         ev if ev else None,
                "functionalityNames": fn if fn else None,
                "totalWeight":        s + len(ev) + len(fn),
            }
        svc["interServiceDependencies"] = merged

    # Functionality-graph M1/M2 (must run after Pass 4 populates functionalityDependencies)
    m1_func, m2_func = compute_functional_metrics(services, all_functionalities)

    # Pass 4c: entities each functionality reaches, then the normalised signals the scores read.
    resolve_touched_entities(services, all_functionalities, ms_root)
    signals = compute_signals(services, all_functionalities)
    for svc in services.values():
        svc.pop("_contents", None)

    # Pass 5: controller endpoint + Locust task linking
    endpoint_map = collect_controller_map(ms_root)
    _locust_dir = locust_dir if locust_dir is not None else (Path(__file__).parent.parent / "locust")
    locust_map = collect_locust_task_map(_locust_dir)
    all_functionalities = enrich_functionalities(all_functionalities, endpoint_map, locust_map)

    return {
        "sourceDir":       str(source_dir.resolve()),
        "serviceCount":    len(services),
        "m1":              m1,
        "m2":              m2,
        "m1Functional":    m1_func,
        "m2Functional":    m2_func,
        **signals,
        "services":        services,
        "functionalities": all_functionalities,
    }

# ─── Output ───────────────────────────────────────────────────────────────────

def _fmt_inter_deps(svc: dict) -> str:
    """Format interServiceDependencies as compact inline text."""
    deps = svc.get("interServiceDependencies", {})
    if not deps:
        return "—"
    parts = []
    for target, edge in sorted(deps.items(), key=lambda kv: -kv[1]["totalWeight"]):
        s  = edge["structuralRefs"]
        e  = len(edge["eventTypes"] or [])
        f  = len(edge["functionalityNames"] or [])
        w  = edge["totalWeight"]
        tokens = []
        if s: tokens.append(f"σ={s}")
        if e: tokens.append(f"e={e}")
        if f: tokens.append(f"f={f}")
        tokens.append(f"w={w}")
        parts.append(f"{target}[{','.join(tokens)}]")
    return "  ".join(parts)


def _method_body(content: str, name: str):
    m = re.search(r'\b' + re.escape(name) + r'\s*\([^)]*\)\s*(?:throws[^{]*)?\{', content)
    if not m:
        return None
    depth, start = 1, m.end()
    for i in range(start, len(content)):
        c = content[i]
        if c == '{':
            depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0:
                return content[start:i]
    return None


def resolve_touched_entities(services: dict, funcs: list, ms_root: Path) -> None:
    """
    Pass 4c parity: per functionality, the entities of each service it reaches — named in its own
    source (class or DTO) or in the body of the service method each sent command delegates to.
    """
    handlers: dict[str, dict[str, str]] = {}     # service → command → "XService.method"
    sources: dict[str, dict[str, str]] = {}      # service → class → content
    for name in services:
        sdir = ms_root / name
        sources[name] = {}
        for f in (sdir / "service").glob("*.java") if (sdir / "service").is_dir() else []:
            try:
                sources[name][f.stem] = f.read_text(encoding="utf-8")
            except OSError:
                pass
        handlers[name] = {}
        for f in (sdir / "messaging").glob("*.java") if (sdir / "messaging").is_dir() else []:
            try:
                content = f.read_text(encoding="utf-8")
            except OSError:
                continue
            for m in RE_HANDLER_CASE.finditer(content):
                body = _method_body(content, m.group(2))
                d = RE_DELEGATION.search(body) if body else None
                if d:
                    handlers[name][m.group(1)] = d.group(1)[0].upper() + d.group(1)[1:] + "." + d.group(2)
    by_name = {f["name"]: f for f in funcs}
    for name in services:
        sdir = ms_root / name / "coordination" / "sagas"
        if not sdir.is_dir():
            continue
        for f in sdir.glob("*.java"):
            fi = by_name.get(f.stem)
            if fi is None:
                continue
            try:
                content = f.read_text(encoding="utf-8")
            except OSError:
                continue
            hits: dict[str, set] = {}
            sent = [(m.group(1), ENUM_TO_SERVICE.get(m.group(2))) for m in RE_COMMAND_SITE.finditer(content)]
            for svc in {fi["ownerService"], *[t for _, t in sent if t]}:
                for entity in services.get(svc, {}).get("entityNames", []):
                    if entity in content or (entity + "Dto") in content:
                        hits.setdefault(svc, set()).add(entity)
            for cmd, target in sent:
                if not target or target not in services:
                    continue
                sm = handlers.get(target, {}).get(cmd)
                if not sm:
                    continue
                cls, method = sm.split(".", 1)
                body = _method_body(sources.get(target, {}).get(cls, ""), method)
                if not body:
                    continue
                for entity in services[target]["entityNames"]:
                    if entity in body:
                        hits.setdefault(target, set()).add(entity)
            if hits:
                fi["touchedEntities"] = {k: sorted(v) for k, v in hits.items()}


def compute_signals(services: dict, funcs: list) -> dict:
    """
    Normalised signals (parity with rt-base StructuralSignals.enrich):
      structuralCohesion = μ / 2(N−1) capped at 1 (1 for N = 1)
      clusterFactor      = 2μ / (2μ + σ_in + σ_out)                      (TurboMQ)
      couplingDensity    = (σ_in + σ_out) / (N·(t'−1) + Σ_j≠i N_j)       system: Σσ / ((t'−1)·ΣN)
      localStepRatio     = local steps / steps of the owned functionalities
      replicaDensity     = @Replica classes / N
      eventEdgeDensity   = event subscriptions / N
      avgDistributedSteps= mean stepCount of owned functionalities that leave the service
      functionalCohesion = mean over functionalities touching s of |E_f ∩ E_s| / N_s
    System values are entity-weighted means; m2Max is M2 with every entity referencing every service.
    """
    t = len(services)
    total_n = sum(s["entityCount"] for s in services.values())
    incoming = {n: 0 for n in services}
    for n, s in services.items():
        for tgt, v in s["externalRelations"].items():
            if tgt in incoming:
                incoming[tgt] += v
    tally = {n: [0, 0, 0, 0] for n in services}   # local, total, distributed funcs, distributed steps
    fractions: dict[str, list] = {}
    for f in funcs:
        owner = f["ownerService"]
        cross = min(len([x for x in f["touchedServices"] if x != owner]), f["stepCount"])
        steps = max(f["stepCount"], cross)
        if owner in tally:
            tl = tally[owner]
            tl[0] += steps - cross; tl[1] += steps
            if cross:
                tl[2] += 1; tl[3] += steps
        for svc, ents in f.get("touchedEntities", {}).items():
            if svc in services and services[svc]["entityCount"] > 0:
                fractions.setdefault(svc, []).append(min(1.0, len(ents) / services[svc]["entityCount"]))
    total_refs = 0
    for n, s in services.items():
        N, mu = s["entityCount"], s["internalRelations"]
        out = sum(s["externalRelations"].values()); inn = incoming[n]; ext = out + inn
        total_refs += out
        s["structuralCohesion"] = 1.0 if N == 1 else min(1.0, mu / (2.0 * (N - 1)))
        s["clusterFactor"] = (1.0 if N == 1 else 0.0) if mu == 0 and ext == 0 else 2.0 * mu / (2.0 * mu + ext)
        mx = N * (t - 1) + (total_n - N)
        s["couplingDensity"] = 0.0 if mx == 0 else min(1.0, ext / mx)
        s["replicaDensity"] = len(s.get("replicaEntities", [])) / N
        s["eventEdgeDensity"] = s.get("eventSubscriptionCount", 0) / N
        tl = tally[n]
        s["localStepRatio"] = (tl[0] / tl[1]) if tl[1] else None
        s["avgDistributedSteps"] = (None if not tl[1] else (tl[3] / tl[2] if tl[2] else 0.0))
        fr = fractions.get(n)
        s["functionalCohesion"] = (sum(fr) / len(fr)) if fr else None
    def weighted(key):
        pairs = [(s["entityCount"], s[key]) for s in services.values() if s.get(key) is not None]
        w = sum(p[0] for p in pairs)
        return (sum(p[0] * p[1] for p in pairs) / w) if w else None
    total_steps = sum(tl[1] for tl in tally.values()); local = sum(tl[0] for tl in tally.values())
    dist_f = sum(tl[2] for tl in tally.values()); dist_s = sum(tl[3] for tl in tally.values())
    lst = list(services.values()); m2max = 0.0
    for i in range(t):
        for j in range(i + 1, t):
            ni, nj = lst[i]["entityCount"], lst[j]["entityCount"]
            if ni and nj:
                m2max += (ni + nj) / (2.0 * ni * nj)
    return {
        "structuralCohesion":  weighted("structuralCohesion"),
        "functionalCohesion":  weighted("functionalCohesion"),
        "clusterFactor":       weighted("clusterFactor"),
        "localStepRatio":      (local / total_steps) if total_steps else None,
        "replicaDensity":      weighted("replicaDensity"),
        "eventEdgeDensity":    weighted("eventEdgeDensity"),
        "couplingDensity":     0.0 if t <= 1 or total_n == 0 else min(1.0, total_refs / ((t - 1) * total_n)),
        "avgDistributedSteps": (None if not total_steps else (dist_s / dist_f if dist_f else 0.0)),
        "m2Max":               (m2max / (t * (t - 1) / 2.0)) if t > 1 else 0.0,
    }


def print_table(result: dict) -> None:
    services = result["services"]
    dep_col_w = 54
    sep = ("+" + "-"*20 + "+" + "-"*6 + "+" + "-"*5 + "+"
           + "-"*8 + "+" + "-"*8 + "+" + "-"*7 + "+" + "-"*8 + "+" + "-"*8 + "+"
           + "-"*(dep_col_w + 2) + "+")

    print(f"\nSource : {result['sourceDir']}")
    print(f"Services analysed : {result['serviceCount']}")
    print(f"Functionalities   : {len(result.get('functionalities', []))}")
    print()
    print(sep)
    print(f"| {'Service':<18} | {'N':>4} | {'μ':>3} | {'M1':>6} | {'M2':>6} | {'steps':>5} "
          f"| {'M1f':>6} | {'M2f':>6} "
          f"| {'Inter-service dependencies (σ=struct  e=events  f=funcs  w=weight)':<{dep_col_w}} |")
    print(sep)

    for svc in services.values():
        deps_str = _fmt_inter_deps(svc)
        print(f"| {svc['serviceName']:<18} | {svc['entityCount']:>4} | {svc['internalRelations']:>3} "
              f"| {svc['m1Contribution']:>6.4f} | {svc.get('m2Contribution', 0.0):>6.4f} "
              f"| {svc.get('functionalityStepCount', 0):>5} "
              f"| {svc.get('m1FunctionalContribution', 0.0):>6.4f} "
              f"| {svc.get('m2FunctionalContribution', 0.0):>6.4f} "
              f"| {deps_str:<{dep_col_w}} |")

    print(sep)
    print()
    def _f(v):
        return "   n/a" if v is None else f"{v:6.3f}"
    print("\n  Normalised signals per service (what the quality scores read):")
    print(f"  | {'Service':<18} | {'S1 tree':>7} | {'S2 func':>7} | {'S3 CF':>7} | {'S4 local':>8} | {'S5 repl':>7} | {'S6 evt':>7} | {'S7 coupl':>8} | {'S8 dist':>7} |")
    for svc in services.values():
        print(f"  | {svc['serviceName']:<18} | {_f(svc.get('structuralCohesion')):>7} | {_f(svc.get('functionalCohesion')):>7} "
              f"| {_f(svc.get('clusterFactor')):>7} | {_f(svc.get('localStepRatio')):>8} | {_f(svc.get('replicaDensity')):>7} "
              f"| {_f(svc.get('eventEdgeDensity')):>7} | {_f(svc.get('couplingDensity')):>8} | {_f(svc.get('avgDistributedSteps')):>7} |")
    print(f"  system: S1={_f(result.get('structuralCohesion'))} S2={_f(result.get('functionalCohesion'))} "
          f"S3={_f(result.get('clusterFactor'))} S4={_f(result.get('localStepRatio'))} S5={_f(result.get('replicaDensity'))} "
          f"S6={_f(result.get('eventEdgeDensity'))} S7={_f(result.get('couplingDensity'))} S8={_f(result.get('avgDistributedSteps'))} "
          f"M2max={_f(result.get('m2Max'))}")
    print(f"  M1 structural  = {result['m1']:.6f}   M2 structural  = {result['m2']:.6f}  (JPA entity references)")
    print(f"  M1 functional  = {result.get('m1Functional', 0.0):.6f}   M2 functional  = {result.get('m2Functional', 0.0):.6f}  (Functionality class calls)")
    print()

# ─── Entry point ──────────────────────────────────────────────────────────────

def main() -> None:
    default_source = Path(__file__).parent.parent.parent.parent / \
        "applications/quizzes/src/main/java"

    parser = argparse.ArgumentParser(
        description="Compute M1/M2 structural metrics from the quizzes Java source tree."
    )
    parser.add_argument(
        "--source-dir",
        type=Path,
        default=default_source,
        help=f"Root of the Java source tree (default: {default_source})",
    )
    parser.add_argument(
        "--json",
        action="store_true",
        help="Output raw JSON instead of the formatted table",
    )
    default_locust = Path(__file__).parent.parent / "locust"
    parser.add_argument(
        "--locust-scripts-dir",
        type=Path,
        default=default_locust,
        help=f"Directory containing Locust *.py scripts (default: {default_locust})",
    )
    args = parser.parse_args()

    result = run_analysis(args.source_dir, locust_dir=args.locust_scripts_dir)

    if args.json:
        print(json.dumps(result, indent=2))
    else:
        print_table(result)


if __name__ == "__main__":
    main()
