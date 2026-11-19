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
Date: 2026-10-12T17:29:55Z
Skill: refactor-merge-aggregate
Invocation: /refactor-merge-aggregate course topic --project-path applications/quizzes
Result: CHANGED
Summary: MergeAggregates(Course, Topic) applied in full. Topic dissolved into Course: it is now a member @Entity (entity id topicId, member-level deleted flag) owned by Course via @OneToMany; topic-service, topic-db and every piece of Topic's root machinery are gone. All 5 topic workflows collapsed to in-process CourseService calls; 6 remote reads in question and tournament were re-pointed to course with their unchecked READ_TOPIC locks dropped; TopicCourse (pointer+version replica of Course) was deleted and QuestionTopic/TournamentTopic re-pointed to @Replica("Course"). UpdateTopicEvent/DeleteTopicEvent survive and keep publishing the retained topic identifier, so question's and tournament's subscriptions keep matching. Test gate green on the first run.
GATE: Course owns Topic (3/3) — case 1
GATE: O1 satisfied — Topic.java:23-24 declares @OneToOne TopicCourse; TopicCourse.java:17 holds courseAggregateId, and its DTO constructor TopicCourse(CourseDto) resolves the owner to microservices/course; reverse direction: Course.java declared no @OneToOne member, so Topic does not own Course (0/3)
GATE: O2 satisfied — TopicController.java:21 @PostMapping("/courses/{courseAggregateId}/create"): Topic was created in Course's scope
GATE: O3 satisfied — commands/topic/FindTopicsByCourseIdCommand.java and TopicService.findTopicsByCourseId: a parent-scoped collection query existed
GATE: Topic lock states unchecked — no setForbiddenStates list names TopicSagaState and TopicRepository has no sagaState query; READ_TOPIC was a marker nothing read back
GATE: Course lock states checked — CourseRepository.java:14 findCourseIdByNameForSaga filters on sagaState; the READ_COURSE lock in CreateTopic is kept in-process
WARNING: invariant CANNOT_DELETE_LAST_EXECUTION_WITH_CONTENT is not an inter-invariant anchored on Topic — it is Course's intra-invariant over courseQuestionCount/courseExecutionCount, counters fed by question and execution, which this merge does not touch; plan-listed name ignored
WARNING: invariant QUESTION_TOPIC_INVALID_COURSE is not an inter-invariant anchored on Topic — it is Question's intra-invariant (TOPIC_BELONGS_TO_QUESTION_COURSE) and is unchanged; plan-listed name ignored
LAYER 1 (aggregate): Topic demoted to member of Course; names resolved — svc course/topic, root Course/Topic, family Course/Topic (SvcB form: TopicServiceApplication); entities moved: 2 (Topic, TopicDto); deleted 9 root classes (SagaTopic, SagasTopicFactory, TopicCustomRepositorySagas, TopicRepository, TopicCustomRepository, TopicFactory, TopicSagaState, TopicServiceApplication, TopicCourse); invariants — 0 member-scoped (Topic.verifyInvariants was empty), 0 now intra-aggregate (header rule COURSE-EXISTS becomes structural containment under one root), 0 left as inter-invariants; counters collapsed: none (both Course counters are fed from outside this merge); replicas — 1 deleted (TopicCourse: courseAggregateId+courseVersion only, no refresher, no reader), 2 re-pointed to Course (QuestionTopic, TournamentTopic: @Replica({"Topic","Course"}) collapsed to @Replica("Course")), 0 kept as snapshots; O1 links added by re-pointing: none (both re-pointed classes are collection-valued @OneToMany members, verdicts unchanged); annotations — @Replica 3 touched, @InterInvariant 0, @ServiceGuard 0
LAYER 2 (root join): Topic identity kept as entity id topicId (external references still reaching the member by id: QuestionTopic.topicAggregateId, TournamentTopic.topicAggregateId and 6 remote GetTopicByIdCommand sites); 5 entry points rewritten to owner-lookup + navigate via CourseCustomRepository.findCourseIdByTopicId + Course.getTopic (same AGGREGATE_NOT_FOUND as before); Course copy constructor deep-copies 1 member collection (topics, Topic::new); 2 events keep the member identifier (UpdateTopicEvent, DeleteTopicEvent constructed with topic.getTopicId()); deletion is a member-level deleted flag — row kept because question and tournament subscribe DeleteTopicEvent and hold the id; TopicDto keeps all field names (courseId now from the back-reference, version from M, state from the flag)
LAYER 3 (coordination): 8 step(s) collapsed (CreateTopic: getCourseStep+createTopicStep; UpdateTopic: getTopicStep+updateTopicStep; DeleteTopic: getTopicStep+deleteTopicStep; GetTopicById: getTopicStep; FindTopicsByCourse: findTopicsStep), 6 re-pointed (GetTopicByIdCommand in CreateQuestion, UpdateQuestionTopics, UpdateQuestionTopicsAsync, CreateTournament, CreateTournamentAsync, UpdateTournament — now addressed to course), 1 of the collapsed deleted whole as replica-only (getCourseStep — its only product was the deleted TopicCourse; its courseDto was read only by its own release compensation); commands deleted: 4 (CreateTopicCommand, commands/topic UpdateTopicCommand, DeleteTopicCommand, FindTopicsByCourseIdCommand), moved: 1 (GetTopicByIdCommand -> commands/course, wire name topicAggregateId kept), 0 stripped of a replica parameter (CreateTopicCommand carried TopicCourse but died whole with its only sender); events deleted: 0 (all four pieces of both topic events retained — consumers question and tournament are outside M), retained: 2; forbidden-state entries re-pointed: 0, dropped: none (no list ever named TopicSagaState); locks — Topic states unchecked, dropped 7 with their NOT_IN_SAGA release compensations (question x3, tournament x2 — CreateTournament/Async had no release to drop, topic's own update+delete), retargeted 0, kept in-process 1 (READ_COURSE registered on the Course id inside the collapsed createTopicStep; workflow commit releases it locally); workflows fully collapsed: all 5 (CreateTopic kept as a single-step SagaWorkflow to carry the checked Course lock; the other four are direct CourseService calls in one SagaUnitOfWork)
LAYER 4 (deployment): topic-service and topic-db removed; 3 route predicate(s) adopted into course as id 'course-service-topic' (a routes: block was created — course had none, and without it /topics/** would 404); topicdb destroyed without migration — acceptable only because every service runs ddl-auto: create and the Monitor phase regenerates state with a load test; 4 test bean(s) dropped and 1 swapped (TopicFunctionalities -> CourseFunctionalities) across 4 regions of BeanConfigurationSagas plus 13 .groovy test files re-pointed; sagas test profile green (152 tests, 0 failures, 0 errors, first run)
Topology: topic-service dissolved into course-service (course keeps port 8089/9099 and coursedb; topicdb destroyed; ports 8086/9096 freed; commands collapsed: 8; commands re-pointed: 6; events deleted: 0)
Impact: affected aggregates: question (replica re-pointed, 3 workflows re-pointed, locks dropped), tournament (replica re-pointed, 3 workflows re-pointed, locks dropped); no references: execution, quiz, answer, user (verified — no Topic imports, ids or commands); replicas retained: 2 (QuestionTopic, TournamentTopic — refresh source is course from here on); replicas now redundant: 1 (TopicCourse, deleted); DTOs: 1 (TopicDto moved, wire shape unchanged); functionalities: 5 folded into CourseFunctionalities; controllers: 1 folded into the previously empty CourseController; tests: 14 files edited; external consumers referencing a member entity: 2 replica families + 6 command sites (resolved later by RemoveReplica or consumer re-pointing)
Impact note: 0 additional subscribers observe M's version today for member changes (course publishes only the retained topic events), but any topic change now bumps Course's version and its events carry course-counter versions
Deviation: CreateTournamentTest "saga compensations" dropped its two SagaTopic.sagaState == NOT_IN_SAGA assertions — Topic has no saga state after dissolution and the unchecked READ_TOPIC locks they observed were dropped by design; the test's behavioural assertions (compensation leaves no tournament, execution unlocked) are unchanged
Note: pre-existing quad mismatch in answer (5/6/6/6, DeleteUserEvent) — not touched by this run; course and topic quads were 0/0/0/0 before and course is 0/0/0/0 after
<!-- REFACTOR_METRICS -->
operation: MERGE_AGGREGATES
survivingService: course
dissolvedService: topic
commandsCollapsed: 8
commandsRePointed: 6
stepsCollapsed: 8
eventsDeleted: 0
invariantsToIntra: none
lockStatesChecked: Topic=unchecked
locksDropped: 7
locksRetargeted: 0
locksKeptInline: 1
errorsPre: 2
errorsPost: 2
routesPre: 4
routesPost: 4
testsSagas: green
<!-- END_REFACTOR_METRICS -->
<!-- end-refactor-log -->
