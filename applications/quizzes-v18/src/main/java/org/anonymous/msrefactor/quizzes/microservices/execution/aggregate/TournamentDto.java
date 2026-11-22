package org.anonymous.msrefactor.quizzes.microservices.execution.aggregate;

import org.anonymous.msrefactor.ms.utils.DateHandler;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.TopicDto;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;

import java.io.Serializable;
import java.util.Set;
import java.util.stream.Collectors;

public class TournamentDto implements Serializable {
    private Integer aggregateId;
    private Long version;
    private String startTime;
    private String endTime;
    private Integer numberOfQuestions;
    private boolean isCancelled;
    private UserDto creator;
    private Set<UserDto> participants;
    private CourseExecutionDto courseExecution;
    private Set<TopicDto> topics;
    private QuizDto quiz;
    private String state;

    public TournamentDto() {
    }

    public TournamentDto(Tournament tournament) {
        setAggregateId(tournament.getTournamentId());
        // members are versioned by their owning root
        setVersion(tournament.getExecution() == null ? null : tournament.getExecution().getVersion());
        setStartTime(DateHandler.toISOString(tournament.getStartTime()));
        setEndTime(DateHandler.toISOString(tournament.getEndTime()));
        setNumberOfQuestions(tournament.getNumberOfQuestions());
        setCancelled(tournament.isCancelled());
        setCreator(tournament.getTournamentCreator().buildDto());
        setParticipants(tournament.getTournamentParticipants().stream().map(TournamentParticipant::buildDto).collect(Collectors.toSet()));
        // the courseExecution field keeps its name; its source is now the owning root
        // (replaces the deleted TournamentCourseExecution replica)
        CourseExecutionDto courseExecutionDto = new CourseExecutionDto();
        Execution execution = tournament.getExecution();
        if (execution != null) {
            courseExecutionDto.setAggregateId(execution.getAggregateId());
            courseExecutionDto.setVersion(execution.getVersion());
            courseExecutionDto.setAcronym(execution.getAcronym());
            courseExecutionDto.setStatus(execution.getState().toString());
        }
        setCourseExecution(courseExecutionDto);
        setTopics(tournament.getTournamentTopics().stream().map(TournamentTopic::buildDto).collect(Collectors.toSet()));
        // the quiz field keeps its name; its source is the retained quiz member identifier
        QuizDto quizDto = new QuizDto();
        quizDto.setAggregateId(tournament.getQuizId());
        setQuiz(quizDto);
        setState(tournament.getState().toString());
    }

    public Integer getAggregateId() {
        return aggregateId;
    }

    public void setAggregateId(Integer aggregateId) {
        this.aggregateId = aggregateId;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public String getStartTime() {
        return startTime;
    }

    public void setStartTime(String startTime) {
        this.startTime = startTime;
    }

    public String getEndTime() {
        return endTime;
    }

    public void setEndTime(String endTime) {
        this.endTime = endTime;
    }

    public Integer getNumberOfQuestions() {
        return numberOfQuestions;
    }

    public void setNumberOfQuestions(Integer numberOfQuestions) {
        this.numberOfQuestions = numberOfQuestions;
    }

    public boolean isCancelled() {
        return isCancelled;
    }

    public void setCancelled(boolean cancelled) {
        isCancelled = cancelled;
    }

    public UserDto getCreator() {
        return creator;
    }

    public void setCreator(UserDto creator) {
        this.creator = creator;
    }

    public Set<UserDto> getParticipants() {
        return participants;
    }

    public void setParticipants(Set<UserDto> participants) {
        this.participants = participants;
    }

    public CourseExecutionDto getCourseExecution() {
        return courseExecution;
    }

    public void setCourseExecution(CourseExecutionDto courseExecution) {
        this.courseExecution = courseExecution;
    }

    public Set<TopicDto> getTopics() {
        return topics;
    }

    public void setTopics(Set<TopicDto> topics) {
        this.topics = topics;
    }

    public QuizDto getQuiz() {
        return quiz;
    }

    public void setQuiz(QuizDto quiz) {
        this.quiz = quiz;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public UserDto findParticipant(Integer userAggregateId) {
        return this.participants.stream().filter(p -> p.getAggregateId().equals(userAggregateId)).findFirst()
                .orElse(null);
    }
}
