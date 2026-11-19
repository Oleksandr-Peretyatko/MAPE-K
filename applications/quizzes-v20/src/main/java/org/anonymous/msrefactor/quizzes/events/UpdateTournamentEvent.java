package org.anonymous.msrefactor.quizzes.events;

import jakarta.persistence.Entity;
import org.anonymous.msrefactor.ms.aggregate.Event;

/*
 * Published by the tournament core when startTime/endTime/cancelled change
 * (update and cancel). Consumed by the tournamentSatellite aggregate to refresh
 * its replicated startTime/cancelled scalars (TOURNAMENT_INFO).
 */
@Entity
public class UpdateTournamentEvent extends Event {
    private String startTime;
    private String endTime;
    private boolean cancelled;

    public UpdateTournamentEvent() {
    }

    public UpdateTournamentEvent(Integer tournamentAggregateId, String startTime, String endTime, boolean cancelled) {
        super(tournamentAggregateId);
        setStartTime(startTime);
        setEndTime(endTime);
        setCancelled(cancelled);
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

    public boolean isCancelled() {
        return cancelled;
    }

    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }
}
