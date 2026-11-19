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
import org.anonymous.msrefactor.ms.exception.SimulatorErrorMessage;
import org.anonymous.msrefactor.ms.exception.SimulatorException;
import org.anonymous.msrefactor.quizzes.microservices.exception.QuizzesErrorMessage;
import org.anonymous.msrefactor.quizzes.microservices.exception.QuizzesException;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.CourseExecutionDto;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/*
    INTRA-INVARIANTS:
        COURSE_TYPE_FINAL
        COURSE_NAME_FINAL
        CANNOT_DELETE_LAST_EXECUTION_WITH_CONTENT
    INTER_INVARIANTS:

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
    private int courseQuestionCount = 0;

    // CANNOT_DELETE_LAST_EXECUTION_WITH_CONTENT
    @Column
    private int courseExecutionCount = 0;

    @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER, mappedBy = "course")
    private Set<Topic> topics = new HashSet<>();

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
        this.courseQuestionCount = other.getCourseQuestionCount();
        this.courseExecutionCount = other.getCourseExecutionCount();
        setTopics(other.getTopics().stream().map(Topic::new).collect(Collectors.toSet()));
    }

    @Override
    public Set<EventSubscription> getEventSubscriptions() {
        return new HashSet<>();
    }

    @Override
    public void verifyInvariants() {
        if (courseExecutionCount == 0 && courseQuestionCount > 0) {
            throw new QuizzesException(QuizzesErrorMessage.CANNOT_DELETE_LAST_EXECUTION_WITH_CONTENT, getAggregateId());
        }
    }
    public CourseType getType() {
        return type;
    }

    public String getName() {
        return name;
    }

    public int getCourseQuestionCount() {
        return courseQuestionCount;
    }

    public void setCourseQuestionCount(int courseQuestionCount) {
        this.courseQuestionCount = courseQuestionCount;
    }

    public int getCourseExecutionCount() {
        return courseExecutionCount;
    }

    public void setCourseExecutionCount(int courseExecutionCount) {
        this.courseExecutionCount = courseExecutionCount;
    }

    public Set<Topic> getTopics() {
        return topics;
    }

    public void setTopics(Set<Topic> topics) {
        this.topics = topics;
        this.topics.forEach(topic -> topic.setCourse(this));
    }

    public void addTopic(Topic topic) {
        this.topics.add(topic);
        topic.setCourse(this);
    }

    public Topic getTopic(Integer topicId) {
        return this.topics.stream()
                .filter(topic -> !topic.isDeleted() && topicId.equals(topic.getTopicId()))
                .findFirst()
                .orElseThrow(() -> new SimulatorException(SimulatorErrorMessage.AGGREGATE_NOT_FOUND, topicId));
    }
}
