# Case 6 — No independent address

P1, P5 and P3 satisfied, P4 **not yet**: the satellite is a real, authoritative aggregate, but it has
no way to be reached. Either no port has been allocated to it, or an externally visible path that
reads or writes its state is matched by one of the core's `Path=` predicates and would keep landing on
the core after the split.

**This is not a refusal.** It is the one case whose content is pure allocation: decide the port, the
grpc port and the route ordering, write them down, and then run the layers exactly as case 1 or case 2
prescribes. The case exists because skipping the decision fails *silently* — the test profile never
loads the gateway, so a shadowed route passes every test and 404s in the deployment.

## Action

1. **Allocate the ports.** Layer 4.2 is the authority: the lowest `server.port` ≥ **8090** that is
   neither published by `docker-compose.yml` or the root file it includes nor **reserved** — 8090 and
   8100 always (the refactor tool's `rt-app` and `locust-service`), plus every port listed under
   `## Reserved host ports` in your prompt — and `grpc.server.port = port + 1010`, the offset every
   existing service uses (8082/9092 … 8089/9099).

   ```bash
   grep -hoE '"[0-9]+(-[0-9]+)?:[0-9]' docker-compose.yml ../../docker-compose.yml | grep -oE '[0-9]+(-[0-9]+)?' | sort -nu
   grep -rn "port:" src/main/resources/application*.yaml | sort -u
   ```

   **8081 is not usable**: its grpc pair 9091 is taken by `grpc.command.version.port` in
   `application.yaml`.

2. **Decide, per path, whether it moves.** For every `@*Mapping` on `<Aggr>`'s controller that reads or
   writes satellite state:

   | Choice | Consequence |
   |---|---|
   | **move it** to `<Satellite>Controller` | the path is served by the new service; its predicate must be carved out of the core's `routes:` and declared in the satellite's, with an `order` below the core's |
   | **keep it on the core** | the core's controller answers and reaches the satellite by command — one broker round-trip per request on that path, paid forever |

   A path whose response composes both sides — a DTO that carries satellite collections — usually
   stays on the core and pays the round-trip, because moving it would only invert the problem.

3. **Order the routes.** The existing `order` values are 1–10 and all taken. The satellite's
   predicates are the *more specific* ones, so they must be evaluated first:

   ```
   satellite order = core order − 10
   ```

   which cannot collide, because every existing order is ≥ 1. Prefer also **narrowing the core's
   predicate** in layer 4.6 rather than relying on the order alone: two predicates matching the same
   request, resolved by order, is a working configuration that is one edit away from breaking.

4. **Emit the `GATE:` lines**, with what was allocated:

   ```
   GATE: P4 satisfied after allocation — <sat> takes port <n>/grpc <n> (reserved, skipped: <ports | none>); <n> route(s) move (<paths>) at order <n>; <n> stay on <svc> and reach <Satellite> by command
   GATE: <Aggr> partitions into <Core> + <Satellite> (5/5) — case 6, running as case <1|2>
   ```

5. **Then run the layers**, following case 1 or case 2 according to P2. This case adds the allocation,
   nothing else: layer 4.5 writes the routes it decided, and layer 4.11 proves them with `curl`.

## Worked example — `tournament`

Four of `TournamentController`'s eleven endpoints are participant-facing: `/tournaments/{id}/join`,
`/leave`, `/solveQuiz` and `/tournaments/{id}/user/{userId}`. All four are matched by `tournament`'s
`Path=/tournaments/**` at `order: 6`.

Moving them means `application-tournament-participation-service.yaml` declares
`Path=/tournaments/*/join, /tournaments/*/leave, /tournaments/*/solveQuiz, /tournaments/*/user/**`
at `order: -4`, and layer 4.6 narrows the core's predicate so the two no longer overlap.

The fifth, `GET /tournaments/{id}`, does **not** move: it returns a `TournamentDto` carrying the
participants, so the core composes it from a remote read. That is the hottest read path in the
aggregate, and it gains a broker hop — say so in the log, because it is the cost the architect is
accepting by cutting here rather than elsewhere.

## Common failure, and how it is caught

1. **The satellite's route is given an order above the core's.** The core's catch-all wins, the moved
   endpoint 404s or reaches a controller that no longer owns the state — and every test passes,
   because no test loads the gateway. Caught only at layer 4.11:
   ```bash
   curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/<moved-path>/<known-id>   # not 404
   docker logs <sat-infra>-service | grep -c "Mapped"                                     # > 0
   ```
2. **A port collision.** Two services claiming the same port, or the satellite claiming a port another
   compose project already publishes on the host: `compose up` fails with `port is already allocated`
   and the deployment half-starts — after every test has passed. v6 of the evaluation lost a full run
   this way, by taking 8090 from `rt-app`. Both checks are part of 4.11:
   ```bash
   grep -hoE '"[0-9]+:' docker-compose.yml ../../docker-compose.yml | grep -v '"8080:' | sort | uniq -d   # empty
   grep -hc '"<port>:' docker-compose.yml ../../docker-compose.yml                                     # 1 in total, <port> not reserved
   ```
3. **Allocating and not recording.** The port and the moved paths are in the `Topology:` line for a
   reason: they are the two facts nobody can recover from the diff six months later.
