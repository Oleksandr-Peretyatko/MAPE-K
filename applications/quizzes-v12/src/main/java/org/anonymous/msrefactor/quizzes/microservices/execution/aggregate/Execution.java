package org.anonymous.msrefactor.quizzes.microservices.execution.aggregate;

import jakarta.persistence.*;
import org.anonymous.msrefactor.ms.aggregate.Aggregate;
import org.anonymous.msrefactor.ms.aggregate.EventSubscription;
import org.anonymous.msrefactor.ms.aggregate.InterInvariant;
import org.anonymous.msrefactor.ms.utils.DateHandler;
import org.anonymous.msrefactor.quizzes.microservices.exception.QuizzesErrorMessage;
import org.anonymous.msrefactor.quizzes.microservices.exception.QuizzesException;
import org.anonymous.msrefactor.ms.exception.SimulatorErrorMessage;
import org.anonymous.msrefactor.ms.exception.SimulatorException;
import org.anonymous.msrefactor.quizzes.microservices.execution.notification.subscribe.CourseExecutionSubscribesRemoveUser;
import org.anonymous.msrefactor.quizzes.microservices.execution.notification.subscribe.TournamentSubscribesAnswerQuestion;
import org.anonymous.msrefactor.quizzes.microservices.execution.notification.subscribe.TournamentSubscribesDeleteTopic;
import org.anonymous.msrefactor.quizzes.microservices.execution.notification.subscribe.TournamentSubscribesInvalidateQuiz;
import org.anonymous.msrefactor.quizzes.microservices.execution.notification.subscribe.TournamentSubscribesUpdateTopic;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.anonymous.msrefactor.ms.aggregate.Aggregate.AggregateState.ACTIVE;

/*
    INTRA-INVARIANTS
        REMOVE_NO_STUDENTS
        (tournament member-scoped, merge execution <- tournament — verified per member:
        CREATOR_IS_FINAL, COURSE_EXECUTION_IS_FINAL, QUIZ_IS_FINAL, START_BEFORE_END_TIME,
        UNIQUE_AS_PARTICIPANT, ENROLL_UNTIL_START_TIME, ANSWER_BEFORE_START, FINAL_AFTER_START,
        LEAVE_TOURNAMENT, AFTER_END, IS_CANCELED, DELETE, CREATOR_PARTICIPANT_CONSISTENCY,
        TOURNAMENT_NUMBER_OF_QUESTIONS_POSITIVE, TOURNAMENT_MUST_HAVE_ONE_TOPIC, TOURNAMENT_MAX_QUESTIONS)
        (formerly tournament inter-invariants on Execution, now maintained synchronously by
        ExecutionService in the same commit: COURSE_EXECUTION_EXISTS, CREATOR_EXISTS,
        PARTICIPANT_EXISTS, CREATOR_COURSE_EXECUTION, PARTICIPANT_COURSE_EXECUTION)
    SERVICE-LAYER GUARDS (Layer 2)
        NO_DUPLICATE_COURSE_EXECUTION (enforced in ExecutionService.createCourseExecution)
    INTER-INVARIANTS
        USER_EXISTS
        TOPIC_EXISTS
        QUIZ_EXISTS
        QUIZ_ANSWER_EXISTS
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
        setTournaments(other.getTournaments().stream().map(Tournament::new).collect(Collectors.toSet()));
    }

    @Override
    public Set<EventSubscription> getEventSubscriptions() {
        Set<EventSubscription> eventSubscriptions = new HashSet<>();
        if (getState() == ACTIVE) {
            interInvariantUsersExist(eventSubscriptions);
            interInvariantTopicsExist(eventSubscriptions);
            interInvariantQuizExists(eventSubscriptions);
            interInvariantQuizAnswersExist(eventSubscriptions);
        }
        return eventSubscriptions;
    }

    @InterInvariant("USER_EXISTS")
    private void interInvariantUsersExist(Set<EventSubscription> eventSubscriptions) {
        for (CourseExecutionStudent student : this.students) {
            eventSubscriptions.add(new CourseExecutionSubscribesRemoveUser(student));
        }
    }

    /* moved from Tournament (merge execution <- tournament): topics are copies of Course members */
    @InterInvariant("TOPIC_EXISTS")
    private void interInvariantTopicsExist(Set<EventSubscription> eventSubscriptions) {
        for (Tournament tournament : activeTournaments()) {
            for (TournamentTopic tournamentTopic : tournament.getTournamentTopics()) {
                eventSubscriptions.add(new TournamentSubscribesDeleteTopic(tournamentTopic));
                eventSubscriptions.add(new TournamentSubscribesUpdateTopic(tournamentTopic));
            }
        }
    }

    @InterInvariant("QUIZ_EXISTS")
    private void interInvariantQuizExists(Set<EventSubscription> eventSubscriptions) {
        for (Tournament tournament : activeTournaments()) {
            eventSubscriptions.add(new TournamentSubscribesInvalidateQuiz(tournament.getTournamentQuiz()));
        }
    }

    @InterInvariant("QUIZ_ANSWER_EXISTS")
    private void interInvariantQuizAnswersExist(Set<EventSubscription> eventSubscriptions) {
        for (Tournament tournament : activeTournaments()) {
            for (TournamentParticipant tournamentParticipant : tournament.getTournamentParticipants()) {
                if (tournamentParticipant.getParticipantAnswer().getQuizAnswerAggregateId() != null) {
                    eventSubscriptions.add(new TournamentSubscribesAnswerQuestion(tournamentParticipant));
                }
            }
        }
    }

    private Set<Tournament> activeTournaments() {
        return this.tournaments.stream()
                .filter(t -> t.getState() == ACTIVE)
                .collect(Collectors.toSet());
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

    /*
     * REMOVE_NO_STUDENTS
     */
    public boolean removedNoStudents() {
        if (getState() == AggregateState.DELETED) {
            return getStudents().size() == 0;
        }
        return true;
    }

    @Override
    public void verifyInvariants() {
        if (!(removedNoStudents())) {
            throw new QuizzesException(QuizzesErrorMessage.INVARIANT_BREAK, getAggregateId());
        }
        /*
         * Tournament member-scoped invariants (merge execution <- tournament).
         * Each member is checked against its own previous version, found in the
         * previous version of this root — members have no prev chain of their own.
         */
        Execution prev = (Execution) getPrev();
        for (Tournament tournament : this.tournaments) {
            Tournament prevTournament = prev == null ? null
                    : prev.getTournaments().stream()
                            .filter(t -> tournament.getTournamentId().equals(t.getTournamentId()))
                            .findFirst()
                            .orElse(null);
            tournament.verifyInvariants(prevTournament);
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
        // a tournament quiz created in this same transaction gets its version upon commit
        // (moved from the dissolved Tournament root's setVersion override)
        for (Tournament tournament : this.tournaments) {
            if (tournament.getTournamentQuiz() != null && tournament.getTournamentQuiz().getQuizVersion() == null) {
                tournament.getTournamentQuiz().setQuizVersion(version);
            }
        }
        super.setVersion(version);
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
     * Resolves a tournament by its retained identity. DELETED members stay invisible,
     * matching the dissolved root's findNonDeletedSagaAggregate semantics; callers see
     * the same AGGREGATE_NOT_FOUND the old aggregate load raised.
     */
    public Tournament getTournament(Integer tournamentId) {
        return this.tournaments.stream()
                .filter(t -> t.getState() != AggregateState.DELETED && tournamentId.equals(t.getTournamentId()))
                .findFirst()
                .orElseThrow(() -> new SimulatorException(SimulatorErrorMessage.AGGREGATE_NOT_FOUND, tournamentId));
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
