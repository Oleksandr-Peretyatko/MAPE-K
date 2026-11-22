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
    SERVICE-LAYER GUARDS (Layer 2)
        NO_DUPLICATE_COURSE_EXECUTION (enforced in ExecutionService.createCourseExecution)
    INTER-INVARIANTS
        USER_EXISTS
        QUESTION_EXISTS (merged from Quiz)
        TOPIC_EXISTS (merged from Tournament)
        COURSE_EXISTS (does it count? course doesn't send events)
    COURSE_EXECUTION_EXISTS and QUIZ_EXISTS, formerly maintained by the Tournament aggregate via
    events, are intra-aggregate now (merge execution <- tournament): removing this execution
    invalidates its tournaments in the same unit of work, and the tournament's quiz is a Quiz
    member of this same root.
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
    // Quiz entities absorbed from the former Quiz aggregate (merge execution <- quiz).
    @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER, mappedBy = "execution")
    private Set<Quiz> quizzes = new HashSet<>();
    // Tournament entities absorbed from the former Tournament aggregate (merge execution <- tournament).
    // Still EAGER like the other member collections, but fetched by a secondary select: joining a
    // second EAGER collection tree (tournaments x topics) into the same query as quizzes x questions
    // x students makes Hibernate build a cartesian product per Execution load.
    @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER, mappedBy = "execution")
    @org.hibernate.annotations.Fetch(org.hibernate.annotations.FetchMode.SELECT)
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
            interInvariantTopicsExist(eventSubscriptions);
        }
        return eventSubscriptions;
    }

    @InterInvariant("USER_EXISTS")
    private void interInvariantUsersExist(Set<EventSubscription> eventSubscriptions) {
        for (CourseExecutionStudent student : this.students) {
            eventSubscriptions.add(new CourseExecutionSubscribesRemoveUser(student));
        }
    }

    @InterInvariant("QUESTION_EXISTS")
    private void interInvariantQuestionsExist(Set<EventSubscription> eventSubscriptions) {
        for (Quiz quiz : this.quizzes) {
            for (QuizQuestion quizQuestion : quiz.getQuizQuestions()) {
                eventSubscriptions.add(new QuizSubscribesUpdateQuestion(quizQuestion));
                eventSubscriptions.add(new QuizSubscribesDeleteQuestion(quizQuestion));
            }
        }
    }

    @InterInvariant("TOPIC_EXISTS")
    private void interInvariantTopicsExist(Set<EventSubscription> eventSubscriptions) {
        for (Tournament tournament : this.tournaments) {
            if (tournament.getState() == AggregateState.ACTIVE) {
                for (TournamentTopic tournamentTopic : tournament.getTournamentTopics()) {
                    eventSubscriptions.add(new TournamentSubscribesDeleteTopic(tournamentTopic));
                    eventSubscriptions.add(new TournamentSubscribesUpdateTopic(tournamentTopic));
                }
            }
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
        // Quiz member invariants (merged from the former Quiz aggregate). The previous version of
        // each quiz is the matching member of this execution's previous version.
        Execution prev = (Execution) getPrev();
        for (Quiz quiz : this.quizzes) {
            Quiz prevQuiz = (prev != null) ? prev.findQuiz(quiz.getQuizId()) : null;
            quiz.verifyInvariants(prevQuiz);
        }
        // Tournament member invariants (merged from the former Tournament aggregate). As before the
        // merge, they are only enforced while the tournament is ACTIVE.
        for (Tournament tournament : this.tournaments) {
            if (tournament.getState() == AggregateState.ACTIVE) {
                Tournament prevTournament = (prev != null) ? prev.findTournament(tournament.getTournamentId()) : null;
                tournament.verifyInvariants(prevTournament);
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

    public Quiz findQuiz(Integer quizId) {
        for (Quiz quiz : this.quizzes) {
            if (quiz.getQuizId().equals(quizId)) {
                return quiz;
            }
        }
        return null;
    }

    public Quiz getQuiz(Integer quizId) {
        Quiz quiz = findQuiz(quizId);
        if (quiz == null) {
            throw new QuizzesException(QuizzesErrorMessage.QUIZ_NOT_FOUND, quizId);
        }
        return quiz;
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

    public Tournament findTournament(Integer tournamentId) {
        for (Tournament tournament : this.tournaments) {
            if (tournament.getTournamentId().equals(tournamentId)) {
                return tournament;
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
