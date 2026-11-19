package org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.aggregate;

import jakarta.persistence.*;
import org.anonymous.msrefactor.ms.aggregate.Aggregate;
import org.anonymous.msrefactor.ms.aggregate.EventSubscription;
import org.anonymous.msrefactor.ms.aggregate.InterInvariant;
import org.anonymous.msrefactor.ms.utils.DateHandler;
import org.anonymous.msrefactor.quizzes.microservices.exception.QuizzesException;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.notification.subscribe.*;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.anonymous.msrefactor.quizzes.microservices.exception.QuizzesErrorMessage.INVARIANT_BREAK;

/*
    TournamentSatelliteSatellite holds the participant side of a TournamentSatellite: its participants
    (with their quiz answers). It is the authoritative owner of enrollTime and the participant answers.
    It replicates the core TournamentSatellite's startTime, cancelled, courseExecution and the creator
    identity so the membership invariants (including creator/participant consistency) stay
    intra-aggregate. The replicated scalars are refreshed by the core's sync commands (startTime,
    cancelled) and by the same student events the core consumes (creator name/anonymize).

    INTRA-INVARIANTS:
        UNIQUE_AS_PARTICIPANT
        ENROLL_UNTIL_START_TIME     (uses replicated startTime)
        ANSWER_BEFORE_START         (uses replicated startTime)
        CREATOR_PARTICIPANT_CONSISTENCY (uses replicated creator identity)
        IS_CANCELED                 (duplicated with core; this side: participants + cancelled)
        DELETE
    INTER-INVARIANTS:
        PARTICIPANT_EXISTS / PARTICIPANT_COURSE_EXECUTION
        QUIZ_ANSWER_EXISTS
 */
@Entity
public abstract class TournamentSatelliteSatellite extends Aggregate {
    private Integer tournamentAggregateId;
    private LocalDateTime startTime;
    private boolean cancelled;
    private Integer courseExecutionAggregateId;
    private Long courseExecutionVersion;

    // replicated creator identity (authoritative copy lives on the core TournamentSatellite); kept here
    // only so CREATOR_PARTICIPANT_CONSISTENCY stays intra-aggregate (split layer 1.4 / case 2).
    private Integer creatorAggregateId;
    private String creatorName;
    private String creatorUsername;
    private Long creatorVersion;

    @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER, mappedBy = "tournamentSatelliteSatellite")
    private Set<TournamentParticipant> tournamentParticipants = new HashSet<>();

    public TournamentSatelliteSatellite() {
    }

    public TournamentSatelliteSatellite(Integer aggregateId, Integer tournamentAggregateId, UserDto creatorDto,
            Integer courseExecutionAggregateId, Long courseExecutionVersion, LocalDateTime startTime,
            boolean cancelled) {
        super(aggregateId);
        setAggregateType(getClass().getSimpleName());
        setTournamentAggregateId(tournamentAggregateId);
        setCourseExecutionAggregateId(courseExecutionAggregateId);
        setCourseExecutionVersion(courseExecutionVersion);
        setStartTime(startTime);
        setCancelled(cancelled);
        if (creatorDto != null) {
            setCreatorAggregateId(creatorDto.getAggregateId());
            setCreatorName(creatorDto.getName());
            setCreatorUsername(creatorDto.getUsername());
            setCreatorVersion(creatorDto.getVersion());
        }
    }

    /* used to update the satellite by creating new versions */
    public TournamentSatelliteSatellite(TournamentSatelliteSatellite other) {
        super(other);
        this.tournamentAggregateId = other.getTournamentAggregateId();
        this.startTime = other.getStartTime();
        this.cancelled = other.isCancelled();
        this.courseExecutionAggregateId = other.getCourseExecutionAggregateId();
        this.courseExecutionVersion = other.getCourseExecutionVersion();
        this.creatorAggregateId = other.getCreatorAggregateId();
        this.creatorName = other.getCreatorName();
        this.creatorUsername = other.getCreatorUsername();
        this.creatorVersion = other.getCreatorVersion();
        setTournamentParticipants(
                other.getTournamentParticipants().stream().map(TournamentParticipant::new).collect(Collectors.toSet()));
    }

    @Override
    public void remove() {
        /*
         * DELETE
         * this.state == DELETED => this.participants.empty
         */
        super.remove();
    }

    @Override
    public Set<EventSubscription> getEventSubscriptions() {
        Set<EventSubscription> eventSubscriptions = new HashSet<>();
        if (this.getState() == AggregateState.ACTIVE) {
            interInvariantMembersExist(eventSubscriptions);
            interInvariantQuizAnswersExist(eventSubscriptions);
        }
        return eventSubscriptions;
    }

    @InterInvariant({ "PARTICIPANT_EXISTS", "PARTICIPANT_COURSE_EXECUTION" })
    private void interInvariantMembersExist(Set<EventSubscription> eventSubscriptions) {
        eventSubscriptions.add(new TournamentSatelliteSatelliteSubscribesDisenrollStudentFromCourseExecution(this));
        eventSubscriptions.add(new TournamentSatelliteSatelliteSubscribesAnonymizeStudent(this));
        eventSubscriptions.add(new TournamentSatelliteSatelliteSubscribesUpdateStudentName(this));
    }

    @InterInvariant("QUIZ_ANSWER_EXISTS")
    private void interInvariantQuizAnswersExist(Set<EventSubscription> eventSubscriptions) {
        for (TournamentParticipant tournamentParticipant : this.tournamentParticipants) {
            if (tournamentParticipant.getParticipantAnswer().getQuizAnswerAggregateId() != null) {
                eventSubscriptions.add(new TournamentSatelliteSatelliteSubscribesAnswerQuestion(tournamentParticipant));
            }
        }
    }

    /*
     * ----------------------------------------- INTRA-AGGREGATE INVARIANTS
     * -----------------------------------------
     */

    public boolean invariantUniqueParticipant() {
        return this.tournamentParticipants.size() == this.tournamentParticipants.stream()
                .map(TournamentParticipant::getParticipantAggregateId)
                .distinct()
                .count();
    }

    public boolean invariantParticipantsEnrolledBeforeStarTime() {
        if (this.startTime == null) {
            return true;
        }
        for (TournamentParticipant p : this.tournamentParticipants) {
            if (p.getEnrollTime().isAfter(this.startTime)) {
                return false;
            }
        }
        return true;
    }

    public boolean invariantAnswerBeforeStart() {
        if (this.startTime != null && DateHandler.now().isBefore(this.startTime)) {
            for (TournamentParticipant t : this.tournamentParticipants) {
                if (t.getParticipantAnswer().getQuizAnswerAggregateId() != null) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean invariantCreatorParticipantConsistency() {
        if (this.creatorAggregateId == null) {
            return true;
        }
        return this.tournamentParticipants.stream()
                .noneMatch(p -> p.getParticipantAggregateId().equals(this.creatorAggregateId)
                        && (!p.getParticipantVersion().equals(this.creatorVersion)
                                || !p.getParticipantName().equals(this.creatorName)
                                || !p.getParticipantUsername().equals(this.creatorUsername)));
    }

    /*
     * IS_CANCELED (duplicated with the core; this side enforces the participant + cancelled side)
     */
    private boolean invariantCancelledFieldsAreFinal() {
        TournamentSatelliteSatellite prev = (TournamentSatelliteSatellite) getPrev();
        if (prev != null && prev.isCancelled()) {
            return this.cancelled == prev.isCancelled()
                    && this.tournamentParticipants.stream().map(TournamentParticipant::getParticipantAggregateId)
                            .collect(Collectors.toSet())
                            .equals(prev.getTournamentParticipants().stream()
                                    .map(TournamentParticipant::getParticipantAggregateId).collect(Collectors.toSet()));
        }
        return true;
    }

    /*
     * DELETE
     * this.state == DELETED => this.participants.empty
     */
    private boolean invariantDeleteWhenNoParticipants() {
        if (getState() == AggregateState.DELETED) {
            return getTournamentParticipants().size() == 0;
        }
        return true;
    }

    @Override
    public void verifyInvariants() {
        if (!invariantDeleteWhenNoParticipants()) {
            throw new QuizzesException(INVARIANT_BREAK, getAggregateId());
        }
        if (getState() == AggregateState.ACTIVE) {
            if (!(invariantAnswerBeforeStart()
                    && invariantUniqueParticipant()
                    && invariantParticipantsEnrolledBeforeStarTime()
                    && invariantCreatorParticipantConsistency()
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

    public Integer getCreatorAggregateId() {
        return creatorAggregateId;
    }

    public void setCreatorAggregateId(Integer creatorAggregateId) {
        this.creatorAggregateId = creatorAggregateId;
    }

    public String getCreatorName() {
        return creatorName;
    }

    public void setCreatorName(String creatorName) {
        this.creatorName = creatorName;
    }

    public String getCreatorUsername() {
        return creatorUsername;
    }

    public void setCreatorUsername(String creatorUsername) {
        this.creatorUsername = creatorUsername;
    }

    public Long getCreatorVersion() {
        return creatorVersion;
    }

    public void setCreatorVersion(Long creatorVersion) {
        this.creatorVersion = creatorVersion;
    }

    public Set<TournamentParticipant> getTournamentParticipants() {
        return tournamentParticipants;
    }

    public void setTournamentParticipants(Set<TournamentParticipant> tournamentParticipants) {
        this.tournamentParticipants = tournamentParticipants;
        this.tournamentParticipants
                .forEach(tournamentParticipant -> tournamentParticipant.setTournamentSatelliteSatellite(this));
    }

    public void addParticipant(TournamentParticipant participant) {
        this.tournamentParticipants.add(participant);
        participant.setTournamentSatelliteSatellite(this);
    }

    public TournamentParticipant findParticipant(Integer userAggregateId) {
        return this.tournamentParticipants.stream().filter(p -> p.getParticipantAggregateId().equals(userAggregateId))
                .findFirst()
                .orElse(null);
    }

    public boolean removeParticipant(TournamentParticipant participant) {
        return this.tournamentParticipants.remove(participant);
    }

    public TournamentSatelliteSatelliteDto buildDto() {
        return new TournamentSatelliteSatelliteDto(this);
    }
}
