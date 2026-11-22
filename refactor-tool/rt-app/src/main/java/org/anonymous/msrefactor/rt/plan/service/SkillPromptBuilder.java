package org.anonymous.msrefactor.rt.plan.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.anonymous.msrefactor.rt.config.AppTargetConfig;
import org.anonymous.msrefactor.rt.analyze.AggregateSnapshot;
import org.anonymous.msrefactor.rt.analyze.EntityMove;
import org.anonymous.msrefactor.rt.analyze.InvariantChange;
import org.anonymous.msrefactor.rt.analyze.RefactorBlueprint;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Builds the full Claude Code skill invocation string from a plan's operation type and blueprint.
 *
 * <p>The resulting string is passed verbatim to the Claude CLI as the prompt argument,
 * e.g. {@code claude --print /merge-aggregate Quiz Tournament --entities E1,E2 --invariants verifyX
 * --project-path applications/quizzes}.
 */
@Component
@RequiredArgsConstructor
public class SkillPromptBuilder {

    private final AppTargetConfig appTarget;

    /**
     * Builds the full skill invocation string for the given operation and blueprint.
     * Falls back to the bare skill name if blueprint is null or insufficient.
     *
     * <p>Every invocation ends with {@code --project-path <path>} so the skill operates on the
     * configured application module rather than the one its SKILL.md used to hardcode.
     */
    public String build(String operation, String targetService,
                        String secondaryTarget, RefactorBlueprint blueprint) {
        if (operation == null) return "";

        String skillName = toSkillName(operation);
        if (skillName == null) return operation.toLowerCase();

        if (blueprint == null) {
            return appTarget.withProjectPath(appendTargets(skillName, targetService, secondaryTarget));
        }

        String invocation = switch (operation.toUpperCase()) {
            case "MERGE_AGGREGATES"  -> buildMergeAggregates(skillName, targetService, secondaryTarget, blueprint);
            case "SPLIT_AGGREGATE"   -> buildSplitAggregate(skillName, targetService, blueprint);
            case "CREATE_REPLICA"    -> buildCreateReplica(skillName, targetService, secondaryTarget, blueprint);
            case "REMOVE_REPLICA"    -> buildRemoveReplica(skillName, targetService, blueprint);
            default                  -> appendTargets(skillName, targetService, secondaryTarget);
        };
        return appTarget.withProjectPath(invocation);
    }

    // ─── Per-operation builders ───────────────────────────────────────────────

    private String buildMergeAggregates(String skill, String target, String secondary,
                                         RefactorBlueprint bp) {
        StringBuilder sb = new StringBuilder(skill);
        appendArg(sb, safe(capitalize(target)));
        appendArg(sb, safe(capitalize(secondary)));

        List<String> entities = bp.getEntityMoves() == null ? List.of() :
                bp.getEntityMoves().stream().map(EntityMove::getEntityName).collect(Collectors.toList());
        List<String> invariants = filterInvariantsByChange(bp, "BECOMES_INTRA");

        if (!entities.isEmpty()) appendFlag(sb, "--entities", String.join(",", entities));
        if (!invariants.isEmpty()) appendFlag(sb, "--invariants", String.join(",", invariants));
        return sb.toString();
    }

    private String buildSplitAggregate(String skill, String target, RefactorBlueprint bp) {
        StringBuilder sb = new StringBuilder(skill);
        appendArg(sb, safe(capitalize(target)));

        if (bp.getAfter() != null && bp.getAfter().size() == 2) {
            AggregateSnapshot core = bp.getAfter().get(0);
            AggregateSnapshot sat  = bp.getAfter().get(1);
            if (core.getEntities() != null && !core.getEntities().isEmpty())
                appendFlag(sb, "--core", String.join(",", core.getEntities()));
            if (sat.getEntities() != null && !sat.getEntities().isEmpty())
                appendFlag(sb, "--satellite", String.join(",", sat.getEntities()));

            // Invariant partition in the execute skill's grammar: <inv>@core / <inv>@satellite.
            List<String> partition = new ArrayList<>();
            if (core.getInvariants() != null)
                for (String i : core.getInvariants()) partition.add(i + "@core");
            if (sat.getInvariants() != null)
                for (String i : sat.getInvariants()) partition.add(i + "@satellite");
            if (partition.isEmpty()) {
                // Fallback: any invariant the blueprint marks as MOVES goes to the satellite side.
                for (String i : filterInvariantsByChange(bp, "MOVES")) partition.add(i + "@satellite");
            }
            if (!partition.isEmpty()) appendFlag(sb, "--invariants", String.join(",", partition));

            if (sat.getAggregateName() != null && !sat.getAggregateName().isBlank())
                appendFlag(sb, "--new-aggregate", sat.getAggregateName());
        }
        return sb.toString();
    }

    private String buildCreateReplica(String skill, String target, String secondary,
                                       RefactorBlueprint bp) {
        StringBuilder sb = new StringBuilder(skill);

        // Exactly one entity — the blueprint's, else the source's root class. CREATE_REPLICA
        // blueprints carry empty before/entityMoves lists (CandidateGenerator), so this usually
        // lands on the root — the right *kind* of name (source-owned), just less specific.
        String entityClass;
        if (bp.getBefore() != null && !bp.getBefore().isEmpty()
                && bp.getBefore().get(0).getEntities() != null
                && !bp.getBefore().get(0).getEntities().isEmpty()) {
            entityClass = bp.getBefore().get(0).getEntities().get(0);
        } else if (bp.getEntityMoves() != null && !bp.getEntityMoves().isEmpty()) {
            entityClass = bp.getEntityMoves().get(0).getEntityName();
        } else {
            entityClass = toClassName(secondary);
        }
        // New format: create-replica <source> <entity> --to <target>
        appendArg(sb, safe(toClassName(secondary)));         // source (owner)
        appendArg(sb, safe(entityClass));                    // entity
        appendFlag(sb, "--to", safe(toClassName(target)));   // --to target (consumer)

        String syncEvent = extractSyncEvent(bp);
        if (syncEvent != null) appendFlag(sb, "--sync-event", syncEvent);

        return sb.toString();
    }

    private String buildRemoveReplica(String skill, String target, RefactorBlueprint bp) {
        StringBuilder sb = new StringBuilder(skill);
        // New format: remove-replica <service> <entity> --source <auth>
        appendArg(sb, safe(target));
        if (bp.getEntityMoves() != null && !bp.getEntityMoves().isEmpty()) {
            appendArg(sb, bp.getEntityMoves().get(0).getEntityName());
        }
        String authSource = extractAuthSource(bp);
        appendFlag(sb, "--source", authSource != null ? authSource : "?");
        return sb.toString();
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private List<String> filterInvariantsByChange(RefactorBlueprint bp, String changeType) {
        if (bp.getInvariantChanges() == null) return List.of();
        return bp.getInvariantChanges().stream()
                .filter(c -> changeType.equalsIgnoreCase(c.getChangeType()))
                .map(InvariantChange::getInvariantName)
                .collect(Collectors.toList());
    }

    private String appendTargets(String skill, String target, String secondary) {
        StringBuilder sb = new StringBuilder(skill);
        if (target != null && !target.isBlank()) appendArg(sb, safe(capitalize(target)));
        if (secondary != null && !secondary.isBlank()) appendArg(sb, safe(capitalize(secondary)));
        return sb.toString();
    }

    private void appendArg(StringBuilder sb, String value) {
        if (value != null && !value.isBlank()) sb.append(" ").append(value);
    }

    private void appendFlag(StringBuilder sb, String flag, String value) {
        if (value != null && !value.isBlank()) sb.append(" ").append(flag).append(" ").append(value);
    }

    private String safe(String s) {
        return s == null ? "" : s.trim();
    }

    private String capitalize(String s) {
        if (s == null || s.isBlank()) return s;
        String t = s.trim();
        return Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    // "quiz-service" → "Quiz", "tournament-service" → "Tournament", "quiz" → "Quiz"
    private String toClassName(String serviceName) {
        if (serviceName == null || serviceName.isBlank()) return "";
        String s = serviceName.trim()
                              .replaceAll("(?i)-service$", "")
                              .replaceAll("-", "");
        return capitalize(s);
    }

    private String extractSyncEvent(RefactorBlueprint bp) {
        if (bp == null || bp.getInvariantChanges() == null) return null;
        return bp.getInvariantChanges().stream()
                .map(InvariantChange::getToLocation)
                .filter(loc -> loc != null && loc.contains("Event"))
                .findFirst().orElse(null);
    }

    private String extractAuthSource(RefactorBlueprint bp) {
        if (bp == null || bp.getImplementationSteps() == null) return null;
        return bp.getImplementationSteps().stream()
                .filter(s -> s != null && s.toLowerCase().contains("source service"))
                .findFirst()
                .map(s -> {
                    int idx = s.toLowerCase().indexOf("source service");
                    String[] parts = s.substring(idx + "source service".length())
                                      .trim().split("[\\s,.:()]+");
                    return parts.length > 0 && !parts[0].isBlank() ? parts[0] : null;
                }).orElse(null);
    }

    /** Operation → skill slug. Mirrors {@code OperationType}; there is one skill per constant. */
    private String toSkillName(String operation) {
        return switch (operation.toUpperCase()) {
            case "MERGE_AGGREGATES" -> "merge-aggregate";
            case "SPLIT_AGGREGATE"  -> "split-aggregate";
            case "CREATE_REPLICA"   -> "create-replica";
            case "REMOVE_REPLICA"   -> "remove-replica";
            default                 -> null;
        };
    }
}
