package org.anonymous.msrefactor.quizzes.microservices.course.aggregate;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import org.anonymous.msrefactor.ms.aggregate.Aggregate;
import org.anonymous.msrefactor.ms.utils.DateHandler;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/*
    Member entity of the Course aggregate (formerly the Question aggregate root).
    Its intra-invariants are enforced by Course.verifyInvariants():
        QUESTION_TOPIC_INVALID_COURSE (header name TOPIC_BELONGS_TO_QUESTION_COURSE)
    COURSE_EXISTS holds structurally: the question belongs to exactly one Course
    root for its whole life. TOPICS_EXIST stays an inter-invariant, registered by
    Course.getEventSubscriptions() over this member's topics.
 */
@Entity
public class Question {
    @Id
    @GeneratedValue
    private Integer id;
    /*
     * Entity identity inside the Course aggregate: the value of the old question
     * aggregateId. Commands, DTOs and replicas in other services keep resolving it.
     */
    private Integer questionId;
    private Aggregate.AggregateState state;
    private String title;
    private String content;
    private LocalDateTime creationDate;
    @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER, mappedBy = "question")
    private Set<QuestionTopic> questionTopics = new HashSet<>();
    @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER, mappedBy = "question")
    private List<Option> options = new ArrayList<>();
    @ManyToOne
    private Course course;

    public Question() {

    }

    public Question(Integer questionId, QuestionDto questionDto, List<QuestionTopic> questionTopics) {
        setQuestionId(questionId);
        setState(Aggregate.AggregateState.ACTIVE);
        setTitle(questionDto.getTitle());
        setContent(questionDto.getContent());
        setCreationDate(DateHandler.now());
        setOptions(questionDto.getOptionDtos().stream().map(Option::new).collect(Collectors.toList()));

        Integer optionKeyGenerator = 1;
        for (Option o : getOptions()) {
            o.setOptionKey(optionKeyGenerator++);
        }

        setQuestionTopics(new HashSet<>(questionTopics));
    }

    public Question(Question other) {
        setQuestionId(other.getQuestionId());
        setState(other.getState());
        setTitle(other.getTitle());
        setContent(other.getContent());
        setCreationDate(other.getCreationDate());
        setOptions(other.getOptions().stream().map(Option::new).collect(Collectors.toList()));
        setQuestionTopics(other.getQuestionTopics().stream().map(QuestionTopic::new).collect(Collectors.toSet()));
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public Integer getQuestionId() {
        return questionId;
    }

    public void setQuestionId(Integer questionId) {
        this.questionId = questionId;
    }

    public Aggregate.AggregateState getState() {
        return state;
    }

    public void setState(Aggregate.AggregateState state) {
        this.state = state;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public LocalDateTime getCreationDate() {
        return creationDate;
    }

    public void setCreationDate(LocalDateTime creationDate) {
        this.creationDate = creationDate;
    }

    @JsonIgnore
    public Course getCourse() {
        return course;
    }

    public void setCourse(Course course) {
        this.course = course;
    }

    public Set<QuestionTopic> getQuestionTopics() {
        return questionTopics;
    }

    public void setQuestionTopics(Set<QuestionTopic> topics) {
        this.questionTopics = topics;
        this.questionTopics.forEach(questionTopic -> questionTopic.setQuestion(this));
    }

    public List<Option> getOptions() {
        return options;
    }

    public void setOptions(List<Option> options) {
        this.options = options;
        this.options.forEach(option -> option.setQuestion(this));
    }

    public void update(QuestionDto questionDto) {
        setTitle(questionDto.getTitle());
        setContent(questionDto.getContent());
        setOptions(questionDto.getOptionDtos().stream().map(Option::new).collect(Collectors.toList()));
    }

    public QuestionTopic findTopic(Integer topicAggregateId) {
        for (QuestionTopic questionTopic : this.questionTopics) {
            if (questionTopic.getTopicAggregateId().equals(topicAggregateId)) {
                return questionTopic;
            }
        }
        return null;
    }
}
