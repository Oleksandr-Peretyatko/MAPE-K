package org.anonymous.msrefactor.rt.monitor.collector;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps an {@code <something>AggregateId} field/method prefix onto the service that owns the
 * referenced aggregate.
 *
 * <h3>Why this is not a service-name lookup</h3>
 *
 * <p>The obvious implementation - "find the longest known service name contained in the prefix" -
 * is only correct while the set of services matches the set of aggregates. Every refactoring
 * operation in the catalogue breaks that assumption:
 *
 * <ul>
 *   <li><b>MergeAggregates</b> dissolves a root into another service. Afterwards no service is
 *       called {@code question}, so every {@code questionAggregateId} in quiz, answer and tournament
 *       resolves to nothing and those dependencies disappear from the external-relation term - while
 *       at runtime the same calls still happen, against the surviving service.</li>
 *   <li><b>SplitAggregate</b> creates a new root (e.g. {@code TournamentParticipation}) without
 *       creating a new service, so {@code tournamentParticipationAggregateId} names an aggregate
 *       that is not in the service set.</li>
 * </ul>
 *
 * <p>Both directions silently understate coupling, which is exactly the signal the loop is trying
 * to measure. So resolution is keyed on what the source actually contains - services, aggregate
 * roots, and member entities - and the index is rebuilt for every analysed version.
 *
 * <h3>Resolution order (first hit wins)</h3>
 *
 * <ol>
 *   <li>whole prefix == a service name</li>
 *   <li>whole prefix == a current aggregate root - <i>handles a split's new root</i></li>
 *   <li>last camelCase word == a service name</li>
 *   <li>last camelCase word == any entity name, unambiguously - <i>handles a merge's absorbed
 *       root, which is no longer a root</i></li>
 *   <li>the declaring file imports exactly one foreign service - <i>handles role-named references
 *       ({@code creatorAggregateId}, {@code studentAggregateId}) whose name shares nothing with
 *       the aggregate they point at</i></li>
 *   <li>otherwise unresolved, and counted in {@link #unresolved()}</li>
 * </ol>
 *
 * <p>Two naming conventions coexist in the target app and no single lookup satisfies both:
 * <b>compound aggregate names</b> ({@code courseExecutionAggregateId} names aggregate
 * {@code Execution}) and <b>possessive phrases</b> ({@code quizQuestionsAggregateIds} names the
 * quiz's questions, i.e. aggregate {@code Question}). Whole-prefix matching gets the first right and
 * the second wrong; last-word matching does the reverse. Hence both, in that order.
 *
 * <p>Step 2 deliberately tests <i>root</i>-hood rather than mere entity-hood. {@code QuizQuestion}
 * and {@code QuestionAnswer} are member replicas whose names concatenate two aggregate names; if
 * whole-prefix matching accepted them, {@code quizQuestions} would resolve to {@code quiz} and
 * {@code questionAnswer} to {@code question}, both wrong. Step 4 must <i>not</i> require root-hood,
 * because an absorbed aggregate stops being a root - that is the case being fixed.
 */
final class AggregateReferenceResolver {

    /** Bean accessor prefix; group(2) is the remainder, null when the prefix was the bare verb. */
    private static final Pattern ACCESSOR_VERB = Pattern.compile("^(get|set|is|has)([A-Z].*)?$");

    private final Set<String> serviceNames;
    private final Map<String, String> rootToService;
    private final Map<String, String> entityToService;
    /** Lower-cased names claimed by more than one service; never resolved, either way would guess. */
    private final Set<String> ambiguous;
    private final Map<String, Integer> unresolved = new TreeMap<>();
    private final Map<String, Integer> contextResolved = new TreeMap<>();

    private AggregateReferenceResolver(Set<String> serviceNames,
                                       Map<String, String> rootToService,
                                       Map<String, String> entityToService,
                                       Set<String> ambiguous) {
        this.serviceNames    = serviceNames;
        this.rootToService   = rootToService;
        this.entityToService = entityToService;
        this.ambiguous       = ambiguous;
    }

    /**
     * @param serviceNames    service directory names, already lower-case
     * @param rootToService   simple class name to owning service, for classes extending {@code Aggregate}
     * @param entityToService simple class name to owning service, for every counted entity
     */
    static AggregateReferenceResolver build(Set<String> serviceNames,
                                            Map<String, String> rootToService,
                                            Map<String, String> entityToService) {
        Set<String> ambiguous = new HashSet<>();
        return new AggregateReferenceResolver(
                Set.copyOf(serviceNames),
                lowerCaseIndex(rootToService, ambiguous),
                lowerCaseIndex(entityToService, ambiguous),
                ambiguous);
    }

    /**
     * @param prefix          the camelCase text before {@code AggregateId[s]}, e.g. {@code courseExecution}
     * @param contextServices foreign services referenced by the declaring file's package imports,
     *                        already excluding the file's own service. Used only as a last resort,
     *                        and only when there is exactly one — see step 5.
     * @return the owning service, or {@code null} when nothing matches (recorded in {@link #unresolved()})
     */
    String resolve(String prefix, Set<String> contextServices) {
        if (prefix == null || prefix.isBlank()) return null;

        // The caller's regex matches method names as well as fields, so getAggregateId() - which
        // every Aggregate and member entity inherits - arrives as the prefix "get". That is the
        // entity's own id, not a reference to anything, and reporting it would bury the real gaps.
        String name = stripAccessorVerb(prefix);
        if (name == null) return null;

        String lower = name.toLowerCase();
        String hit = matchService(lower);
        if (hit == null) hit = matchIndex(rootToService, lower);
        if (hit == null) {
            String last = lastWord(name);
            hit = matchService(last);
            if (hit == null) hit = matchIndex(entityToService, last);
        }

        // Last resort. A role-named id (creatorAggregateId, studentAggregateId, participantAggregateId)
        // shares no text with the aggregate it points at, so no name lookup can find it. The file
        // holding it does import the target — all three such classes in the reference app import
        // user.aggregate.UserDto. Only act when the import is unambiguous; guessing between two
        // foreign services would be worse than reporting the gap.
        if (hit == null && contextServices.size() == 1) {
            hit = contextServices.iterator().next();
            contextResolved.merge(lower, 1, Integer::sum);
        }

        if (hit == null) unresolved.merge(lower, 1, Integer::sum);
        return hit;
    }

    /** The service set this index was built from, so callers need not thread it separately. */
    Set<String> services() {
        return serviceNames;
    }

    /** Prefixes that resolved to nothing, with occurrence counts - for the caller's summary log. */
    Map<String, Integer> unresolved() {
        return new LinkedHashMap<>(unresolved);
    }

    /**
     * Prefixes rescued by step 5, with occurrence counts. Reported separately because they are the
     * one class of match not justified by a name, so they are the first thing to inspect if an edge
     * looks wrong.
     */
    Map<String, Integer> contextResolved() {
        return new LinkedHashMap<>(contextResolved);
    }

    // --- internals -----------------------------------------------------------

    private String matchService(String candidate) {
        if (serviceNames.contains(candidate)) return candidate;
        String singular = singularise(candidate);
        return singular != null && serviceNames.contains(singular) ? singular : null;
    }

    private String matchIndex(Map<String, String> index, String candidate) {
        if (ambiguous.contains(candidate)) return null;
        String hit = index.get(candidate);
        if (hit != null) return hit;

        String singular = singularise(candidate);
        if (singular == null || ambiguous.contains(singular)) return null;
        return index.get(singular);
    }

    /**
     * Drops a leading bean-accessor verb: {@code getCreator} becomes {@code Creator}. Returns
     * {@code null} when the prefix is <i>only</i> a verb ({@code get}, {@code set}), which means the
     * match came from {@code getAggregateId()} and names no aggregate.
     *
     * <p>Resolution outcomes are unaffected - last-word matching already handled {@code getAnswer}.
     * What changes is that the role-named gaps stay legible in {@link #unresolved()} instead of
     * competing with dozens of accessor hits, and that {@code getCreator}, {@code setCreator} and
     * {@code creator} are reported as one gap rather than three.
     */
    private static String stripAccessorVerb(String prefix) {
        Matcher m = ACCESSOR_VERB.matcher(prefix);
        if (!m.matches()) return prefix;
        return m.group(2);   // null when the prefix was the bare verb
    }

    /**
     * Last camelCase word, lower-cased: {@code courseExecution} gives {@code execution},
     * {@code quizQuestions} gives {@code questions}. An all-lower-case prefix is its own last word.
     */
    private static String lastWord(String prefix) {
        int start = 0;
        for (int i = 0; i < prefix.length(); i++) {
            if (Character.isUpperCase(prefix.charAt(i))) start = i;
        }
        return prefix.substring(start).toLowerCase();
    }

    /** Naive plural strip; {@code null} when the word does not look plural. Callers try both forms. */
    private static String singularise(String word) {
        return word.length() > 1 && word.endsWith("s") ? word.substring(0, word.length() - 1) : null;
    }

    /** Lower-cases keys, collecting collisions into {@code ambiguous} rather than letting one win. */
    private static Map<String, String> lowerCaseIndex(Map<String, String> source,
                                                      Set<String> ambiguous) {
        Map<String, String> index = new LinkedHashMap<>();
        source.forEach((className, service) -> {
            String key = className.toLowerCase();
            String previous = index.put(key, service);
            if (previous != null && !previous.equals(service)) ambiguous.add(key);
        });
        return index;
    }
}
