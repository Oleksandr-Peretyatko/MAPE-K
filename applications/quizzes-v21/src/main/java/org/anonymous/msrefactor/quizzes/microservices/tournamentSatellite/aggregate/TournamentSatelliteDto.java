package org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.aggregate;

import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;

import java.io.Serializable;

public class TournamentSatelliteDto implements Serializable {
    private Integer aggregateId;
    private Long version;
    private String state;
    /* the id the rest of the system holds — points back at the core Tournament */
    private Integer tournamentAggregateId;
    private String startTime;
    private boolean cancelled;
    private Integer courseExecutionAggregateId;
    private UserDto creator;

    public TournamentSatelliteDto() {
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

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public Integer getTournamentAggregateId() {
        return tournamentAggregateId;
    }

    public void setTournamentAggregateId(Integer tournamentAggregateId) {
        this.tournamentAggregateId = tournamentAggregateId;
    }

    public String getStartTime() {
        return startTime;
    }

    public void setStartTime(String startTime) {
        this.startTime = startTime;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    public Integer getCourseExecutionAggregateId() {
        return courseExecutionAggregateId;
    }

    public void setCourseExecutionAggregateId(Integer courseExecutionAggregateId) {
        this.courseExecutionAggregateId = courseExecutionAggregateId;
    }

    public UserDto getCreator() {
        return creator;
    }

    public void setCreator(UserDto creator) {
        this.creator = creator;
    }
}
