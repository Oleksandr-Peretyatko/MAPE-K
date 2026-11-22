package org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.aggregate;

import jakarta.persistence.*;
import org.anonymous.msrefactor.ms.aggregate.Aggregate;
import org.anonymous.msrefactor.ms.aggregate.EventSubscription;
import org.anonymous.msrefactor.ms.aggregate.InterInvariant;
import org.anonymous.msrefactor.ms.utils.DateHandler;
import org.anonymous.msrefactor.quizzes.microservices.exception.QuizzesException;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.CourseExecutionDto;
import org.anonymous.msrefactor.quizzes.microservices.tournament.aggregate.TournamentDto;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.notification.subscribe.*;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

import static org.anonymous.msrefactor.quizzes.microservices.exception.QuizzesErrorMessage.INVARIANT_BREAK;

/*
 * Satellite aggregate split out of Tournament: it owns the creator. The
 * participant roster (with its quiz-answer replicas) was split out again into
 * the TournamentSatelliteSatellite aggregate. It references the core Tournament
 * by id only, and keeps local copies of the core's startTime and cancelled
 * scalars, refreshed by UpdateTournamentEvent.
 *
    INTRA-INVARIANTS:
        CREATOR_IS_FINAL
        CREATOR_IS_NOT_ANONYMOUS
    INTER-INVARIANTS:
        CREATOR_COURSE_EXECUTION
        CREATOR_STUDENT
        CREATOR_EXISTS
        TOURNAMENT_INFO
        CREATOR_PARTICIPANT_CONSISTENCY
 *
 * CREATOR_PARTICIPANT_CONSISTENCY was intra-aggregate before the roster split;
 * the participants now live on TournamentSatelliteSatellite, so the rule is the
 * reconciliation property that both roots' replicas of the same User converge
 * through the same user events (AnonymizeStudent, UpdateStudentName,
 * Disenroll) each root subscribes to independently.
 */
@Entity
public abstract class TournamentSatellite extends Aggregate {
    private Integer tournamentAggregateId;
    private Long tournamentVersion;
    /* replicated core scalars — refreshed by UpdateTournamentEvent */
    private LocalDateTime startTime;
    private boolean cancelled;
    /* anchor of the student-event subscriptions, replicated from the core */
    private Integer courseExecutionAggregateId;
    private Long courseExecutionVersion;
    /*
     * CREATOR_IS_FINAL
     * final this.creator.id
     */
    @OneToOne(cascade = CascadeType.ALL, mappedBy = "tournamentSatellite")
    private TournamentCreator tournamentCreator;

    public TournamentSatellite() {
    }

    public TournamentSatellite(Integer aggregateId, Integer tournamentAggregateId, TournamentDto tournamentDto,
            UserDto creatorDto, CourseExecutionDto courseExecutionDto) {
        super(aggregateId);
        setAggregateType(getClass().getSimpleName());
        setTournamentAggregateId(tournamentAggregateId);
        setTournamentVersion(0L);
        setStartTime(DateHandler.toLocalDateTime(tournamentDto.getStartTime()));
        setCancelled(tournamentDto.isCancelled());
        setCourseExecutionAggregateId(courseExecutionDto.getAggregateId());
        setCourseExecutionVersion(courseExecutionDto.getVersion());
        setTournamentCreator(new TournamentCreator(creatorDto.getAggregateId(), creatorDto.getName(),
                creatorDto.getUsername(), creatorDto.getVersion()));
    }

    /* used to update the satellite by creating new versions */
    public TournamentSatellite(TournamentSatellite other) {
        super(other);
        this.tournamentAggregateId = other.getTournamentAggregateId();
        this.tournamentVersion = other.getTournamentVersion();
        this.startTime = other.getStartTime();
        this.cancelled = other.isCancelled();
        this.courseExecutionAggregateId = other.getCourseExecutionAggregateId();
        this.courseExecutionVersion = other.getCourseExecutionVersion();
        setTournamentCreator(new TournamentCreator(other.getTournamentCreator()));
    }

    @Override
    public Set<EventSubscription> getEventSubscriptions() {
        Set<EventSubscription> eventSubscriptions = new HashSet<>();
        if (this.getState() == AggregateState.ACTIVE) {
            interInvariantCreatorExists(eventSubscriptions);
            interInvariantTournamentInfo(eventSubscriptions);
        }
        return eventSubscriptions;
    }

    @InterInvariant({ "CREATOR_EXISTS", "CREATOR_COURSE_EXECUTION" })
    private void interInvariantCreatorExists(Set<EventSubscription> eventSubscriptions) {
        eventSubscriptions.add(new TournamentSatelliteSubscribesDisenrollStudentFromCourseExecution(this));
        eventSubscriptions.add(new TournamentSatelliteSubscribesAnonymizeStudent(this));
        eventSubscriptions.add(new TournamentSatelliteSubscribesUpdateStudentName(this));
    }

    /*
     * TOURNAMENT_INFO
     * this.startTime == Tournament(this.tournamentAggregateId).startTime &&
     * this.cancelled == Tournament(this.tournamentAggregateId).cancelled
     * — keeps the replicated scalars current. TournamentSatelliteSatellite keeps
     * its own copy of the same scalars and its own subscription; the replica is
     * read by both sides, so it is duplicated rather than re-pointed.
     */
    @InterInvariant("TOURNAMENT_INFO")
    private void interInvariantTournamentInfo(Set<EventSubscription> eventSubscriptions) {
        eventSubscriptions.add(new TournamentSatelliteSubscribesUpdateTournament(this));
    }

    /*
     * ----------------------------------------- INTRA-AGGREGATE INVARIANTS
     * -----------------------------------------
     */

    /*
     * CREATOR_IS_NOT_ANONYMOUS
     */
    private boolean invariantCreatorIsNotAnonymous() {
        return !tournamentCreator.getCreatorName().equals("ANONYMOUS")
                && !tournamentCreator.getCreatorUsername().equals("ANONYMOUS");
    }

    @Override
    public void verifyInvariants() {
        /*
         * Invariants only apply to ACTIVE aggregates. INACTIVE aggregates (e.g. after
         * anonymisation) are excluded because their creator name may be "ANONYMOUS".
         */
        if (getState() == AggregateState.ACTIVE) {
            if (!invariantCreatorIsNotAnonymous()) {
                throw new QuizzesException(INVARIANT_BREAK, getTournamentAggregateId());
            }
        }
    }

    public Integer getTournamentAggregateId() {
        return tournamentAggregateId;
    }

    public void setTournamentAggregateId(Integer tournamentAggregateId) {
        this.tournamentAggregateId = tournamentAggregateId;
    }

    public Long getTournamentVersion() {
        return tournamentVersion;
    }

    public void setTournamentVersion(Long tournamentVersion) {
        this.tournamentVersion = tournamentVersion;
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
        TournamentSatelliteDto dto = new TournamentSatelliteDto();
        dto.setAggregateId(getAggregateId());
        dto.setVersion(getVersion());
        dto.setState(getState() == null ? null : getState().toString());
        dto.setTournamentAggregateId(getTournamentAggregateId());
        dto.setStartTime(DateHandler.toISOString(getStartTime()));
        dto.setCancelled(isCancelled());
        dto.setCourseExecutionAggregateId(getCourseExecutionAggregateId());
        dto.setCreator(getTournamentCreator().buildDto());
        return dto;
    }
}
