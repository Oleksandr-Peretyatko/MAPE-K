package org.anonymous.msrefactor.quizzes.microservices.course.aggregate;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;

/*
 * Link between a question and a topic of the same course. It stopped being a
 * replica when Topic dissolved into Course: the copied name/version/state fields
 * are gone, and reads navigate to the local Topic member instead.
 */
@Entity
public class QuestionTopic {
    @Id
    @GeneratedValue
    private Long id;
    private Integer topicId;
    /*
     * TOPIC_BELONGS_TO_QUESTION_COURSE: the course the topic belonged to when it was
     * attached; Course.verifyInvariants() compares it against the owning root's id.
     */
    private Integer courseAggregateId;
    @ManyToOne
    private Question question;

    public QuestionTopic() {
    }

    public QuestionTopic(TopicDto topicDto) {
        setTopicId(topicDto.getAggregateId());
        setCourseAggregateId(topicDto.getCourseId());
    }

    public QuestionTopic(QuestionTopic other) {
        setTopicId(other.getTopicId());
        setCourseAggregateId(other.getCourseAggregateId());
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Integer getTopicId() {
        return topicId;
    }

    public void setTopicId(Integer topicId) {
        this.topicId = topicId;
    }

    public Integer getCourseAggregateId() {
        return courseAggregateId;
    }

    public void setCourseAggregateId(Integer courseAggregateId) {
        this.courseAggregateId = courseAggregateId;
    }

    @JsonIgnore
    public Question getQuestion() {
        return question;
    }

    public void setQuestion(Question question) {
        this.question = question;
    }

    public TopicDto buildDto() {
        TopicDto dto = new TopicDto();
        dto.setAggregateId(this.topicId);
        Course course = this.question == null ? null : this.question.getCourse();
        if (course != null) {
            dto.setVersion(course.getVersion());
            Topic topic = course.getTopicByTopicId(this.topicId);
            if (topic != null) {
                dto.setName(topic.getName());
                dto.setState(topic.getState());
            }
        }
        return dto;
    }

    @Override
    public int hashCode() {
        int hash = 7;
        hash = 31 * hash + getTopicId();
        return hash;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof QuestionTopic)) {
            return false;
        }
        QuestionTopic questionTopic = (QuestionTopic) obj;

        return getTopicId() != null && getTopicId().equals(questionTopic.getTopicId());
    }
}
