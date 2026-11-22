package org.anonymous.msrefactor.rt.monitor.collector;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.anonymous.msrefactor.rt.monitor.CommandInfo;
import org.anonymous.msrefactor.rt.monitor.FunctionalityInfo;
import org.anonymous.msrefactor.rt.monitor.InterInvariantInfo;
import org.anonymous.msrefactor.rt.monitor.InterServiceDependency;
import org.anonymous.msrefactor.rt.monitor.InvariantInfo;
import org.anonymous.msrefactor.rt.monitor.ServiceStaticMetrics;
import org.anonymous.msrefactor.rt.monitor.StaticMetrics;
import org.anonymous.msrefactor.rt.monitor.StructuralSignals;
import org.anonymous.msrefactor.rt.monitor.VersionAnalysisResult;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.anonymous.msrefactor.rt.constants.AppTargetConstants;

/**
 * Derives static structural metrics (M1 / M2) from Java source trees.
 *
 * <h3>Version discovery</h3>
 * Always includes a {@code "current"} version by scanning the live filesystem.
 * Additionally enumerates git tags matching {@code v<number>} (e.g., {@code v1}, {@code v2})
 * for historical comparison; analysis of tagged versions reads directly from the git object
 * database — no filesystem copies needed.
 *
 * <h3>Detection heuristics — three passes per service</h3>
 * <ul>
 *   <li><b>Service</b> — each subdirectory directly under {@code microservices/}</li>
 *   <li><b>Entity (Nᵢ)</b> — {@code .java} file in {@code aggregate/}
 *       (one level deep only; the transactional-model subdirectories are excluded from entity
 *       counting, and only the <i>active</i> model's contents are scanned in Pass 2 for
 *       cross-service refs — see {@link #ACTIVE_MODEL_DIRS}),
 *       whose filename does not end with {@code Repository}, {@code Factory}, {@code Dto},
 *       {@code State}, or {@code Type}, does not start with {@code Causal} or {@code Saga},
 *       and is either non-abstract or carries {@code @Entity}</li>
 *   <li><b>Internal relation (μᵢ)</b> — each occurrence of {@code @OneToOne}, {@code @OneToMany},
 *       or {@code @ManyToOne} in entity files belonging to the same service</li>
 *   <li><b>External relation (σᵢⱼ) — Pass 2</b> — entities in service A whose source file:
 *       (a) contains the name of an entity belonging to service B as a standalone identifier
 *           (word-boundary match); OR
 *       (b) declares a field matching {@code *AggregateId[s]?} whose camel-case prefix
 *           resolves to service B (catches {@code @ElementCollection List<Integer>} ID lists
 *           invisible to the class-name scanner)</li>
 *   <li><b>Event subscriptions — Pass 3</b> — each {@code *Subscribes*.java} file in
 *       {@code events/subscribe/} encodes one event subscription; the publisher service is
 *       resolved from the {@code .getXxxAggregateId()} call in the constructor, and the event
 *       type from the {@code EventType.class.getSimpleName()} argument</li>
 * </ul>
 *
 * <h3>Formulas</h3>
 * <pre>
 *   M1 = (1/t') · Σᵢ (μᵢ / Nᵢ²)
 *   M2 = 1/(t'(t'−1)/2) · Σᵢ≠ⱼ σᵢⱼ / (2·Nᵢ·Nⱼ)
 * </pre>
 */
@Slf4j
@Component
public class StaticAnalysisCollector {

    @Value("${rt.monitor.static-analysis.git-repo-path:../..}")
    private String gitRepoPath;

    @Value("${rt.monitor.locust.scripts-path:utils/locust}")
    private String locustScriptsPath;

    private static final String SOURCE_TREE_PREFIX = AppTargetConstants.SOURCE_TREE_PATH;

    private static final String MICROSERVICES_SUBPATH = AppTargetConstants.MICROSERVICES_PACKAGE_PATH;

    private static final String CURRENT_VERSION = "current";

    private static final List<String> EXCLUDED_SUFFIXES =
            List.of("Repository", "Factory", "Dto", "State", "Type");
    private static final List<String> EXCLUDED_PREFIXES = List.of("Saga");

    /**
     * Transactional-model subdirectories to scan, under both {@code aggregate/} and
     * {@code coordination/}. The target app implements sagas only, so this is also the set of
     * known structural directories the nested-package guard must not report as stray packages.
     */
    private static final List<String> ACTIVE_MODEL_DIRS = List.of("sagas");

    private static final Pattern ABSTRACT_CLASS        = Pattern.compile("\\babstract\\s+class\\b");
    /** Marks an aggregate root. A root absorbed by MergeAggregates loses this and becomes a member. */
    private static final Pattern AGGREGATE_ROOT        = Pattern.compile("\\bextends\\s+Aggregate\\b");
    /** Suffix exclusion catches {@code CourseType} but not {@code Role}; declaration kind catches both. */
    private static final String  NON_CLASS_DECLARATION = "\\b(?:enum|interface|@interface)\\s+";
    // Gap 1 fix: @ManyToOne added alongside @OneToOne / @OneToMany
    private static final Pattern JPA_RELATION          = Pattern.compile("@OneToOne|@OneToMany|@ManyToOne");
    private static final Pattern NON_ENTITY_ANNOTATION = Pattern.compile("@Embeddable|@MappedSuperclass");
    /**
     * Invariants are found by walking {@code verifyInvariants()}, not by matching a name: the
     * {@code invariant*} prefix is a convention of two aggregates, and four real rules elsewhere
     * ({@code removedNoStudents}, {@code deletedState}, and two inline checks) do not follow it.
     */
    private static final Pattern NO_ARG_CALL =
            Pattern.compile("\\b([a-z]\\w*)\\s*\\(\\s*\\)");
    /** The error constant names an inline check; it is the domain rule's own name. */
    private static final Pattern THROWN_CONSTANT =
            Pattern.compile("throw\\s+new\\s+\\w*Exception\\s*\\(\\s*(?:\\w+\\.)?([A-Z][A-Z0-9_]*)");
    /** Captures the declared type and, for a collection, its element type. */
    private static final Pattern FIELD_DECL = Pattern.compile(
            "\\bprivate\\s+(?:final\\s+|transient\\s+|static\\s+)*([\\w.]+)\\s*(?:<\\s*([\\w.]+)\\s*>)?\\s+(\\w+)\\s*[;=]");
    /** {@code TournamentParticipant::getX} and {@code for (TournamentParticipant t : …)}. */
    private static final Pattern TYPE_REFERENCE =
            Pattern.compile("\\b([A-Z]\\w*)\\s*(?:::|(?=\\s+\\w+\\s*[:)]))");
    private static final Pattern ACCESSOR_CALL =
            Pattern.compile("\\b(?:get|is)([A-Z]\\w*)\\s*\\(");
    private static final Pattern IDENTIFIER = Pattern.compile("\\b([a-z]\\w*)\\b");
    private static final Pattern METHOD_RETURNING_CHECK =
            Pattern.compile("\\b(?:boolean|void)\\s+(\\w+)\\s*\\(");
    /** {@code getState()} on the aggregate itself, not {@code someMember.getState()}. */
    private static final Pattern OWN_STATE_CALL =
            Pattern.compile("(?<![.\\w])getState\\s*\\(");
    /**
     * The owning side of a composition: {@code @OneToOne(... mappedBy = "…")} or the {@code
     * @OneToMany} equivalent, followed by the field it annotates. The class that declares
     * {@code mappedBy} contains the field's type; the other side is a plain back-reference.
     *
     * <p>Group 1 is the annotation, group 2 the declared type and group 3 the element type of a
     * collection. Group 1 and the presence of group 3 are what tell a single-valued member from a
     * collection — the distinction {@link #extractSingleValuedMembers} needs and
     * {@link #extractCompositions} deliberately flattens.
     */
    private static final Pattern COMPOSITION_PARENT = Pattern.compile(
            "@(OneToOne|OneToMany)\\s*\\([^)]*mappedBy[^)]*\\)[^;]*?"
                    + "\\b([\\w.]+)\\s*(?:<\\s*([\\w.]+)\\s*>)?\\s+\\w+\\s*[;=]",
            Pattern.DOTALL);
    /** The non-owning side: a relation with no {@code mappedBy}, normally the pointer to the parent. */
    private static final Pattern BACK_REFERENCE = Pattern.compile(
            "@(?:OneToOne|ManyToOne)\\s*(?:\\((?![^)]*mappedBy)[^)]*\\))?[^;]*?"
                    + "\\bprivate\\s+([\\w.]+)\\s+\\w+\\s*[;=]",
            Pattern.DOTALL);

    // Gap 4: detects fields like quizQuestionsAggregateIds, courseExecutionAggregateId
    private static final Pattern AGGREGATE_ID_FIELD    = Pattern.compile("\\b([a-z][a-zA-Z]*)AggregateIds?\\b");
    /** A cross-service package reference, e.g. {@code microservices.user.aggregate.UserDto}. */
    private static final Pattern PACKAGE_REFERENCE     = Pattern.compile("microservices\\.([a-z][a-zA-Z0-9]*)\\.");

    // Pass 4: detects ServiceMapping.XYZ.getServiceName() calls in Functionality classes
    private static final Pattern SERVICE_MAPPING_REF =
            Pattern.compile("ServiceMapping\\.([A-Z_]+)\\.getServiceName\\(\\)");

    // Pass 4: counts SagaStep / Step instantiations in buildWorkflow()
    private static final Pattern STEP_INSTANTIATION =
            Pattern.compile("\\bnew\\s+(?:Saga)?Step\\s*\\(");

    /**
     * Pass 4: a command construction that names its target service on the same statement, e.g.
     * {@code new GetCourseExecutionByIdCommand(unitOfWork, ServiceMapping.EXECUTION.getServiceName(), id)}.
     * Matches both protocols and both the assigned and the inline {@code commandGateway.send(new …)}
     * forms.
     *
     * <p>{@code [A-Z]\w*Command} deliberately does not match the bare {@code Command} class used by
     * saga compensations — those carry no entity — and {@code [^;]} keeps the match inside one
     * statement so a later command's ServiceMapping cannot be attributed to an earlier one.
     */
    private static final Pattern COMMAND_TO_SERVICE = Pattern.compile(
            "\\bnew\\s+([A-Z]\\w*Command)\\s*\\([^;]*?ServiceMapping\\.([A-Z_]+)\\.getServiceName\\(\\)");

    /** Pass 4: {@code sagaCommand.setSemanticLock(CourseExecutionSagaState.READ_COURSE)}. */
    private static final Pattern SEMANTIC_LOCK =
            Pattern.compile("setSemanticLock\\s*\\(\\s*\\w+\\.([A-Z_]+)\\s*\\)");

    /**
     * Pass 4b: a step declaration, up to the opening brace of its lambda —
     * {@code new SagaStep("getUserStep", () -> {}. Covers {@code Step} too.
     */
    private static final Pattern STEP_DECLARATION = Pattern.compile(
            "\\bnew\\s+(?:Saga)?Step\\s*\\(\\s*\"(\\w+)\"\\s*,\\s*\\(\\s*\\)\\s*->\\s*\\{");

    /**
     * Pass 4b: the variable a step is assigned to, matched against the text <i>before</i> the
     * declaration. Dependencies and compensations both name the variable, never the step's string
     * name, so without this the DAG cannot be resolved.
     */
    private static final Pattern STEP_ASSIGNMENT =
            Pattern.compile("(?:Saga)?Step\\s+(\\w+)\\s*=\\s*$");

    /** Pass 4b: {@code getCourseStep.registerCompensation(() -> {}. */
    private static final Pattern COMPENSATION = Pattern.compile(
            "\\b(\\w+)\\s*\\.\\s*registerCompensation\\s*\\(\\s*\\(\\s*\\)\\s*->\\s*\\{");

    /** Pass 4b: {@code workflow.addStep(getUserStep)} — a step not named by one never runs. */
    private static final Pattern ADD_STEP =
            Pattern.compile("\\.addStep\\s*\\(\\s*(\\w+)\\s*\\)");

    /** Pass 4b: the dependency argument, {@code new ArrayList<>(Arrays.asList(a, b))} or {@code List.of(a)}. */
    private static final Pattern DEPENDENCY_LIST =
            Pattern.compile("(?:Arrays\\.asList|List\\.of)\\s*\\(([^)]*)\\)");

    /**
     * Pass 4b: the bare {@code Command} base class, which carries no entity and exists to release a
     * semantic lock on abort.
     *
     * <p>Deliberately absent from {@link #COMMAND_TO_SERVICE} — it is not an operation on the target
     * and must not reach the M2 coupling map — but it <i>is</i> a construction site naming a service,
     * so a refactoring that moves that service has to re-point it. Matched only inside a compensation
     * body.
     */
    private static final Pattern BARE_COMMAND = Pattern.compile(
            "\\bnew\\s+Command\\s*\\([^;]*?ServiceMapping\\.([A-Z_]+)\\.getServiceName\\(\\)");

    // Pass 5A: @PostMapping("/path") / @GetMapping etc. on controller methods
    private static final Pattern CONTROLLER_MAPPING =
            Pattern.compile("@(Post|Get|Put|Delete|Patch)Mapping\\s*\\(\\s*\"([^\"]+)\"\\s*\\)");
    /**
     * A creation-scope probe: the {@code @PostMapping} path exactly as written, keeping the path
     * variable's name, which {@link #extractControllerMappings} rewrites to <code>{id}</code>.
     *
     * <p>Accepts the {@code value = "…"} form too — {@code TournamentController} and
     * {@code ExecutionController} use it, and {@link #CONTROLLER_MAPPING} misses them both.
     */
    private static final Pattern POST_MAPPING_RAW =
            Pattern.compile("@PostMapping\\s*\\(\\s*(?:value\\s*=\\s*)?\"([^\"]+)\"");
    // First lowercase-starting method call after a @Mapping annotation (i.e., the service dispatch call)
    private static final Pattern SERVICE_METHOD_CALL =
            Pattern.compile("\\.([a-z][a-zA-Z0-9_]*)\\s*\\(");
    // Pass 5B: self.client.post("/path", ..., name="Task Name") in Locust .py files
    private static final Pattern LOCUST_CLIENT_CALL =
            Pattern.compile(
                    "self\\.client\\.(post|get|put|delete|patch)\\s*\\([^\"']*(?:f)?[\"']([^\"'{}]+(?:\\{[^}]*\\}[^\"'{}]*)*)[\"'].*?name\\s*=\\s*[\"']([^\"']+)[\"']",
                    Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    // Gap 3: extracts publisher service and event type from EventSubscription subclass constructors
    // Matches .getXxxAggregateId() — group(1) is the capitalized service prefix (e.g. "Quiz", "User")
    private static final Pattern SUBSCRIBE_PUBLISHER   = Pattern.compile("\\.get([A-Z][a-zA-Z]*)AggregateId\\s*\\(\\s*\\)");
    // Matches EventTypeName.class.getSimpleName() — group(1) is the event class name
    private static final Pattern SUBSCRIBE_EVENT_TYPE  = Pattern.compile("([A-Z][a-zA-Z]+Event)\\.class\\.getSimpleName\\(\\)");

    // ─── Pass 3b: inter-invariant patterns ───────────────────────────────────────

    /**
     * The local {@code Set} the subscriptions are collected into. Its <i>name</i> is what identifies
     * a delegate call, because a method whose single argument is the accumulator is registering
     * subscriptions and nothing else does that.
     *
     * <p>Matching {@code interInvariant*} by name instead would repeat the mistake already fixed for
     * {@code invariant*}: a prefix is a convention of some aggregates, while the typed argument is
     * forced by the signature.
     */
    private static final Pattern SUBSCRIPTION_ACCUMULATOR =
            Pattern.compile("\\bSet\\s*<\\s*EventSubscription\\s*>\\s+(\\w+)\\s*=");
    /** {@code new QuizAnswerSubscribesUpdateStudentName(…)} — group(1) is the subscription class. */
    private static final Pattern NEW_SUBSCRIPTION = Pattern.compile("\\bnew\\s+([A-Z]\\w*)\\s*\\(");
    /**
     * {@code if (getState() == ACTIVE)} and {@code if (this.getState() == AggregateState.ACTIVE)},
     * the two forms in use. Bounded to one line because the condition contains its own parentheses,
     * which a {@code [^)]*} class cannot cross.
     */
    private static final Pattern ACTIVE_STATE_GUARD = Pattern.compile(
            "\\bif\\s*\\([^\\n]*?getState\\s*\\(\\s*\\)\\s*==\\s*(?:\\w+\\.)?ACTIVE[^\\n]*?\\)");
    private static final Pattern FOR_LOOP   = Pattern.compile("\\bfor\\s*\\(");
    private static final Pattern IF_BRANCH  = Pattern.compile("\\bif\\s*\\(");
    /** Stripped when present, never required — see {@link InterInvariantInfo#getName()}. */
    private static final String INTER_INVARIANT_PREFIX = "interInvariant";
    /**
     * {@code @InterInvariant("USER_EXISTS")} or {@code @InterInvariant({"QUIZ_EXISTS", "…"})} on the
     * method that follows: group(1) is the value list, group(2) the method it annotates.
     *
     * <p>This is the whole link between a rule and its implementation, and it is read literally. The
     * two do not agree by name — {@code USER_EXISTS} is implemented by
     * {@code interInvariantStudentExists} — and a method can maintain two rules at once, which no
     * name comparison can represent.
     */
    private static final Pattern INTER_INVARIANT_ANNOTATION = Pattern.compile(
            "@InterInvariant\\s*\\(([^)]*)\\)\\s*(?:@\\w+\\s*)*"
                    + "(?:public|protected|private|static|final|\\s)*\\bvoid\\s+(\\w+)\\s*\\(");
    /** One quoted rule name inside that value list. */
    private static final Pattern QUOTED_NAME = Pattern.compile("\"([^\"]+)\"");
    /**
     * {@code @Replica("User")} on the class that follows. Declares what a class copies, replacing the
     * guess that read it off a {@code <prefix>AggregateId} field: that scan could not tell a field
     * declaration from a method call, and the prefix often names a role
     * ({@code creatorAggregateId} → {@code User}) rather than the aggregate.
     */
    private static final Pattern REPLICA_ANNOTATION = Pattern.compile(
            "@Replica\\s*\\(([^)]*)\\)");
    /**
     * {@code @ServiceGuard({"…"})} on the method that follows: group(1) is the value list, group(2)
     * the method. Matched across the whole application, because the aggregate that declares a rule and
     * the service that guards it need not be the same one.
     */
    private static final Pattern SERVICE_GUARD_ANNOTATION = Pattern.compile(
            "@ServiceGuard\\s*\\(([^)]*)\\)\\s*(?:@\\w+(?:\\s*\\([^)]*\\))?\\s*)*"
                    + "(?:public|protected|private|static|final|synchronized|\\s)*"
                    + "[\\w.<>\\[\\], ]+?\\s+(\\w+)\\s*\\(");

    // ─── Pass 6: event publication ───────────────────────────────────────────────

    /**
     * Where an event is published: {@code unitOfWorkService.registerEvent(new DeleteUserEvent(…))}.
     *
     * <p>The only reading of the publisher side anywhere in the collector. Everywhere else the
     * publisher is inferred from a subscription's <i>anchor</i>, which is a different thing:
     * {@code UpdateStudentNameEvent} is anchored on the course execution and was therefore attributed
     * to {@code execution} without anyone checking where it is registered.
     *
     * <p><b>The {@code \s*} spans the newline deliberately.</b> In 5 of the 12 call sites in the
     * reference application the {@code new XxxEvent(} sits on the line <i>after</i>
     * {@code registerEvent(}. A line-oriented search reports those five events as never published,
     * which is both wrong and plausible enough to be believed.
     */
    private static final Pattern EVENT_PUBLICATION = Pattern.compile(
            "registerEvent\\s*\\(\\s*new\\s+([A-Z]\\w*Event)\\s*\\(");
    // ─── Pass 7: the command surface ─────────────────────────────────────────────

    /**
     * The dispatch arm of a command handler: {@code case GetCourseByIdCommand cmd -> handleGetCourseById(cmd);}
     *
     * <p>This is where an aggregate declares what it accepts across the boundary. For {@code Course}
     * it is the <em>only</em> such declaration: its controller and its {@code Functionalities} class
     * are both empty, so every operation on it exists solely as a command another saga sends.
     */
    private static final Pattern HANDLER_CASE = Pattern.compile(
            "case\\s+([A-Z]\\w*Command)\\s+\\w+\\s*->\\s*(\\w+)\\s*\\(");
    /**
     * The service call a handler method delegates to: {@code courseService.getCourseById(…)}.
     *
     * <p>First match wins — the logging line above it calls {@code logger.info}, which does not match
     * a {@code *Service} receiver.
     */
    private static final Pattern HANDLER_DELEGATION = Pattern.compile(
            "\\b(\\w*[Ss]ervice)\\.(\\w+)\\s*\\(");

    /** A method signature, used to attribute a publication to the method it sits in. */
    private static final Pattern METHOD_SIGNATURE = Pattern.compile(
            "(?:public|protected|private)\\s+(?:static\\s+|final\\s+)*"
                    + "[\\w.<>\\[\\], ]+?\\s+(\\w+)\\s*\\([^;{}]*\\)\\s*\\{");
    /**
     * The header block that lists the rules an aggregate claims to maintain. Both spellings occur —
     * {@code INTER-INVARIANTS} and {@code INTER_INVARIANTS} — and the colon is optional.
     */
    private static final Pattern INTER_INVARIANT_HEADER =
            Pattern.compile("\\bINTER[-_]INVARIANTS?\\s*:?[ \\t]*");
    /**
     * The other block a rule can be declared in: {@code Execution} lists
     * {@code NO_DUPLICATE_COURSE_EXECUTION} under {@code SERVICE-LAYER GUARDS (Layer 2)}. Read for the
     * same reason as the one above — a {@code @ServiceGuard} has to have something to point at.
     */
    private static final Pattern SERVICE_GUARD_HEADER =
            Pattern.compile("\\bSERVICE[-_ ]LAYER\\s+GUARDS\\b[^\\n]*");
    /** One entry of that block: the constant, then whatever was written beside it. */
    private static final Pattern DECLARED_RULE =
            Pattern.compile("^\\s*\\*?\\s*([A-Z][A-Z0-9_-]{2,})\\b(.*)$");

    // ─── Pass-4 intermediate result ──────────────────────────────────────────────

    private record FunctionalityPass4Result(
            List<FunctionalityInfo> allFunctionalities,
            Map<String, Map<String, List<String>>> depsPerService,  // service → {target → [funcNames]}
            Map<String, Map<String, CommandUsage>> commandsPerService // service → {target → commands}
    ) {}

    /**
     * The commands one service sends to another, split by whether they read or write.
     *
     * <p>This is the distinction {@code CreateReplica} turns on: replicating an entity the consumer
     * only reads trades consistency for locality and changes no behaviour, while replicating one it
     * also writes creates a second source of truth. Nothing else the collector produces can tell the
     * two apart — {@code readWriteRatio} is a service's own global traffic mix, not a property of
     * the edge.
     */
    record CommandUsage(Set<String> reads, Set<String> writes) {
        CommandUsage() { this(new LinkedHashSet<>(), new LinkedHashSet<>()); }
    }

    // ─── Composition graph ───────────────────────────────────────────────────────

    /**
     * Builds the containment graph of a service's aggregates: owning class → the classes it holds.
     *
     * <p>This is what lets a proposed split be checked. Moving an entity out of an aggregate has to
     * take its children with it; the previous representation was a bare relation count, which cannot
     * express that, and a partition built on it once proposed moving {@code TournamentParticipant}
     * while leaving its {@code cascade = ALL} child behind.
     *
     * <p>Also warns when a relation joins two entities that are not parent and child. In the
     * reference application there is none — every non-{@code mappedBy} relation points back at its
     * own parent, so the graph is a strict forest — and the partitioner relies on that: if the cut
     * units are whole subtrees, no cut can sever a relation. A sibling edge would break the
     * assumption, so it must be visible rather than silently violated.
     */
    Map<String, List<String>> extractCompositions(Map<String, String> contentsByClass,
                                                  Collection<String> entityNames) {
        Set<String> entities = new LinkedHashSet<>(entityNames);
        Map<String, List<String>> parentToChildren = new LinkedHashMap<>();
        Map<String, String> childToParent = new LinkedHashMap<>();

        for (Map.Entry<String, String> e : contentsByClass.entrySet()) {
            String owner = e.getKey();
            if (!entities.contains(owner)) continue;         // Saga*/Causal* variants restate fields
            for (java.util.regex.MatchResult m : COMPOSITION_PARENT.matcher(e.getValue()).results().toList()) {
                String child = simpleName(m.group(3) != null ? m.group(3) : m.group(2));
                if (!entities.contains(child) || child.equals(owner)) continue;
                parentToChildren.computeIfAbsent(owner, k -> new ArrayList<>()).add(child);
                childToParent.put(child, owner);
            }
        }

        warnOnSiblingRelations(contentsByClass, entities, childToParent);
        return parentToChildren;
    }

    /**
     * The same edges as {@link #extractCompositions}, kept only where the member is held
     * <b>one-to-one</b>: owning class → its single-valued members.
     *
     * <p>The cardinality lives on the owning side. {@code QuestionTopic} holds a single
     * {@code topicAggregateId} like any replica, yet {@code Question} declares
     * {@code @OneToMany Set<QuestionTopic>} — reading the member class cannot tell the two apart,
     * reading the root can. Without this distinction an N:M relation reads as ownership, and a merge
     * is proposed for a pair no single root could contain.
     */
    Map<String, List<String>> extractSingleValuedMembers(Map<String, String> contentsByClass,
                                                        Collection<String> entityNames) {
        Set<String> entities = new LinkedHashSet<>(entityNames);
        Map<String, List<String>> parentToMembers = new LinkedHashMap<>();

        for (Map.Entry<String, String> e : contentsByClass.entrySet()) {
            String owner = e.getKey();
            if (!entities.contains(owner)) continue;         // Saga* variants restate the same fields
            for (java.util.regex.MatchResult m : COMPOSITION_PARENT.matcher(e.getValue()).results().toList()) {
                if (!"OneToOne".equals(m.group(1)) || m.group(3) != null) continue;   // a collection is not one
                String member = simpleName(m.group(2));
                if (!entities.contains(member) || member.equals(owner)) continue;
                parentToMembers.computeIfAbsent(owner, k -> new ArrayList<>()).add(member);
            }
        }
        return parentToMembers;
    }

    private void warnOnSiblingRelations(Map<String, String> contentsByClass, Set<String> entities,
                                        Map<String, String> childToParent) {
        for (Map.Entry<String, String> e : contentsByClass.entrySet()) {
            String owner = e.getKey();
            if (!entities.contains(owner)) continue;
            Matcher m = BACK_REFERENCE.matcher(e.getValue());
            while (m.find()) {
                String target = simpleName(m.group(1));
                if (!entities.contains(target) || target.equals(owner)) continue;
                if (target.equals(childToParent.get(owner))) continue;       // the normal back-ref
                if (owner.equals(childToParent.get(target))) continue;       // the owning side
                log.warn("[compositions] {} references sibling {} — the composition graph is not a "
                       + "forest, so whole-subtree cuts no longer guarantee structural validity",
                        owner, target);
            }
        }
    }

    // ─── Invariant extraction ────────────────────────────────────────────────────

    /**
     * Collects every P1 invariant of a service together with the state it reads.
     *
     * <p>Entry point is {@code verifyInvariants()}, which is the real choke point — every rule the
     * aggregate enforces is reachable from it, whatever the method is called. Scanning for
     * {@code invariant*} instead finds 16 rules in this codebase and misses 4.
     *
     * @param contentsByClass class → source, including active-model variants
     * @param entityNames     the service's entity classes; used to tell a member from a root scalar
     */
    List<InvariantInfo> extractInvariants(Map<String, String> contentsByClass,
                                          Collection<String> entityNames) {
        List<InvariantInfo> out = new ArrayList<>();
        Set<String> entities = new LinkedHashSet<>(entityNames);

        // One field map for the whole aggregate, not just the root. An invariant reaches a nested
        // member through its parent's accessor — invariantAnswerBeforeStart gets to
        // TournamentParticipantQuizAnswer through t.getParticipantAnswer(), and `participantAnswer`
        // is declared on TournamentParticipant, not on the root. Resolving against the root's fields
        // alone silently drops the nested member from the footprint.
        Map<String, String> aggregateFields = new LinkedHashMap<>();
        contentsByClass.forEach((cls, src) -> {
            if (entities.contains(cls)) aggregateFields.putAll(fieldTypeMap(src));
        });

        for (Map.Entry<String, String> e : contentsByClass.entrySet()) {
            String className = e.getKey();
            String content   = e.getValue();
            // Only the base entity declares verifyInvariants; Saga*/Causal* variants inherit it, and
            // processing them too would double every rule.
            if (!entities.contains(className)) continue;

            String verifyBody = methodBody(content, "verifyInvariants");
            if (verifyBody == null) continue;

            // Two maps, deliberately. The aggregate-wide one resolves a field name to its *type*, so
            // a nested member is found wherever it is declared. The declaring class's own map says
            // which fields are root scalars — a member's field is not one, and counting it as such
            // makes a rule look like it needs the root when it does not.
            Map<String, String> typeResolution = new LinkedHashMap<>(aggregateFields);
            Map<String, String> ownFields = fieldTypeMap(content);
            typeResolution.putAll(ownFields);
            Set<String> localChecks = declaredCheckMethods(content);

            for (String delegate : calledNoArgMethods(verifyBody)) {
                if (!localChecks.contains(delegate)) continue;      // an accessor or inherited call
                String body = methodBody(content, delegate);
                if (body == null) continue;
                out.add(footprint(delegate, className, InvariantInfo.Kind.METHOD,
                        resolveOneLevel(body, content, localChecks, className),
                        typeResolution, ownFields, entities));
            }

            for (String constant : thrownConstants(verifyBody)) {
                // INVARIANT_BREAK is the generic companion of a delegate already captured above.
                if ("INVARIANT_BREAK".equals(constant)) continue;
                out.add(footprint(constant, className, InvariantInfo.Kind.INLINE,
                        verifyBody, typeResolution, ownFields, entities));
            }
        }
        return out;
    }

    /**
     * Merges the bodies of any check methods this one calls, one level deep.
     *
     * <p>Every invariant in the reference application is flat, so one level suffices; a second level
     * is logged rather than followed, so the limit shows up in the logs instead of silently
     * truncating a footprint.
     */
    private String resolveOneLevel(String body, String content, Set<String> localChecks, String cls) {
        StringBuilder merged = new StringBuilder(body);
        for (String called : calledNoArgMethods(body)) {
            if (!localChecks.contains(called)) continue;
            String nested = methodBody(content, called);
            if (nested == null) continue;
            merged.append('\n').append(nested);
            if (!calledNoArgMethods(nested).stream().noneMatch(localChecks::contains)) {
                log.debug("[invariants] {}.{} nests deeper than one level; footprint may be partial",
                        cls, called);
            }
        }
        return merged.toString();
    }

    /** The four access styles an invariant uses to reach state; all of them occur in practice. */
    private InvariantInfo footprint(String name, String declaredIn, InvariantInfo.Kind kind,
                                    String body, Map<String, String> typeResolution,
                                    Map<String, String> ownFields, Set<String> entities) {
        Set<String> members = new LinkedHashSet<>();
        Set<String> scalars = new LinkedHashSet<>();

        // 1 + 2. Field by name, whether written bare or as this.field.
        IDENTIFIER.matcher(body).results().map(r -> r.group(1))
                .forEach(id -> classify(typeResolution.get(id), id, ownFields, entities, members, scalars));

        // 3. Through an accessor — the only way removedNoStudents reaches its collection.
        ACCESSOR_CALL.matcher(body).results()
                .map(r -> decapitalise(r.group(1)))
                .forEach(f -> classify(typeResolution.get(f), f, ownFields, entities, members, scalars));

        // 4. By type name — the only thing invariantUniqueParticipant names.
        TYPE_REFERENCE.matcher(body).results().map(r -> r.group(1))
                .filter(entities::contains)
                .forEach(members::add);

        boolean readsPrev = body.contains("getPrev(");
        // Only the aggregate's *own* state counts, because only the root's state stays with the core.
        // `qq.getState()` is a member's own flag: invariantQuizQuestionSequenceConsecutive reads it
        // and needs nothing from the root, so it travels with QuizQuestion.
        boolean readsState = OWN_STATE_CALL.matcher(body).find() || scalars.contains("state");

        return InvariantInfo.builder()
                .name(name).declaredIn(declaredIn).kind(kind)
                .memberEntities(new ArrayList<>(members))
                .rootScalars(new ArrayList<>(scalars))
                .readsPrev(readsPrev).readsState(readsState)
                .build();
    }

    // ─── Inter-invariant extraction (Pass 3b) ────────────────────────────────────

    /**
     * Collects every cross-aggregate rule an aggregate maintains, together with the subscriptions
     * that keep it true and the state it ranges over.
     *
     * <p>Entry point is {@code getEventSubscriptions()}, the choke point for the "inter" dimension
     * exactly as {@code verifyInvariants()} is for the "intra" one: every rule is registered there,
     * and the method it delegates to groups the subscriptions of one rule. Reading the
     * {@code notification/subscribe/} directory instead — which is all Pass 3 does — recovers the
     * subscriptions but not the grouping, so three subscriptions that maintain one rule arrive as
     * three unrelated event types.
     *
     * @param subscriptionsByClass Pass 3's index, keyed by subscription class name
     * @param rootByService        service → its single aggregate root, to name the far end of an edge
     * @param replicaOwners        local entity → the foreign services it declares a copy of
     * @param serviceGuards        rule name → the service method that enforces it, application-wide
     */
    List<InterInvariantInfo> extractInterInvariants(Map<String, String> contentsByClass,
                                                    Collection<String> entityNames,
                                                    Map<String, SubscriptionScan> subscriptionsByClass,
                                                    Map<String, String> rootByService,
                                                    Map<String, List<String>> replicaOwners,
                                                    Map<String, String> serviceGuards) {
        List<InterInvariantInfo> out = new ArrayList<>();
        Set<String> entities = new LinkedHashSet<>(entityNames);

        // Same reason as extractInvariants: a rule reaches a nested member through its parent's
        // accessor, so the field map has to span the whole aggregate, not just the root.
        Map<String, String> aggregateFields = new LinkedHashMap<>();
        contentsByClass.forEach((cls, src) -> {
            if (entities.contains(cls)) aggregateFields.putAll(fieldTypeMap(src));
        });

        for (Map.Entry<String, String> e : contentsByClass.entrySet()) {
            String className = e.getKey();
            String content   = e.getValue();
            // Only the base entity declares getEventSubscriptions; Saga*/Causal* variants inherit it.
            if (!entities.contains(className)) continue;

            String registration = methodBody(content, "getEventSubscriptions");
            if (registration == null) continue;

            Map<String, String> typeResolution = new LinkedHashMap<>(aggregateFields);
            Map<String, String> ownFields = fieldTypeMap(content);
            typeResolution.putAll(ownFields);
            Set<String> localMethods = declaredCheckMethods(content);
            Map<String, List<String>> claims = interInvariantClaims(content);
            int[] guard = activeGuardBlock(registration);

            Matcher accumulator = SUBSCRIPTION_ACCUMULATOR.matcher(registration);
            if (accumulator.find()) {
                // The accumulator's name identifies a delegate: a method whose single argument is
                // the Set is registering subscriptions, and nothing else in the body does that.
                Matcher call = Pattern.compile(
                        "\\b(\\w+)\\s*\\(\\s*" + Pattern.quote(accumulator.group(1)) + "\\s*\\)")
                        .matcher(registration);
                Set<String> seen = new LinkedHashSet<>();
                while (call.find()) {
                    String delegate = call.group(1);
                    if (!localMethods.contains(delegate) || !seen.add(delegate)) continue;
                    String body = methodBody(content, delegate);
                    if (body == null) continue;
                    List<String> claimed = claims.getOrDefault(delegate, List.of());
                    if (claimed.isEmpty()) {
                        log.warn("[inter-invariants] {}.{} has no @InterInvariant; the header rules it "
                               + "maintains will be reported as unimplemented", className, delegate);
                    }
                    out.add(interInvariant(ruleName(delegate), className,
                            InterInvariantInfo.Kind.METHOD, body, inside(guard, call.start()),
                            claimed, typeResolution, ownFields, entities,
                            subscriptionsByClass, rootByService, replicaOwners));
                }
            }

            // A subscription registered with no method of its own: the rule exists but has no name,
            // so it takes the subscription class's. None today; the shape is cheap to accept.
            for (String subscriptionClass : newSubscriptions(registration, subscriptionsByClass)) {
                out.add(interInvariant(subscriptionClass, className,
                        InterInvariantInfo.Kind.INLINE, registration, guard != null,
                        List.of(), typeResolution, ownFields, entities,
                        subscriptionsByClass, rootByService, replicaOwners));
            }

            out.addAll(declaredButUnsubscribed(content, className, out, serviceGuards));
        }
        return out;
    }

    /**
     * Rules the aggregate's header blocks name that no {@code @InterInvariant} claims — split by
     * whether a {@code @ServiceGuard} somewhere in the application claims them instead.
     *
     * <p>Set difference on the exact strings, with no normalisation. That is the point of the
     * annotations: the header and the method names disagree ({@code USER_EXISTS} versus
     * {@code interInvariantStudentExists}), one method can maintain two rules, and the guard for a
     * rule need not live in the declaring aggregate's own service. Every one of those breaks matching
     * by name; reading what the code claims does not.
     *
     * <p>What remains after both is genuinely unbacked, which is still a legitimate state:
     * {@code COURSE_EXISTS} holds because course publishes no events, and the header says so.
     *
     * @param guards rule name → {@code "ServiceClass.method"}, collected across the whole application
     */
    private List<InterInvariantInfo> declaredButUnsubscribed(String content, String className,
                                                             List<InterInvariantInfo> captured,
                                                             Map<String, String> guards) {
        Set<String> claimed = captured.stream()
                .filter(i -> className.equals(i.getDeclaredIn()))
                .flatMap(i -> i.getDeclaredNames() == null
                        ? java.util.stream.Stream.<String>empty() : i.getDeclaredNames().stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<String, String> declared = declaredRules(content);

        List<InterInvariantInfo> out = new ArrayList<>();
        declared.forEach((name, note) -> {
            if (claimed.contains(name)) return;
            String guard = guards.get(name);
            out.add(InterInvariantInfo.builder()
                    .name(name).declaredIn(className)
                    .kind(guard != null ? InterInvariantInfo.Kind.SERVICE_GUARD
                                        : InterInvariantInfo.Kind.DECLARED)
                    .declaredNames(List.of(name))
                    .enforcedIn(guard)
                    .note(note == null || note.isBlank() ? null : note)
                    .memberEntities(List.of()).rootScalars(List.of())
                    .subscriptions(List.of()).dependsOnServices(List.of())
                    .build());
        });

        // Drift the other way: the code claims a rule the specification does not mention. Only worth
        // saying when there is a specification to disagree with — an aggregate with no header block
        // has not drifted, it has simply never written its rules down.
        if (!declared.isEmpty()) {
            claimed.stream()
                    .filter(n -> !declared.containsKey(n))
                    .forEach(n -> log.warn("[inter-invariants] {} claims {} but its header blocks "
                            + "do not list it", className, n));
        }
        return out;
    }

    /**
     * The events one service's classes publish: event type → {@code "ServiceClass.method"}.
     *
     * <p>Read from the {@code registerEvent(new …)} call, which is where publication actually
     * happens, rather than inferred from anything a consumer declares.
     *
     * <p>The same event may be registered from more than one method — {@code
     * DisenrollStudentFromCourseExecutionEvent} is published by two of {@code ExecutionService}'s
     * operations. The first is kept, because the question this answers is "which aggregate owns this
     * event", and both answers agree on that.
     */
    Map<String, String> publishedEvents(Map<String, String> serviceSources) {
        Map<String, String> published = new LinkedHashMap<>();
        serviceSources.forEach((className, content) -> {
            Matcher m = EVENT_PUBLICATION.matcher(content);
            while (m.find()) {
                String method = enclosingMethod(content, m.start());
                published.putIfAbsent(m.group(1), method == null ? className : className + "." + method);
            }
        });
        return published;
    }

    /**
     * The method a source offset sits in: the nearest signature that opens before it.
     *
     * <p>Best effort by design. When nothing matches — an initialiser, a lambda, a shape the pattern
     * does not cover — the publication is attributed to the class alone rather than to a guessed
     * method.
     */
    private String enclosingMethod(String content, int offset) {
        Matcher m = METHOD_SIGNATURE.matcher(content);
        String nearest = null;
        while (m.find()) {
            if (m.start() > offset) break;
            nearest = m.group(1);
        }
        return nearest;
    }

    /**
     * Every {@code @ServiceGuard} in the application: rule name → {@code "ServiceClass.method"}.
     *
     * <p>Global on purpose. {@code UNIQUE_QUIZ_ANSWER_PER_STUDENT} is declared by {@code Quiz} and
     * guarded in {@code QuizAnswerService}, because that is where the answer is created; an index
     * scoped to the declaring aggregate's own service would never find it.
     */
    Map<String, String> serviceGuardClaims(Map<String, String> serviceSources) {
        Map<String, String> claims = new LinkedHashMap<>();
        serviceSources.forEach((className, content) -> {
            Matcher m = SERVICE_GUARD_ANNOTATION.matcher(content);
            while (m.find()) {
                String where = className + "." + m.group(2);
                QUOTED_NAME.matcher(m.group(1)).results().map(r -> r.group(1)).distinct()
                        .forEach(rule -> {
                            String previous = claims.putIfAbsent(rule, where);
                            if (previous != null && !previous.equals(where)) {
                                log.warn("[service-guards] {} is claimed by both {} and {}; keeping "
                                        + "the first", rule, previous, where);
                            }
                        });
            }
        });
        return claims;
    }

    /** Method name → the rule names its {@code @InterInvariant} annotation claims. */
    private Map<String, List<String>> interInvariantClaims(String content) {
        Map<String, List<String>> claims = new LinkedHashMap<>();
        Matcher m = INTER_INVARIANT_ANNOTATION.matcher(content);
        while (m.find()) {
            List<String> names = QUOTED_NAME.matcher(m.group(1)).results()
                    .map(r -> r.group(1)).distinct().toList();
            if (!names.isEmpty()) claims.put(m.group(2), names);
        }
        return claims;
    }

    /**
     * Rule name → the text written after it, from the header blocks a rule can be declared in.
     *
     * <p>Both {@code INTER-INVARIANTS} and {@code SERVICE-LAYER GUARDS} are read: a
     * {@code @ServiceGuard} must have something to point at, and {@code Execution} declares
     * {@code NO_DUPLICATE_COURSE_EXECUTION} under the second.
     */
    private Map<String, String> declaredRules(String content) {
        Map<String, String> declared = new LinkedHashMap<>();
        readHeaderBlock(content, INTER_INVARIANT_HEADER, declared);
        readHeaderBlock(content, SERVICE_GUARD_HEADER, declared);
        return declared;
    }

    private void readHeaderBlock(String content, Pattern heading, Map<String, String> into) {
        Matcher header = heading.matcher(content);
        if (!header.find()) return;
        for (String line : content.substring(header.end()).split("\\R")) {
            if (line.contains("*/")) break;
            Matcher rule = DECLARED_RULE.matcher(line);
            if (!rule.find()) continue;
            // Another section heading ends the block; a rule name never contains the word.
            if (rule.group(1).contains("INVARIANT") || rule.group(1).contains("GUARD")) break;
            into.putIfAbsent(rule.group(1), rule.group(2).trim());
        }
    }


    /**
     * Assembles one rule: the local state it reads, the subscriptions it registers, and the services
     * those two together implicate.
     *
     * <p>The footprint comes from two independent sources because neither is sufficient. The
     * delegate body names the member when it iterates a collection or passes one, and names nothing
     * when it passes {@code this} — the shape of every {@code QuizAnswer} rule. For those, the
     * accessors the subscription class calls on the aggregate are the only evidence left.
     */
    private InterInvariantInfo interInvariant(String name, String declaredIn,
            InterInvariantInfo.Kind kind, String body, boolean guarded, List<String> declaredNames,
            Map<String, String> typeResolution, Map<String, String> ownFields, Set<String> entities,
            Map<String, SubscriptionScan> subscriptionsByClass,
            Map<String, String> rootByService, Map<String, List<String>> replicaOwners) {

        InvariantInfo shape = footprint(name, declaredIn, InvariantInfo.Kind.METHOD, body,
                typeResolution, ownFields, entities);

        Set<String> members  = new LinkedHashSet<>(shape.getMemberEntities());
        Set<String> services = new LinkedHashSet<>();
        List<InterInvariantInfo.SubscriptionRef> refs = new ArrayList<>();

        for (String subscriptionClass : newSubscriptions(body, subscriptionsByClass)) {
            SubscriptionScan scan = subscriptionsByClass.get(subscriptionClass);
            refs.add(InterInvariantInfo.SubscriptionRef.builder()
                    .subscriptionClass(subscriptionClass)
                    .eventType(scan.eventType())
                    .publisherService(scan.publisherService())
                    .publisherAggregate(scan.publisherService() == null
                            ? null : rootByService.get(scan.publisherService()))
                    .build());
            if (scan.publisherService() != null) services.add(scan.publisherService());
            for (String field : scan.accessorFields()) {
                String type = typeResolution.get(field);
                if (type != null && entities.contains(type)) {
                    members.add(type);
                    continue;
                }
                String bySuffix = entityBySuffix(field, entities, declaredIn);
                if (bySuffix != null) members.add(bySuffix);
            }
        }

        // The publisher is the subscription anchor, not necessarily the aggregate the rule is about:
        // QuizAnswer's student rules are anchored on the course execution, which publishes the
        // events, while the rule constrains the user. Both ends go into the dependency set.
        for (String member : members) {
            services.addAll(replicaOwners.getOrDefault(member, List.of()));
        }

        return InterInvariantInfo.builder()
                .name(name).declaredIn(declaredIn).kind(kind)
                .declaredNames(declaredNames)
                .memberEntities(new ArrayList<>(members))
                .rootScalars(shape.getRootScalars())
                .readsState(shape.isReadsState())
                .guardedByActiveState(guarded)
                .iterated(FOR_LOOP.matcher(body).find())
                .conditional(IF_BRANCH.matcher(body).find())
                .subscriptions(refs)
                .dependsOnServices(new ArrayList<>(services))
                .build();
    }

    /**
     * The aggregate's member entity whose name ends with {@code accessor}, when exactly one does.
     *
     * <p>A subscription handed the root reaches its state through whatever wrapper it keeps, whose
     * accessors need not match the aggregate's field names:
     * {@code TournamentSubscribesDisenrollStudentFromCourseExecution} holds a {@code TournamentDto}
     * and reads {@code getCreator()}, while the field is {@code tournamentCreator}. Resolving the
     * accessor as a field finds nothing, and the rule then looks like it constrains only the course
     * execution it is anchored on — losing the very entity it is named after.
     *
     * <p>Ambiguity is never resolved: two candidates mean no answer, the same rule
     * {@link AggregateReferenceResolver} follows. The declaring class is excluded because the root is
     * not a member.
     */
    private String entityBySuffix(String accessor, Set<String> entities, String declaredIn) {
        if (accessor.length() < 4) return null;
        String needle = accessor.toLowerCase();
        String singular = needle.endsWith("s") ? needle.substring(0, needle.length() - 1) : needle;
        String match = null;
        for (String entity : entities) {
            if (entity.equals(declaredIn)) continue;
            String lower = entity.toLowerCase();
            if (!lower.endsWith(needle) && !lower.endsWith(singular)) continue;
            if (match != null) return null;
            match = entity;
        }
        return match;
    }

    /** Subscription classes instantiated in {@code body}, filtered to the ones Pass 3 indexed. */
    private List<String> newSubscriptions(String body, Map<String, SubscriptionScan> index) {
        return NEW_SUBSCRIPTION.matcher(body).results()
                .map(r -> r.group(1))
                .filter(index::containsKey)
                .distinct()
                .toList();
    }

    /**
     * {@code [start, end)} of the body of the {@code if (getState() == ACTIVE)} block, or null when
     * the aggregate registers unconditionally — which means it keeps subscribing after deletion.
     */
    private int[] activeGuardBlock(String body) {
        Matcher m = ACTIVE_STATE_GUARD.matcher(body);
        if (!m.find()) return null;
        int open = body.indexOf('{', m.end());
        if (open < 0) return null;
        int depth = 1;
        for (int i = open + 1; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return new int[] {open + 1, i};
        }
        return null;
    }

    private boolean inside(int[] range, int index) {
        return range != null && index >= range[0] && index < range[1];
    }

    private static String ruleName(String method) {
        return method.startsWith(INTER_INVARIANT_PREFIX) && method.length() > INTER_INVARIANT_PREFIX.length()
                ? method.substring(INTER_INVARIANT_PREFIX.length())
                : method;
    }

    private void classify(String type, String field, Map<String, String> ownFields,
                          Set<String> entities, Set<String> members, Set<String> scalars) {
        if (type == null) return;
        if (entities.contains(type)) members.add(type);
        // A scalar counts only when the declaring class owns it. A member entity's own field is
        // reachable through a traversal but lives on the other side of the boundary with its owner.
        else if (ownFields.containsKey(field)) scalars.add(field);
    }

    /** field name → its type, taking the element type for collections. */
    private Map<String, String> fieldTypeMap(String content) {
        Map<String, String> map = new LinkedHashMap<>();
        FIELD_DECL.matcher(content).results().forEach(r -> {
            String elementType = r.group(2);                 // Set<TournamentParticipant> → the inner
            String type = elementType != null ? elementType : r.group(1);
            map.put(r.group(3), simpleName(type));
        });
        return map;
    }

    private Set<String> declaredCheckMethods(String content) {
        return METHOD_RETURNING_CHECK.matcher(content).results()
                .map(r -> r.group(1))
                .filter(n -> !n.startsWith("get") && !n.startsWith("is") && !n.startsWith("set"))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private List<String> calledNoArgMethods(String body) {
        return NO_ARG_CALL.matcher(body).results().map(r -> r.group(1)).distinct().toList();
    }

    private List<String> thrownConstants(String body) {
        return THROWN_CONSTANT.matcher(body).results().map(r -> r.group(1)).distinct().toList();
    }

    /**
     * The body of {@code name}, brace-matched. A regex cannot do this: invariant bodies nest blocks,
     * and stopping at the first {@code }} truncates every rule that contains an {@code if}.
     */
    String methodBody(String content, String name) {
        Matcher m = Pattern.compile("\\b" + Pattern.quote(name) + "\\s*\\([^)]*\\)\\s*\\{")
                .matcher(content);
        if (!m.find()) return null;
        int depth = 1;
        int start = m.end();
        for (int i = start; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return content.substring(start, i);
        }
        return null;
    }

    private static String decapitalise(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    private static String simpleName(String type) {
        int dot = type.lastIndexOf('.');
        return dot < 0 ? type : type.substring(dot + 1);
    }

    // ─── Pass-1 intermediate result ──────────────────────────────────────────────

    private record PartialServiceData(
            String serviceName,
            List<String> entityNames,
            int internalRelations,
            List<InvariantInfo> invariants,
            Map<String, List<String>> compositions,
            Map<String, String> contentsByClass,  // className → file content (base + active-model variants)
            /**
             * Subset of {@link #entityNames} that {@code extends Aggregate} — i.e. the service's
             * aggregate roots. {@link AggregateReferenceResolver} needs roots and members kept apart:
             * a whole-prefix match is only trustworthy against a root, because member replicas such
             * as {@code QuizQuestion} concatenate two aggregate names.
             */
            Set<String> rootNames,
            /** Owning class → the members it holds one-to-one; see {@link #extractSingleValuedMembers}. */
            Map<String, List<String>> singleValuedMembers
    ) {
        static PartialServiceData empty(String serviceName) {
            return new PartialServiceData(serviceName, List.of(), 0, List.of(), Map.of(), Map.of(),
                    Set.of(), Map.of());
        }
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    public List<String> listVersions() {
        List<String> versions = new ArrayList<>();
        versions.add(CURRENT_VERSION);
        versions.addAll(listGitTags());
        return versions;
    }

    public List<VersionAnalysisResult> collectAll() {
        List<VersionAnalysisResult> results = new ArrayList<>();
        results.add(scanFilesystem());
        List<String> tags = listGitTags();
        if (!tags.isEmpty()) {
            log.info("Static analysis: also analysing {} git tag(s): {}", tags.size(), tags);
            tags.forEach(tag -> results.add(collectVersion(tag)));
        }
        log.info("Static analysis: collected {} version(s)", results.size());
        return results;
    }

    public VersionAnalysisResult collectVersion(String tag) {
        if (CURRENT_VERSION.equals(tag)) {
            return scanFilesystem();
        }
        File gitDir = new File(gitRepoPath, ".git");
        try (Repository repo = new FileRepositoryBuilder().setGitDir(gitDir).build()) {
            ObjectId commitId = resolveTagToCommit(repo, tag);
            if (commitId == null) {
                log.warn("Static analysis [{}]: tag not found", tag);
                return emptyResult(tag);
            }
            StaticMetrics metrics = analyzeCommit(repo, commitId, tag);
            log.debug("Static analysis [{}]: M1={}, M2={}, services={}",
                    tag, metrics.getM1(), metrics.getM2(), metrics.getServiceCount());
            return VersionAnalysisResult.builder()
                    .version(tag)
                    .path("git:" + tag)
                    .metrics(metrics)
                    .build();
        } catch (IOException e) {
            log.warn("Static analysis [{}]: failed to open repository: {}", tag, e.getMessage());
            return emptyResult(tag);
        }
    }

    /**
     * Analyses the source tree of one commit, addressed by sha.
     *
     * <p>The companion of {@link #collectVersion(String)}, which resolves only {@code refs/tags/}.
     * Tags named {@code v1}, {@code v2}, … are not created by this project — they come from outside —
     * whereas every {@code CodeVersion} records the sha of the commit it was built from. Addressing
     * the commit directly is therefore the resolution that always works.
     *
     * @param sha   any revision expression git can resolve; a full or abbreviated commit sha
     * @param label the name to report the result under
     */
    public VersionAnalysisResult collectCommit(String sha, String label) {
        if (sha == null || sha.isBlank()) return emptyResult(label);
        File gitDir = new File(gitRepoPath, ".git");
        try (Repository repo = new FileRepositoryBuilder().setGitDir(gitDir).build()) {
            ObjectId commitId = repo.resolve(sha);
            if (commitId == null) {
                log.warn("Static analysis [{}]: commit {} not found", label, sha);
                return emptyResult(label);
            }
            StaticMetrics metrics = analyzeCommit(repo, commitId, label);
            return VersionAnalysisResult.builder()
                    .version(label)
                    .path("git:" + sha)
                    .metrics(metrics)
                    .build();
        } catch (IOException e) {
            log.warn("Static analysis [{}]: failed to read commit {}: {}", label, sha, e.getMessage());
            return emptyResult(label);
        }
    }

    /** Whether git resolves this revision (branch, sha, …) — a cheap check before paying for a scan. */
    public boolean hasRef(String rev) {
        if (rev == null || rev.isBlank()) return false;
        File gitDir = new File(gitRepoPath, ".git");
        if (!gitDir.isDirectory()) return false;
        try (Repository repo = new FileRepositoryBuilder().setGitDir(gitDir).build()) {
            return repo.resolve(rev) != null;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Whether a tag of this name exists — lets a caller choose a resolution before paying for a scan. */
    public boolean hasTag(String tag) {
        File gitDir = new File(gitRepoPath, ".git");
        if (!gitDir.isDirectory()) return false;
        try (Repository repo = new FileRepositoryBuilder().setGitDir(gitDir).build()) {
            return resolveTagToCommit(repo, tag) != null;
        } catch (IOException e) {
            return false;
        }
    }

    // ─── Live filesystem scan ─────────────────────────────────────────────────

    private VersionAnalysisResult scanFilesystem() {
        Path msDir = Paths.get(gitRepoPath, SOURCE_TREE_PREFIX.split("/"))
                          .resolve(MICROSERVICES_SUBPATH.replace('/', File.separatorChar));
        if (!Files.isDirectory(msDir)) {
            log.warn("Static analysis [current]: microservices path not found at {}", msDir.toAbsolutePath());
            return emptyResult(CURRENT_VERSION);
        }
        try {
            // Collect service directories upfront so the stream can be closed and we can re-use them in Pass 3
            List<Path> serviceDirs;
            try (Stream<Path> dirs = Files.list(msDir)) {
                serviceDirs = dirs.filter(Files::isDirectory).collect(Collectors.toList());
            }

            // Pass 1: collect entity metadata and file contents per service (base + sagas/causal)
            Map<String, PartialServiceData> partials = new LinkedHashMap<>();
            Map<String, Path> serviceDirMap = new LinkedHashMap<>();
            for (Path serviceDir : serviceDirs) {
                String name = serviceDir.getFileName().toString();
                serviceDirMap.put(name, serviceDir);
                partials.put(name, collectEntityDataFromDirectory(name, serviceDir));
            }

            // Remove non-service directories (no entities found = no aggregate/ subdirectory)
            partials.entrySet().removeIf(e -> e.getValue().entityNames().isEmpty());
            serviceDirMap.keySet().retainAll(partials.keySet());

            // Passes 2 and 3 share one reference index so unresolved prefixes are reported once
            AggregateReferenceResolver resolver = buildReferenceResolver(partials);

            // Pass 2: detect cross-service references (word-boundary + field-name heuristic)
            Map<String, ServiceStaticMetrics> serviceMap = enrichCrossServiceRefs(partials, resolver);

            // Pass 3: event subscription scan
            Set<String> knownServices = partials.keySet();
            Map<String, Map<String, List<String>>> eventSubsMap = new LinkedHashMap<>();
            Map<String, Map<String, SubscriptionScan>> subsByClass = new LinkedHashMap<>();
            for (Map.Entry<String, Path> entry : serviceDirMap.entrySet()) {
                SubscriptionScanResult subs = collectEventSubscriptionsFromDirectory(
                        entry.getKey(), entry.getValue(), resolver);
                if (!subs.byPublisher().isEmpty()) eventSubsMap.put(entry.getKey(), subs.byPublisher());
                if (!subs.byClass().isEmpty()) subsByClass.put(entry.getKey(), subs.byClass());
            }
            logUnresolvedPrefixes(CURRENT_VERSION, resolver);

            // Application-wide: the aggregate that declares a rule and the service that guards it
            // need not belong to the same microservice.
            Map<String, String> serviceSources = new LinkedHashMap<>();
            Map<String, Map<String, String>> sourcesPerService = new LinkedHashMap<>();
            serviceDirMap.forEach((name, dir) ->
                    sourcesPerService.put(name, collectServiceSourcesFromDirectory(name, dir)));
            sourcesPerService.values().forEach(serviceSources::putAll);
            Map<String, String> serviceGuards = serviceGuardClaims(serviceSources);

            Map<String, ServiceStaticMetrics> enrichedMap = withM2Contributions(serviceMap);
            enrichedMap = withEventSubscriptions(enrichedMap, eventSubsMap);
            // Pass 3b: the rules those subscriptions maintain — needs Pass 3's index and Pass 2's
            // replica map, so it cannot run inside Pass 1 where the intra-invariants are collected.
            enrichedMap = withInterInvariants(enrichedMap, partials, subsByClass, serviceGuards);
            // Pass 6: who publishes each event, read rather than inferred from a subscription anchor.
            enrichedMap = withPublishedEvents(enrichedMap, sourcesPerService);
            // Pass 7: the command surface — what each aggregate accepts across the boundary.
            Map<String, Map<String, String>> handlersPerService = new LinkedHashMap<>();
            serviceDirMap.forEach((name, dir) -> handlersPerService.put(
                    name, collectSourcesFromDirectory(name, dir, "messaging")));
            enrichedMap = withHandledCommands(enrichedMap, handlersPerService);
            // Creation scope, for the ownership classifier: raw POST paths, variable names intact.
            enrichedMap = withPostEndpoints(enrichedMap, collectPostEndpoints(serviceDirMap));

            // Pass 4: Functionality mapper
            FunctionalEntityResolver entityResolver = functionalEntityResolver(enrichedMap, sourcesPerService);
            FunctionalityPass4Result pass4 = collectFunctionalityDepsFromDirectory(
                    serviceDirMap, knownServices, entityResolver);
            enrichedMap = withFunctionalityDependencies(enrichedMap, pass4.depsPerService());
            enrichedMap = withInterServiceDependencies(enrichedMap, pass4.commandsPerService());
            enrichedMap = withFunctionalMetrics(enrichedMap, pass4.allFunctionalities());

            // Pass 5: controller endpoint + Locust task linking
            Map<String, String> endpointMap5 = collectEndpointFunctionalityMap(serviceDirMap);
            Map<String, String> locustMap5   = collectLocustTaskMap();
            List<FunctionalityInfo> enrichedFuncs = enrichWithEndpoints(
                    pass4.allFunctionalities(), endpointMap5, locustMap5);

            StaticMetrics metrics = StructuralSignals.enrich(StaticMetrics.builder()
                    .services(enrichedMap)
                    .m1(computeM1(enrichedMap))
                    .m2(computeM2(enrichedMap))
                    .m1Functional(computeM1Functional(enrichedMap))
                    .m2Functional(computeM2Functional(enrichedMap))
                    .serviceCount(enrichedMap.size())
                    .sourceDir(msDir.toAbsolutePath().toString())
                    .functionalities(enrichedFuncs)
                    .eventCatalogue(eventCatalogue(msDir))
                    .build());
            log.debug("Static analysis [current]: M1={}, M2={}, M1f={}, M2f={}, services={}, functionalities={}",
                    metrics.getM1(), metrics.getM2(),
                    metrics.getM1Functional(), metrics.getM2Functional(),
                    metrics.getServiceCount(), pass4.allFunctionalities().size());
            return VersionAnalysisResult.builder()
                    .version(CURRENT_VERSION)
                    .path(msDir.toAbsolutePath().toString())
                    .metrics(metrics)
                    .build();
        } catch (IOException e) {
            log.warn("Static analysis [current]: failed to scan filesystem: {}", e.getMessage());
            return emptyResult(CURRENT_VERSION);
        }
    }

    /**
     * Pass 1 (filesystem): reads entity files from aggregate/ (base only for entity counting),
     * then appends contents of aggregate/sagas/ and aggregate/causal/ for cross-service reference
     * scanning only (Gap 2 fix — sagas/causal classes are not counted as entities).
     */
    private PartialServiceData collectEntityDataFromDirectory(String serviceName, Path serviceDir) {
        Path aggregateDir = serviceDir.resolve("aggregate");
        if (!Files.isDirectory(aggregateDir)) {
            log.debug("Service {} [current]: no aggregate/ directory", serviceName);
            return PartialServiceData.empty(serviceName);
        }
        try {
            List<String> entityNames     = new ArrayList<>();
            Set<String> rootNames        = new LinkedHashSet<>();
            int internalRelations        = 0;
            Map<String, String> contents = new LinkedHashMap<>();

            warnOnNestedAggregatePackages(serviceName, aggregateDir);

            // Base entities (counted toward Nᵢ)
            try (Stream<Path> files = Files.list(aggregateDir)) {
                List<Path> javaFiles = files
                        .filter(p -> !Files.isDirectory(p) && p.getFileName().toString().endsWith(".java"))
                        .collect(Collectors.toList());
                for (Path f : javaFiles) {
                    String className = stripJavaSuffix(f.getFileName().toString());
                    if (isExcluded(className)) continue;
                    String content = Files.readString(f, StandardCharsets.UTF_8);
                    if (ABSTRACT_CLASS.matcher(content).find() && !content.contains("@Entity")) continue;
                    if (NON_ENTITY_ANNOTATION.matcher(content).find()
                            && !content.contains("@Entity")) continue;
                    if (isNonClassDeclaration(className, content)) continue;

                    entityNames.add(className);
                    if (AGGREGATE_ROOT.matcher(content).find()) rootNames.add(className);
                    internalRelations += (int) JPA_RELATION.matcher(content).results().count();
                    contents.put(className, content);
                }
            }

            // Deferred until entityNames is complete: telling a member entity from a root scalar
            // needs the full entity set, which is only known once every file has been read.
            List<InvariantInfo> invariants = extractInvariants(contents, entityNames);
            Map<String, List<String>> compositions = extractCompositions(contents, entityNames);
            Map<String, List<String>> singleValued = extractSingleValuedMembers(contents, entityNames);

            // Active-model variants: scan content only, NOT counted as entities (Gap 2 fix)
            for (String subDir : ACTIVE_MODEL_DIRS) {
                Path variantDir = aggregateDir.resolve(subDir);
                if (!Files.isDirectory(variantDir)) continue;
                try (Stream<Path> files = Files.list(variantDir)) {
                    files.filter(p -> p.getFileName().toString().endsWith(".java"))
                         .forEach(f -> {
                             String className = stripJavaSuffix(f.getFileName().toString());
                             try {
                                 String content = Files.readString(f, StandardCharsets.UTF_8);
                                 contents.put(className, content);
                             } catch (IOException e) {
                                 log.warn("Service {} [current]: error reading {}: {}",
                                         serviceName, f.getFileName(), e.getMessage());
                             }
                         });
                }
            }

            log.debug("Service {} [current]: N={}, μ={}, roots={}, invariants={}, contentFiles={}",
                    serviceName, entityNames.size(), internalRelations, rootNames.size(),
                    invariants.size(), contents.size());
            return new PartialServiceData(serviceName, entityNames, internalRelations, invariants,
                    compositions, contents, rootNames, singleValued);

        } catch (IOException e) {
            log.warn("Service {} [current]: error reading files: {}", serviceName, e.getMessage());
            return PartialServiceData.empty(serviceName);
        }
    }

    /**
     * Entities are discovered in {@code aggregate/} one level deep only. A root scaffolded into a
     * nested package — which {@code refactor-split-aggregate} could do when it creates a satellite
     * aggregate — would be invisible to both Nᵢ and {@link AggregateReferenceResolver}, silently
     * understating the metric. Detection only; nothing recurses.
     */
    private void warnOnNestedAggregatePackages(String serviceName, Path aggregateDir) {
        try (Stream<Path> dirs = Files.list(aggregateDir)) {
            dirs.filter(Files::isDirectory)
                .filter(d -> !ACTIVE_MODEL_DIRS.contains(d.getFileName().toString()))
                .forEach(d -> {
                    try (Stream<Path> nested = Files.list(d)) {
                        long javaFiles = nested.filter(p -> p.getFileName().toString().endsWith(".java")).count();
                        if (javaFiles > 0) {
                            log.warn("[static] {}: aggregate/{}/ holds {} java file(s) not counted as "
                                    + "entities — aggregate roots must sit directly in aggregate/",
                                    serviceName, d.getFileName(), javaFiles);
                        }
                    } catch (IOException ignored) {
                        // A directory we cannot list is not evidence of a miscount; stay quiet.
                    }
                });
        } catch (IOException ignored) {
            // aggregateDir was listable a moment ago; a transient failure here is not worth a warning.
        }
    }

    // ─── Git helpers ──────────────────────────────────────────────────────────

    private List<String> listGitTags() {
        File gitDir = new File(gitRepoPath, ".git");
        if (!gitDir.isDirectory()) {
            log.warn("Static analysis: .git not found at {} — no git-tagged versions available",
                    gitDir.getAbsolutePath());
            return Collections.emptyList();
        }
        try (Repository repo = new FileRepositoryBuilder().setGitDir(gitDir).build();
             Git git = new Git(repo)) {
            return git.tagList().call().stream()
                    .map(ref -> Repository.shortenRefName(ref.getName()))
                    .filter(name -> name.matches("v\\d+"))
                    .sorted(Comparator.comparingInt(name -> Integer.parseInt(name.substring(1))))
                    .collect(Collectors.toList());
        } catch (IOException | GitAPIException e) {
            log.warn("Static analysis: failed to list git tags: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private ObjectId resolveTagToCommit(Repository repo, String tag) throws IOException {
        Ref ref = repo.exactRef("refs/tags/" + tag);
        if (ref == null) return null;
        Ref peeled = repo.getRefDatabase().peel(ref);
        ObjectId peeledId = peeled.getPeeledObjectId();
        return peeledId != null ? peeledId : ref.getObjectId();
    }

    // ─── Git commit analysis ──────────────────────────────────────────────────

    private StaticMetrics analyzeCommit(Repository repo, ObjectId commitId, String tag) {
        String msTreePath = SOURCE_TREE_PREFIX + "/" + MICROSERVICES_SUBPATH;
        try (RevWalk revWalk = new RevWalk(repo)) {
            RevCommit commit = revWalk.parseCommit(commitId);
            RevTree rootTree = commit.getTree();

            ObjectId msTreeId = resolveTreePath(repo, rootTree, msTreePath);
            if (msTreeId == null) {
                log.warn("Static analysis [{}]: microservices path not found in tree", tag);
                return zeroStaticMetrics("git:" + tag);
            }

            Map<String, ObjectId> serviceTrees = listSubtrees(repo, msTreeId);
            if (serviceTrees.isEmpty()) {
                log.warn("Static analysis [{}]: no service directories found", tag);
                return zeroStaticMetrics("git:" + tag);
            }

            // Pass 1: collect entity metadata and blob contents per service
            Map<String, PartialServiceData> partials = new LinkedHashMap<>();
            for (Map.Entry<String, ObjectId> entry : serviceTrees.entrySet()) {
                partials.put(entry.getKey(),
                        collectEntityDataFromTree(repo, entry.getKey(), entry.getValue()));
            }

            // Remove non-service directories (no entities found = no aggregate/ subdirectory)
            partials.entrySet().removeIf(e -> e.getValue().entityNames().isEmpty());
            serviceTrees.keySet().retainAll(partials.keySet());

            // Passes 2 and 3 share one reference index so unresolved prefixes are reported once
            AggregateReferenceResolver resolver = buildReferenceResolver(partials);

            // Pass 2: detect cross-service references
            Map<String, ServiceStaticMetrics> serviceMap = enrichCrossServiceRefs(partials, resolver);

            // Pass 3: event subscription scan
            Set<String> knownServices = partials.keySet();
            Map<String, Map<String, List<String>>> eventSubsMap = new LinkedHashMap<>();
            Map<String, Map<String, SubscriptionScan>> subsByClass = new LinkedHashMap<>();
            for (Map.Entry<String, ObjectId> entry : serviceTrees.entrySet()) {
                SubscriptionScanResult subs = collectEventSubscriptionsFromTree(
                        repo, entry.getKey(), entry.getValue(), resolver);
                if (!subs.byPublisher().isEmpty()) eventSubsMap.put(entry.getKey(), subs.byPublisher());
                if (!subs.byClass().isEmpty()) subsByClass.put(entry.getKey(), subs.byClass());
            }
            logUnresolvedPrefixes(tag, resolver);

            Map<String, String> serviceSources = new LinkedHashMap<>();
            Map<String, Map<String, String>> sourcesPerService = new LinkedHashMap<>();
            serviceTrees.forEach((name, tree) ->
                    sourcesPerService.put(name, collectServiceSourcesFromTree(repo, name, tree)));
            sourcesPerService.values().forEach(serviceSources::putAll);
            Map<String, String> serviceGuards = serviceGuardClaims(serviceSources);

            Map<String, ServiceStaticMetrics> enrichedMap = withM2Contributions(serviceMap);
            enrichedMap = withEventSubscriptions(enrichedMap, eventSubsMap);
            // Pass 3b: see the filesystem variant.
            enrichedMap = withInterInvariants(enrichedMap, partials, subsByClass, serviceGuards);
            enrichedMap = withPublishedEvents(enrichedMap, sourcesPerService);
            Map<String, Map<String, String>> handlersPerService = new LinkedHashMap<>();
            serviceTrees.forEach((name, tree) -> handlersPerService.put(
                    name, collectSourcesFromTree(repo, name, tree, "messaging")));
            enrichedMap = withHandledCommands(enrichedMap, handlersPerService);
            // Creation scope, for the ownership classifier: raw POST paths, variable names intact.
            enrichedMap = withPostEndpoints(enrichedMap, collectPostEndpointsFromTree(repo, serviceTrees));

            // Pass 4: Functionality mapper (git variant)
            FunctionalEntityResolver entityResolver = functionalEntityResolver(enrichedMap, sourcesPerService);
            FunctionalityPass4Result pass4 = collectFunctionalityDepsFromTree(
                    repo, serviceTrees, knownServices, entityResolver);
            enrichedMap = withFunctionalityDependencies(enrichedMap, pass4.depsPerService());
            enrichedMap = withInterServiceDependencies(enrichedMap, pass4.commandsPerService());
            enrichedMap = withFunctionalMetrics(enrichedMap, pass4.allFunctionalities());

            // Pass 5: controller endpoint (git) + Locust task linking (filesystem — scripts don't change between commits)
            Map<String, String> endpointMap5 = collectEndpointFunctionalityMapFromTree(repo, serviceTrees);
            Map<String, String> locustMap5   = collectLocustTaskMap();
            List<FunctionalityInfo> enrichedFuncs = enrichWithEndpoints(
                    pass4.allFunctionalities(), endpointMap5, locustMap5);

            return StructuralSignals.enrich(StaticMetrics.builder()
                    .services(enrichedMap)
                    .m1(computeM1(enrichedMap))
                    .m2(computeM2(enrichedMap))
                    .m1Functional(computeM1Functional(enrichedMap))
                    .m2Functional(computeM2Functional(enrichedMap))
                    .serviceCount(enrichedMap.size())
                    .sourceDir("git:" + tag)
                    .functionalities(enrichedFuncs)
                    .eventCatalogue(eventCatalogueFromTree(repo, rootTree))
                    .build());
        } catch (IOException e) {
            log.warn("Static analysis [{}]: error reading git tree: {}", tag, e.getMessage());
            return zeroStaticMetrics("git:" + tag);
        }
    }

    private ObjectId resolveTreePath(Repository repo, RevTree rootTree, String path) throws IOException {
        try (TreeWalk tw = TreeWalk.forPath(repo, path, rootTree)) {
            if (tw == null) return null;
            return tw.getObjectId(0);
        }
    }

    private Map<String, ObjectId> listSubtrees(Repository repo, ObjectId treeId) throws IOException {
        Map<String, ObjectId> result = new LinkedHashMap<>();
        try (TreeWalk tw = new TreeWalk(repo)) {
            tw.addTree(treeId);
            tw.setRecursive(false);
            while (tw.next()) {
                if (tw.isSubtree()) {
                    result.put(tw.getNameString(), tw.getObjectId(0));
                }
            }
        }
        return result;
    }

    /**
     * Pass 1 (git): reads entity blobs from aggregate/ tree; also reads sagas/ and causal/
     * subtree blobs for content-only scanning (Gap 2 fix).
     */
    private PartialServiceData collectEntityDataFromTree(Repository repo, String serviceName,
                                                          ObjectId serviceTreeId) {
        try {
            ObjectId aggregateTreeId = findDirectSubtree(repo, serviceTreeId, "aggregate");
            if (aggregateTreeId == null) {
                log.debug("Service {}: no aggregate/ directory in tree", serviceName);
                return PartialServiceData.empty(serviceName);
            }

            List<String> entityNames     = new ArrayList<>();
            Set<String> rootNames        = new LinkedHashSet<>();
            int internalRelations        = 0;
            Map<String, String> contents = new LinkedHashMap<>();

            // Base entities (counted toward Nᵢ)
            Map<String, ObjectId> javaBlobs = listDirectJavaBlobs(repo, aggregateTreeId);
            for (Map.Entry<String, ObjectId> entry : javaBlobs.entrySet()) {
                String className = stripJavaSuffix(entry.getKey());
                if (isExcluded(className)) continue;
                String content = readBlob(repo, entry.getValue());
                if (content == null) continue;
                if (ABSTRACT_CLASS.matcher(content).find() && !content.contains("@Entity")) continue;
                if (NON_ENTITY_ANNOTATION.matcher(content).find()
                        && !content.contains("@Entity")) continue;
                if (isNonClassDeclaration(className, content)) continue;

                entityNames.add(className);
                if (AGGREGATE_ROOT.matcher(content).find()) rootNames.add(className);
                internalRelations += (int) JPA_RELATION.matcher(content).results().count();
                contents.put(className, content);
            }

            List<InvariantInfo> invariants = extractInvariants(contents, entityNames);
            Map<String, List<String>> compositions = extractCompositions(contents, entityNames);
            Map<String, List<String>> singleValued = extractSingleValuedMembers(contents, entityNames);

            // Active-model variants: content only (Gap 2 fix)
            for (String subDir : ACTIVE_MODEL_DIRS) {
                ObjectId variantTreeId = findDirectSubtree(repo, aggregateTreeId, subDir);
                if (variantTreeId == null) continue;
                Map<String, ObjectId> variantBlobs = listDirectJavaBlobs(repo, variantTreeId);
                for (Map.Entry<String, ObjectId> entry : variantBlobs.entrySet()) {
                    String className = stripJavaSuffix(entry.getKey());
                    String content = readBlob(repo, entry.getValue());
                    if (content != null) contents.put(className, content);
                }
            }

            log.debug("Service {}: N={}, μ={}, roots={}, invariants={}, contentFiles={}",
                    serviceName, entityNames.size(), internalRelations, rootNames.size(),
                    invariants.size(), contents.size());
            return new PartialServiceData(serviceName, entityNames, internalRelations, invariants,
                    compositions, contents, rootNames, singleValued);

        } catch (IOException e) {
            log.warn("Service {}: error reading tree: {}", serviceName, e.getMessage());
            return PartialServiceData.empty(serviceName);
        }
    }

    private ObjectId findDirectSubtree(Repository repo, ObjectId treeId, String name) throws IOException {
        try (TreeWalk tw = new TreeWalk(repo)) {
            tw.addTree(treeId);
            tw.setRecursive(false);
            while (tw.next()) {
                if (tw.isSubtree() && tw.getNameString().equals(name)) {
                    return tw.getObjectId(0);
                }
            }
        }
        return null;
    }

    private Map<String, ObjectId> listDirectJavaBlobs(Repository repo, ObjectId treeId) throws IOException {
        Map<String, ObjectId> result = new LinkedHashMap<>();
        try (TreeWalk tw = new TreeWalk(repo)) {
            tw.addTree(treeId);
            tw.setRecursive(false);
            while (tw.next()) {
                if (!tw.isSubtree() && tw.getNameString().endsWith(".java")) {
                    result.put(tw.getNameString(), tw.getObjectId(0));
                }
            }
        }
        return result;
    }

    private String readBlob(Repository repo, ObjectId blobId) {
        try {
            ObjectLoader loader = repo.open(blobId);
            return new String(loader.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to read blob {}: {}", blobId.name(), e.getMessage());
            return null;
        }
    }

    // ─── Pass 2: cross-service reference detection ────────────────────────────

    /**
     * Pass 2 — detects cross-service entity references.
     *
     * <p>For each entity Ea in service A, two complementary checks are applied for each
     * candidate target service B:
     *
     * <ol>
     *   <li><b>Word-boundary scan</b> (existing): searches Ea's source content for the class
     *       name of any entity in B as a standalone identifier ({@code \bClassName\b}).</li>
     *   <li><b>Field-name heuristic</b> (Gap 4 fix): extracts occurrences of
     *       {@code *AggregateId[s]?} and maps the camel-case prefix to a service via
     *       {@link AggregateReferenceResolver}. Catches {@code @ElementCollection List<Integer>}
     *       ID lists where no class name of the target service appears in the source.</li>
     * </ol>
     *
     * <p>The two are complementary and both are needed: check 1 finds references carried by a type,
     * check 2 finds references carried only by a bare {@code Integer} id.
     *
     * <p>Each entity is counted at most once per target service regardless of how many
     * matching fields or class names it contains.
     */
    private Map<String, ServiceStaticMetrics> enrichCrossServiceRefs(
            Map<String, PartialServiceData> partials, AggregateReferenceResolver resolver) {

        // Pre-compile one word-boundary pattern per entity name per service
        Map<String, Map<String, Pattern>> entityPatterns = new LinkedHashMap<>();
        for (Map.Entry<String, PartialServiceData> e : partials.entrySet()) {
            Map<String, Pattern> patterns = new LinkedHashMap<>();
            for (String entityName : e.getValue().entityNames()) {
                patterns.put(entityName,
                        Pattern.compile("\\b" + Pattern.quote(entityName) + "\\b"));
            }
            entityPatterns.put(e.getKey(), patterns);
        }

        Map<String, Map<String, List<String>>> declaredReplicas = declaredReplicas(partials);
        Map<String, ServiceStaticMetrics> result = new LinkedHashMap<>();

        for (Map.Entry<String, PartialServiceData> entryA : partials.entrySet()) {
            String serviceA = entryA.getKey();
            PartialServiceData dataA = entryA.getValue();

            Map<String, Integer>       externalRelations    = new LinkedHashMap<>();
            Map<String, List<String>>  crossServiceEntities = new LinkedHashMap<>();
            // The mirror of crossServiceEntities: that one holds A's classes, this one holds B's.
            Map<String, List<String>>  referencedForeignEntities = new LinkedHashMap<>();

            for (Map.Entry<String, String> entityEntry : dataA.contentsByClass().entrySet()) {
                String entityAName = entityEntry.getKey();
                String contentA    = entityEntry.getValue();

                // Precompute field-name hints for this entity (Gap 4 fix)
                Set<String> context = importedServices(contentA, serviceA, partials.keySet());
                Set<String> fieldHints = new HashSet<>();
                Matcher fieldMatcher = AGGREGATE_ID_FIELD.matcher(contentA);
                while (fieldMatcher.find()) {
                    String targetService = resolver.resolve(fieldMatcher.group(1), context);
                    if (targetService != null && !targetService.equals(serviceA)) {
                        fieldHints.add(targetService);
                    }
                }

                for (Map.Entry<String, PartialServiceData> entryB : partials.entrySet()) {
                    String serviceB = entryB.getKey();
                    if (serviceB.equals(serviceA)) continue;

                    // Skip if this entity already recorded as referencing serviceB
                    List<String> existing = crossServiceEntities.get(serviceB);
                    if (existing != null && existing.contains(entityAName)) continue;

                    // Check 1: word-boundary match against B's entity class names.
                    // The matched KEY is which of B's entities A names — the operand a replica would
                    // copy. Iterating values() and discarding it was why the entity had to be
                    // guessed downstream. Every match is kept, not just the first.
                    boolean refFound = false;
                    Map<String, Pattern> patternsB = entityPatterns.get(serviceB);
                    if (patternsB != null) {
                        for (Map.Entry<String, Pattern> pe : patternsB.entrySet()) {
                            if (pe.getValue().matcher(contentA).find()) {
                                refFound = true;
                                List<String> foreign = referencedForeignEntities
                                        .computeIfAbsent(serviceB, k -> new ArrayList<>());
                                if (!foreign.contains(pe.getKey())) foreign.add(pe.getKey());
                            }
                        }
                    }

                    // Check 2: field-name heuristic
                    if (!refFound) {
                        refFound = fieldHints.contains(serviceB);
                    }

                    if (refFound) {
                        externalRelations.merge(serviceB, 1, Integer::sum);
                        crossServiceEntities.computeIfAbsent(serviceB, k -> new ArrayList<>())
                                .add(entityAName);
                    }
                }
            }

            int n      = dataA.entityNames().size();
            double m1c = n > 0 ? (double) dataA.internalRelations() / ((double) n * n) : 0.0;

            log.debug("Service {} [pass2]: σ={}, crossServiceEntities={}",
                    serviceA, externalRelations, crossServiceEntities.keySet());

            result.put(serviceA, ServiceStaticMetrics.builder()
                    .serviceName(serviceA)
                    .entityCount(n)
                    .entityNames(dataA.entityNames())
                    // Already computed by both passes and, until now, only used to build the
                    // reverse root→service map. Analyze needs it to name a service's root without
                    // guessing it from the service name.
                    .rootNames(new ArrayList<>(dataA.rootNames()))
                    .invariants(dataA.invariants())
                    .compositions(dataA.compositions())
                    // The same edges with the cardinality kept: the ownership classifier needs to
                    // tell a @OneToOne member from a collection one, which compositions cannot.
                    .singleValuedMembers(dataA.singleValuedMembers().isEmpty()
                            ? null : dataA.singleValuedMembers())
                    .internalRelations(dataA.internalRelations())
                    .externalRelations(externalRelations)
                    .crossServiceEntities(crossServiceEntities.isEmpty() ? null : crossServiceEntities)
                    .referencedForeignEntities(
                            referencedForeignEntities.isEmpty() ? null : referencedForeignEntities)
                    .replicaEntities(declaredReplicas.get(serviceA))
                    .m1Contribution(m1c)
                    .build());
        }

        return result;
    }

    /**
     * Per service, the {@code @Replica} declarations of its entities: foreign root → local classes.
     *
     * <p>Read, not inferred. The previous signal — a field named {@code <prefix>AggregateId} whose
     * prefix resolved to a service — got {@code CourseExecutionStudent} wrong twice over: the scan
     * matched the {@code getParticipantAggregateId()} call inside its {@code equals}, and the
     * resolver then reached {@code tournament} through its weakest step, "the file imports exactly
     * one foreign service".
     *
     * <p>Every service maps to null when the whole version declares no replica at all — a revision
     * older than the convention. Saying "unknown" there is the point: an empty map would claim the
     * application has no replicas.
     */
    private Map<String, Map<String, List<String>>> declaredReplicas(
            Map<String, PartialServiceData> partials) {
        Map<String, Map<String, String>> contents = new LinkedHashMap<>();
        Map<String, Collection<String>> entities = new LinkedHashMap<>();
        partials.forEach((service, data) -> {
            contents.put(service, data.contentsByClass());
            entities.put(service, data.entityNames());
        });
        return declaredReplicas(contents, entities);
    }

    Map<String, Map<String, List<String>>> declaredReplicas(
            Map<String, Map<String, String>> contentsByService,
            Map<String, Collection<String>> entitiesByService) {

        Map<String, Map<String, List<String>>> perService = new LinkedHashMap<>();
        boolean anyDeclared = false;

        for (Map.Entry<String, Map<String, String>> e : contentsByService.entrySet()) {
            Set<String> entities =
                    new LinkedHashSet<>(entitiesByService.getOrDefault(e.getKey(), List.of()));
            Map<String, List<String>> byRoot = new LinkedHashMap<>();
            for (Map.Entry<String, String> cls : e.getValue().entrySet()) {
                if (!entities.contains(cls.getKey())) continue;      // Saga*/Causal* variants
                Matcher m = REPLICA_ANNOTATION.matcher(cls.getValue());
                if (!m.find()) continue;
                for (String root : QUOTED_NAME.matcher(m.group(1)).results()
                        .map(r -> r.group(1)).distinct().toList()) {
                    byRoot.computeIfAbsent(root, k -> new ArrayList<>()).add(cls.getKey());
                    anyDeclared = true;
                }
            }
            perService.put(e.getKey(), byRoot);
        }

        if (!anyDeclared) {
            log.debug("[replicas] no @Replica in this version; reporting replicas as unknown");
            contentsByService.keySet().forEach(s -> perService.put(s, null));
        }
        return perService;
    }

    // ─── Pass 3: event subscription scan ─────────────────────────────────────

    /**
     * Where a service keeps its {@code EventSubscription} subclasses, in the order tried.
     *
     * <p>The reference application puts them in {@code notification/subscribe}; this pass used to
     * look only in {@code events/subscribe}, which exists in no service, so it found nothing and
     * every event edge came back empty — {@code eventSubscriptions} and the {@code eventTypes} of
     * every {@link InterServiceDependency} alike. Both layouts are accepted because the directory is
     * a convention of the analysed project, not of the tool.
     */
    private static final List<String[]> SUBSCRIPTION_DIRS = List.of(
            new String[] {"notification", "subscribe"},
            new String[] {"events", "subscribe"});

    /**
     * What one service's subscription directory yields.
     *
     * @param byPublisher publisher service → event types, the coupling term that feeds
     *                    {@code eventSubscriptions} and {@link InterServiceDependency}
     * @param byClass     subscription class simple name → what that class subscribes to. Keyed by
     *                    class because that is how the aggregate names it: {@code new
     *                    QuizAnswerSubscribesInvalidateQuiz(…)}. Pass 3b joins on this key, which is
     *                    the only link between a rule and its transport.
     */
    private record SubscriptionScanResult(Map<String, List<String>> byPublisher,
                                          Map<String, SubscriptionScan> byClass) {
        static SubscriptionScanResult empty() {
            return new SubscriptionScanResult(new LinkedHashMap<>(), new LinkedHashMap<>());
        }
    }

    /**
     * One {@code EventSubscription} subclass, as read from its source.
     *
     * @param accessorFields decapitalised {@code get*()} names the class calls, e.g. {@code student}
     *                       from {@code quizAnswer.getStudent()}. These are the only evidence of what
     *                       state a rule ranges over when the aggregate passes {@code this} to the
     *                       subscription instead of naming a member.
     */
    record SubscriptionScan(String eventType, String publisherService,
                            List<String> accessorFields) {}

    /**
     * Pass 3 (filesystem): scans each service's subscription directory and extracts the event
     * subscriptions.
     */
    private SubscriptionScanResult collectEventSubscriptionsFromDirectory(
            String serviceName, Path serviceDir, AggregateReferenceResolver resolver) {
        SubscriptionScanResult result = SubscriptionScanResult.empty();
        for (String[] segments : SUBSCRIPTION_DIRS) {
            Path subscribeDir = serviceDir;
            for (String segment : segments) subscribeDir = subscribeDir.resolve(segment);
            if (!Files.isDirectory(subscribeDir)) continue;

            try (Stream<Path> files = Files.list(subscribeDir)) {
                files.filter(p -> p.getFileName().toString().endsWith(".java"))
                     .forEach(f -> {
                         try {
                             String content = Files.readString(f, StandardCharsets.UTF_8);
                             processSubscriptionFile(content, stripJavaSuffix(f.getFileName().toString()),
                                     serviceName, resolver, result);
                         } catch (IOException e) {
                             log.warn("Service {} [current]: error reading subscription {}: {}",
                                     serviceName, f.getFileName(), e.getMessage());
                         }
                     });
            } catch (IOException e) {
                log.warn("Service {} [current]: error listing subscribe dir: {}", serviceName, e.getMessage());
            }
        }
        return result;
    }

    /**
     * Pass 3 (git): same as filesystem variant but reads from git blobs.
     */
    private SubscriptionScanResult collectEventSubscriptionsFromTree(
            Repository repo, String serviceName, ObjectId serviceTreeId, AggregateReferenceResolver resolver) {
        SubscriptionScanResult result = SubscriptionScanResult.empty();
        try {
            for (String[] segments : SUBSCRIPTION_DIRS) {
                ObjectId treeId = serviceTreeId;
                for (String segment : segments) {
                    treeId = treeId == null ? null : findDirectSubtree(repo, treeId, segment);
                }
                if (treeId == null) continue;

                for (Map.Entry<String, ObjectId> entry : listDirectJavaBlobs(repo, treeId).entrySet()) {
                    String content = readBlob(repo, entry.getValue());
                    if (content != null) {
                        processSubscriptionFile(content, stripJavaSuffix(entry.getKey()),
                                serviceName, resolver, result);
                    }
                }
            }
            return result;
        } catch (IOException e) {
            log.warn("Service {}: error reading subscription tree: {}", serviceName, e.getMessage());
            return SubscriptionScanResult.empty();
        }
    }

    /**
     * Extracts one event subscription from an {@code EventSubscription} subclass source file.
     *
     * <p>Two patterns are matched:
     * <ul>
     *   <li>{@code .getXxxAggregateId()} — group(1) gives the capitalized publisher entity prefix
     *       (e.g. {@code Quiz}, {@code User}, {@code CourseExecution}), mapped to the publisher
     *       service by {@link AggregateReferenceResolver}.</li>
     *   <li>{@code EventName.class.getSimpleName()} — group(1) gives the event type name.</li>
     * </ul>
     *
     * <p>The class is indexed even when the publisher is unresolved or is the consumer's own
     * service, because Pass 3b still has to be able to name the rule's subscriptions. The coupling
     * map keeps its original filter, so M2 and the dependency edges are unchanged.
     */
    private void processSubscriptionFile(String content, String className, String consumerService,
            AggregateReferenceResolver resolver, SubscriptionScanResult result) {
        Matcher publisherMatcher = SUBSCRIBE_PUBLISHER.matcher(content);
        if (!publisherMatcher.find()) return;
        String publisherService = resolver.resolve(publisherMatcher.group(1),
                importedServices(content, consumerService, resolver.services()));

        Matcher eventMatcher = SUBSCRIBE_EVENT_TYPE.matcher(content);
        if (!eventMatcher.find()) return;
        String eventType = eventMatcher.group(1);

        result.byClass().put(className,
                new SubscriptionScan(eventType, publisherService, accessorFields(content)));

        if (publisherService == null || publisherService.equals(consumerService)) return;
        result.byPublisher().computeIfAbsent(publisherService, k -> new ArrayList<>()).add(eventType);
        log.debug("Service {} subscribes to {} from {}", consumerService, eventType, publisherService);
    }

    /**
     * The application's event classes, from the {@code events/} package beside {@code microservices/}.
     *
     * @param msDir the microservices directory; the events package is its sibling
     */
    private List<String> eventCatalogue(Path msDir) {
        Path events = msDir.resolveSibling("events");
        if (!Files.isDirectory(events)) {
            log.debug("[events] no events package beside {}", msDir);
            return null;
        }
        try (Stream<Path> files = Files.list(events)) {
            return files.map(p -> stripJavaSuffix(p.getFileName().toString()))
                    .filter(n -> n.endsWith("Event"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            log.warn("[events] error listing {}: {}", events, e.getMessage());
            return null;
        }
    }

    /** Same, from a git tree. */
    private List<String> eventCatalogueFromTree(Repository repo, RevTree rootTree) {
        try {
            ObjectId dir = resolveTreePath(repo, rootTree,
                    AppTargetConstants.SOURCE_TREE_PATH + "/" + AppTargetConstants.EVENTS_PACKAGE_PATH);
            if (dir == null) return null;
            return listDirectJavaBlobs(repo, dir).keySet().stream()
                    .map(this::stripJavaSuffix)
                    .filter(n -> n.endsWith("Event"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            log.warn("[events] error reading events tree: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Pass 7: the commands one service accepts, each with the service method behind it.
     *
     * <p>Read from the {@code CommandHandler}'s dispatch, which is where the seam is declared. The
     * chain matters more than the name: a refactoring has to move the {@code case}, the handler
     * method, the service method and every sender together, and recording all four is what makes a
     * half-moved seam visible without running anything.
     *
     * @param handlerSources the service's {@code messaging/} classes, by class name
     */
    List<CommandInfo> handledCommands(Map<String, String> handlerSources) {
        List<CommandInfo> commands = new ArrayList<>();
        handlerSources.forEach((className, content) -> {
            Matcher m = HANDLER_CASE.matcher(content);
            while (m.find()) {
                String handlerMethod = m.group(2);
                commands.add(CommandInfo.builder()
                        .name(m.group(1))
                        .handlerMethod(className + "." + handlerMethod)
                        .serviceMethod(delegationOf(content, handlerMethod))
                        .build());
            }
        });
        return commands;
    }

    /** {@code CourseService.getCourseById} — the first {@code *Service} call in the handler's body. */
    private String delegationOf(String content, String handlerMethod) {
        String body = methodBody(content, handlerMethod);
        if (body == null) return null;
        Matcher m = HANDLER_DELEGATION.matcher(body);
        if (!m.find()) return null;
        // The field is `courseService`; the class it names is `CourseService`.
        String field = m.group(1);
        return Character.toUpperCase(field.charAt(0)) + field.substring(1) + "." + m.group(2);
    }

    /**
     * The classes of one subdirectory of a service — {@code service/} for {@code @ServiceGuard} and
     * event publications, {@code messaging/} for the command handler.
     */
    private Map<String, String> collectSourcesFromDirectory(String serviceName, Path serviceDir,
                                                            String subdirectory) {
        Path dir = serviceDir.resolve(subdirectory);
        if (!Files.isDirectory(dir)) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".java")).forEach(f -> {
                try {
                    out.put(stripJavaSuffix(f.getFileName().toString()),
                            Files.readString(f, StandardCharsets.UTF_8));
                } catch (IOException e) {
                    log.warn("Service {} [current]: error reading {}/{}: {}",
                            serviceName, subdirectory, f.getFileName(), e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("Service {} [current]: error listing {}: {}", serviceName, subdirectory, e.getMessage());
        }
        return out;
    }

    /** Same, from a git tree. */
    private Map<String, String> collectSourcesFromTree(Repository repo, String serviceName,
                                                       ObjectId serviceTreeId, String subdirectory) {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            ObjectId dir = findDirectSubtree(repo, serviceTreeId, subdirectory);
            if (dir == null) return out;
            for (Map.Entry<String, ObjectId> entry : listDirectJavaBlobs(repo, dir).entrySet()) {
                String content = readBlob(repo, entry.getValue());
                if (content != null) out.put(stripJavaSuffix(entry.getKey()), content);
            }
        } catch (IOException e) {
            log.warn("Service {}: error reading {} tree: {}", serviceName, subdirectory, e.getMessage());
        }
        return out;
    }

    /** The service classes of one service — where {@code @ServiceGuard} lives. */
    private Map<String, String> collectServiceSourcesFromDirectory(String serviceName, Path serviceDir) {
        Path dir = serviceDir.resolve("service");
        if (!Files.isDirectory(dir)) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".java")).forEach(f -> {
                try {
                    out.put(stripJavaSuffix(f.getFileName().toString()),
                            Files.readString(f, StandardCharsets.UTF_8));
                } catch (IOException e) {
                    log.warn("Service {} [current]: error reading service class {}: {}",
                            serviceName, f.getFileName(), e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("Service {} [current]: error listing service dir: {}", serviceName, e.getMessage());
        }
        return out;
    }

    /** Same, from git blobs. */
    private Map<String, String> collectServiceSourcesFromTree(Repository repo, String serviceName,
                                                              ObjectId serviceTreeId) {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            ObjectId dir = findDirectSubtree(repo, serviceTreeId, "service");
            if (dir == null) return out;
            for (Map.Entry<String, ObjectId> entry : listDirectJavaBlobs(repo, dir).entrySet()) {
                String content = readBlob(repo, entry.getValue());
                if (content != null) out.put(stripJavaSuffix(entry.getKey()), content);
            }
        } catch (IOException e) {
            log.warn("Service {}: error reading service tree: {}", serviceName, e.getMessage());
        }
        return out;
    }

    /** Every {@code get*()}/{@code is*()} the source calls, decapitalised to a field name. */
    private List<String> accessorFields(String content) {
        return ACCESSOR_CALL.matcher(content).results()
                .map(r -> decapitalise(r.group(1)))
                .distinct()
                .toList();
    }

    /**
     * Foreign services this source file references by package, e.g.
     * {@code microservices.user.aggregate.UserDto} yields {@code user}.
     *
     * <p>Feeds the resolver's last-resort step. Deliberately derived from package references rather
     * than from a curated alias list: it is what the compiler sees, so it needs no maintenance when
     * an operation renames or dissolves a service.
     */
    private Set<String> importedServices(String content, String ownService, Set<String> knownServices) {
        Set<String> found = new LinkedHashSet<>();
        Matcher m = PACKAGE_REFERENCE.matcher(content);
        while (m.find()) {
            String service = m.group(1);
            if (!service.equals(ownService) && knownServices.contains(service)) found.add(service);
        }
        return found;
    }

    /**
     * Builds the per-version reference index shared by Pass 2 and Pass 3.
     *
     * <p>Rebuilt for every analysed version rather than cached, which is what lets a root that a
     * merge absorbed and a root that a split created both resolve in the version they appear in.
     */
    private AggregateReferenceResolver buildReferenceResolver(Map<String, PartialServiceData> partials) {
        Map<String, String> rootToService   = new LinkedHashMap<>();
        Map<String, String> entityToService = new LinkedHashMap<>();
        partials.forEach((service, data) -> {
            data.rootNames().forEach(root -> rootToService.put(root, service));
            data.entityNames().forEach(entity -> entityToService.put(entity, service));
        });
        return AggregateReferenceResolver.build(partials.keySet(), rootToService, entityToService);
    }

    /**
     * Reports id prefixes that matched no service, root or entity. Such a reference is dropped from
     * σᵢⱼ, so coupling is understated — silently, until this line. Expected residue on the reference
     * app: role-named references to User ({@code creator}, {@code student}, {@code participant}) and
     * {@code findAll} from {@code findAllAggregateIds()}.
     */
    private void logUnresolvedPrefixes(String version, AggregateReferenceResolver resolver) {
        Map<String, Integer> byContext = resolver.contextResolved();
        if (!byContext.isEmpty()) {
            log.debug("[static:{}] {} id prefix(es) resolved from the declaring file's imports "
                    + "rather than from their name: {}", version, byContext.size(), byContext);
        }
        Map<String, Integer> unresolved = resolver.unresolved();
        if (unresolved.isEmpty()) return;
        log.warn("[static:{}] {} id prefix(es) resolved to no service, root or entity — "
                + "their references are missing from σ: {}", version, unresolved.size(), unresolved);
    }

    // ─── Pass 4: Functionality mapper ────────────────────────────────────────

    /**
     * Pass 4 (filesystem): scans {@code coordination/sagas/*.java} and
     * {@code coordination/causal/*.java} for each service, extracts
     * {@code ServiceMapping.XYZ.getServiceName()} references, and counts step instantiations.
     */
    private FunctionalityPass4Result collectFunctionalityDepsFromDirectory(
            Map<String, Path> serviceDirMap, Set<String> knownServices,
            FunctionalEntityResolver entityResolver) {

        List<FunctionalityInfo> allFuncs = new ArrayList<>();
        Map<String, Map<String, List<String>>> depsPerService = new LinkedHashMap<>();
        Map<String, Map<String, CommandUsage>> commandsPerService = new LinkedHashMap<>();

        for (Map.Entry<String, Path> entry : serviceDirMap.entrySet()) {
            String serviceName = entry.getKey();
            Path serviceDir = entry.getValue();
            Path coordDir = serviceDir.resolve("coordination");
            if (!Files.isDirectory(coordDir)) continue;

            // NOTE: this used to read List.of("sagas", "tcc"). No "tcc" directory exists — the TCC
            // sources live in coordination/causal/ — so TCC deps were never collected. That
            // omission is now intentional and named: see ACTIVE_MODEL_DIRS. Do not "fix" the old
            // spelling to "causal"; that would silently start counting a model the stack never runs.
            for (String protocol : ACTIVE_MODEL_DIRS) {
                Path protoDir = coordDir.resolve(protocol);
                if (!Files.isDirectory(protoDir)) continue;
                try (Stream<Path> files = Files.list(protoDir)) {
                    files.filter(p -> p.getFileName().toString().endsWith(".java"))
                         .forEach(f -> {
                             try {
                                 String content = Files.readString(f, StandardCharsets.UTF_8);
                                 processFunctionalityFile(content,
                                         stripJavaSuffix(f.getFileName().toString()),
                                         serviceName, protocol, knownServices,
                                         allFuncs, depsPerService, commandsPerService, entityResolver);
                             } catch (IOException e) {
                                 log.warn("Pass4 {}/{}: error reading {}: {}",
                                         serviceName, protocol, f.getFileName(), e.getMessage());
                             }
                         });
                } catch (IOException e) {
                    log.warn("Pass4 {}: error listing coordination/{}/: {}",
                            serviceName, protocol, e.getMessage());
                }
            }
        }
        log.debug("Pass4 [current]: {} functionalities found", allFuncs.size());
        return new FunctionalityPass4Result(allFuncs, depsPerService, commandsPerService);
    }

    /**
     * Pass 4 (git): same as filesystem variant but reads from git blobs.
     */
    private FunctionalityPass4Result collectFunctionalityDepsFromTree(
            Repository repo, Map<String, ObjectId> serviceTrees, Set<String> knownServices,
            FunctionalEntityResolver entityResolver) {

        List<FunctionalityInfo> allFuncs = new ArrayList<>();
        Map<String, Map<String, List<String>>> depsPerService = new LinkedHashMap<>();
        Map<String, Map<String, CommandUsage>> commandsPerService = new LinkedHashMap<>();

        for (Map.Entry<String, ObjectId> entry : serviceTrees.entrySet()) {
            String serviceName = entry.getKey();
            ObjectId serviceTreeId = entry.getValue();
            try {
                ObjectId coordTreeId = findDirectSubtree(repo, serviceTreeId, "coordination");
                if (coordTreeId == null) continue;

                for (String protocol : ACTIVE_MODEL_DIRS) {
                    ObjectId protoTreeId = findDirectSubtree(repo, coordTreeId, protocol);
                    if (protoTreeId == null) continue;
                    Map<String, ObjectId> blobs = listDirectJavaBlobs(repo, protoTreeId);
                    for (Map.Entry<String, ObjectId> blobEntry : blobs.entrySet()) {
                        String content = readBlob(repo, blobEntry.getValue());
                        if (content == null) continue;
                        processFunctionalityFile(content,
                                stripJavaSuffix(blobEntry.getKey()),
                                serviceName, protocol, knownServices,
                                allFuncs, depsPerService, commandsPerService, entityResolver);
                    }
                }
            } catch (IOException e) {
                log.warn("Pass4 {}: error reading git tree: {}", serviceName, e.getMessage());
            }
        }
        log.debug("Pass4 [git]: {} functionalities found", allFuncs.size());
        return new FunctionalityPass4Result(allFuncs, depsPerService, commandsPerService);
    }

    /**
     * Shared helper: extracts one Functionality's inter-service deps from its source content.
     * Enum names from {@code ServiceMapping} are lowercased to match service directory names
     * (e.g., {@code QUIZ} → {@code "quiz"}, {@code EXECUTION} → {@code "execution"}).
     */
    private void processFunctionalityFile(String content, String className,
            String serviceName, String protocol, Set<String> knownServices,
            List<FunctionalityInfo> allFuncs,
            Map<String, Map<String, List<String>>> depsPerService,
            Map<String, Map<String, CommandUsage>> commandsPerService,
            FunctionalEntityResolver entityResolver) {

        Set<String> touched = new LinkedHashSet<>();
        Matcher m = SERVICE_MAPPING_REF.matcher(content);
        while (m.find()) {
            String svc = m.group(1).toLowerCase();
            if (knownServices.contains(svc)) touched.add(svc);
        }
        if (touched.isEmpty()) return;

        List<FunctionalityInfo.SentCommand> sent =
                captureCommands(content, className, serviceName, knownServices, commandsPerService);

        int steps = (int) STEP_INSTANTIATION.matcher(content).results().count();

        WorkflowParse workflow = captureSteps(content, commandSites(content, knownServices), sent);
        if (workflow.steps().size() != steps) {
            log.warn("Pass4b {}/{}: {} of {} step constructions parsed",
                    serviceName, className, workflow.steps().size(), steps);
        }
        if (!workflow.loose().isEmpty()) {
            log.warn("Pass4b {}/{}: {} command site(s) belong to no step",
                    serviceName, className, workflow.loose().size());
        }

        // Pass 4c: the entities behind the services, for functional cohesion.
        Map<String, List<String>> touchedEntities = entityResolver == null
                ? Map.of() : entityResolver.touchedEntities(content, serviceName, sent);

        allFuncs.add(FunctionalityInfo.builder()
                .name(className)
                .ownerService(serviceName)
                .protocol(protocol)
                .touchedServices(new ArrayList<>(touched))
                .stepCount(steps)
                .touchedEntities(touchedEntities.isEmpty() ? null : touchedEntities)
                .commands(sent.isEmpty() ? null : sent)
                .steps(workflow.steps().isEmpty() ? null : workflow.steps())
                .looseCommands(workflow.loose().isEmpty() ? null : workflow.loose())
                .build());

        Map<String, List<String>> serviceDeps =
                depsPerService.computeIfAbsent(serviceName, k -> new LinkedHashMap<>());
        for (String target : touched) {
            if (!target.equals(serviceName)) {
                serviceDeps.computeIfAbsent(target, k -> new ArrayList<>()).add(className);
            }
        }
        log.debug("Pass4 {}/{}: {} touches {}", serviceName, className, protocol, touched);
    }

    /**
     * Records which commands this Functionality sends to each other service, split read vs write.
     *
     * <p>The lock is the primary signal and the command's verb only the fallback, because the verb
     * lies: {@code GetAndOrCreateCourseRemoteCommand} reads <i>and</i> writes. Sagas carry
     * {@code setSemanticLock(...)} on most steps; the fallback covers the ones that do not.
     *
     * <p>A lock is attributed to the command construction it follows, up to the next construction —
     * the shape every step in this codebase uses:
     * <pre>
     * XCommand cmd = new XCommand(unitOfWork, ServiceMapping.Y.getServiceName(), id);
     * SagaCommand sagaCommand = new SagaCommand(cmd);
     * sagaCommand.setSemanticLock(YSagaState.READ_Z);
     * </pre>
     */
    List<FunctionalityInfo.SentCommand> captureCommands(String content, String className, String serviceName,
                                 Set<String> knownServices,
                                 Map<String, Map<String, CommandUsage>> commandsPerService) {

        List<CommandSite> found = commandSites(content, knownServices);
        List<int[]> sites = new ArrayList<>();          // {startIndex}
        List<String> commandClasses = new ArrayList<>();
        List<String> targets = new ArrayList<>();
        for (CommandSite site : found) {
            sites.add(new int[]{site.start()});
            commandClasses.add(site.name());
            targets.add(site.targetService());
        }
        List<FunctionalityInfo.SentCommand> sent = new ArrayList<>();
        if (sites.isEmpty()) return sent;

        // Lock sites, in source order.
        List<int[]> lockAt = new ArrayList<>();
        List<String> lockState = new ArrayList<>();
        Matcher lm = SEMANTIC_LOCK.matcher(content);
        while (lm.find()) {
            lockAt.add(new int[]{lm.start()});
            lockState.add(lm.group(1));
        }

        for (int i = 0; i < sites.size(); i++) {
            int from = sites.get(i)[0];
            int to = i + 1 < sites.size() ? sites.get(i + 1)[0] : content.length();

            String lock = null;
            for (int j = 0; j < lockAt.size(); j++) {
                int at = lockAt.get(j)[0];
                if (at > from && at < to) { lock = lockState.get(j); break; }
            }

            Boolean read = classifyCommand(commandClasses.get(i), lock);
            // The per-functionality list keeps the compensation call too: a refactoring has to
            // re-point that construction site like any other, and `access` says it is one.
            sent.add(FunctionalityInfo.SentCommand.builder()
                    .name(commandClasses.get(i)).targetService(targets.get(i))
                    .access(read == null ? null : read ? "READ" : "WRITE")
                    .build());
            if (read == null) continue;   // compensation — neither a read nor a write of the entity
            // A service calling itself is a real call site, but not inter-service coupling: it is
            // kept on the functionality above and left out of the M2-facing map here.
            if (targets.get(i).equals(serviceName)) continue;

            CommandUsage usage = commandsPerService
                    .computeIfAbsent(serviceName, k -> new LinkedHashMap<>())
                    .computeIfAbsent(targets.get(i), k -> new CommandUsage());
            (read ? usage.reads() : usage.writes()).add(commandClasses.get(i));
        }
        log.debug("Pass4 {}/{}: commands {}", serviceName, className,
                commandsPerService.getOrDefault(serviceName, Map.of()));
        return sent;
    }

    /**
     * @return {@code TRUE} read, {@code FALSE} write, {@code null} when the call is saga
     *         compensation and says nothing about how the entity is used
     */
    Boolean classifyCommand(String commandClass, String lock) {
        if (lock != null) {
            if (lock.equals("NOT_IN_SAGA")) return null;   // compensation
            if (lock.startsWith("READ")) return Boolean.TRUE;
            if (lock.startsWith("IN_")) return Boolean.FALSE;
        }
        // No lock declared; fall back to the verb, which is right except for the
        // GetAndOrCreate… family — and those do carry an IN_* lock.
        return commandClass.startsWith("Get") || commandClass.startsWith("Find");
    }

    // ─── Pass 4b: the workflow, parsed into steps ───────────────────────────────

    /** One command construction, with the offset that says which step owns it. */
    record CommandSite(String name, String targetService, int start) {}

    /** The parse of one {@code buildWorkflow()}: its steps, plus whatever fell outside all of them. */
    record WorkflowParse(List<FunctionalityInfo.WorkflowStep> steps,
                         List<FunctionalityInfo.SentCommand> loose) {}

    /**
     * Every typed command construction that names its target service, in source order.
     *
     * <p>Split out of {@link #captureCommands} so the step parser can place each site by offset
     * rather than scanning again: the flat list and the per-step lists are then the same set of
     * sites, partitioned, and cannot drift apart.
     */
    List<CommandSite> commandSites(String content, Set<String> knownServices) {
        List<CommandSite> sites = new ArrayList<>();
        Matcher cm = COMMAND_TO_SERVICE.matcher(content);
        while (cm.find()) {
            String target = cm.group(2).toLowerCase();
            if (!knownServices.contains(target)) continue;
            sites.add(new CommandSite(cm.group(1), target, cm.start()));
        }
        return sites;
    }

    /**
     * Breaks a workflow into its steps and hands each the commands constructed inside it.
     *
     * <p>Declaration order is <b>not</b> execution order: {@code SagaWorkflow.planOrder()} sorts the
     * dependency DAG topologically, so two steps with no edge between them run in an order nothing
     * fixes. That is why each step carries its {@code dependsOn} and a wave number rather than just
     * a position — a refactoring may reorder within a wave and may not across one.
     *
     * @param sent the classified commands from {@link #captureCommands}, in the same order as
     *             {@code sites}: they are two views of one scan, zipped here by index
     */
    WorkflowParse captureSteps(String content, List<CommandSite> sites,
                               List<FunctionalityInfo.SentCommand> sent) {

        List<StepParse> parsed = new ArrayList<>();
        Map<String, StepParse> byVariable = new LinkedHashMap<>();

        Matcher sm = STEP_DECLARATION.matcher(content);
        while (sm.find()) {
            int open = sm.end() - 1;                    // the '{' of the lambda
            int close = matchBrace(content, open);
            if (close < 0) {
                log.warn("[steps] unbalanced braces in step {}; skipped", sm.group(1));
                continue;
            }
            StepParse step = new StepParse(sm.group(1), open, close);
            step.variable = assignedVariable(content, sm.start());
            step.dependsOn = dependencyNames(content, close);
            parsed.add(step);
            if (step.variable != null) byVariable.put(step.variable, step);
        }
        if (parsed.isEmpty()) return new WorkflowParse(List.of(), List.of());

        // Compensations, attached by the variable they are called on.
        Matcher km = COMPENSATION.matcher(content);
        while (km.find()) {
            int open = km.end() - 1;
            int close = matchBrace(content, open);
            StepParse owner = byVariable.get(km.group(1));
            if (close < 0 || owner == null) {
                log.warn("[steps] compensation on {} could not be placed", km.group(1));
                continue;
            }
            owner.compensationFrom = open;
            owner.compensationTo = close;
        }

        Set<String> registered = new LinkedHashSet<>();
        Matcher am = ADD_STEP.matcher(content);
        while (am.find()) registered.add(am.group(1));

        // Place every typed command site by offset. Compensations are checked first so a site inside
        // one is never claimed by an enclosing step, whatever the nesting.
        List<FunctionalityInfo.SentCommand> loose = new ArrayList<>();
        for (int i = 0; i < sites.size() && i < sent.size(); i++) {
            int at = sites.get(i).start();
            StepParse owner = parsed.stream().filter(s -> s.inCompensation(at)).findFirst()
                    .orElse(null);
            if (owner != null) { owner.compensation.add(sent.get(i)); continue; }
            owner = parsed.stream().filter(s -> s.inBody(at)).findFirst().orElse(null);
            if (owner != null) owner.commands.add(sent.get(i)); else loose.add(sent.get(i));
        }

        // The bare Command base class, inside compensation bodies only.
        for (StepParse step : parsed) {
            if (step.compensationFrom < 0) continue;
            Matcher bm = BARE_COMMAND.matcher(
                    content.substring(step.compensationFrom, step.compensationTo + 1));
            while (bm.find()) {
                step.compensation.add(FunctionalityInfo.SentCommand.builder()
                        .name("Command").targetService(bm.group(1).toLowerCase()).build());
            }
        }

        Map<String, StepParse> byName = new LinkedHashMap<>();
        parsed.forEach(s -> byName.put(s.name, s));

        List<FunctionalityInfo.WorkflowStep> steps = new ArrayList<>();
        for (int i = 0; i < parsed.size(); i++) {
            StepParse s = parsed.get(i);
            List<String> deps = s.dependsOn.stream()
                    .map(v -> byVariable.containsKey(v) ? byVariable.get(v).name : null)
                    .filter(Objects::nonNull).toList();
            if (deps.size() != s.dependsOn.size()) {
                log.warn("[steps] {} depends on a name that is not a step in this workflow: {}",
                        s.name, s.dependsOn);
            }
            steps.add(FunctionalityInfo.WorkflowStep.builder()
                    .index(i + 1)
                    .name(s.name)
                    .dependsOn(deps.isEmpty() ? null : deps)
                    .wave(wave(s.name, byName, byVariable, new LinkedHashSet<>()))
                    .registered(s.variable != null && registered.contains(s.variable))
                    .commands(s.commands.isEmpty() ? null : List.copyOf(s.commands))
                    .compensation(s.compensation.isEmpty() ? null : List.copyOf(s.compensation))
                    .build());
        }
        return new WorkflowParse(steps, loose);
    }

    /** Mutable scratch for one step while the workflow is being read. */
    private static final class StepParse {
        final String name;
        final int from;
        final int to;
        String variable;
        List<String> dependsOn = List.of();
        int compensationFrom = -1;
        int compensationTo = -1;
        final List<FunctionalityInfo.SentCommand> commands = new ArrayList<>();
        final List<FunctionalityInfo.SentCommand> compensation = new ArrayList<>();

        StepParse(String name, int from, int to) { this.name = name; this.from = from; this.to = to; }

        boolean inBody(int at) { return at > from && at < to; }
        boolean inCompensation(int at) {
            return compensationFrom >= 0 && at > compensationFrom && at < compensationTo;
        }
    }

    /**
     * Longest dependency chain behind a step. Steps sharing a wave have no order between them, which
     * is the fact a plain 1..N numbering would hide.
     */
    private int wave(String stepName, Map<String, StepParse> byName,
                     Map<String, StepParse> byVariable, Set<String> visiting) {
        StepParse s = byName.get(stepName);
        if (s == null || s.dependsOn.isEmpty()) return 0;
        if (!visiting.add(stepName)) {
            // planOrder() throws on this at runtime; here it must not become an infinite recursion.
            log.warn("[steps] cyclic dependency at {}", stepName);
            return 0;
        }
        int deepest = 0;
        for (String var : s.dependsOn) {
            StepParse dep = byVariable.get(var);
            if (dep == null) continue;
            deepest = Math.max(deepest, 1 + wave(dep.name, byName, byVariable, visiting));
        }
        visiting.remove(stepName);
        return deepest;
    }

    /** The variable a step declaration is assigned to, read from the text just before it. */
    private String assignedVariable(String content, int declarationStart) {
        int from = Math.max(0, declarationStart - 120);
        Matcher m = STEP_ASSIGNMENT.matcher(content.substring(from, declarationStart));
        return m.find() ? m.group(1) : null;
    }

    /** The dependency variables named after a step's lambda, up to the end of that statement. */
    private List<String> dependencyNames(String content, int bodyEnd) {
        int limit = Math.min(content.length(), bodyEnd + 400);
        String tail = content.substring(bodyEnd, limit);
        int semicolon = tail.indexOf(';');
        if (semicolon >= 0) tail = tail.substring(0, semicolon);
        Matcher m = DEPENDENCY_LIST.matcher(tail);
        if (!m.find()) return List.of();
        return Arrays.stream(m.group(1).split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /**
     * The index of the brace closing the one at {@code openIndex}, skipping string and char literals
     * and comments.
     *
     * <p>The skipping is not defensive padding: a {@code "}"} inside a literal would otherwise end
     * the body early and hand the rest of the step's commands to nobody.
     *
     * @return the closing index, or -1 if the braces do not balance
     */
    static int matchBrace(String s, int openIndex) {
        int depth = 0;
        for (int i = openIndex; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\'') {
                i = skipLiteral(s, i, c);
            } else if (c == '/' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                if (next == '/') {
                    int nl = s.indexOf('\n', i);
                    i = nl < 0 ? s.length() : nl;
                } else if (next == '*') {
                    int end = s.indexOf("*/", i + 2);
                    i = end < 0 ? s.length() : end + 1;
                }
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                if (--depth == 0) return i;
            }
        }
        return -1;
    }

    /** @return the index of the literal's closing quote, or the last index if it never closes */
    private static int skipLiteral(String s, int openIndex, char quote) {
        for (int i = openIndex + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') { i++; continue; }
            if (c == quote) return i;
            if (c == '\n' && quote == '\'') return i;   // an unterminated char literal is not a literal
        }
        return s.length() - 1;
    }

    /** Merges Pass 4 functionality dependency data into the already-built metrics map. */
    private Map<String, ServiceStaticMetrics> withFunctionalityDependencies(
            Map<String, ServiceStaticMetrics> services,
            Map<String, Map<String, List<String>>> depsPerService) {
        if (depsPerService.isEmpty()) return services;
        Map<String, ServiceStaticMetrics> result = new LinkedHashMap<>();
        for (Map.Entry<String, ServiceStaticMetrics> entry : services.entrySet()) {
            String name = entry.getKey();
            Map<String, List<String>> deps = depsPerService.get(name);
            result.put(name, deps != null
                    ? entry.getValue().toBuilder().functionalityDependencies(deps).build()
                    : entry.getValue());
        }
        return result;
    }

    /**
     * Merge step: combines Pass 2 (externalRelations), Pass 3 (eventSubscriptions), and
     * Pass 4 (functionalityDependencies) into a single {@link InterServiceDependency} per
     * (source, target) pair, stored in {@link ServiceStaticMetrics#getInterServiceDependencies()}.
     */
    private Map<String, ServiceStaticMetrics> withInterServiceDependencies(
            Map<String, ServiceStaticMetrics> services,
            Map<String, Map<String, CommandUsage>> commandsPerService) {
        Map<String, ServiceStaticMetrics> result = new LinkedHashMap<>();
        for (ServiceStaticMetrics ssm : services.values()) {
            Set<String> allTargets = new LinkedHashSet<>();
            if (ssm.getExternalRelations() != null)         allTargets.addAll(ssm.getExternalRelations().keySet());
            if (ssm.getEventSubscriptions() != null)        allTargets.addAll(ssm.getEventSubscriptions().keySet());
            if (ssm.getFunctionalityDependencies() != null) allTargets.addAll(ssm.getFunctionalityDependencies().keySet());

            if (allTargets.isEmpty()) {
                result.put(ssm.getServiceName(), ssm);
                continue;
            }

            Map<String, InterServiceDependency> merged = new LinkedHashMap<>();
            for (String target : allTargets) {
                int structural = ssm.getExternalRelations() == null ? 0
                        : ssm.getExternalRelations().getOrDefault(target, 0);
                List<String> events = ssm.getEventSubscriptions() == null ? List.of()
                        : ssm.getEventSubscriptions().getOrDefault(target, List.of());
                List<String> funcs = ssm.getFunctionalityDependencies() == null ? List.of()
                        : ssm.getFunctionalityDependencies().getOrDefault(target, List.of());

                CommandUsage usage = commandsPerService
                        .getOrDefault(ssm.getServiceName(), Map.of()).get(target);
                List<String> reads  = usage == null ? List.of() : new ArrayList<>(usage.reads());
                List<String> writes = usage == null ? List.of() : new ArrayList<>(usage.writes());

                merged.put(target, InterServiceDependency.builder()
                        .targetService(target)
                        .structuralRefs(structural)
                        .eventTypes(events.isEmpty() ? null : events)
                        .functionalityNames(funcs.isEmpty() ? null : funcs)
                        .readCommands(reads.isEmpty() ? null : reads)
                        .writeCommands(writes.isEmpty() ? null : writes)
                        .totalWeight(structural + events.size() + funcs.size())
                        .build());
            }
            result.put(ssm.getServiceName(),
                    ssm.toBuilder().interServiceDependencies(merged).build());
        }
        return result;
    }

    // ─── Per-service M2 contribution ─────────────────────────────────────────

    private Map<String, ServiceStaticMetrics> withM2Contributions(Map<String, ServiceStaticMetrics> services) {
        List<ServiceStaticMetrics> list = new ArrayList<>(services.values());
        Map<String, Double> contrib = new HashMap<>();
        for (int i = 0; i < list.size(); i++) {
            ServiceStaticMetrics si = list.get(i);
            if (si.getEntityCount() == 0) { contrib.put(si.getServiceName(), 0.0); continue; }
            double sum = 0.0;
            for (int j = 0; j < list.size(); j++) {
                if (i == j) continue;
                ServiceStaticMetrics sj = list.get(j);
                if (sj.getEntityCount() == 0) continue;
                int sigma = si.getExternalRelations().getOrDefault(sj.getServiceName(), 0)
                          + sj.getExternalRelations().getOrDefault(si.getServiceName(), 0);
                if (sigma > 0) sum += (double) sigma / (2.0 * si.getEntityCount() * sj.getEntityCount());
            }
            contrib.put(si.getServiceName(), sum);
        }
        Map<String, ServiceStaticMetrics> result = new LinkedHashMap<>();
        for (ServiceStaticMetrics s : list) {
            result.put(s.getServiceName(),
                s.toBuilder().m2Contribution(contrib.getOrDefault(s.getServiceName(), 0.0)).build());
        }
        return result;
    }

    /** Merges Pass 3 event subscription data into the already-built metrics map. */
    private Map<String, ServiceStaticMetrics> withEventSubscriptions(
            Map<String, ServiceStaticMetrics> services,
            Map<String, Map<String, List<String>>> eventSubsMap) {
        if (eventSubsMap.isEmpty()) return services;
        Map<String, ServiceStaticMetrics> result = new LinkedHashMap<>();
        for (Map.Entry<String, ServiceStaticMetrics> entry : services.entrySet()) {
            String name = entry.getKey();
            ServiceStaticMetrics ssm = entry.getValue();
            Map<String, List<String>> subs = eventSubsMap.get(name);
            result.put(name, subs != null ? ssm.toBuilder().eventSubscriptions(subs).build() : ssm);
        }
        return result;
    }

    /** Pass 7: attaches, per service, the commands its handler accepts. */
    private Map<String, ServiceStaticMetrics> withHandledCommands(
            Map<String, ServiceStaticMetrics> services,
            Map<String, Map<String, String>> handlersPerService) {

        Map<String, ServiceStaticMetrics> result = new LinkedHashMap<>();
        services.forEach((name, ssm) -> {
            Map<String, String> handlers = handlersPerService.get(name);
            if (handlers == null || handlers.isEmpty()) {
                result.put(name, ssm);
                return;
            }
            List<CommandInfo> commands = handledCommands(handlers);
            log.debug("Service {}: handles {} command(s)", name, commands.size());
            result.put(name, ssm.toBuilder()
                    .handledCommands(commands.isEmpty() ? null : commands).build());
        });
        return result;
    }

    /** Pass 6: attaches, per service, the events its own classes register. */
    private Map<String, ServiceStaticMetrics> withPublishedEvents(
            Map<String, ServiceStaticMetrics> services,
            Map<String, Map<String, String>> sourcesPerService) {

        Map<String, ServiceStaticMetrics> result = new LinkedHashMap<>();
        services.forEach((name, ssm) -> {
            Map<String, String> sources = sourcesPerService.get(name);
            if (sources == null || sources.isEmpty()) {
                result.put(name, ssm);
                return;
            }
            Map<String, String> published = publishedEvents(sources);
            log.debug("Service {}: publishes {}", name, published.keySet());
            result.put(name, ssm.toBuilder().publishedEvents(published).build());
        });
        return result;
    }

    /**
     * Pass 3b: attaches each service's inter-invariants.
     *
     * <p>{@code interInvariants} is set even when empty, the same way {@code invariants} is: an
     * empty list means "this aggregate maintains no cross-aggregate rule", which is true of
     * {@code Course}, {@code Topic} and {@code User}, while null means the snapshot predates the
     * field. Collapsing the two would make an old snapshot look rule-less.
     */
    private Map<String, ServiceStaticMetrics> withInterInvariants(
            Map<String, ServiceStaticMetrics> services,
            Map<String, PartialServiceData> partials,
            Map<String, Map<String, SubscriptionScan>> subscriptionsByService,
            Map<String, String> serviceGuards) {

        // Only a service with exactly one root can name the aggregate at the far end of an edge;
        // with several, the service name is all that is honestly known.
        Map<String, String> rootByService = new LinkedHashMap<>();
        // The inverse, which @Replica needs: it names roots, and dependsOnServices wants services.
        Map<String, String> serviceByRoot = new LinkedHashMap<>();
        partials.forEach((name, data) -> {
            if (data.rootNames().size() == 1) rootByService.put(name, data.rootNames().iterator().next());
            data.rootNames().forEach(root -> serviceByRoot.put(root, name));
        });

        Map<String, ServiceStaticMetrics> result = new LinkedHashMap<>();
        for (Map.Entry<String, ServiceStaticMetrics> entry : services.entrySet()) {
            String name = entry.getKey();
            ServiceStaticMetrics ssm = entry.getValue();
            PartialServiceData data = partials.get(name);
            if (data == null) {
                result.put(name, ssm);
                continue;
            }
            List<InterInvariantInfo> rules = extractInterInvariants(
                    data.contentsByClass(), data.entityNames(),
                    subscriptionsByService.getOrDefault(name, Map.of()),
                    rootByService, replicaOwners(ssm, serviceByRoot), serviceGuards);
            log.debug("Service {}: inter-invariants={} over {} subscription classes",
                    name, rules.size(), subscriptionsByService.getOrDefault(name, Map.of()).size());
            result.put(name, ssm.toBuilder().interInvariants(rules).build());
        }
        return result;
    }

    /**
     * Local entity class → the foreign service it holds a copy of, from its {@code @Replica}
     * declaration.
     *
     * <p>Read from {@code replicaEntities}, not from {@code crossServiceEntities}: the latter records
     * every foreign service a class mentions, so inverting it made {@code UsersExist} depend on
     * {@code tournament} because {@code CourseExecutionStudent} names {@code TournamentParticipant} in
     * an {@code equals}. A class that copies two roots keeps both.
     */
    private Map<String, List<String>> replicaOwners(ServiceStaticMetrics sm,
                                                    Map<String, String> serviceByRoot) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (sm.getReplicaEntities() == null) return out;
        sm.getReplicaEntities().forEach((root, entities) -> {
            String service = serviceByRoot.get(root);
            if (service == null || entities == null) return;
            entities.forEach(e -> out.computeIfAbsent(e, k -> new ArrayList<>()).add(service));
        });
        return out;
    }

    // ─── M1 / M2 computation ─────────────────────────────────────────────────

    /**
     * Pass 4c input: each service's entity names, service-class sources and command surface, so a
     * workflow's commands can be followed to the entities the delegated service methods name.
     */
    private FunctionalEntityResolver functionalEntityResolver(
            Map<String, ServiceStaticMetrics> services,
            Map<String, Map<String, String>> sourcesPerService) {
        Map<String, List<String>> entities = new LinkedHashMap<>();
        Map<String, List<CommandInfo>> handled = new LinkedHashMap<>();
        services.forEach((name, ssm) -> {
            entities.put(name, ssm.getEntityNames() == null ? List.of() : ssm.getEntityNames());
            handled.put(name, ssm.getHandledCommands() == null ? List.of() : ssm.getHandledCommands());
        });
        return new FunctionalEntityResolver(entities, sourcesPerService, handled);
    }

    private double computeM1(Map<String, ServiceStaticMetrics> services) {
        int t = services.size();
        if (t == 0) return 0.0;
        double sum = services.values().stream()
                .mapToDouble(ServiceStaticMetrics::getM1Contribution).sum();
        return sum / t;
    }

    private double computeM2(Map<String, ServiceStaticMetrics> services) {
        int t = services.size();
        if (t <= 1) return 0.0;
        double denomPairs = (double) t * (t - 1) / 2.0;

        double sigmaSum = 0.0;
        List<ServiceStaticMetrics> list = new ArrayList<>(services.values());
        for (int i = 0; i < list.size(); i++) {
            for (int j = i + 1; j < list.size(); j++) {
                ServiceStaticMetrics si = list.get(i);
                ServiceStaticMetrics sj = list.get(j);
                int sigma = si.getExternalRelations().getOrDefault(sj.getServiceName(), 0)
                          + sj.getExternalRelations().getOrDefault(si.getServiceName(), 0);
                int ni = si.getEntityCount();
                int nj = sj.getEntityCount();
                if (sigma > 0 && ni > 0 && nj > 0) {
                    sigmaSum += (double) sigma / (2.0 * ni * nj);
                }
            }
        }
        return sigmaSum / denomPairs;
    }

    // ─── Functionality-graph M1 / M2 computation ─────────────────────────────

    /**
     * σ'ᵢⱼ for the functionality graph: number of Functionality classes owned by {@code ssm}
     * that call {@code target}.
     */
    private int funcSigma(ServiceStaticMetrics ssm, String target) {
        if (ssm.getFunctionalityDependencies() == null) return 0;
        List<String> funcs = ssm.getFunctionalityDependencies().get(target);
        return funcs == null ? 0 : funcs.size();
    }

    /**
     * Adds functionality-graph metrics to each service:
     * <ul>
     *   <li>{@code functionalityStepCount} — Σ stepCount for all owned Functionalities</li>
     *   <li>{@code m1FunctionalContribution} — functionalityStepCount / Nᵢ²</li>
     *   <li>{@code m2FunctionalContribution} — Σⱼ (σ'ᵢⱼ + σ'ⱼᵢ) / (2·Nᵢ·Nⱼ)</li>
     * </ul>
     * Must be called after {@link #withFunctionalityDependencies}.
     */
    private Map<String, ServiceStaticMetrics> withFunctionalMetrics(
            Map<String, ServiceStaticMetrics> services,
            List<FunctionalityInfo> allFunctionalities) {

        // Step 1: sum step counts per ownerService
        Map<String, Integer> stepSumByService = new LinkedHashMap<>();
        for (FunctionalityInfo fi : allFunctionalities) {
            stepSumByService.merge(fi.getOwnerService(), fi.getStepCount(), Integer::sum);
        }

        // Step 2: m1FunctionalContribution and functionalityStepCount per service
        Map<String, ServiceStaticMetrics> step1 = new LinkedHashMap<>();
        for (ServiceStaticMetrics ssm : services.values()) {
            int steps = stepSumByService.getOrDefault(ssm.getServiceName(), 0);
            int n = ssm.getEntityCount();
            double m1fc = (n > 0) ? (double) steps / ((double) n * n) : 0.0;
            step1.put(ssm.getServiceName(),
                    ssm.toBuilder()
                       .functionalityStepCount(steps)
                       .m1FunctionalContribution(m1fc)
                       .build());
        }

        // Step 3: m2FunctionalContribution per service
        List<ServiceStaticMetrics> list = new ArrayList<>(step1.values());
        Map<String, Double> m2fc = new LinkedHashMap<>();
        for (ServiceStaticMetrics si : list) {
            int ni = si.getEntityCount();
            if (ni == 0) { m2fc.put(si.getServiceName(), 0.0); continue; }
            double sum = 0.0;
            for (ServiceStaticMetrics sj : list) {
                if (sj.getServiceName().equals(si.getServiceName())) continue;
                int nj = sj.getEntityCount();
                if (nj == 0) continue;
                int sigma = funcSigma(si, sj.getServiceName()) + funcSigma(sj, si.getServiceName());
                if (sigma > 0) sum += (double) sigma / (2.0 * ni * nj);
            }
            m2fc.put(si.getServiceName(), sum);
        }

        Map<String, ServiceStaticMetrics> result = new LinkedHashMap<>();
        for (ServiceStaticMetrics ssm : list) {
            result.put(ssm.getServiceName(),
                    ssm.toBuilder()
                       .m2FunctionalContribution(m2fc.getOrDefault(ssm.getServiceName(), 0.0))
                       .build());
        }
        return result;
    }

    // ─── Pass 5: Endpoint → Functionality → Locust task linking ─────────────────

    /**
     * Pass 5A (filesystem): scans {@code coordination/webapi/*Controller.java} for each service.
     * Returns a map from {@code "HTTP_METHOD:normalizedPath"} to functionality base name
     * (e.g. {@code "POST:/tournaments/{id}/join"} → {@code "AddParticipant"}).
     */
    private Map<String, String> collectEndpointFunctionalityMap(Map<String, Path> serviceDirMap) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, Path> entry : serviceDirMap.entrySet()) {
            Path webapiDir = entry.getValue().resolve("coordination").resolve("webapi");
            if (!Files.isDirectory(webapiDir)) continue;
            try (Stream<Path> files = Files.list(webapiDir)) {
                files.filter(p -> p.getFileName().toString().endsWith("Controller.java"))
                     .forEach(f -> {
                         try {
                             extractControllerMappings(
                                     Files.readString(f, StandardCharsets.UTF_8), result);
                         } catch (IOException e) {
                             log.warn("Pass5A: error reading {}: {}", f.getFileName(), e.getMessage());
                         }
                     });
            } catch (IOException e) {
                log.warn("Pass5A: error listing webapi for {}: {}", entry.getKey(), e.getMessage());
            }
        }
        log.debug("Pass5A [current]: {} endpoint→functionality mappings found", result.size());
        return result;
    }

    /**
     * Pass 5A (git): same as filesystem variant but reads from git blobs.
     */
    private Map<String, String> collectEndpointFunctionalityMapFromTree(
            Repository repo, Map<String, ObjectId> serviceTrees) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, ObjectId> entry : serviceTrees.entrySet()) {
            try {
                ObjectId coordTreeId = findDirectSubtree(repo, entry.getValue(), "coordination");
                if (coordTreeId == null) continue;
                ObjectId webapiTreeId = findDirectSubtree(repo, coordTreeId, "webapi");
                if (webapiTreeId == null) continue;
                for (Map.Entry<String, ObjectId> blobEntry : listDirectJavaBlobs(repo, webapiTreeId).entrySet()) {
                    if (!blobEntry.getKey().endsWith("Controller.java")) continue;
                    String content = readBlob(repo, blobEntry.getValue());
                    if (content != null) extractControllerMappings(content, result);
                }
            } catch (IOException e) {
                log.warn("Pass5A [git]: error scanning webapi for {}: {}", entry.getKey(), e.getMessage());
            }
        }
        log.debug("Pass5A [git]: {} endpoint→functionality mappings found", result.size());
        return result;
    }

    /**
     * The raw {@code @PostMapping} paths of each service's controllers (filesystem).
     *
     * <p>Separate from {@link #collectEndpointFunctionalityMap} because that one normalises the path
     * variables away, and the variable name is the whole signal here.
     */
    private Map<String, List<String>> collectPostEndpoints(Map<String, Path> serviceDirMap) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Path> entry : serviceDirMap.entrySet()) {
            Path webapiDir = entry.getValue().resolve("coordination").resolve("webapi");
            if (!Files.isDirectory(webapiDir)) continue;
            List<String> paths = new ArrayList<>();
            try (Stream<Path> files = Files.list(webapiDir)) {
                files.filter(p -> p.getFileName().toString().endsWith("Controller.java"))
                     .forEach(f -> {
                         try {
                             extractPostPaths(Files.readString(f, StandardCharsets.UTF_8), paths);
                         } catch (IOException e) {
                             log.warn("[postEndpoints] error reading {}: {}", f.getFileName(), e.getMessage());
                         }
                     });
            } catch (IOException e) {
                log.warn("[postEndpoints] error listing webapi for {}: {}", entry.getKey(), e.getMessage());
            }
            if (!paths.isEmpty()) result.put(entry.getKey(), paths);
        }
        return result;
    }

    /** The same scan over git blobs, so version snapshots carry the field too. */
    private Map<String, List<String>> collectPostEndpointsFromTree(
            Repository repo, Map<String, ObjectId> serviceTrees) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Map.Entry<String, ObjectId> entry : serviceTrees.entrySet()) {
            List<String> paths = new ArrayList<>();
            try {
                ObjectId coordTreeId = findDirectSubtree(repo, entry.getValue(), "coordination");
                if (coordTreeId == null) continue;
                ObjectId webapiTreeId = findDirectSubtree(repo, coordTreeId, "webapi");
                if (webapiTreeId == null) continue;
                for (Map.Entry<String, ObjectId> blobEntry : listDirectJavaBlobs(repo, webapiTreeId).entrySet()) {
                    if (!blobEntry.getKey().endsWith("Controller.java")) continue;
                    String content = readBlob(repo, blobEntry.getValue());
                    if (content != null) extractPostPaths(content, paths);
                }
            } catch (IOException e) {
                log.warn("[postEndpoints, git] error scanning webapi for {}: {}",
                        entry.getKey(), e.getMessage());
            }
            if (!paths.isEmpty()) result.put(entry.getKey(), paths);
        }
        return result;
    }

    /** Package-private so the capture can be tested without a directory or a git tree. */
    void extractPostPaths(String content, List<String> into) {
        Matcher m = POST_MAPPING_RAW.matcher(content);
        while (m.find()) {
            String path = m.group(1);
            if (!into.contains(path)) into.add(path);
        }
    }

    /** Attaches, per service, the raw POST paths its controllers declare. */
    private Map<String, ServiceStaticMetrics> withPostEndpoints(
            Map<String, ServiceStaticMetrics> services,
            Map<String, List<String>> postEndpointsPerService) {

        Map<String, ServiceStaticMetrics> result = new LinkedHashMap<>();
        services.forEach((name, ssm) -> {
            List<String> paths = postEndpointsPerService.get(name);
            result.put(name, paths == null || paths.isEmpty()
                    ? ssm : ssm.toBuilder().postEndpoints(paths).build());
        });
        return result;
    }

    /** Shared helper: extracts @Mapping annotations + service method call from one controller file. */
    private void extractControllerMappings(String content, Map<String, String> result) {
        Matcher m = CONTROLLER_MAPPING.matcher(content);
        while (m.find()) {
            String httpMethod = m.group(1).toUpperCase();
            String normalizedPath = m.group(2).replaceAll("\\{[^}]+\\}", "{id}");
            String remainder = content.substring(m.end(), Math.min(m.end() + 600, content.length()));
            Matcher bodyM = SERVICE_METHOD_CALL.matcher(remainder);
            if (bodyM.find()) {
                String methodName = bodyM.group(1);
                String base = Character.toUpperCase(methodName.charAt(0)) + methodName.substring(1);
                result.putIfAbsent(httpMethod + ":" + normalizedPath, base);
            }
        }
    }

    /**
     * Pass 5B: scans Locust {@code *.py} scripts under {@code rt.monitor.locust.scripts-path}
     * and extracts {@code self.client.METHOD(path, ..., name="Task Name")} calls.
     * Returns map from {@code "HTTP_METHOD:normalizedPath"} to Locust task name.
     */
    private Map<String, String> collectLocustTaskMap() {
        Map<String, String> result = new LinkedHashMap<>();
        Path locustDir = Paths.get(gitRepoPath).resolve(locustScriptsPath);
        if (!Files.isDirectory(locustDir)) {
            log.debug("Pass5B: Locust scripts dir not found at {} — skipping", locustDir.toAbsolutePath());
            return result;
        }
        try (Stream<Path> walk = Files.walk(locustDir)) {
            walk.filter(p -> p.getFileName().toString().endsWith(".py"))
                .forEach(f -> {
                    try {
                        String content = Files.readString(f, StandardCharsets.UTF_8);
                        Matcher m = LOCUST_CLIENT_CALL.matcher(content);
                        while (m.find()) {
                            String method = m.group(1).toUpperCase();
                            String rawPath = m.group(2).replaceAll("\\{[^}]+\\}", "{id}");
                            String taskName = m.group(3);
                            result.putIfAbsent(method + ":" + rawPath, taskName);
                        }
                    } catch (IOException e) {
                        log.warn("Pass5B: error reading {}: {}", f.getFileName(), e.getMessage());
                    }
                });
        } catch (IOException e) {
            log.warn("Pass5B: error walking Locust scripts dir: {}", e.getMessage());
        }
        log.debug("Pass5B: {} locust task mappings found", result.size());
        return result;
    }

    /**
     * Enriches each {@link FunctionalityInfo} with {@code httpMethod}, {@code httpEndpoint},
     * and {@code locustTaskName} by joining the endpoint map (Pass 5A) and Locust task map (Pass 5B).
     */
    private List<FunctionalityInfo> enrichWithEndpoints(
            List<FunctionalityInfo> funcs,
            Map<String, String> endpointMap,
            Map<String, String> locustTaskMap) {
        if (endpointMap.isEmpty()) return funcs;
        // Invert endpointMap: functionalityBase → first matching endpoint key
        Map<String, String> baseToKey = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : endpointMap.entrySet()) {
            baseToKey.putIfAbsent(e.getValue(), e.getKey());
        }
        return funcs.stream().map(fi -> {
            String base = extractFunctionalityBase(fi.getName());
            String key = baseToKey.get(base);
            if (key == null) return fi;
            String[] parts = key.split(":", 2);
            String httpMethod   = parts[0];
            String httpEndpoint = parts.length > 1 ? parts[1] : null;
            String locustTaskName = locustTaskMap.get(key);
            // toBuilder, not a fresh builder: enumerating the fields by hand dropped `commands`
            // for every functionality that had an endpoint, and would drop the next field too.
            return fi.toBuilder()
                    .httpMethod(httpMethod)
                    .httpEndpoint(httpEndpoint)
                    .locustTaskName(locustTaskName)
                    .build();
        }).collect(Collectors.toList());
    }

    /**
     * Strips the Sagas suffix from a Functionality class name to get the base operation name.
     * e.g. {@code "AddParticipantFunctionalitySagas"} → {@code "AddParticipant"}.
     */
    private String extractFunctionalityBase(String className) {
        for (String suffix : List.of("FunctionalitySagas", "Functionality")) {
            if (className.endsWith(suffix)) {
                return className.substring(0, className.length() - suffix.length());
            }
        }
        return className;
    }

    private double computeM1Functional(Map<String, ServiceStaticMetrics> services) {
        int t = services.size();
        if (t == 0) return 0.0;
        return services.values().stream()
                .mapToDouble(ServiceStaticMetrics::getM1FunctionalContribution).sum() / t;
    }

    private double computeM2Functional(Map<String, ServiceStaticMetrics> services) {
        int t = services.size();
        if (t <= 1) return 0.0;
        double denomPairs = (double) t * (t - 1) / 2.0;
        double sigmaSum = 0.0;
        List<ServiceStaticMetrics> list = new ArrayList<>(services.values());
        for (int i = 0; i < list.size(); i++) {
            for (int j = i + 1; j < list.size(); j++) {
                ServiceStaticMetrics si = list.get(i), sj = list.get(j);
                int sigma = funcSigma(si, sj.getServiceName()) + funcSigma(sj, si.getServiceName());
                int ni = si.getEntityCount(), nj = sj.getEntityCount();
                if (sigma > 0 && ni > 0 && nj > 0)
                    sigmaSum += (double) sigma / (2.0 * ni * nj);
            }
        }
        return sigmaSum / denomPairs;
    }

    // ─── Misc helpers ─────────────────────────────────────────────────────────

    private String stripJavaSuffix(String filename) {
        return filename.endsWith(".java") ? filename.substring(0, filename.length() - 5) : filename;
    }

    /**
     * True when the file's top-level type is an {@code enum}, {@code interface} or annotation rather
     * than a class — {@code user/aggregate/Role.java} is {@code public enum Role}, which the suffix
     * exclusions miss. Counting it inflates Nᵢ and so shrinks every {@code 2·Nᵢ·Nⱼ} denominator in M2.
     *
     * <p>Anchored to the file's own name so a class holding a nested enum still counts.
     */
    private boolean isNonClassDeclaration(String className, String content) {
        return Pattern.compile(NON_CLASS_DECLARATION + Pattern.quote(className) + "\\b")
                .matcher(content).find();
    }

    private boolean isExcluded(String className) {
        for (String suffix : EXCLUDED_SUFFIXES) {
            if (className.endsWith(suffix)) return true;
        }
        for (String prefix : EXCLUDED_PREFIXES) {
            if (className.startsWith(prefix)) return true;
        }
        return false;
    }

    private StaticMetrics zeroStaticMetrics(String sourceDir) {
        return StaticMetrics.builder()
                .services(Collections.emptyMap())
                .m1(0.0).m2(0.0).serviceCount(0)
                .sourceDir(sourceDir)
                .build();
    }

    private VersionAnalysisResult emptyResult(String tag) {
        return VersionAnalysisResult.builder()
                .version(tag)
                .path("git:" + tag)
                .metrics(zeroStaticMetrics("git:" + tag))
                .build();
    }
}
