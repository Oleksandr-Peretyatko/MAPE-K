package org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.aggregate;

import jakarta.persistence.*;
import org.anonymous.msrefactor.ms.aggregate.Aggregate;
import org.anonymous.msrefactor.ms.aggregate.EventSubscription;
import org.anonymous.msrefactor.ms.aggregate.InterInvariant;
import org.anonymous.msrefactor.quizzes.microservices.exception.QuizzesException;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.notification.subscribe.*;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

import static org.anonymous.msrefactor.quizzes.microservices.exception.QuizzesErrorMessage.INVARIANT_BREAK;

/*
    TournamentSatellite holds the creator side of a Tournament. Its participants were split out into the
    TournamentSatelliteSatellite aggregate/service; this root keeps the creator, the replicated
    startTime, cancelled and courseExecution, and coordinates the core Tournament (set INACTIVE on
    creator anonymize/remove) by command.

    INTRA-INVARIANTS:
        CREATOR_IS_NOT_ANONYMOUS
        IS_CANCELED                 (duplicated with the participant satellite; this side: cancelled scalar)
    INTER-INVARIANTS:
        CREATOR_EXISTS / CREATOR_COURSE_EXECUTION
 */
@Entity
public abstract class TournamentSatellite extends Aggregate {
    private Integer tournamentAggregateId;
    private LocalDateTime startTime;
    private boolean cancelled;
    private Integer courseExecutionAggregateId;
    private Long courseExecutionVersion;

    @OneToOne(cascade = CascadeType.ALL, mappedBy = "tournamentSatellite")
    private TournamentCreator tournamentCreator;

    public TournamentSatellite() {
    }

    public TournamentSatellite(Integer aggregateId, Integer tournamentAggregateId, UserDto creatorDto,
            Integer courseExecutionAggregateId, Long courseExecutionVersion, LocalDateTime startTime,
            boolean cancelled) {
        super(aggregateId);
        setAggregateType(getClass().getSimpleName());
        setTournamentAggregateId(tournamentAggregateId);
        setCourseExecutionAggregateId(courseExecutionAggregateId);
        setCourseExecutionVersion(courseExecutionVersion);
        setStartTime(startTime);
        setCancelled(cancelled);
        setTournamentCreator(new TournamentCreator(creatorDto.getAggregateId(), creatorDto.getName(),
                creatorDto.getUsername(), creatorDto.getVersion()));
    }

    /* used to update the satellite by creating new versions */
    public TournamentSatellite(TournamentSatellite other) {
        super(other);
        this.tournamentAggregateId = other.getTournamentAggregateId();
        this.startTime = other.getStartTime();
        this.cancelled = other.isCancelled();
        this.courseExecutionAggregateId = other.getCourseExecutionAggregateId();
        this.courseExecutionVersion = other.getCourseExecutionVersion();
        setTournamentCreator(new TournamentCreator(other.getTournamentCreator()));
    }

    @Override
    public void remove() {
        super.remove();
    }

    @Override
    public Set<EventSubscription> getEventSubscriptions() {
        Set<EventSubscription> eventSubscriptions = new HashSet<>();
        if (this.getState() == AggregateState.ACTIVE) {
            interInvariantMembersExist(eventSubscriptions);
        }
        return eventSubscriptions;
    }

    @InterInvariant({ "CREATOR_EXISTS", "CREATOR_COURSE_EXECUTION" })
    private void interInvariantMembersExist(Set<EventSubscription> eventSubscriptions) {
        eventSubscriptions.add(new TournamentSatelliteSubscribesDisenrollStudentFromCourseExecution(this));
        eventSubscriptions.add(new TournamentSatelliteSubscribesAnonymizeStudent(this));
        eventSubscriptions.add(new TournamentSatelliteSubscribesUpdateStudentName(this));
    }

    /*
     * ----------------------------------------- INTRA-AGGREGATE INVARIANTS
     * -----------------------------------------
     */

    private boolean invariantCreatorIsNotAnonymous() {
        return !tournamentCreator.getCreatorName().equals("ANONYMOUS")
                && !tournamentCreator.getCreatorUsername().equals("ANONYMOUS");
    }

    /*
     * IS_CANCELED (duplicated with the participant satellite; this side enforces the cancelled scalar)
     */
    private boolean invariantCancelledFieldsAreFinal() {
        TournamentSatellite prev = (TournamentSatellite) getPrev();
        if (prev != null && prev.isCancelled()) {
            return this.cancelled == prev.isCancelled();
        }
        return true;
    }

    @Override
    public void verifyInvariants() {
        if (getState() == AggregateState.ACTIVE) {
            if (!(invariantCreatorIsNotAnonymous()
                    && invariantCancelledFieldsAreFinal())) {
                throw new QuizzesException(INVARIANT_BREAK, getAggregateId());
            }
        }
    }

    public Integer getTournamentAggregateId() {
        return tournamentAggregateId;
    }

    public void setTournamentAggregateId(Integer tournamentAggregateId) {
        this.tournamentAggregateId = tournamentAggregateId;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalDateTime startTime) {
        this.startTime = startTime;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    public void cancel() {
        this.cancelled = true;
    }

    public Integer getCourseExecutionAggregateId() {
        return courseExecutionAggregateId;
    }

    public void setCourseExecutionAggregateId(Integer courseExecutionAggregateId) {
        this.courseExecutionAggregateId = courseExecutionAggregateId;
    }

    public Long getCourseExecutionVersion() {
        return courseExecutionVersion;
    }

    public void setCourseExecutionVersion(Long courseExecutionVersion) {
        this.courseExecutionVersion = courseExecutionVersion;
    }

    public TournamentCreator getTournamentCreator() {
        return tournamentCreator;
    }

    public void setTournamentCreator(TournamentCreator tournamentCreator) {
        this.tournamentCreator = tournamentCreator;
        tournamentCreator.setTournamentSatellite(this);
    }

    public TournamentSatelliteDto buildDto() {
        return new TournamentSatelliteDto(this);
    }
}
