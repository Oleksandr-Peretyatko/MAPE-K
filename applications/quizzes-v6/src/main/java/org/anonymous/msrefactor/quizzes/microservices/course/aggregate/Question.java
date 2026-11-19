package org.anonymous.msrefactor.quizzes.microservices.course.aggregate;

import jakarta.persistence.*;
import org.anonymous.msrefactor.ms.aggregate.Aggregate;
import org.anonymous.msrefactor.ms.utils.DateHandler;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/*
    Member entity of Course (merged from the former Question aggregate, merge course <- question).
    INTRA-INVARIANTS (verified from Course.verifyInvariants(), per question member):
        QUESTION_TOPIC_INVALID_COURSE
    TOPICS_EXIST inter-invariant is registered from Course.getEventSubscriptions().
 */
@Entity
public class Question {
    @Id
    @GeneratedValue
    private Long id;
    // entity identity: the former aggregateId value. Not an aggregate id any more.
    private Integer questionId;
    @ManyToOne
    private Course course;

    private String title;
    private String content;
    private LocalDateTime creationDate;
    @Enumerated(EnumType.STRING)
    private Aggregate.AggregateState state;
    @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER, mappedBy = "question")
    private Set<QuestionTopic> questionTopics = new HashSet<>();
    @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER, mappedBy = "question")
    private List<Option> options = new ArrayList<>();

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

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Integer getQuestionId() {
        return questionId;
    }

    public void setQuestionId(Integer questionId) {
        this.questionId = questionId;
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public Course getCourse() {
        return course;
    }

    public void setCourse(Course course) {
        this.course = course;
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
