package org.anonymous.msrefactor.rt.monitor.collector;

import org.anonymous.msrefactor.rt.monitor.CommandInfo;
import org.anonymous.msrefactor.rt.monitor.FunctionalityInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pass 4c: which entities of which service a functionality reaches.
 *
 * <p>Two sources, both read from text already in hand:
 * <ol>
 *   <li>the functionality's own source, where a service's entities appear by class name or as
 *       their DTO ({@code QuizDto} → {@code Quiz});</li>
 *   <li>the body of every service method its commands delegate to, resolved through the target
 *       service's {@code handledCommands} ({@code CreateQuizCommand} → {@code QuizService.createQuiz})
 *       — the only place member entities such as {@code QuizQuestion} are actually named, since a
 *       workflow talks to an aggregate through its root and DTOs.</li>
 * </ol>
 *
 * <p>Matching is by case-sensitive substring on the class name, so {@code getQuizQuestions()}
 * counts as reaching {@code QuizQuestion}. The cost is over-matching a short name inside a longer
 * one of the same service, which is rare in this naming scheme and biases every service alike.
 */
final class FunctionalEntityResolver {

    private final Map<String, List<String>> entitiesByService;
    private final Map<String, Map<String, String>> serviceSourcesByService;
    private final Map<String, Map<String, CommandInfo>> commandsByService;
    /** service → class.method → body, memoised: the same method is reached by many workflows. */
    private final Map<String, String> bodyCache = new LinkedHashMap<>();

    FunctionalEntityResolver(Map<String, List<String>> entitiesByService,
                             Map<String, Map<String, String>> serviceSourcesByService,
                             Map<String, List<CommandInfo>> handledByService) {
        this.entitiesByService = entitiesByService;
        this.serviceSourcesByService = serviceSourcesByService;
        this.commandsByService = new LinkedHashMap<>();
        handledByService.forEach((svc, list) -> {
            Map<String, CommandInfo> byName = new LinkedHashMap<>();
            if (list != null) list.forEach(c -> byName.putIfAbsent(c.getName(), c));
            commandsByService.put(svc, byName);
        });
    }

    /**
     * @return service → entity names reached, only for services with at least one match; empty
     *         when nothing resolved
     */
    Map<String, List<String>> touchedEntities(String functionalitySource, String ownerService,
                                              List<FunctionalityInfo.SentCommand> sent) {
        Map<String, Set<String>> hits = new LinkedHashMap<>();

        // 1. Names the workflow itself uses: the owner's, and any service it addresses.
        Set<String> inSource = new LinkedHashSet<>();
        if (ownerService != null) inSource.add(ownerService);
        if (sent != null) sent.forEach(c -> { if (c.getTargetService() != null) inSource.add(c.getTargetService()); });
        for (String svc : inSource) {
            for (String entity : entitiesByService.getOrDefault(svc, List.of())) {
                if (mentions(functionalitySource, entity) || mentions(functionalitySource, entity + "Dto")) {
                    hits.computeIfAbsent(svc, k -> new LinkedHashSet<>()).add(entity);
                }
            }
        }

        // 2. Names the delegated service methods use.
        if (sent != null) {
            for (FunctionalityInfo.SentCommand c : sent) {
                String target = c.getTargetService();
                if (target == null) continue;
                CommandInfo info = commandsByService.getOrDefault(target, Map.of()).get(c.getName());
                if (info == null || info.getServiceMethod() == null) continue;
                String body = methodBody(target, info.getServiceMethod());
                if (body == null) continue;
                for (String entity : entitiesByService.getOrDefault(target, List.of())) {
                    if (mentions(body, entity)) hits.computeIfAbsent(target, k -> new LinkedHashSet<>()).add(entity);
                }
            }
        }

        Map<String, List<String>> out = new LinkedHashMap<>();
        hits.forEach((svc, ents) -> out.put(svc, new ArrayList<>(ents)));
        return out;
    }

    private static boolean mentions(String text, String name) {
        return text != null && text.contains(name);
    }

    /** {@code "QuizService.createQuiz"} → that method's body in the target service's sources. */
    private String methodBody(String service, String serviceMethod) {
        String key = service + "#" + serviceMethod;
        if (bodyCache.containsKey(key)) return bodyCache.get(key);
        String body = null;
        int dot = serviceMethod.indexOf('.');
        if (dot > 0) {
            String className = serviceMethod.substring(0, dot);
            String method = serviceMethod.substring(dot + 1);
            Map<String, String> sources = serviceSourcesByService.getOrDefault(service, Map.of());
            String content = sources.get(className);
            if (content != null) body = bodyOf(content, method);
        }
        bodyCache.put(key, body);
        return body;
    }

    /** Brace-matched body of {@code name}; the first declaration wins when it is overloaded. */
    static String bodyOf(String content, String name) {
        Matcher m = Pattern.compile("\\b" + Pattern.quote(name) + "\\s*\\([^)]*\\)\\s*(?:throws[^{]*)?\\{")
                .matcher(content);
        if (!m.find()) return null;
        int close = StaticAnalysisCollector.matchBrace(content, m.end() - 1);
        return close < 0 ? null : content.substring(m.end(), close);
    }
}
