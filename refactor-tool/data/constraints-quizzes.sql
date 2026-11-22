-- Baseline refactor constraints for the `quizzes` application.
--
-- What this is: the verdicts that do not depend on any run — facts about the aggregates as they are
-- declared today, recorded up front so the loop does not spend an iteration rediscovering them.
-- Their evidence is `applications/quizzes/quizzes-composition.md` §4 (ownership) and §6 (split
-- feasibility); each `reason` below names the fact it rests on.
--
-- How it is applied: `./deploy.sh --seed-basic` or `./deploy-rt.sh --seed-basic`. Safe to re-run —
-- every INSERT is `ON CONFLICT (id) DO NOTHING`, and the ids are fixed, so a second run is a no-op.
-- It is deliberately NOT mounted into /docker-entrypoint-initdb.d/: that directory runs only on the
-- first init of an empty PGDATA, which is the one moment when nobody asked for it.
--
-- To apply it by hand against a running stack:
--     docker compose exec -T rt-db psql -U rt -d refactordb -v ON_ERROR_STOP=1 \
--         < data/constraints-quizzes.sql
--
-- The DDL is repeated here so the file also works before rt-app has ever booted, when Hibernate
-- has not yet created the table.

CREATE TABLE IF NOT EXISTS refactor_constraint (
    id               varchar(36)                 NOT NULL,
    -- Application this rule belongs to (AppTargetConfig.name). NULL = global, applies everywhere.
    project_id       varchar(36),
    operation        varchar(64),
    target_service   varchar(64),
    secondary_target varchar(64),
    -- Entity class the rule is scoped to; NULL = the whole (operation, service pair).
    target_entity    varchar(128),
    reason           varchar(512)                NOT NULL,
    blocking         boolean                     NOT NULL,
    -- Sibling merges record the sequence that would make the pair reachable, e.g.
    -- "course<-topic, course<-question". NULL for architect rules and for true refusals.
    prerequisite_chain varchar(512),
    created_at       timestamp(6) with time zone NOT NULL,
    PRIMARY KEY (id)
);

-- ── SPLIT_AGGREGATE ──────────────────────────────────────────────────────────
--
-- `secondary_target` is NULL because a split concerns one aggregate, and because the two call sites
-- that consult these rows pass a null secondary: a non-null value here would never match.
-- `target_entity` is NULL so the rule answers every per-entity question the partitioner asks, which
-- is what makes every candidate cut of that aggregate blocked rather than just some of them.

-- course — one entity, zero composition units (case 4, a permanent property of the aggregate).
INSERT INTO refactor_constraint (id, project_id, operation, target_service, secondary_target,
    target_entity, reason, blocking, prerequisite_chain, created_at)
VALUES (
    '00000000-0000-0000-0000-0000000000c1',
    'quizzes', 'SPLIT_AGGREGATE', 'course', NULL, NULL,
    'Course declares one entity and zero composition units - CourseType is an enum, not an entity - so there is no second group of state to give a boundary of its own. Case 4 in refactor-split-aggregate: a single-entity aggregate does not become splittable by adding a functionality.',
    true, NULL,
    '2026-09-24 00:00:00.000+00'
) ON CONFLICT (id) DO NOTHING;

-- user — same shape as course.
INSERT INTO refactor_constraint (id, project_id, operation, target_service, secondary_target,
    target_entity, reason, blocking, prerequisite_chain, created_at)
VALUES (
    '00000000-0000-0000-0000-0000000000c2',
    'quizzes', 'SPLIT_AGGREGATE', 'user', NULL, NULL,
    'User declares one entity and zero composition units - Role is an enum, not an entity - so there is nothing to partition. Case 4 in refactor-split-aggregate.',
    true, NULL,
    '2026-09-24 00:00:00.000+00'
) ON CONFLICT (id) DO NOTHING;

-- topic — its only unit is a pure replica (case 5). Revisable: one authoritative field changes it.
INSERT INTO refactor_constraint (id, project_id, operation, target_service, secondary_target,
    target_entity, reason, blocking, prerequisite_chain, created_at)
VALUES (
    '00000000-0000-0000-0000-0000000000c3',
    'quizzes', 'SPLIT_AGGREGATE', 'topic', NULL, NULL,
    'Topic has one composition unit, TopicCourse, which is @Replica("Course") and carries only courseAggregateId and courseVersion; the core would keep nothing but name, and verifyInvariants() and getEventSubscriptions() are both empty. The cut would deploy a cache of another service. Case 5 in refactor-split-aggregate; use remove-replica or the merge course<-topic instead. Revisable: one authoritative field on the satellite makes it a real cut.',
    true, NULL,
    '2026-09-24 00:00:00.000+00'
) ON CONFLICT (id) DO NOTHING;

-- execution — both units are pure replicas (case 5). Revisable on the same terms as topic.
INSERT INTO refactor_constraint (id, project_id, operation, target_service, secondary_target,
    target_entity, reason, blocking, prerequisite_chain, created_at)
VALUES (
    '00000000-0000-0000-0000-0000000000c4',
    'quizzes', 'SPLIT_AGGREGATE', 'execution', NULL, NULL,
    'Execution has two composition units and both are pure replicas: CourseExecutionCourse of Course and CourseExecutionStudent of User, every field mirrored from its owner. Any cut yields an aggregate whose only assertion is about its own cache, and UsersExist would make that cache the sole subscriber of the user events. Case 5 in refactor-split-aggregate. Revisable: one authoritative field on the satellite makes it a real cut.',
    true, NULL,
    '2026-09-24 00:00:00.000+00'
) ON CONFLICT (id) DO NOTHING;

-- ── MERGE_AGGREGATES ─────────────────────────────────────────────────────────
--
-- Two rows cover all fourteen orderings, because ConstraintService matches the operands positionally
-- and a NULL field is a wildcard: one row with user as the target, one with user as the secondary.

-- merge(user, X) for any X.
INSERT INTO refactor_constraint (id, project_id, operation, target_service, secondary_target,
    target_entity, reason, blocking, prerequisite_chain, created_at)
VALUES (
    '00000000-0000-0000-0000-0000000000c5',
    'quizzes', 'MERGE_AGGREGATES', 'user', NULL, NULL,
    'User owns nothing and is owned by nothing: it holds no single-valued replica of another root, and the aggregates that reach it do so through role-named replicas (creatorAggregateId, studentAggregateId, participantAggregateId). No cardinality binds a User instance to an instance of anything else, so no single root could contain both. Case 5 in refactor-merge-aggregate - the only true refusal in this application.',
    true, NULL,
    '2026-09-24 00:00:00.000+00'
) ON CONFLICT (id) DO NOTHING;

-- merge(X, user) for any X — the same fact, reached from the other side.
INSERT INTO refactor_constraint (id, project_id, operation, target_service, secondary_target,
    target_entity, reason, blocking, prerequisite_chain, created_at)
VALUES (
    '00000000-0000-0000-0000-0000000000c6',
    'quizzes', 'MERGE_AGGREGATES', NULL, 'user', NULL,
    'User owns nothing and is owned by nothing, so it cannot be absorbed by any aggregate either: what reaches it are role-named replicas, not containment. Case 5 in refactor-merge-aggregate.',
    true, NULL,
    '2026-09-24 00:00:00.000+00'
) ON CONFLICT (id) DO NOTHING;
