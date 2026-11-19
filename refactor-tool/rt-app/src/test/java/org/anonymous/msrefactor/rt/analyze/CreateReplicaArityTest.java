package org.anonymous.msrefactor.rt.analyze;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code CreateReplica} replicates one entity, out of one source, into one target.
 *
 * <p>The command has no list-valued argument, so the arity has to hold at the point the command
 * string is built — otherwise a multi-entity candidate reads as a valid invocation and the surplus
 * reaches the plan document, whose invocation Execute runs verbatim without a schema check.
 */
class CreateReplicaArityTest {

    /** Exactly four tokens: the skill, the source, the entity, and one {@code --to <target>}. */
    private static final String SHAPE = "^/create-replica \\S+ \\S+ --to \\S+$";

    private CandidateOperation.CandidateOperationBuilder candidate() {
        return CandidateOperation.builder()
                .operation(OperationType.CREATE_REPLICA)
                .targetService("tournament")
                .secondaryTarget("user");
    }

    @Test
    void emitsOneSourceOneEntityOneTarget() {
        CandidateOperation c = candidate()
                .affectedEntities(List.of("User"))
                .build();

        assertThat(c.getCommandSignature())
                .isEqualTo("/create-replica user User --to tournament")
                .matches(SHAPE);
    }

    @Test
    void keepsItsShapeWhenNoEntityIsKnown() {
        // A missing entity still yields a well-formed, single-operand command with a visible gap,
        // rather than a command with a token missing that would silently shift --to's position.
        assertThat(candidate().affectedEntities(null).build().getCommandSignature())
                .isEqualTo("/create-replica user ? --to tournament")
                .matches(SHAPE);

        assertThat(candidate().affectedEntities(List.of()).build().getCommandSignature())
                .matches(SHAPE);
    }

    @Test
    void neverEmitsASecondEntityEvenIfTheCandidateCarriesOne() {
        // The validator truncates upstream; this is the second line of defence at the point the
        // string is built, so a hand-built or deserialised candidate cannot widen the command.
        CandidateOperation c = candidate()
                .affectedEntities(List.of("User", "UserProfile", "Student"))
                .build();

        assertThat(c.getCommandSignature())
                .matches(SHAPE)
                .isEqualTo("/create-replica user User --to tournament")
                .doesNotContain("UserProfile")
                .doesNotContain("Student")
                .doesNotContain(",");
    }

    @Test
    void removeReplicaHoldsTheSameArity() {
        CandidateOperation c = CandidateOperation.builder()
                .operation(OperationType.REMOVE_REPLICA)
                .targetService("topic")
                .secondaryTarget("course")
                .affectedEntities(List.of("TopicCourse", "Extra"))
                .build();

        assertThat(c.getCommandSignature())
                .isEqualTo("/remove-replica topic TopicCourse --source course")
                .doesNotContain("Extra");
    }
}
