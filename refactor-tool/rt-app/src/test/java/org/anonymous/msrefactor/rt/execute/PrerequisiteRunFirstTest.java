package org.anonymous.msrefactor.rt.execute;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code runFirst:} turns a line a model wrote into a file into a subprocess Execute runs, so the
 * parsing side of that contract is worth pinning: the field has to survive the block parser
 * unchanged, and a block without it has to stay an ordinary prerequisite.
 *
 * <p>The allowlist that decides which skills may run lives in {@code ExecuteService}; these tests
 * cover the input it is given.
 */
class PrerequisiteRunFirstTest {

    private String entry(String block) {
        return """
                <!-- refactor-log -->
                Date: 2026-08-25T10:00:00Z
                Skill: refactor-create-replica
                Invocation: /create-replica execution CourseExecution --to tournament
                Result: NO_CHANGE
                Summary: no event carries a change to the replicated fields
                """ + block + "\n<!-- end-refactor-log -->\n";
    }

    private static final String CASE_5 = """
            <!-- REFACTOR_PREREQUISITE -->
            operation: CREATE_REPLICA
            targetService: tournament
            secondaryTarget: execution
            chain: new-event UpdateExecutionAcronymEvent execution tournament, then CREATE_REPLICA execution CourseExecution --to tournament
            runFirst: /new-event UpdateExecutionAcronymEvent Execution Tournament
            reason: Execution publishes no event carrying a change to acronym, and acronym is mutable via ExecutionService.updateAcronym.
            <!-- END_REFACTOR_PREREQUISITE -->""";

    @Test
    @DisplayName("runFirst survives the block parser verbatim, alongside chain and reason")
    void parsesRunFirst() {
        Optional<Map<String, String>> parsed = RestrictionParser.parsePrerequisite(entry(CASE_5));

        assertThat(parsed).isPresent();
        Map<String, String> f = parsed.get();
        assertThat(f.get("runFirst"))
                .isEqualTo("/new-event UpdateExecutionAcronymEvent Execution Tournament");
        assertThat(f.get("operation")).isEqualTo("CREATE_REPLICA");
        assertThat(f.get("targetService")).isEqualTo("tournament");
        assertThat(f.get("secondaryTarget")).isEqualTo("execution");
        // chain stays the human-readable form and is not what Execute acts on
        assertThat(f.get("chain")).contains("then CREATE_REPLICA");
    }

    @Test
    @DisplayName("a prerequisite without runFirst still parses — it is recorded, not fulfilled")
    void runFirstIsOptional() {
        String case3 = """
                <!-- REFACTOR_PREREQUISITE -->
                operation: CREATE_REPLICA
                targetService: tournament
                secondaryTarget: execution
                chain: MERGE_AGGREGATES tournament execution
                reason: tournament writes the replicated field via AddParticipantFunctionality.
                <!-- END_REFACTOR_PREREQUISITE -->""";

        Optional<Map<String, String>> parsed = RestrictionParser.parsePrerequisite(entry(case3));

        assertThat(parsed).isPresent();
        assertThat(parsed.get().get("chain")).isEqualTo("MERGE_AGGREGATES tournament execution");
        assertThat(parsed.get()).doesNotContainKey("runFirst");
    }

    @Test
    @DisplayName("a run that changed code cannot also ask for a prerequisite")
    void ignoredWhenTheRunReportsChanged() {
        String changed = entry(CASE_5).replace("Result: NO_CHANGE", "Result: CHANGED");

        assertThat(RestrictionParser.parsePrerequisite(changed)).isEmpty();
    }

    @Test
    @DisplayName("only the current entry is read — an earlier run's runFirst is not re-fulfilled")
    void onlyTheNewestEntryCounts() {
        String twoEntries = entry(CASE_5) + """
                <!-- refactor-log -->
                Date: 2026-08-25T11:00:00Z
                Skill: refactor-create-replica
                Invocation: /create-replica execution CourseExecution --to quiz
                Result: CHANGED
                Summary: replica created
                <!-- end-refactor-log -->
                """;

        assertThat(RestrictionParser.parsePrerequisite(twoEntries)).isEmpty();
    }
}
