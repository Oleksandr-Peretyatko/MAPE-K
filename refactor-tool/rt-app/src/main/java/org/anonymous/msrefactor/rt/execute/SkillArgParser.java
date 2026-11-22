package org.anonymous.msrefactor.rt.execute;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a skill invocation arg string into a named-field map.
 *
 * Positional args are labeled semantically per skill (e.g. "source", "entity" for create-replica).
 * Flag args (--flag value1 value2) consume all following non-flag tokens, joined with a space.
 * Used for structured logging; the schema reflects the actual SKILL.md Parameters tables.
 *
 * <p>Every invocation also ends with {@code --project-path <path>}, appended by
 * {@code AppTargetConfig.withProjectPath}. It needs no schema entry — the generic flag branch
 * below picks it up, which is what puts it in the Execute log banner.
 */
public class SkillArgParser {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(SkillArgParser.class);

    private static final Map<String, List<String>> POSITIONAL_SCHEMA = Map.ofEntries(
        // /merge-aggregate <AggrA> <AggrB> [--entities E1,E2,...] [--invariants I1,I2,...] [--remove-sagas S1,S2,...] [--project-path <path>]
        Map.entry("merge-aggregate",    List.of("aggregate-1", "aggregate-2")),
        // /split-aggregate <Aggr> --core E1,E2,... --satellite E3,E4,... --invariants I1@core,I2@satellite,... [--new-aggregate <Name>] [--project-path <path>]
        Map.entry("split-aggregate",    List.of("aggregate")),
        // /create-replica <source> <entity> --to <target> [--sync-event EventClass] [--project-path <path>]
        // Arity is exactly one source, one entity, one target — no argument of this skill is list-valued.
        Map.entry("create-replica",     List.of("source", "entity")),
        // /remove-replica <service> <entity> --source <authoritative-service> [--project-path <path>]
        Map.entry("remove-replica",     List.of("service", "entity"))
    );

    private static final Pattern TOKEN_PATTERN =
            Pattern.compile("\"([^\"]*)\"|'([^']*)'|(\\S+)");

    /**
     * Parses {@code argsString} (everything after the skill name in the invocation) into an
     * ordered map of named fields. Positionals are named by the schema; flags use their flag name.
     *
     * @param skillName  the CLI skill name (e.g. {@code "move-entity"})
     * @param argsString the remaining args (e.g. {@code "tournament TournamentParticipant --to answer"})
     * @return ordered map: positional names first, then flag names
     */
    public static Map<String, String> parse(String skillName, String argsString) {
        Map<String, String> result = new LinkedHashMap<>();
        if (argsString == null || argsString.isBlank()) return result;

        List<String> tokens = tokenize(argsString);
        List<String> positionalNames = POSITIONAL_SCHEMA.getOrDefault(skillName, List.of());
        int positionalIdx = 0;
        int i = 0;

        while (i < tokens.size()) {
            String t = tokens.get(i);
            if (t.startsWith("--")) {
                List<String> values = new ArrayList<>();
                i++;
                while (i < tokens.size() && !tokens.get(i).startsWith("--")) {
                    values.add(tokens.get(i++));
                }
                result.put(t, values.isEmpty() ? "true" : String.join(" ", values));
            } else {
                String name = positionalIdx < positionalNames.size()
                        ? positionalNames.get(positionalIdx) : "arg" + positionalIdx;
                result.put(name, t);
                positionalIdx++;
                i++;
            }
        }
        warnOnExcessArity(skillName, result, positionalNames.size(), positionalIdx);
        return result;
    }

    /**
     * Logs when an invocation carries more operands than its skill accepts.
     *
     * <p>Diagnostic only — the skill's own arity rule is the enforcement, and this parser is used
     * for the Execute log banner. But the excess is otherwise invisible: a surplus positional is
     * silently named {@code argN}, and a flag greedily joins the tokens that follow it, so
     * {@code --to quiz tournament} becomes one value with a space in it and reads as deliberate.
     *
     * <p>Only single-operand skills are checked. {@code merge-aggregate} and {@code split-aggregate}
     * take list-valued flags by design.
     */
    private static void warnOnExcessArity(String skillName, Map<String, String> parsed,
                                          int expectedPositionals, int actualPositionals) {
        if (!"create-replica".equals(skillName) && !"remove-replica".equals(skillName)) return;

        if (actualPositionals > expectedPositionals) {
            LOG.warn("[skill-args] {}: {} positional operand(s) supplied, {} accepted — the skill "
                    + "will ignore the extras and warn; parsed={}",
                    skillName, actualPositionals, expectedPositionals, parsed);
        }
        for (String flag : List.of("--to", "--source", "--sync-event")) {
            String v = parsed.get(flag);
            if (v == null) continue;
            if (v.contains(" ") || v.contains(",")) {
                LOG.warn("[skill-args] {}: {} holds more than one value ('{}') — this skill takes "
                        + "exactly one; the skill will keep the first and warn", skillName, flag, v);
            }
        }
    }

    private static List<String> tokenize(String s) {
        List<String> tokens = new ArrayList<>();
        Matcher m = TOKEN_PATTERN.matcher(s);
        while (m.find()) {
            if (m.group(1) != null)      tokens.add(m.group(1));
            else if (m.group(2) != null) tokens.add(m.group(2));
            else                         tokens.add(m.group(3));
        }
        return tokens;
    }
}
