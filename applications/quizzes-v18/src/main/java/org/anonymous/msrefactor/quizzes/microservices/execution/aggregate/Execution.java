package org.anonymous.msrefactor.quizzes.microservices.execution.aggregate;

import jakarta.persistence.*;
import org.anonymous.msrefactor.ms.aggregate.Aggregate;
import org.anonymous.msrefactor.ms.aggregate.EventSubscription;
import org.anonymous.msrefactor.ms.aggregate.InterInvariant;
import org.anonymous.msrefactor.ms.utils.DateHandler;
import org.anonymous.msrefactor.quizzes.microservices.exception.QuizzesErrorMessage;
import org.anonymous.msrefactor.quizzes.microservices.exception.QuizzesException;
import org.anonymous.msrefactor.quizzes.microservices.execution.notification.subscribe.CourseExecutionSubscribesRemoveUser;
import org.anonymous.msrefactor.quizzes.microservices.execution.notification.subscribe.QuizSubscribesDeleteQuestion;
import org.anonymous.msrefactor.quizzes.microservices.execution.notification.subscribe.QuizSubscribesUpdateQuestion;
import org.anonymous.msrefactor.quizzes.microservices.execution.notification.subscribe.TournamentSubscribesAnswerQuestion;
import org.anonymous.msrefactor.quizzes.microservices.execution.notification.subscribe.TournamentSubscribesDeleteTopic;
import org.anonymous.msrefactor.quizzes.microservices.execution.notification.subscribe.TournamentSubscribesUpdateTopic;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.anonymous.msrefactor.ms.aggregate.Aggregate.AggregateState.ACTIVE;

/*
    INTRA-INVARIANTS
        REMOVE_NO_STUDENTS
        COURSE_EXECUTION_EXISTS (was inter-invariant of the dissolved Quiz aggregate:
            no quiz stays ACTIVE once its execution is deleted; the dissolved Tournament
            aggregate's rule of the same name is enforced the same way — a removed
            execution keeps no ACTIVE tournament, see ExecutionService.removeCourseExecution)
        QUESTIONS_FINAL_AFTER_AVAILABLE_DATE
        CREATION_DATE_FINAL
        AVAILABLE_DATE_FINAL_AFTER_AVAILABLE_DATE
        CONCLUSION_DATE_FINAL_AFTER_AVAILABLE_DATE
        RESULTS_DATE_FINAL_AFTER_AVAILABLE_DATE
        QUIZ_IN_CLASS_REQUIRES_CONCLUSION_DATE
        QUIZ_QUESTION_SEQUENCE_CONSECUTIVE
        COURSE_EXECUTION_FINAL (structural: a quiz belongs to exactly one Execution root)
        START_BEFORE_END_TIME (per tournament member)
        UNIQUE_AS_PARTICIPANT (per tournament member)
        ENROLL_UNTIL_START_TIME (per tournament member)
        ANSWER_BEFORE_START (per tournament member)
        FINAL_AFTER_START (per tournament member)
        IS_CANCELED (per tournament member)
        DELETE (per tournament member)
        CREATOR_PARTICIPANT_CONSISTENCY (per tournament member)
        TOURNAMENT_NUMBER_OF_QUESTIONS_POSITIVE (per tournament member)
        TOURNAMENT_MUST_HAVE_ONE_TOPIC (per tournament member)
        TOURNAMENT_MAX_QUESTIONS (per tournament member)
        CREATOR_EXISTS / PARTICIPANT_EXISTS / QUIZ_EXISTS (were inter-invariants of the
            dissolved Tournament aggregate on events this root itself published; now
            enforced synchronously by ExecutionService when students are anonymised,
            disenrolled or renamed and when quizzes are invalidated)
    SERVICE-LAYER GUARDS (Layer 2)
        NO_DUPLICATE_COURSE_EXECUTION (enforced in ExecutionService.createCourseExecution)
    INTER-INVARIANTS
        USER_EXISTS
        QUESTION_EXISTS (moved from the dissolved Quiz aggregate)
        TOPIC_EXISTS (moved from the dissolved Tournament aggregate)
        QUIZ_ANSWER_EXISTS (moved from the dissolved Tournament aggregate)
        COURSE_EXISTS (does it count? course doesn't send events)
 */

@Entity
public abstract class Execution extends Aggregate {
    private String acronym;
    private String academicTerm;
    private LocalDateTime endDate;
    @OneToOne(cascade = CascadeType.ALL, mappedBy = "execution")
    private CourseExecutionCourse courseExecutionCourse;
    @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER, mappedBy = "execution")
    private Set<CourseExecutionStudent> students = new HashSet<>();
    @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER, mappedBy = "execution")
    private Set<Quiz> quizzes = new HashSet<>();
    @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER, mappedBy = "execution")
    private Set<Tournament> tournaments = new HashSet<>();

    public Execution() {
    }

    public Execution(Integer aggregateId, CourseExecutionDto courseExecutionDto,
            CourseExecutionCourse courseExecutionCourse) {
        super(aggregateId);
        setAggregateType(getClass().getSimpleName());
        setAcronym(courseExecutionDto.getAcronym());
        setAcademicTerm(courseExecutionDto.getAcademicTerm());
        setEndDate(DateHandler.toLocalDateTime(courseExecutionDto.getEndDate()));
        setExecutionCourse(courseExecutionCourse);
    }

    public Execution(Execution other) {
        super(other);
        setAcronym(other.getAcronym());
        setAcademicTerm(other.getAcademicTerm());
        setEndDate(other.getEndDate());
        setExecutionCourse(new CourseExecutionCourse(other.getExecutionCourse()));
        setStudents(other.getStudents().stream().map(CourseExecutionStudent::new).collect(Collectors.toSet()));
        setQuizzes(other.getQuizzes().stream().map(Quiz::new).collect(Collectors.toSet()));
        setTournaments(other.getTournaments().stream().map(Tournament::new).collect(Collectors.toSet()));
    }

    @Override
    public Set<EventSubscription> getEventSubscriptions() {
        Set<EventSubscription> eventSubscriptions = new HashSet<>();
        if (getState() == ACTIVE) {
            interInvariantUsersExist(eventSubscriptions);
            interInvariantQuestionsExist(eventSubscriptions);
            interInvariantTournamentTopicsExist(eventSubscriptions);
            interInvariantTournamentQuizAnswersExist(eventSubscriptions);
        }
        return eventSubscriptions;
    }

    @InterInvariant("TOPIC_EXISTS")
    private void interInvariantTournamentTopicsExist(Set<EventSubscription> eventSubscriptions) {
        for (Tournament tournament : this.tournaments) {
            if (tournament.getState() != AggregateState.ACTIVE) {
                continue;
            }
            for (TournamentTopic tournamentTopic : tournament.getTournamentTopics()) {
                eventSubscriptions.add(new TournamentSubscribesDeleteTopic(tournamentTopic));
                eventSubscriptions.add(new TournamentSubscribesUpdateTopic(tournamentTopic));
            }
        }
    }

    @InterInvariant("QUIZ_ANSWER_EXISTS")
    private void interInvariantTournamentQuizAnswersExist(Set<EventSubscription> eventSubscriptions) {
        for (Tournament tournament : this.tournaments) {
            if (tournament.getState() != AggregateState.ACTIVE) {
                continue;
            }
            for (TournamentParticipant tournamentParticipant : tournament.getTournamentParticipants()) {
                if (tournamentParticipant.getParticipantAnswer().getQuizAnswerAggregateId() != null) {
                    eventSubscriptions.add(new TournamentSubscribesAnswerQuestion(tournamentParticipant));
                }
            }
        }
    }

    @InterInvariant("QUESTION_EXISTS")
    private void interInvariantQuestionsExist(Set<EventSubscription> eventSubscriptions) {
        for (Quiz quiz : this.quizzes) {
            if (quiz.getState() != AggregateState.ACTIVE) {
                continue;
            }
            for (QuizQuestion quizQuestion : quiz.getQuizQuestions()) {
                eventSubscriptions.add(new QuizSubscribesUpdateQuestion(quizQuestion));
                eventSubscriptions.add(new QuizSubscribesDeleteQuestion(quizQuestion));
            }
        }
    }

    @InterInvariant("USER_EXISTS")
    private void interInvariantUsersExist(Set<EventSubscription> eventSubscriptions) {
        for (CourseExecutionStudent student : this.students) {
            eventSubscriptions.add(new CourseExecutionSubscribesRemoveUser(student));
        }
    }

    public String getAcronym() {
        return acronym;
    }

    public void setAcronym(String acronym) {
        this.acronym = acronym;
    }

    public String getAcademicTerm() {
        return academicTerm;
    }

    public void setAcademicTerm(String academicTerm) {
        this.academicTerm = academicTerm;
    }

    public LocalDateTime getEndDate() {
        return endDate;
    }

    public void setEndDate(LocalDateTime endDate) {
        this.endDate = endDate;
    }

    public CourseExecutionCourse getExecutionCourse() {
        return courseExecutionCourse;
    }

    public void setExecutionCourse(CourseExecutionCourse course) {
        this.courseExecutionCourse = course;
        this.courseExecutionCourse.setCourseExecution(this);
    }

    public Set<CourseExecutionStudent> getStudents() {
        return students;
    }

    public void setStudents(Set<CourseExecutionStudent> students) {
        this.students = students;
        this.students.forEach(courseExecutionStudent -> courseExecutionStudent.setCourseExecution(this));
    }

    public void addStudent(CourseExecutionStudent courseExecutionStudent) {
        this.students.add(courseExecutionStudent);
        courseExecutionStudent.setCourseExecution(this);
    }

    public Set<Quiz> getQuizzes() {
        return quizzes;
    }

    public void setQuizzes(Set<Quiz> quizzes) {
        this.quizzes = quizzes;
        this.quizzes.forEach(quiz -> quiz.setExecution(this));
    }

    public void addQuiz(Quiz quiz) {
        this.quizzes.add(quiz);
        quiz.setExecution(this);
    }

    /*
     * Entity lookup by the retained quiz identifier; DELETED members are skipped,
     * matching the old root lookup that no longer resolved a deleted Quiz aggregate.
     */
    public Quiz getQuizByQuizId(Integer quizId) {
        for (Quiz quiz : this.quizzes) {
            if (quiz.getQuizId().equals(quizId) && quiz.getState() != AggregateState.DELETED) {
                return quiz;
            }
        }
        return null;
    }

    private Quiz findQuizAnyState(Integer quizId) {
        for (Quiz quiz : this.quizzes) {
            if (quiz.getQuizId().equals(quizId)) {
                return quiz;
            }
        }
        return null;
    }

    public Set<Tournament> getTournaments() {
        return tournaments;
    }

    public void setTournaments(Set<Tournament> tournaments) {
        this.tournaments = tournaments;
        this.tournaments.forEach(tournament -> tournament.setExecution(this));
    }

    public void addTournament(Tournament tournament) {
        this.tournaments.add(tournament);
        tournament.setExecution(this);
    }

    /*
     * Entity lookup by the retained tournament identifier; DELETED members are skipped,
     * matching the old root lookup that no longer resolved a deleted Tournament aggregate.
     */
    public Tournament getTournamentByTournamentId(Integer tournamentId) {
        for (Tournament tournament : this.tournaments) {
            if (tournament.getTournamentId().equals(tournamentId)
                    && tournament.getState() != AggregateState.DELETED) {
                return tournament;
            }
        }
        return null;
    }

    private Tournament findTournamentAnyState(Integer tournamentId) {
        for (Tournament tournament : this.tournaments) {
            if (tournament.getTournamentId().equals(tournamentId)) {
                return tournament;
            }
        }
        return null;
    }

    /*
     * REMOVE_NO_STUDENTS
     */
    public boolean removedNoStudents() {
        if (getState() == AggregateState.DELETED) {
            return getStudents().size() == 0;
        }
        return true;
    }

    /*
     * COURSE_EXECUTION_EXISTS
     * Was the Quiz aggregate's inter-invariant on DeleteCourseExecutionEvent; now that
     * one root owns both, a deleted execution may not keep an ACTIVE quiz.
     */
    public boolean quizzesInactiveWhenDeleted() {
        if (getState() == AggregateState.DELETED) {
            return getQuizzes().stream().noneMatch(quiz -> quiz.getState() == AggregateState.ACTIVE);
        }
        return true;
    }

    private boolean verifyQuizInvariants(Quiz quiz) {
        Execution prevExecution = (Execution) getPrev();
        Quiz prevQuiz = prevExecution == null ? null : prevExecution.findQuizAnyState(quiz.getQuizId());
        return quiz.invariantDateOrdering()
                && quiz.invariantFieldsFinalAfterAvailableDate(prevQuiz)
                && quiz.invariantInClassRequiresConclusionDate()
                && quiz.invariantQuizQuestionSequenceConsecutive();
    }

    private boolean verifyTournamentInvariants(Tournament tournament) {
        /*
         * DELETE invariant applies to all member states
         */
        if (!tournament.invariantDeleteWhenNoParticipants()) {
            return false;
        }
        /*
         * All other invariants only apply to ACTIVE tournaments. INACTIVE tournaments
         * (e.g. after anonymisation) are excluded because their creator/participant
         * names may be "ANONYMOUS".
         */
        if (tournament.getState() != AggregateState.ACTIVE) {
            return true;
        }
        Execution prevExecution = (Execution) getPrev();
        Tournament prevTournament = prevExecution == null ? null
                : prevExecution.findTournamentAnyState(tournament.getTournamentId());
        return tournament.invariantAnswerBeforeStart()
                && tournament.invariantUniqueParticipant()
                && tournament.invariantParticipantsEnrolledBeforeStarTime()
                && tournament.invariantStartTimeBeforeEndTime()
                && tournament.invariantCreatorParticipantConsistency()
                && tournament.invariantCreatorIsNotAnonymous()
                && tournament.invariantFinalAfterStart(prevTournament)
                && tournament.invariantCancelledFieldsAreFinal(prevTournament)
                && tournament.invariantMustHaveOneTopic()
                && tournament.invariantNumberOfQuestionsPositive()
                && tournament.invariantNumberOfQuestionsAtMost30();
    }

    @Override
    public void verifyInvariants() {
        if (!(removedNoStudents() && quizzesInactiveWhenDeleted())) {
            throw new QuizzesException(QuizzesErrorMessage.INVARIANT_BREAK, getAggregateId());
        }
        for (Quiz quiz : this.quizzes) {
            if (!verifyQuizInvariants(quiz)) {
                throw new QuizzesException(QuizzesErrorMessage.INVARIANT_BREAK, getAggregateId());
            }
        }
        for (Tournament tournament : this.tournaments) {
            if (!verifyTournamentInvariants(tournament)) {
                throw new QuizzesException(QuizzesErrorMessage.INVARIANT_BREAK, getAggregateId());
            }
        }
    }

    @Override
    public void remove() {
        super.remove();
    }

    @Override
    public void setVersion(Long version) {
        // if the course version is null, it means it that we're creating during this transaction
        if (this.courseExecutionCourse != null && this.courseExecutionCourse.getCourseVersion() == null) {
            this.courseExecutionCourse.setCourseVersion(version);
        }
        super.setVersion(version);
    }

    public boolean hasStudent(Integer userAggregateId) {
        for (CourseExecutionStudent student : this.students) {
            if (student.getUserAggregateId().equals(userAggregateId)) {
                return true;
            }
        }
        return false;
    }

    public CourseExecutionStudent findStudent(Integer userAggregateId) {
        for (CourseExecutionStudent student : this.students) {
            if (student.getUserAggregateId().equals(userAggregateId)) {
                return student;
            }
        }
        return null;
    }

    public void removeStudent(Integer userAggregateId) {
        CourseExecutionStudent studentToRemove = null;
        if (!hasStudent(userAggregateId)) {
            throw new QuizzesException(QuizzesErrorMessage.COURSE_EXECUTION_STUDENT_NOT_FOUND, userAggregateId,
                    getAggregateId());
        }
        for (CourseExecutionStudent student : this.students) {
            if (student.getUserAggregateId().equals(userAggregateId)) {
                studentToRemove = student;
            }
        }
        this.students.remove(studentToRemove);
    }
}
