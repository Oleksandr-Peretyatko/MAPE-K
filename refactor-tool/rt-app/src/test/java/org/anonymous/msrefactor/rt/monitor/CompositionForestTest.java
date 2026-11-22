package org.anonymous.msrefactor.rt.monitor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one definition of "what is inside this aggregate".
 *
 * <p>Two callers walk this graph: the partitioner, which builds cut candidates out of whole subtrees
 * so a cut cannot orphan a child, and the structure view, which renders the same containment on
 * screen. The property that matters is that the two views agree — a flat list the partitioner cuts
 * along and a tree the architect reads must describe the same set, or the picture stops being
 * evidence for the decision.
 */
class CompositionForestTest {

    /** The real Tournament shape, including the level-2 child. */
    private static final Map<String, List<String>> TOURNAMENT = Map.of(
            "Tournament", List.of("TournamentCreator", "TournamentParticipant",
                    "TournamentCourseExecution", "TournamentTopic", "TournamentQuiz"),
            "TournamentParticipant", List.of("TournamentParticipantQuizAnswer"));

    @Test
    @DisplayName("the flat walk and the nested walk describe the same set")
    void subtreeAndTreeAgree() {
        List<String> flat = CompositionForest.subtree("Tournament", TOURNAMENT);
        List<String> fromTree = flatten(CompositionForest.tree("Tournament", TOURNAMENT));

        assertThat(fromTree).containsExactlyInAnyOrderElementsOf(flat);
        assertThat(flat).hasSize(7);
    }

    @Test
    @DisplayName("a subtree carries its descendants, however deep")
    void subtreeIsClosedOverDescendants() {
        assertThat(CompositionForest.subtree("TournamentParticipant", TOURNAMENT))
                .containsExactly("TournamentParticipant", "TournamentParticipantQuizAnswer");
    }

    @Test
    @DisplayName("units are the direct members of the root, each closed over its own children")
    void unitsAreWholeSubtrees() {
        List<List<String>> units = CompositionForest.units("Tournament", TOURNAMENT);

        assertThat(units).hasSize(5);
        assertThat(units).anySatisfy(u -> assertThat(u)
                .containsExactly("TournamentParticipant", "TournamentParticipantQuizAnswer"));
        assertThat(units).allSatisfy(u -> assertThat(u).doesNotContain("Tournament"));
    }

    @Test
    @DisplayName("the root is the parent nothing else contains")
    void resolvesTheRoot() {
        assertThat(CompositionForest.resolveRoot(TOURNAMENT, List.of())).isEqualTo("Tournament");
    }

    @Test
    @DisplayName("without composition data the caller's own roots decide")
    void fallsBackToDeclaredRoots() {
        assertThat(CompositionForest.resolveRoot(Map.of(), List.of("QuizAnswer"))).isEqualTo("QuizAnswer");
        assertThat(CompositionForest.resolveRoot(null, List.of("QuizAnswer"))).isEqualTo("QuizAnswer");
        assertThat(CompositionForest.resolveRoot(null, List.of())).isNull();
    }

    @Test
    @DisplayName("a cycle terminates instead of hanging")
    void cyclesAreGuarded() {
        Map<String, List<String>> cyclic = Map.of(
                "A", List.of("B"),
                "B", List.of("A"));

        assertThat(CompositionForest.subtree("A", cyclic)).containsExactly("A", "B");
        assertThat(flatten(CompositionForest.tree("A", cyclic))).containsExactly("A", "B");
    }

    private List<String> flatten(CompositionForest.Node node) {
        List<String> out = new ArrayList<>();
        out.add(node.name());
        node.children().forEach(c -> out.addAll(flatten(c)));
        return out;
    }
}
