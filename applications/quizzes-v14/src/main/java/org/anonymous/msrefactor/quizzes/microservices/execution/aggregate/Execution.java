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

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.anonymous.msrefactor.ms.aggregate.Aggregate.AggregateState.ACTIVE;

/*
    INTRA-INVARIANTS
        REMOVE_NO_STUDENTS
        COURSE_EXECUTION_EXISTS (was inter-invariant of the dissolved Quiz aggregate:
            no quiz stays ACTIVE once its execution is deleted)
        QUESTIONS_FINAL_AFTER_AVAILABLE_DATE
        CREATION_DATE_FINAL
        AVAILABLE_DATE_FINAL_AFTER_AVAILABLE_DATE
        CONCLUSION_DATE_FINAL_AFTER_AVAILABLE_DATE
        RESULTS_DATE_FINAL_AFTER_AVAILABLE_DATE
        QUIZ_IN_CLASS_REQUIRES_CONCLUSION_DATE
        QUIZ_QUESTION_SEQUENCE_CONSECUTIVE
        COURSE_EXECUTION_FINAL (structural: a quiz belongs to exactly one Execution root)
    SERVICE-LAYER GUARDS (Layer 2)
        NO_DUPLICATE_COURSE_EXECUTION (enforced in ExecutionService.createCourseExecution)
    INTER-INVARIANTS
        USER_EXISTS
        QUESTION_EXISTS (moved from the dissolved Quiz aggregate)
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
    }

    @Override
    public Set<EventSubscription> getEventSubscriptions() {
        Set<EventSubscription> eventSubscriptions = new HashSet<>();
        if (getState() == ACTIVE) {
            interInvariantUsersExist(eventSubscriptions);
            interInvariantQuestionsExist(eventSubscriptions);
        }
        return eventSubscriptions;
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
