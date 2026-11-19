package org.anonymous.msrefactor.rt.analyze.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How the two channels of {@code dependencies} combine.
 *
 * <p>One is reactive — a skill hit a prerequisite while executing and recorded the chain that would
 * clear it. The other is derived — Analyze deduced it from what the cut breaks. The service used to
 * take whichever was non-empty, reactive winning; that was invisible while nothing was derived, and
 * would now silently drop the derived chain of any candidate an architect constraint also matched.
 */
class MergeDependenciesTest {

    @Test
    @DisplayName("both channels survive, reactive first")
    void unionsInsteadOfReplacing() {
        List<String> merged = AnalyzeService.mergeDependencies(
                List.of("course<-topic", "course<-question"),
                List.of("/new-event UpdateTournamentStartTimeEvent Tournament TournamentParticipantCluster"));

        assertThat(merged).containsExactly(
                "course<-topic",
                "course<-question",
                "/new-event UpdateTournamentStartTimeEvent Tournament TournamentParticipantCluster");
    }

    @Test
    @DisplayName("a step recorded by both channels appears once")
    void deduplicates() {
        assertThat(AnalyzeService.mergeDependencies(
                List.of("/new-event UpdateTournamentCreatorEvent Tournament TournamentCluster"),
                List.of("/new-event UpdateTournamentCreatorEvent Tournament TournamentCluster")))
                .hasSize(1);
    }

    @Test
    @DisplayName("with nothing reactive the derived chain passes through unchanged")
    void derivedOnly() {
        assertThat(AnalyzeService.mergeDependencies(List.of(), List.of("/inter-invariant X ruleOne")))
                .containsExactly("/inter-invariant X ruleOne");
    }

    @Test
    @DisplayName("nothing on either side gives back what the candidate had — null included")
    void bothEmpty() {
        assertThat(AnalyzeService.mergeDependencies(List.of(), List.of())).isEmpty();
        assertThat(AnalyzeService.mergeDependencies(List.of(), null)).isNull();
    }
}
