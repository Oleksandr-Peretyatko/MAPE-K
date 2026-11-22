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
        TOPIC_BELONGS_TO_QUESTION_COURSE (per question member)
        TOPICS_EXIST (was an inter-invariant maintained through Update/DeleteTopicEvent;
            the topics a question references are local members now, so the reference
            resolves by containment and topic renames/deletions are visible to the
            course's questions in the same commit)
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
    private int courseExecutionCount = 0;

    @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER, mappedBy = "course")
    private Set<Question> questions = new HashSet<>();

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
        this.courseExecutionCount = other.getCourseExecutionCount();
        setQuestions(other.getQuestions().stream().map(Question::new).collect(Collectors.toSet()));
        setTopics(other.getTopics().stream().map(Topic::new).collect(Collectors.toSet()));
    }

    @Override
    public Set<EventSubscription> getEventSubscriptions() {
        return new HashSet<>();
    }

    @Override
    public void verifyInvariants() {
        // CANNOT_DELETE_LAST_EXECUTION_WITH_CONTENT: was a remote counter
        // (courseQuestionCount) kept current by UpdateCourseQuestionCountCommand;
        // now a direct check over the owned collection
        if (courseExecutionCount == 0 && countActiveQuestions() > 0) {
            throw new QuizzesException(QuizzesErrorMessage.CANNOT_DELETE_LAST_EXECUTION_WITH_CONTENT, getAggregateId());
        }
        for (Question question : this.questions) {
            if (question.getState() == AggregateState.DELETED) {
                continue;
            }
            verifyQuestionInvariants(question);
        }
    }

    /*
        TOPIC_BELONGS_TO_QUESTION_COURSE: the old check compared each topic's
        denormalised course id against the question's Course replica; the replica is
        gone, so it compares against the owning root's own id
     */
    private void verifyQuestionInvariants(Question question) {
        for (QuestionTopic qt : question.getQuestionTopics()) {
            if (!qt.getCourseAggregateId().equals(getAggregateId())) {
                throw new QuizzesException(QuizzesErrorMessage.QUESTION_TOPIC_INVALID_COURSE,
                        qt.getTopicId(), getAggregateId());
            }
        }
    }

    public CourseType getType() {
        return type;
    }

    public String getName() {
        return name;
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

    public Question getQuestionByQuestionId(Integer questionId) {
        for (Question question : this.questions) {
            if (question.getQuestionId().equals(questionId) && question.getState() != AggregateState.DELETED) {
                return question;
            }
        }
        return null;
    }

    public int countActiveQuestions() {
        int count = 0;
        for (Question question : this.questions) {
            if (question.getState() != AggregateState.DELETED) {
                count++;
            }
        }
        return count;
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

    public Topic getTopicByTopicId(Integer topicId) {
        for (Topic topic : this.topics) {
            if (topic.getTopicId().equals(topicId) && topic.getState() != AggregateState.DELETED) {
                return topic;
            }
        }
        return null;
    }

    public int getCourseExecutionCount() {
        return courseExecutionCount;
    }

    public void setCourseExecutionCount(int courseExecutionCount) {
        this.courseExecutionCount = courseExecutionCount;
    }
}
