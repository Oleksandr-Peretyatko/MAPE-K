package org.anonymous.msrefactor.rt.execute.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.anonymous.msrefactor.rt.config.AppTargetConfig;
import org.anonymous.msrefactor.rt.config.ClaudeConfig;
import org.anonymous.msrefactor.rt.execute.config.ExecuteConfig;
import org.anonymous.msrefactor.rt.execute.util.ProcessRunner;

import static org.assertj.core.api.Assertions.assertThat;

class TestGateCommandTest {

    @Test
    @DisplayName("the test-gate command comes from the app-target and build-profile configuration")
    void commandFollowsConfiguration() {
        AppTargetConfig appTarget = new AppTargetConfig();
        appTarget.setPath("applications/answers");
        ExecuteConfig executeConfig = new ExecuteConfig();
        executeConfig.setBuildProfiles("tcc");

        ClaudeCommandMutator mutator =
                new ClaudeCommandMutator(new ClaudeConfig(), new ProcessRunner(), appTarget, executeConfig,
                        org.mockito.Mockito.mock(org.anonymous.msrefactor.rt.topology.HostPortInspector.class));

        assertThat(mutator.testGateCommand())
                .isEqualTo("test-gate --project-path applications/answers --profile tcc");
        assertThat(mutator.finalStepReminder())
                .contains("`test-gate --project-path applications/answers --profile tcc`")
                .contains("-Ptest-tcc")
                .doesNotContain("quizzes");
    }
}
