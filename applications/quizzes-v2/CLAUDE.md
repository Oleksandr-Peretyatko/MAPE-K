@AGENTS.md

<!-- refactor-log -->
Date: 2026-08-14T13:45:02Z
Skill: refactor-merge-aggregate
Invocation: /refactor-merge-aggregate tournament answer --project-path applications/quizzes
Result: NO_CHANGE
Summary: Dissolution Gate stopped the merge. Answer (QuizAnswer) is an independent peer aggregate, not owned by Tournament: it is created per (quiz,user) directly by students via its own controller/functionalities with no Tournament involvement, is loaded/mutated by its own aggregateId from outside tournament, and all its invariants range over execution/quiz/user/question rather than a single Tournament root. Tournament only references a QuizAnswer by id (replica) and coordinates it remotely. Merging would strip QuizAnswer's root while the vast majority of QuizAnswers belong to no tournament — behaviourally impossible. No code, compose, or config changes were made.
GATE: MergeAggregates(Tournament, QuizAnswer) not applicable — 3/3 dissolution conditions unmet
GATE: D1 not satisfied — QuizAnswer is created independently of Tournament by its own StartQuizFunctionalitySagas (src/main/java/org/anonymous/msrefactor/quizzes/microservices/answer/coordination/sagas/StartQuizFunctionalitySagas.java:39-64), whose only dependencies are Quiz (getQuizStep:43) and User (getUserStep:51) — no Tournament; it is invoked directly by external clients through QuizAnswerController and QuizAnswerFunctionalities.startQuiz (.../answer/coordination/functionalities/QuizAnswerFunctionalities.java:77). A QuizAnswer exists for every quiz a student starts, the overwhelming majority with no tournament, so it cannot become a member entity of a Tournament root.
GATE: D2 not satisfied — QuizAnswer is loaded and mutated by its own quizAnswerAggregateId from outside tournament via its own functionalities removeQuizAnswer (QuizAnswerFunctionalities.java:169) and updateUserNameInQuizAnswer (QuizAnswerFunctionalities.java:189), driven by external HTTP/events. Tournament holds only a replica reference quizAnswerAggregateId in TournamentParticipantQuizAnswer.java:12 — it references Answer by id, it does not contain Answer's root.
GATE: D3 not satisfied — QuizAnswer.verifyInvariants() is empty (.../answer/aggregate/QuizAnswer.java:75) and every consistency rule is a cross-aggregate subscription to execution, quiz, user and question (QuizAnswer.java:26-31, getEventSubscriptions:85-107) — none is Tournament. None ranges over state reachable from a single Tournament root; a standalone QuizAnswer has no Tournament root at all.
Note: No aggregate owns Answer's lifecycle — it is a genuinely independent root keyed by (quiz, user), created directly by students. Its relationship to Tournament is peer reference, not containment, so there is no alternative absorbing aggregate to redirect this merge to.
<!-- REFACTOR_RESTRICTION -->
operation: MERGE_AGGREGATES
targetService: tournament
secondaryTarget: answer
blocking: true
reason: Answer (QuizAnswer) is an independent aggregate created per quiz-and-student directly by students and used by every quiz-answering flow; Tournament merely references it by id and coordinates it remotely. An aggregate cannot absorb a peer it neither creates nor owns.
<!-- END_REFACTOR_RESTRICTION -->
<!-- end-refactor-log -->

<!-- refactor-log -->
Date: 2026-10-04T19:40:00Z
Skill: refactor-split-aggregate
Invocation: /refactor-split-aggregate tournament --core Tournament,TournamentCourseExecution,TournamentQuiz,TournamentTopic --satellite TournamentCreator,TournamentParticipant,TournamentParticipantQuizAnswer --invariants invariantDeleteWhenNoParticipants@core,invariantAnswerBeforeStart@core,invariantParticipantsEnrolledBeforeStarTime@core,invariantStartTimeBeforeEndTime@core,invariantFinalAfterStart@core,invariantCancelledFieldsAreFinal@core,invariantMustHaveOneTopic@core,invariantNumberOfQuestionsPositive@core,invariantNumberOfQuestionsAtMost30@core,invariantUniqueParticipant@satellite,invariantCreatorParticipantConsistency@satellite,invariantCreatorIsNotAnonymous@satellite --new-aggregate TournamentSatellite --project-path applications/quizzes
Result: CHANGED
Summary: Split Tournament into Tournament (core: scalars + TournamentCourseExecution, TournamentQuiz, TournamentTopic) and the new TournamentSatellite aggregate/service (TournamentCreator, TournamentParticipant, TournamentParticipantQuizAnswer), deployed as tournament-satellite-service on port 8091/grpc 9101 with its own empty tournamentsatellitedb (no data migrated). The core keeps a denormalized creatorAggregateId scalar and replicates startTime/cancelled/courseExecution onto the satellite so the membership invariants stay intra-aggregate and strong; the participant/creator event subscriptions (AnonymizeStudent, UpdateStudentName, Disenroll, AnswerQuestion) moved to the satellite with full quads, and the satellite coordinates the core (set INACTIVE on creator anonymize/remove) by command. Orchestration, controllers and routes stay on the core (case 6: endpoints reach the satellite by command, no routes moved). test-sagas: green (152 tests, 0 failures).

GATE: Tournament partitions into Tournament + TournamentSatellite (5/5) — case 6, running as case 2
GATE: P1 satisfied — core ∪ satellite covers all 7 entities, disjoint
GATE: P5 satisfied — satellite is authoritative: TournamentParticipant.enrollTime and the nested answer's answered/numberOfAnswered/numberOfCorrect are written by no synchronising event; the core keeps 3 member entities
GATE: P3 satisfied — satellite has its own aggregateId, a creation path (CreateTournamentSatelliteCommand from CreateTournament), and a command surface (12 commands)
GATE: P4 satisfied after allocation — tournamentSatellite takes port 8091/grpc 9101 (reserved, skipped: 8090); 0 route(s) move (participant endpoints kept on core and reach the satellite by command); the 2 event-trigger @PostMappings moved to the satellite keeping their paths
GATE: P2 not satisfied — straddling invariants found (case 2): invariantParticipantsEnrolledBeforeStarTime reads TournamentParticipant.enrollTime (satellite) and this.startTime (core); invariantAnswerBeforeStart reads the participant answer (satellite) and this.startTime (core); invariantDeleteWhenNoParticipants reads getState() (core) and tournamentParticipants (satellite); invariantCancelledFieldsAreFinal reads tournamentTopics (core) and tournamentParticipants (satellite) and getPrev()
GATE: P2 resolution — the two startTime-straddling rules stay intra on the satellite via a replicated startTime scalar; invariantCancelledFieldsAreFinal is duplicated on both roots (core: topics+scalars; satellite: participants+cancelled), each over its own side (getPrev() has no cross-root meaning); invariantDeleteWhenNoParticipants moved to the satellite (it owns the participants) and the core's removal cascades to the satellite which enforces it. invariants converted to inter-aggregate: 0 — the straddles were resolved by scalar replication + duplication (case 2 "copy the scalar"), not dropped.

LAYER 1 (partition): TournamentSatellite scaffolded in microservices/tournamentSatellite/ (aggregate root + Dto + Factory + Repository + CustomRepository + Saga + SagaState + Sagas factory + Sagas repository, name given TournamentSatellite, service name tournamentSatellite); entities moved: 3; invariants — 5 core, 6 satellite (incl. deleteWhenNoParticipants), 1 duplicated (cancelledFieldsAreFinal via getPrev), 2 kept intra via replicated startTime; annotations moved — @InterInvariant 4 rule-names to satellite, @Replica 3 (TournamentCreator, TournamentParticipant, TournamentParticipantQuizAnswer), @ServiceGuard 0
LAYER 2 (root split): TournamentSatellite resolved from tournamentAggregateId inside its own service (findTournamentSatelliteIdByTournamentId); event-driven ops resolve by the satellite's own subscriberAggregateId; 1 core read turned into a composition command (FindTournamentSatelliteByTournamentIdCommand); copy constructors — satellite deep-copies creator + participants, core dropped both; events keep the core's id as publisher (none newly published — tournament is a leaf); deletion: cascades via layer-3 removeSatelliteStep, gated by the satellite's invariantDeleteWhenNoParticipants; dto — compose (creator + participants), composed in FindTournamentFunctionalitySagas, 0 external readers re-pointed (CourseExecutionStudent.equals import re-pointed)
LAYER 3 (coordination): dispatch domain=broker, sagaCommit=n/a (satellite is read/written but never saga-locked — no setSemanticLock on satellite commands, so commit() sends nothing for it; spring.application.name=tournamentSatellite=lowerFirst(TournamentSatellite) so a future lock would commit in-process); steps split/added: createTournamentSatelliteStep (create + createAsync), composeSatelliteStep (find), getParticipantStep re-pointed (findParticipant), updateSatelliteScalarsStep (update), cancelSatelliteStep (cancel), removeSatelliteStep (remove), addParticipantStep now writes the satellite + verifies the core IN_UPDATE_TOURNAMENT forbidden state; commands created: 12; forbidden-state entries re-pointed: IN_UPDATE_TOURNAMENT preserved for addParticipant and anonymize via a core verify command; quad — moved 4 (AnonymizeStudent, UpdateStudentName, Disenroll, AnswerQuestion) to the satellite, created 0, gate ok (core 4/4/4/4, satellite 4/4/4/4); satellite surface — service, functionalities, 4 workflows, controller stub, notification created, notification/api created (core had one)
LAYER 4 (deployment): tournament-satellite-service created (port 8091, grpc 9101, tournamentsatellitedb created empty — no data migrated); ServiceMapping.TOURNAMENT_SATELLITE added; routes moved: none; 7 test bean(s) across 7 regions in BeanConfigurationSagas; deployment units 8 -> 9 (seven readings agree); compose config ok; sagas test profile green

Topology: Tournament split into Tournament (tournament-service, unchanged) + TournamentSatellite (tournament-satellite-service, port 8091/grpc 9101, tournamentsatellitedb created empty — no data migrated); routes moved: none (participant endpoints kept on core, reach satellite by command); ServiceMapping.TOURNAMENT_SATELLITE added; satelliteDispatch: domain=broker, sagaCommit=n/a — satellite never saga-locked
Impact: entities kept: 4; entities moved: 3; invariants — 5 @core, 6 @satellite, 0 converted to inter-aggregate, 1 duplicated (invariantCancelledFieldsAreFinal), 2 kept intra via replicated startTime (invariantParticipantsEnrolledBeforeStarTime, invariantAnswerBeforeStart); replicas re-homed: 3; replicas duplicated: 0 (startTime/cancelled/courseExecution are scalar replicas synced by command, not @Replica entities); functionalities touched: 13 core + 4 satellite; saga steps added: ~12; commands created: 12; DTOs: 1; events re-homed: 4; test beans added: 7; tests: 152 green; no references: no aggregate holds @Replica("Tournament"), no tournamentAggregateId stored outside, no service sends tournament a command (tournament is the application's only leaf)
WARNING: the two startTime-straddling invariants and invariantCancelledFieldsAreFinal rely on startTime/cancelled replicated onto the satellite and refreshed by synchronous commands (UpdateTournament/Cancel sync steps) rather than an event quad; if a future flow mutates the core's startTime/cancelled without a satellite-sync step, those satellite invariants would check a stale scalar.

<!-- REFACTOR_METRICS -->
operation: SPLIT_AGGREGATE
targetService: tournament
satellite: TournamentSatellite
satelliteService: tournamentSatellite
satellitePort: 8091
dispatchDomain: broker
dispatchSagaCommit: n/a
deploymentUnits: 8 -> 9
invariantsConverted: none (resolved via scalar replication + duplication)
invariantsDuplicated: invariantCancelledFieldsAreFinal
sagaStepsAdded: 12
errorsPre: 11
errorsPost: 12
routesPre: 14
routesPost: 14
testsSagas: green
<!-- END_REFACTOR_METRICS -->
<!-- end-refactor-log -->

<!-- refactor-log -->
Date: 2026-10-05T12:05:00Z
Skill: refactor-merge-aggregate
Invocation: /refactor-merge-aggregate execution quiz --project-path applications/quizzes
Result: CHANGED
Summary: Merged Quiz into Execution (case 1, ownership merge — Execution owns Quiz 2/3, O1+O2 per composition §4). Quiz dissolved: its entities (Quiz, QuizQuestion, QuizType, QuizOption, QuizDto) became members of Execution; the QuizCourseExecution replica of Execution was deleted (pointer+version only); quiz-service and quizdb were removed as a deployment unit (9 -> 8). Quiz's intra-invariants now run per-member inside Execution.verifyInvariants(); the COURSE_EXECUTION_EXISTS inter-invariant collapsed to intra (execution removal invalidates its quizzes in one unit of work); the QUESTION_EXISTS inter-invariant (UpdateQuestion/DeleteQuestion quad) moved to Execution. Quiz commands re-homed to commands/execution and dispatch in-process to EXECUTION (the application-wide self-command idiom); quiz semantic locks (READ_QUIZ, STARTED_TOURNAMENT_QUIZ, STARTED_QUIZ-on-quiz-id) were unchecked and dropped. TournamentQuiz re-pointed @Replica("Quiz")->@Replica("Execution"); AnsweredQuiz @Replica({"Quiz","Question"})->@Replica({"Execution","Question"}). test-sagas: green (152 tests, 0 failures).

GATE: Execution owns Quiz (2/3) — case 1
GATE: O1 satisfied — Quiz.java declares a single-valued @OneToOne QuizCourseExecution whose DTO constructor takes CourseExecutionDto (microservices/execution/aggregate/CourseExecutionDto) → owner Execution
GATE: O2 satisfied — Quiz creation path POST /executions/{courseExecutionId} (QuizController.createQuiz) and createQuiz takes courseExecutionId first; quiz is created in the execution's scope
GATE: O3 not satisfied — no Find<Quiz>sBy<Execution>Id command/service method; ownership holds on O1+O2 (2/3), matching composition §4
GATE: Quiz lock states unchecked — no setForbiddenStates names QuizSagaState and QuizRepository has no …ForSaga query filtering sagaState; all quiz locks dropped (READ_QUIZ in answer.StartQuiz; STARTED_TOURNAMENT_QUIZ in tournament.SolveQuiz/SolveQuizAsync; QuizAnswerSagaState.STARTED_QUIZ registered on the quiz id in SolveQuiz/SolveQuizAsync)

LAYER 1 (aggregate): Quiz demoted to member of Execution; names resolved — svc execution/quiz, root Execution/Quiz, family CourseExecution/Quiz; entities moved: 4 (Quiz, QuizQuestion, QuizType, QuizOption) + QuizDto; deleted 7 root classes (SagaQuiz, QuizFactory, SagasQuizFactory, QuizRepository, QuizSagaState, QuizServiceApplication, QuizCourseExecution); invariants — 4 member-scoped (invariantDateOrdering, invariantFieldsFinalAfterAvailableDate[prev via root], invariantInClassRequiresConclusionDate, invariantQuizQuestionSequenceConsecutive), 1 now intra-aggregate (COURSE_EXECUTION_EXISTS -> removeCourseExecution invalidates quizzes), 1 kept as inter-invariant (QUESTION_EXISTS moved to Execution); counters collapsed: none; replicas — 1 deleted (QuizCourseExecution), 2 re-pointed to Execution (TournamentQuiz, AnsweredQuiz "Quiz"->"Execution"), 0 kept as snapshots; O1 links added by re-pointing: TournamentQuiz single-valued on Tournament becomes @Replica("Execution") beside TournamentCourseExecution — second O1 witness of execution<-tournament, verdict OWNED unchanged; annotations — @Replica 2 re-pointed +1 deleted, @InterInvariant QUESTION_EXISTS moved, @ServiceGuard 0
LAYER 2 (root join): Quiz identity kept as entity id quizId (2 external references still reach a quiz member entity — TournamentQuiz.quizAggregateId, AnsweredQuiz.quizAggregateId); 8 entry point(s) rewritten to owner-lookup (findExecutionIdByQuizId on CourseExecutionCustomRepository + sagas impl) + navigate (getQuizById, startTournamentQuiz, generateQuiz, createQuiz, updateGeneratedQuiz, updateQuiz, getAvailableQuizzes, removeQuiz, updateQuestion, removeQuizQuestion); Execution copy constructor deep-copies 1 member collection (quizzes via Quiz::new); 1 event (InvalidateQuizEvent) keeps the member identifier (quiz.getQuizId())
LAYER 3 (coordination): quiz service/handler/functionalities/sagas/controller/eventProcessing/notification folded into execution; steps re-pointed to EXECUTION (in-process self-dispatch, the app-wide idiom — the CreateQuiz getCourseExecutionStep keeps READ_COURSE in-process on M); commands re-homed: 9 to commands/execution (CreateQuiz, GenerateQuiz, GetAvailableQuizzes, GetQuizById, RemoveQuiz, RemoveQuizQuestion, StartTournamentQuiz, UpdateGeneratedQuiz, UpdateQuestion, UpdateQuiz), deleted: 1 (commands/quiz/RemoveCourseExecutionCommand — its DeleteCourseExecution consumption became intra); events deleted: 1 consumption (QuizSubscribesDeleteCourseExecution + DeleteCourseExecutionEventHandler + @Scheduled + processDeleteCourseExecutionEvent — publisher Execution now inside M), retained: 2 (UpdateQuestion, DeleteQuestion — publisher Question outside M, moved to execution); forbidden-state entries re-pointed: 0, dropped: none; locks — Quiz states unchecked, dropped 4 (answer StartQuiz READ_QUIZ; tournament SolveQuiz/SolveQuizAsync STARTED_TOURNAMENT_QUIZ; SolveQuiz/SolveQuizAsync STARTED_QUIZ-on-quiz-id), retargeted 0, kept in-process 1 (CreateQuiz READ_COURSE on the execution); workflows fully collapsed to in-process: CreateQuiz, FindQuiz, GetAvailableQuizzes, UpdateQuiz, UpdateQuestionInQuiz, RemoveQuizQuestion (RemoveCourseExecutionFromQuiz deleted)
LAYER 4 (deployment): quiz-service and quiz-db removed; 2 route predicate(s) adopted into execution as id 'execution-service-quiz' (/quizzes/**) and 'execution-service-quiz-execution-post' (POST /executions/{executionId}); quizdb destroyed without migration (acceptable only because ddl-auto: create and Monitor regenerates state with a load test); quizServiceCommandChannel kept in execution's function.definition + shared command-channel bindings as the 4.5 safety net; quizEventSubscriber removed from application.yaml; 6 test bean(s) dropped across 6 regions in BeanConfigurationSagas (quizFunctionalities, quizEventProcessing, sagasQuizFactory, quizService, quizEventDetection, quizCommandHandler); deployment units 9 -> 8 (ServiceApplication 8, compose service 8, compose db 8, pom profile 8, application-*-service.yaml 8, ServiceMapping 8, EventSubscriber-in-0 8); sagas test profile green

Topology: quiz-service dissolved into execution-service (execution keeps port 8083 and executiondb; quizdb destroyed; commands collapsed/re-homed: 9 to EXECUTION in-process; commands deleted: 1; events deleted: 1 consumption)
Impact: affected aggregates: execution (absorbs), tournament (TournamentQuiz replica re-pointed, SolveQuiz/SolveQuizAsync/CreateTournament/CreateTournamentAsync/UpdateTournament/RemoveTournament re-pointed to EXECUTION), answer (AnsweredQuiz replica re-pointed, StartQuiz/AnswerQuestion re-pointed to EXECUTION), question (publishes UpdateQuestion/DeleteQuestion now consumed by execution); no references: course, topic, user, tournamentSatellite hold no quiz reference; replicas retained: 2 re-pointed to Execution (TournamentQuiz, AnsweredQuiz); replicas now redundant: 1 deleted (QuizCourseExecution); DTOs: 1 (QuizDto moved to execution/aggregate, version now the root's); functionalities: 13 execution (6 quiz folded in); controllers: 3 quiz endpoints moved to ExecutionController, QuizController stub deleted; tests: 152 green; external consumers referencing a member entity: 2 (TournamentQuiz, AnsweredQuiz by quizAggregateId)
WARNING: findQuiz/getQuizById now resolve a quiz member via findExecutionIdByQuizId and throw AGGREGATE_NOT_FOUND for non-ACTIVE quizzes, preserving the former Quiz-aggregate load semantics; update+remove of a tournament's quiz is now synchronous on one aggregate (the merge payoff), so the former eventual-consistency snapshot assertions (sagaStateOf(quizId), aggregateDeletedLoad(quizId)) were adapted to read the quiz member off its owning execution and the dropped-quiz-lock assertion in StartQuizCompensationTest was replaced with a readability check — documented test deviations, not weakened coverage.

<!-- REFACTOR_METRICS -->
operation: MERGE_AGGREGATES
survivingService: execution
dissolvedService: quiz
commandsCollapsed: 9
commandsRePointed: 9
stepsCollapsed: 6
eventsDeleted: 1
invariantsToIntra: COURSE_EXECUTION_EXISTS
lockStatesChecked: Quiz=unchecked
locksDropped: 4
locksRetargeted: 0
locksKeptInline: 1
errorsPre: 11
errorsPost: 12
routesPre: 14
routesPost: 14
testsSagas: green
<!-- END_REFACTOR_METRICS -->
<!-- end-refactor-log -->
