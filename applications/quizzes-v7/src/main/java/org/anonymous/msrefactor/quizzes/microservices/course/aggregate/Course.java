package org.anonymous.msrefactor.quizzes.microservices.course.aggregate;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import org.anonymous.msrefactor.ms.aggregate.Aggregate;
import org.anonymous.msrefactor.ms.aggregate.EventSubscription;
import org.anonymous.msrefactor.ms.aggregate.InterInvariant;
import org.anonymous.msrefactor.quizzes.microservices.exception.QuizzesErrorMessage;
import org.anonymous.msrefactor.quizzes.microservices.exception.QuizzesException;
import org.anonymous.msrefactor.quizzes.microservices.course.notification.subscribe.QuestionSubscribesDeleteTopic;
import org.anonymous.msrefactor.quizzes.microservices.course.notification.subscribe.QuestionSubscribesUpdateTopic;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.CourseExecutionDto;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.anonymous.msrefactor.ms.aggregate.Aggregate.AggregateState.ACTIVE;

/*
    INTRA-INVARIANTS:
        COURSE_TYPE_FINAL
        COURSE_NAME_FINAL
        CANNOT_DELETE_LAST_EXECUTION_WITH_CONTENT
        QUESTION_TOPIC_INVALID_COURSE (per Question member, merged from the former Question aggregate)
    INTER-INVARIANTS:
        TOPICS_EXIST (merged from the former Question aggregate)
 */
@Entity
public abstract class Course extends Aggregate {
    /*
        COURSE_TYPE_FINAL
     */
    @Enumerated(EnumType.STRING)
    private final CourseType type;
    /*
        COURSE_NAME_FINAL
     */
    @Column
    private final String name;

    // CANNOT_DELETE_LAST_EXECUTION_WITH_CONTENT
    @Column
    private int courseExecutionCount = 0;

    // Question entities absorbed from the former Question aggregate (merge course <- question).
    @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER, mappedBy = "course")
    private Set<Question> questions = new HashSet<>();

    public Course() {
        this.name = "COURSE NAME";
        this.type = CourseType.TECNICO;
    }

    public Course(Integer aggregateId, CourseExecutionDto courseExecutionDto) {
        super(aggregateId);
        setAggregateType(getClass().getSimpleName());
        this.name = courseExecutionDto.getName();
        this.type = CourseType.valueOf(courseExecutionDto.getType());
    }

    public Course(Course other) {
        super(other);
        this.name = other.getName();
        this.type = other.getType();
        this.courseExecutionCount = other.getCourseExecutionCount();
        setQuestions(other.getQuestions().stream().map(Question::new).collect(Collectors.toSet()));
    }

    @Override
    public Set<EventSubscription> getEventSubscriptions() {
        Set<EventSubscription> eventSubscriptions = new HashSet<>();
        if (getState() == ACTIVE) {
            interInvariantTopicsExist(eventSubscriptions);
        }
        return eventSubscriptions;
    }

    @InterInvariant("TOPICS_EXIST")
    private void interInvariantTopicsExist(Set<EventSubscription> eventSubscriptions) {
        for (Question question : this.questions) {
            if (question.getState() != ACTIVE) {
                continue;
            }
            for (QuestionTopic topic : question.getQuestionTopics()) {
                eventSubscriptions.add(new QuestionSubscribesDeleteTopic(topic));
                eventSubscriptions.add(new QuestionSubscribesUpdateTopic(topic));
            }
        }
    }

    @Override
    public void verifyInvariants() {
        // CANNOT_DELETE_LAST_EXECUTION_WITH_CONTENT: courseQuestionCount collapsed to the live
        // count of active Question members (merge course <- question, layer 1.6).
        if (courseExecutionCount == 0 && getCourseQuestionCount() > 0) {
            throw new QuizzesException(QuizzesErrorMessage.CANNOT_DELETE_LAST_EXECUTION_WITH_CONTENT, getAggregateId());
        }
        // QUESTION_TOPIC_INVALID_COURSE, per Question member (merged from the former Question aggregate).
        for (Question question : this.questions) {
            if (question.getState() != ACTIVE) {
                continue;
            }
            for (QuestionTopic qt : question.getQuestionTopics()) {
                if (!qt.getCourseAggregateId().equals(getAggregateId())) {
                    throw new QuizzesException(QuizzesErrorMessage.QUESTION_TOPIC_INVALID_COURSE,
                            qt.getTopicAggregateId(), getAggregateId());
                }
            }
        }
    }

    public CourseType getType() {
        return type;
    }

    public String getName() {
        return name;
    }

    // CANNOT_DELETE_LAST_EXECUTION_WITH_CONTENT: derived from the Question members, no longer a stored
    // counter kept current by UpdateCourseQuestionCountCommand (merge course <- question, layer 1.6).
    public int getCourseQuestionCount() {
        return (int) this.questions.stream()
                .filter(question -> question.getState() == ACTIVE)
                .count();
    }

    public int getCourseExecutionCount() {
        return courseExecutionCount;
    }

    public void setCourseExecutionCount(int courseExecutionCount) {
        this.courseExecutionCount = courseExecutionCount;
    }

    public Set<Question> getQuestions() {
        return questions;
    }

    public void setQuestions(Set<Question> questions) {
        this.questions = questions;
        this.questions.forEach(question -> question.setCourse(this));
    }

    public void addQuestion(Question question) {
        this.questions.add(question);
        question.setCourse(this);
    }

    public Question findQuestion(Integer questionId) {
        for (Question question : this.questions) {
            if (question.getQuestionId().equals(questionId)) {
                return question;
            }
        }
        return null;
    }

    public Question getQuestion(Integer questionId) {
        Question question = findQuestion(questionId);
        if (question == null) {
            throw new QuizzesException(QuizzesErrorMessage.QUESTION_NOT_FOUND, questionId);
        }
        return question;
    }
}
