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
