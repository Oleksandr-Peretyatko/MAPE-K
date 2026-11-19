package org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.aggregate;

import org.anonymous.msrefactor.ms.utils.DateHandler;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;

import java.io.Serializable;

public class TournamentSatelliteDto implements Serializable {
    private Integer aggregateId;
    private Long version;
    private Integer tournamentAggregateId;
    private String startTime;
    private boolean cancelled;
    private Integer courseExecutionAggregateId;
    private UserDto creator;
    private String state;

    public TournamentSatelliteDto() {
    }

    public TournamentSatelliteDto(TournamentSatellite tournamentSatellite) {
        setAggregateId(tournamentSatellite.getAggregateId());
        setVersion(tournamentSatellite.getVersion());
        setTournamentAggregateId(tournamentSatellite.getTournamentAggregateId());
        setStartTime(DateHandler.toISOString(tournamentSatellite.getStartTime()));
        setCancelled(tournamentSatellite.isCancelled());
        setCourseExecutionAggregateId(tournamentSatellite.getCourseExecutionAggregateId());
        if (tournamentSatellite.getTournamentCreator() != null) {
            setCreator(tournamentSatellite.getTournamentCreator().buildDto());
        }
        setState(tournamentSatellite.getState().toString());
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

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }
}
