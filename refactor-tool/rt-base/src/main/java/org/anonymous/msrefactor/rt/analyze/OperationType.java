package org.anonymous.msrefactor.rt.analyze;

/**
 * Supported refactoring operations, as defined in the SKILL.md operation catalogue.
 *
 * <p>Each constant has exactly one executable skill under
 * {@code applications/.claude/skills/refactor-<slug>/} — see
 * {@code SkillPromptBuilder.toSkillName}. Adding a constant without its skill produces a plan the
 * Execute phase cannot run.
 */
public enum OperationType {
    /** Consolidate two tightly coupled aggregates into one. */
    MERGE_AGGREGATES,
    /** Break down an oversized aggregate lacking cohesion. */
    SPLIT_AGGREGATE,
    /** Cache remote data locally with acceptable staleness. */
    CREATE_REPLICA,
    /** Eliminate stale replicated data that is causing consistency problems. */
    REMOVE_REPLICA
}
