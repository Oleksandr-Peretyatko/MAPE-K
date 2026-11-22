package org.anonymous.msrefactor.rt.execute.util;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Real processes, run through {@code sh}. Guards the Execute pipeline against hanging on a child
 * that writes more than a pipe buffer to the stream the runner is not reading.
 */
class ProcessRunnerTest {

    private static final String DIR = System.getProperty("java.io.tmpdir");
    private final ProcessRunner runner = new ProcessRunner();

    @BeforeAll
    static void requiresShell() {
        boolean hasSh;
        try {
            hasSh = new ProcessBuilder("sh", "-c", "true").start().waitFor() == 0;
        } catch (Exception e) {
            hasSh = false;
        }
        assumeTrue(hasSh, "needs a POSIX sh on the PATH");
    }

    private static List<String> sh(String script) {
        return List.of("sh", "-c", script);
    }

    @Test
    @Timeout(30)
    @DisplayName("run() does not deadlock when stderr far exceeds the pipe buffer (docker build under BuildKit)")
    void runDrainsLargeStderr() throws Exception {
        ProcessResult r = runner.run(
                sh("head -c 2000000 /dev/zero | tr '\\0' x >&2; echo ok"), DIR, Map.of());

        assertThat(r.succeeded()).isTrue();
        assertThat(r.stdout()).isEqualTo("ok");
        assertThat(r.stderr()).hasSize(2_000_000);
    }

    @Test
    @Timeout(30)
    @DisplayName("runStreaming() hands stdout and stderr lines to the consumer and returns them all")
    void runStreamingDeliversBothStreams() throws Exception {
        List<String> seen = new ArrayList<>();
        ProcessResult r = runner.runStreaming(
                sh("echo out1; echo err1 >&2; echo out2"), DIR, Map.of("X_TEST", "v"),
                Duration.ofSeconds(20), seen::add);

        assertThat(r.succeeded()).isTrue();
        assertThat(seen).containsExactlyInAnyOrder("out1", "err1", "out2");
        assertThat(r.stdout()).contains("out1", "err1", "out2");
    }

    @Test
    @Timeout(30)
    @DisplayName("runStreaming() passes extra environment to the child")
    void runStreamingPassesEnv() throws Exception {
        ProcessResult r = runner.runStreaming(sh("echo \"$X_TEST\""), DIR, Map.of("X_TEST", "hello"),
                Duration.ofSeconds(20), null);

        assertThat(r.stdout()).isEqualTo("hello");
    }

    @Test
    @Timeout(30)
    @DisplayName("runStreaming() kills a process that outlives its timeout and reports it as timed out")
    void runStreamingTimesOut() throws Exception {
        long start = System.nanoTime();
        ProcessResult r = runner.runStreaming(sh("echo started; sleep 60"), DIR, Map.of(),
                Duration.ofSeconds(1), null);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(r.timedOut()).isTrue();
        assertThat(r.succeeded()).isFalse();
        assertThat(r.stdout()).contains("started");
        assertThat(r.stderr()).contains("[TIMEOUT]");
        assertThat(elapsedMs).isLessThan(15_000);
    }
}
