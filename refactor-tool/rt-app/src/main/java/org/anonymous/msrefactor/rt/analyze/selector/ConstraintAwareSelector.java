package org.anonymous.msrefactor.rt.analyze.selector;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.anonymous.msrefactor.rt.analyze.service.ConstraintService;
import org.anonymous.msrefactor.rt.monitor.ProjectConfig;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Single entry point for <em>constraint-aware operand selection</em> across the candidate
 * generators and selectors.
 *
 * <p>When {@link #enabled()} is true (the {@code constraintAwareSelection} project flag), the
 * selection sites pick the strongest operand (partner / source / owner / entity) that no
 * <em>blocking</em> architect constraint forbids, instead of selecting blindly and being demoted
 * at Stage&nbsp;9. When the flag is false every method is a no-op so the legacy behaviour is
 * reproduced exactly.
 *
 * <p>This class is intentionally thin: it only filters an already-ranked candidate list and
 * surfaces blocking reasons. Ranking (by &sigma; or by cross-entity-group size) stays in the
 * callers, where the structural maps already live.
 */
@Component
@RequiredArgsConstructor
public class ConstraintAwareSelector {

    private final ConstraintService constraintService;
    private final ProjectConfig     projectConfig;

    /** Whether constraint-aware selection is active for this project. */
    public boolean enabled() {
        return projectConfig.isConstraintAwareSelection();
    }

    /**
     * Returns the first candidate in {@code rankedCandidates} (highest priority first) for which no
     * blocking constraint forbids {@code (op, source, candidate, entitiesFor(candidate))}, or empty
     * when every candidate is blocked. {@code rankedCandidates} is expected pre-sorted strongest-first.
     */
    public Optional<String> firstAllowed(String op, String source,
                                         List<String> rankedCandidates,
                                         Function<String, List<String>> entitiesFor) {
        for (String candidate : rankedCandidates) {
            if (!constraintService.isBlocked(op, source, candidate, entitiesFor.apply(candidate))) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /** True when a blocking constraint forbids this exact operand tuple. */
    public boolean isBlocked(String op, String target, String secondary, List<String> entities) {
        return constraintService.isBlocked(op, target, secondary, entities);
    }

    /** Blocking reasons for a single operand tuple — used when emitting a blocked placeholder. */
    public List<String> blockingReasons(String op, String target, String secondary, List<String> entities) {
        return constraintService.blockingReasons(op, target, secondary, entities);
    }

    /**
     * Distinct blocking reasons across all candidates of a fully-blocked ranked list. Used to
     * explain a placeholder when no partner/source/owner is permitted at all.
     */
    public List<String> blockingReasonsAcross(String op, String source,
                                              List<String> rankedCandidates,
                                              Function<String, List<String>> entitiesFor) {
        LinkedHashSet<String> reasons = new LinkedHashSet<>();
        for (String candidate : rankedCandidates) {
            reasons.addAll(constraintService.blockingReasons(op, source, candidate, entitiesFor.apply(candidate)));
        }
        return List.copyOf(reasons);
    }
}
