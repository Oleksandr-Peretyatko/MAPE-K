package org.anonymous.msrefactor.rt.execute.persistence;

public enum ExecuteStatus {
    PENDING,
    MUTATING,
    BUILDING,
    DEPLOYING,
    DEPLOYED,
    FAILED,
    ROLLED_BACK,
    /**
     * The skill found the operation inapplicable to this codebase and changed nothing — for example
     * a merge whose aggregates cannot be dissolved into one another. Terminal and <b>not</b> a
     * failure: no build, no deploy, no new code version, and the iteration stays open so the loop
     * can try the next candidate. The run normally leaves an architect restriction behind so the
     * same operation is not proposed again.
     */
    NOT_APPLICABLE,
    /**
     * Ghost mode only: the branch exists but nothing has been mutated, and the run is parked
     * waiting for an operator verdict via {@code POST /execute/verdict}. Non-terminal — the
     * verdict is what decides the CLAUDE.md log entry's {@code Result}, so it must be taken before
     * anything is written or committed. A parked run still counts as in-flight, so no second
     * execution can start behind it.
     */
    AWAITING_VERDICT
}
