package org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.aggregate;

import org.anonymous.msrefactor.ms.utils.DateHandler;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;

import java.io.Serializable;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

public class TournamentSatelliteSatelliteDto implements Serializable {
    private Integer aggregateId;
    private Long version;
    private Integer tournamentAggregateId;
    private String startTime;
    private boolean cancelled;
    private Integer courseExecutionAggregateId;
    private Set<UserDto> participants = new HashSet<>();
    private String state;

    public TournamentSatelliteSatelliteDto() {
    }

    public TournamentSatelliteSatelliteDto(TournamentSatelliteSatellite tournamentSatelliteSatellite) {
        setAggregateId(tournamentSatelliteSatellite.getAggregateId());
        setVersion(tournamentSatelliteSatellite.getVersion());
        setTournamentAggregateId(tournamentSatelliteSatellite.getTournamentAggregateId());
        setStartTime(DateHandler.toISOString(tournamentSatelliteSatellite.getStartTime()));
        setCancelled(tournamentSatelliteSatellite.isCancelled());
        setCourseExecutionAggregateId(tournamentSatelliteSatellite.getCourseExecutionAggregateId());
        setParticipants(tournamentSatelliteSatellite.getTournamentParticipants().stream()
                .map(TournamentParticipant::buildDto).collect(Collectors.toSet()));
        setState(tournamentSatelliteSatellite.getState().toString());
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

    public Set<UserDto> getParticipants() {
        return participants;
    }

    public void setParticipants(Set<UserDto> participants) {
        this.participants = participants;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }
}
