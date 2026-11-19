package org.anonymous.msrefactor.rt.execute.util;

import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Component
public class ProcessRunner {

    public ProcessResult run(List<String> command, String workingDir) throws IOException, InterruptedException {
        return run(command, workingDir, Map.of());
    }

    /**
     * Runs the command and returns stdout and stderr separately.
     *
     * <p>Both streams are drained concurrently. Reading one to EOF before touching the other
     * deadlocks as soon as the child fills the unread pipe (~64 KB on Linux): {@code docker build}
     * under BuildKit writes all its progress, Maven output included, to stderr.
     */
    public ProcessResult run(List<String> command, String workingDir, Map<String, String> extraEnv)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new File(workingDir));
        if (!extraEnv.isEmpty()) {
            pb.environment().putAll(extraEnv);
        }

        Process process = pb.start();
        process.getOutputStream().close();
        CompletableFuture<String> stderr = CompletableFuture.supplyAsync(() -> readAll(process.getErrorStream()));
        String stdout = readAll(process.getInputStream());
        int exitCode = process.waitFor();

        return new ProcessResult(exitCode, stdout.trim(), stderr.join().trim());
    }

    /**
     * Runs the command and streams stdout+stderr line-by-line to {@code lineConsumer}.
     * Use for long-running processes (e.g. the claude CLI) where real-time log output matters.
     */
    public ProcessResult runStreaming(List<String> command, String workingDir,
                                      Consumer<String> lineConsumer)
            throws IOException, InterruptedException {
        return runStreaming(command, workingDir, Map.of(), null, lineConsumer);
    }

    /**
     * As {@link #runStreaming(List, String, Consumer)}, with extra environment and an optional
     * timeout. A process still running after {@code timeout} is killed together with its
     * descendants and reported with {@link ProcessResult#TIMEOUT_EXIT_CODE}. A {@code null}
     * timeout waits indefinitely. {@code lineConsumer} is called on the calling thread.
     */
    public ProcessResult runStreaming(List<String> command, String workingDir, Map<String, String> extraEnv,
                                      Duration timeout, Consumer<String> lineConsumer)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new File(workingDir));
        pb.redirectErrorStream(true); // merge stderr into stdout stream
        if (!pb.environment().containsKey("HOME")) {
            pb.environment().put("HOME", System.getProperty("user.home", "/root"));
        }
        if (!extraEnv.isEmpty()) {
            pb.environment().putAll(extraEnv);
        }

        Process process = pb.start();
        // Close stdin immediately so claude (and similar CLIs) don't wait for piped input.
        process.getOutputStream().close();
        StringBuilder out = new StringBuilder();
        long deadline = timeout == null ? Long.MAX_VALUE : System.nanoTime() + timeout.toNanos();

        // The reader thread only reads; lines are handed to the consumer here, so callers need
        // no synchronisation. A killed process closes the pipe, which ends the reader.
        BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        String eof = new String("<eof>"); // identity sentinel: compared with ==, never equals a real line
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) lines.add(line);
            } catch (IOException ignored) {
                // stream closed by destroyForcibly()
            } finally {
                lines.add(eof);
            }
        }, "process-reader");
        reader.setDaemon(true);
        reader.start();

        boolean timedOut = false;
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                timedOut = true;
                break;
            }
            String line = lines.poll(Math.min(remaining, TimeUnit.SECONDS.toNanos(1)), TimeUnit.NANOSECONDS);
            if (line == null) continue;
            if (line == eof) break;
            out.append(line).append("\n");
            if (lineConsumer != null) lineConsumer.accept(line);
        }

        if (timedOut) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
            return new ProcessResult(ProcessResult.TIMEOUT_EXIT_CODE, out.toString().trim(),
                    "[TIMEOUT] killed after " + timeout.toSeconds() + "s");
        }

        int exitCode = process.waitFor();
        return new ProcessResult(exitCode, out.toString().trim(), "");
    }

    private static String readAll(InputStream in) {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
