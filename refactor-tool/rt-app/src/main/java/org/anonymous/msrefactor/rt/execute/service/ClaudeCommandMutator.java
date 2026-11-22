package org.anonymous.msrefactor.rt.execute.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.anonymous.msrefactor.rt.config.AppTargetConfig;
import org.anonymous.msrefactor.rt.config.ClaudeConfig;
import org.anonymous.msrefactor.rt.constants.AppTargetConstants;
import org.anonymous.msrefactor.rt.execute.config.ExecuteConfig;
import org.anonymous.msrefactor.rt.execute.util.ProcessResult;
import org.anonymous.msrefactor.rt.execute.util.ProcessRunner;
import org.anonymous.msrefactor.rt.execute.util.SecretRedactor;
import org.anonymous.msrefactor.rt.topology.HostPortInspector;

import org.anonymous.msrefactor.rt.execute.SkillArgParser;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class ClaudeCommandMutator {

    /**
     * Appended to operation prompts. Self-contained on purpose: the target's CLAUDE.md and the
     * skills are read from the checked-out branch, which may predate the "Final step" section.
     * Placeholders: the {@code test-gate} command, then the Maven test profile it runs.
     */
    private static final String FINAL_STEP_TEMPLATE = """

            ## Final step — test gate (mandatory when you changed code)
            There is no JDK or Maven here. Once every layer is done, and before writing the CLAUDE.md \
            log entry, run `%1$s` (Bash; takes several minutes; it runs the -P%2$s suite through \
            docker build, exactly as the pipeline's gate does). On red, fix the reported failures and \
            run it again, at most 3 fix cycles. Never delete, @Ignore or weaken a test to get green; a \
            test whose behaviour the operation changed on purpose is adapted and recorded as a \
            deviation. Report the observed result (`testsSagas: green | red`, plus `WARNING: %2$s \
            red — <n> failure(s): <tests>` if red). The pipeline re-runs the same gate afterwards and \
            discards the branch if it is red.""";

    private final ClaudeConfig claudeConfig;
    private final ProcessRunner processRunner;
    private final AppTargetConfig appTarget;
    private final ExecuteConfig executeConfig;
    private final HostPortInspector hostPortInspector;

    /**
     * The {@code test-gate} invocation for the configured target, with the same module and build
     * profile as the pipeline's own gate ({@code ExecuteService.buildDockerTestCmdForProfile}).
     */
    String testGateCommand() {
        return "test-gate " + AppTargetConstants.PROJECT_PATH_FLAG + " " + appTarget.getPath()
                + " --profile " + executeConfig.getBuildProfiles();
    }

    String finalStepReminder() {
        return FINAL_STEP_TEMPLATE.formatted(testGateCommand(), "test-" + executeConfig.getBuildProfiles());
    }

    /**
     * The host ports other compose projects hold, as a prompt section — or {@code ""}. The skill reads
     * it when it allocates a port for a new service; a skill checked out from an older version does
     * not know about it, but still reads the prompt. Never fails the run: without docker, no section.
     */
    String reservedPortsBlock() {
        try {
            return HostPortInspector.promptBlock(hostPortInspector.foreignBindings());
        } catch (Exception e) {
            log.warn("[execute] reserved host ports unavailable, prompt sent without them: {}", e.getMessage());
            return "";
        }
    }

    public void apply(String skillInvocation, String fullPrompt, String planContext,
                      String workingDir, StringBuilder execLog, int versionNum) throws Exception {
        // Backstop for --project-path: Execute prefers the enriched invocation, which is free text
        // produced by Claude and may have dropped the flag, and plans stored before the flag existed
        // never carried one. withProjectPath is a no-op when it is already there.
        run(appTarget.withProjectPath(skillInvocation.trim()), "refactor-", planContext,
                workingDir, execLog, versionNum);
    }

    /**
     * Runs a <b>domain</b> skill named by a prerequisite block's {@code runFirst:} field.
     *
     * <p>Separate from {@link #apply} for two reasons. The domain skills live at
     * {@code applications/.claude/skills/<name>/} with no {@code refactor-} prefix, which
     * {@code apply} hard-codes. And each call spawns its own {@code claude} process, so the
     * prerequisite gets a full {@code --max-turns} budget rather than eating the operation's.
     *
     * <p>No plan context is passed: the prerequisite is scaffolding, not the refactoring the plan
     * describes, and handing it the plan would invite it to attempt the operation itself. Runs
     * without {@code --project-path} too, which the domain skills do not declare.
     */
    public void applyPrerequisite(String skillInvocation, String workingDir,
                                  StringBuilder execLog, int versionNum) throws Exception {
        run(skillInvocation.trim(), "", null, workingDir, execLog, versionNum);
    }

    /**
     * @param skillDirPrefix prefix of the skill's directory under {@code applications/.claude/skills/}
     *                       — {@code "refactor-"} for the four operations, empty for domain skills
     */
    private void run(String invocation, String skillDirPrefix, String planContext,
                     String workingDir, StringBuilder execLog, int versionNum) throws Exception {

        String skillName = invocation.split("\\s+")[0];
        String skillArgs = invocation.length() > skillName.length()
                ? invocation.substring(skillName.length()).stripLeading() : "";
        String refactoredCommand = skillDirPrefix + skillName + (skillArgs.isEmpty() ? "" : " " + skillArgs);

        StringBuilder combined = new StringBuilder()
                .append("/").append(refactoredCommand).append("\n\n");

        if (planContext != null && !planContext.isBlank()) {
            combined.append("## Refactoring Plan\n\n").append(planContext).append("\n\n");
        }
        combined.append("Working directory: ").append(workingDir).append("\n");
        if (!skillDirPrefix.isEmpty()) {
            // Operations only: a prerequisite's changes are covered by the operation's own gate, and
            // no domain skill allocates a port.
            String reserved = reservedPortsBlock();
            if (!reserved.isEmpty()) combined.append("\n").append(reserved).append("\n");
            combined.append(finalStepReminder()).append("\n");
        }

        String combinedPrompt = combined.toString();

        Map<String, String> parsedArgs = SkillArgParser.parse(skillName, skillArgs);
        String skillRef = "applications/.claude/skills/" + skillDirPrefix + skillName + "/SKILL.md";
        String claudeBin = resolveBinary();
        int maxTurns = claudeConfig.getMutatorMaxTurns();
        List<String> allowedTools = claudeConfig.getAllowedTools();

        log.info("[execute] ┌─ EXECUTE phase v{} ─────────────────────────────────────", versionNum);
        log.info("[execute] │  skill          : {}", skillName);
        log.info("[execute] │  command        : /{}", refactoredCommand);
        parsedArgs.forEach((k, v) -> log.info("[execute] │  {}", String.format("%-16s : %s", k, v)));
        log.info("[execute] │  binary         : {}", claudeBin);
        log.info("[execute] │  workingDir     : {}", workingDir);
        log.info("[execute] │  allowedTools   : {}", allowedTools);
        log.info("[execute] │  maxTurns       : {}", maxTurns);
        log.info("[execute] │  promptChars    : {}", combinedPrompt.length());
        log.info("[execute] │  skill ref      : {}", skillRef);
        log.info("[execute] │  mandate        : applications/CLAUDE.md");
        log.info("[execute] └────────────────────────────────────────────────────────");

        execLog.append("\n[execute:input]")
               .append(" phase=v").append(versionNum)
               .append(" skill=").append(skillName)
               .append(" command=/").append(refactoredCommand);
        parsedArgs.forEach((k, v) -> execLog.append(" ").append(k).append("=").append(v));
        execLog.append(" binary=").append(claudeBin)
               .append(" workingDir=").append(workingDir)
               .append(" maxTurns=").append(maxTurns)
               .append(" promptChars=").append(combinedPrompt.length())
               .append(" skillRef=").append(skillRef)
               .append("\n[prompt]\n").append(combinedPrompt).append("\n[/prompt]\n");

        List<String> cmd = List.of(
                claudeBin,
                "-p", combinedPrompt,
                "--output-format", "text",
                "--allowedTools", String.join(",", allowedTools),
                "--max-turns", String.valueOf(maxTurns)
        );

        ProcessResult result = processRunner.runStreaming(cmd, workingDir, rawLine -> {
            String line = SecretRedactor.redact(rawLine);
            execLog.append("[claude-cmd] ").append(line).append("\n");
            log.info("[claude-cmd] v{} {}", versionNum, line);
        });

        long outputLines = execLog.toString().lines()
                .filter(l -> l.startsWith("[claude-cmd] ")).count();
        log.info("[execute] v{} finished exit={}  outputLines={}", versionNum, result.exitCode(), outputLines);

        if (!result.succeeded()) {
            throw new RuntimeException("claude command failed (exit=" + result.exitCode() + "): "
                    + SecretRedactor.redact(result.combined()));
        }
    }

    /** Runs a short one-shot prompt for health-check purposes; working dir is repo root. */
    public ProcessResult runOneShot(String prompt, String workingDir) throws Exception {
        String claudeBin = resolveBinary();
        List<String> cmd = List.of(
                claudeBin,
                "-p", prompt,
                "--output-format", "text",
                "--allowedTools", "Bash",
                "--max-turns", String.valueOf(claudeConfig.getOneshotMaxTurns())
        );
        return processRunner.runStreaming(cmd, workingDir,
                line -> log.info("[claude-cmd:test] {}", line));
    }

    /**
     * Runs a discovery analysis prompt; allows file-reading tools so Claude can scan source.
     * Returns the full combined stdout+stderr output for JSON extraction.
     */
    public ProcessResult runDiscovery(String prompt, String workingDir) throws Exception {
        String claudeBin = resolveBinary();
        List<String> cmd = List.of(
                claudeBin,
                "-p", prompt,
                "--output-format", "text",
                "--allowedTools", String.join(",", claudeConfig.getDiscoveryTools()),
                "--max-turns", String.valueOf(claudeConfig.getDiscoveryMaxTurns())
        );
        return processRunner.runStreaming(cmd, workingDir,
                line -> log.info("[claude-cmd:discovery] {}", line));
    }

    /** Returns the path to the discovered claude binary, or throws if none found. */
    public String resolveBinary() {
        List<String> candidates = claudeConfig.getBinaryCandidatePaths();
        for (String candidate : candidates) {
            try {
                ProcessResult r = processRunner.run(
                        List.of(candidate, "--version"), System.getProperty("user.dir"));
                if (r.exitCode() == 0) return candidate;
            } catch (Exception ignored) {
                // try next
            }
        }
        throw new IllegalStateException(
                "claude binary not found — is @anthropic-ai/claude-code installed? " +
                "Tried: " + candidates);
    }
}
