package org.anonymous.msrefactor.rt.execute.util;

public record ProcessResult(int exitCode, String stdout, String stderr) {

    /** Exit code reported when {@link ProcessRunner} kills a process for exceeding its timeout (as GNU timeout). */
    public static final int TIMEOUT_EXIT_CODE = 124;

    public boolean succeeded() {
        return exitCode == 0;
    }

    public boolean timedOut() {
        return exitCode == TIMEOUT_EXIT_CODE;
    }

    public String combined() {
        if (stderr == null || stderr.isBlank()) return stdout;
        return stdout + "\n[STDERR]\n" + stderr;
    }

    public String toLogEntry(String stepLabel) {
        return "\n=== " + stepLabel + " (exit=" + exitCode + ") ===\n" + combined();
    }
}
