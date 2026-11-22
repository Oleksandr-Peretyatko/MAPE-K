package org.anonymous.msrefactor.rt.analyze.selector;

import org.junit.jupiter.api.Test;
import org.anonymous.msrefactor.rt.analyze.service.ConstraintService;
import org.anonymous.msrefactor.rt.monitor.ProjectConfig;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ConstraintAwareSelector} — the strongest-allowed-tie filter (Pattern 1).
 */
class ConstraintAwareSelectorTest {

    @Test
    void firstAllowed_picksStrongestWhenNoneBlocked() {
        ConstraintService cs = mock(ConstraintService.class);
        ProjectConfig pc = mock(ProjectConfig.class);
        when(cs.isBlocked(any(), any(), any(), any())).thenReturn(false);
        ConstraintAwareSelector sel = new ConstraintAwareSelector(cs, pc);

        // candidates are pre-ranked strongest-first → the first (user) wins when nothing is blocked
        Optional<String> r = sel.firstAllowed("MERGE_AGGREGATES", "tournament",
                List.of("user", "execution"), b -> List.of());
        assertThat(r).contains("user");
    }

    @Test
    void firstAllowed_skipsBlockedAndReturnsNextStrongest() {
        ConstraintService cs = mock(ConstraintService.class);
        ProjectConfig pc = mock(ProjectConfig.class);
        when(cs.isBlocked(eq("MERGE_AGGREGATES"), eq("tournament"), eq("user"), any())).thenReturn(true);
        when(cs.isBlocked(eq("MERGE_AGGREGATES"), eq("tournament"), eq("execution"), any())).thenReturn(false);
        ConstraintAwareSelector sel = new ConstraintAwareSelector(cs, pc);

        Optional<String> r = sel.firstAllowed("MERGE_AGGREGATES", "tournament",
                List.of("user", "execution"), b -> List.of());
        assertThat(r).contains("execution"); // user is the strongest but forbidden → fall through
    }

    @Test
    void firstAllowed_emptyWhenEveryCandidateBlocked() {
        ConstraintService cs = mock(ConstraintService.class);
        ProjectConfig pc = mock(ProjectConfig.class);
        when(cs.isBlocked(any(), any(), any(), any())).thenReturn(true);
        ConstraintAwareSelector sel = new ConstraintAwareSelector(cs, pc);

        Optional<String> r = sel.firstAllowed("MERGE_AGGREGATES", "tournament",
                List.of("user", "execution"), b -> List.of());
        assertThat(r).isEmpty();
    }

    @Test
    void enabled_reflectsProjectConfigFlag() {
        ConstraintService cs = mock(ConstraintService.class);
        ProjectConfig pc = mock(ProjectConfig.class);
        when(pc.isConstraintAwareSelection()).thenReturn(true, false);
        ConstraintAwareSelector sel = new ConstraintAwareSelector(cs, pc);

        assertThat(sel.enabled()).isTrue();
        assertThat(sel.enabled()).isFalse();
    }
}
