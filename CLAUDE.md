# CLAUDE.md — Navigation Hub

This file is the entry point for both humans and AI agents. Each section links to the authoritative source for deeper information.

---

## Build Commands

```bash
# Install core library first
cd simulator && mvn install

# Run Quizzes application tests
cd applications/quizzes

mvn clean -Ptest-sagas test          # all sagas tests
mvn clean -Ptest-sagas test -Dtest=AddParticipantAndUpdateStudentNameTest  # single class

# Frontend (Next.js)
cd ../../frontend
yarn dev        # dev server
yarn build      # production build
yarn lint       # ESLint
```

---

## Module Map

| Module | Purpose | Local context |
|--------|---------|---------------|
| `simulator/` | Core library: `Aggregate`, `Workflow`, `UnitOfWork`, `CommandGateway`, events | [`simulator/CLAUDE.md`](simulator/CLAUDE.md) |
| `applications/quizzes/` | Quizzes case study built on the simulator | [`applications/quizzes/CLAUDE.md`](applications/quizzes/CLAUDE.md) |
| `frontend/` | Next.js frontend (TypeScript, Tailwind, Radix UI, Cytoscape) — routes: `/loop`, `/versions`, `/iterations`, `/config`, `/graph`; package manager: yarn 4 | [`frontend/README.md`](frontend/README.md) |

---

Core concepts and architectural overview → [`docs/architecture.md`](docs/architecture.md)

---

## Key File Locations

| Concern | Path |
|---------|------|
| Execution aggregate (with inter-invariants) | `applications/.../execution/aggregate/Execution.java` |
| Tournament saga functionality (multi-step) | `applications/.../tournament/coordination/sagas/AddParticipantFunctionalitySagas.java` |
| Test bean config (Sagas) | `applications/.../quizzes/BeanConfigurationSagas.groovy` |
| Base Spock test class | `applications/.../quizzes/QuizzesSpockTest.groovy` |
| Error messages | `applications/.../quizzes/microservices/exception/QuizzesErrorMessage.java` |

---

## Documentation

| Topic | Path |
|-------|------|
| Application architecture & restrictions | [`docs/architecture.md`](docs/architecture.md) |
| Aggregate versioning | [`docs/concepts/aggregate.md`](docs/concepts/aggregate.md) |
| Domain events | [`docs/concepts/events.md`](docs/concepts/events.md) |
| Sagas semantic locks | [`docs/concepts/sagas.md`](docs/concepts/sagas.md) |
| Rule enforcement patterns | [`docs/concepts/rule-enforcement-patterns.md`](docs/concepts/rule-enforcement-patterns.md) |

---

## Available Skills

### Domain / DDD skills (`applications/.claude/skills/` — authoritative location)

Invoke these with `/skill-name <arguments>` when implementing new features in `applications/quizzes/`.
They are also **pipeline prerequisites**: when a refactoring skill emits a `runFirst:` directive
(or `CandidateGenerator` plans one), `ExecuteService.applyPrerequisite()` runs the named skill
before the main operation (see `RUNNABLE_PREREQUISITE_SKILLS`).

| Skill | When to use | Invoke with |
|-------|------------|-------------|
| `intra-invariant` | Add a Layer 1 intra-invariant (`verifyInvariants` check) inside a single aggregate | `/intra-invariant <AggregateName> <rule-description>` |
| `service-guard` | Add a Layer 3 service-layer guard that reads the DB and throws before any mutation | `/service-guard <ServiceName> <operation-method> <precondition>` |
| `inter-invariant` | Add a Layer 6 cross-aggregate consistency rule maintained via domain events (eventual) | `/inter-invariant <ConsumerAggregate> <condition>` |
| `new-aggregate` | Scaffold a new domain aggregate (base + Saga + factory + repo) | `/new-aggregate <AggregateName>` |
| `new-event` | Add a new domain event with subscriptions, handler, and polling | `/new-event <EventName> <publisher> <consumer>` |
| `new-functionality` | Implement a new cross-service operation (Sagas + command handler + controller) | `/new-functionality <Name> <aggregates...>` |

### MAPE-K automation skills (`applications/.claude/skills/refactor-*` — pipeline use only)

These are loaded **autonomously by Claude** via slash-command traversal from `applications/quizzes`.
They live in `applications/.claude/skills/` with a `refactor-` prefix (e.g., `refactor-merge-aggregate/`).
The pipeline sends only `/refactor-{skill} {args}` — Claude reads the SKILL.md itself.

**Executable operations** — one skill per `OperationType` constant:
`refactor-merge-aggregate`, `refactor-split-aggregate`, `refactor-create-replica`,
`refactor-remove-replica`. They are invoked by `ClaudeCommandMutator`
(replica skills only when `RT_ANALYZE_ENABLED_OPERATIONS` includes them).
