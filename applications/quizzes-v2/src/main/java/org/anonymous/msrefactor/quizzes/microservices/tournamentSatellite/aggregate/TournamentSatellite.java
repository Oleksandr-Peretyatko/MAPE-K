package org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.aggregate;

import jakarta.persistence.*;
import org.anonymous.msrefactor.ms.aggregate.Aggregate;
import org.anonymous.msrefactor.ms.aggregate.EventSubscription;
import org.anonymous.msrefactor.ms.aggregate.InterInvariant;
import org.anonymous.msrefactor.ms.utils.DateHandler;
import org.anonymous.msrefactor.quizzes.microservices.exception.QuizzesException;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.notification.subscribe.*;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.anonymous.msrefactor.quizzes.microservices.exception.QuizzesErrorMessage.INVARIANT_BREAK;

/*
    TournamentSatellite holds the membership side of a Tournament: its creator and its participants
    (with their quiz answers). It is the authoritative owner of enrollTime and the participant
    answers, and it replicates the core Tournament's startTime, cancelled and courseExecution so the
    membership invariants stay intra-aggregate.

    INTRA-INVARIANTS:
        UNIQUE_AS_PARTICIPANT
        ENROLL_UNTIL_START_TIME     (uses replicated startTime)
        ANSWER_BEFORE_START         (uses replicated startTime)
        CREATOR_PARTICIPANT_CONSISTENCY
        CREATOR_IS_NOT_ANONYMOUS
        IS_CANCELED                 (duplicated with core; satellite side: participants + cancelled)
        DELETE
    INTER-INVARIANTS:
        CREATOR_EXISTS / CREATOR_COURSE_EXECUTION
        PARTICIPANT_EXISTS / PARTICIPANT_COURSE_EXECUTION
        QUIZ_ANSWER_EXISTS
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
    @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER, mappedBy = "tournamentSatellite")
    private Set<TournamentParticipant> tournamentParticipants = new HashSet<>();

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

    @InterInvariant({ "CREATOR_EXISTS", "CREATOR_COURSE_EXECUTION", "PARTICIPANT_EXISTS",
            "PARTICIPANT_COURSE_EXECUTION" })
    private void interInvariantMembersExist(Set<EventSubscription> eventSubscriptions) {
        eventSubscriptions.add(new TournamentSatelliteSubscribesDisenrollStudentFromCourseExecution(this));
        eventSubscriptions.add(new TournamentSatelliteSubscribesAnonymizeStudent(this));
        eventSubscriptions.add(new TournamentSatelliteSubscribesUpdateStudentName(this));
    }

    @InterInvariant("QUIZ_ANSWER_EXISTS")
    private void interInvariantQuizAnswersExist(Set<EventSubscription> eventSubscriptions) {
        for (TournamentParticipant tournamentParticipant : this.tournamentParticipants) {
            if (tournamentParticipant.getParticipantAnswer().getQuizAnswerAggregateId() != null) {
                eventSubscriptions.add(new TournamentSatelliteSubscribesAnswerQuestion(tournamentParticipant));
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
        return this.tournamentParticipants.stream()
                .noneMatch(p -> p.getParticipantAggregateId().equals(this.tournamentCreator.getCreatorAggregateId())
                        && (!p.getParticipantVersion().equals(this.tournamentCreator.getCreatorVersion())
                                || !p.getParticipantName().equals(this.tournamentCreator.getCreatorName())
                                || !p.getParticipantUsername().equals(this.tournamentCreator.getCreatorUsername())));
    }

    private boolean invariantCreatorIsNotAnonymous() {
        return !tournamentCreator.getCreatorName().equals("ANONYMOUS")
                && !tournamentCreator.getCreatorUsername().equals("ANONYMOUS");
    }

    /*
     * IS_CANCELED (duplicated with the core; the satellite enforces the participant + cancelled side)
     */
    private boolean invariantCancelledFieldsAreFinal() {
        TournamentSatellite prev = (TournamentSatellite) getPrev();
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
                    && invariantCreatorIsNotAnonymous()
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

    public Set<TournamentParticipant> getTournamentParticipants() {
        return tournamentParticipants;
    }

    public void setTournamentParticipants(Set<TournamentParticipant> tournamentParticipants) {
        this.tournamentParticipants = tournamentParticipants;
        this.tournamentParticipants.forEach(tournamentParticipant -> tournamentParticipant.setTournamentSatellite(this));
    }

    public void addParticipant(TournamentParticipant participant) {
        this.tournamentParticipants.add(participant);
        participant.setTournamentSatellite(this);
    }

    public TournamentParticipant findParticipant(Integer userAggregateId) {
        return this.tournamentParticipants.stream().filter(p -> p.getParticipantAggregateId().equals(userAggregateId))
                .findFirst()
                .orElse(null);
    }

    public boolean removeParticipant(TournamentParticipant participant) {
        return this.tournamentParticipants.remove(participant);
    }

    public TournamentSatelliteDto buildDto() {
        return new TournamentSatelliteDto(this);
    }
}
