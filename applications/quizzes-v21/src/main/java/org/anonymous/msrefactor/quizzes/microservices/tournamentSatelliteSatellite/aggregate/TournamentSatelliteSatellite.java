package org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.aggregate;

import jakarta.persistence.*;
import org.anonymous.msrefactor.ms.aggregate.Aggregate;
import org.anonymous.msrefactor.ms.aggregate.EventSubscription;
import org.anonymous.msrefactor.ms.aggregate.InterInvariant;
import org.anonymous.msrefactor.ms.utils.DateHandler;
import org.anonymous.msrefactor.quizzes.microservices.exception.QuizzesException;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.CourseExecutionDto;
import org.anonymous.msrefactor.quizzes.microservices.tournament.aggregate.TournamentDto;
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.notification.subscribe.*;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.anonymous.msrefactor.quizzes.microservices.exception.QuizzesErrorMessage.INVARIANT_BREAK;

/*
 * Satellite aggregate split out of TournamentSatellite: it owns the participant
 * roster (with its quiz-answer replicas). It references the core
 * TournamentSatellite and the Tournament by id only, and keeps local copies of
 * the tournament's startTime and cancelled scalars, refreshed by
 * UpdateTournamentEvent, so the enrollment and answer-before-start rules stay
 * intra-aggregate.
 *
    INTRA-INVARIANTS:
        UNIQUE_AS_PARTICIPANT
        ENROLL_UNTIL_START_TIME
        ANSWER_BEFORE_START
        LEAVE_TOURNAMENT
        IS_CANCELED
        DELETE
    INTER-INVARIANTS:
        PARTICIPANT_COURSE_EXECUTION
        QUIZ_QUIZ_ANSWER
        PARTICIPANT_STUDENT
        NUMBER_OF_ANSWERED
        NUMBER_OF_CORRECT
        PARTICIPANT_EXISTS
        QUIZ_ANSWER_EXISTS
        TOURNAMENT_INFO
        CREATOR_PARTICIPANT_CONSISTENCY
 *
 * CREATOR_PARTICIPANT_CONSISTENCY was intra-aggregate before this split; the
 * creator stays on TournamentSatellite, so the rule is now the reconciliation
 * property that both roots' replicas of the same User converge through the same
 * user events (AnonymizeStudent, UpdateStudentName, Disenroll) each root
 * subscribes to independently.
 */
@Entity
public abstract class TournamentSatelliteSatellite extends Aggregate {
    /* the core satellite this roster was cut from — cross-aggregate reference by id */
    private Integer tournamentSatelliteAggregateId;
    /* the id every caller carries — REST paths and commands address by tournament id */
    private Integer tournamentAggregateId;
    private Long tournamentVersion;
    /* replicated tournament scalars — refreshed by UpdateTournamentEvent */
    private LocalDateTime startTime;
    private boolean cancelled;
    /* anchor of the student-event subscriptions, replicated from the execution */
    private Integer courseExecutionAggregateId;
    private Long courseExecutionVersion;
    @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER, mappedBy = "tournamentSatelliteSatellite")
    private Set<TournamentParticipant> tournamentParticipants = new HashSet<>();

    public TournamentSatelliteSatellite() {
    }

    public TournamentSatelliteSatellite(Integer aggregateId, Integer tournamentAggregateId,
            Integer tournamentSatelliteAggregateId, TournamentDto tournamentDto,
            CourseExecutionDto courseExecutionDto) {
        super(aggregateId);
        setAggregateType(getClass().getSimpleName());
        setTournamentAggregateId(tournamentAggregateId);
        setTournamentSatelliteAggregateId(tournamentSatelliteAggregateId);
        setTournamentVersion(0L);
        setStartTime(DateHandler.toLocalDateTime(tournamentDto.getStartTime()));
        setCancelled(tournamentDto.isCancelled());
        setCourseExecutionAggregateId(courseExecutionDto.getAggregateId());
        setCourseExecutionVersion(courseExecutionDto.getVersion());
    }

    /* used to update the satellite by creating new versions */
    public TournamentSatelliteSatellite(TournamentSatelliteSatellite other) {
        super(other);
        this.tournamentSatelliteAggregateId = other.getTournamentSatelliteAggregateId();
        this.tournamentAggregateId = other.getTournamentAggregateId();
        this.tournamentVersion = other.getTournamentVersion();
        this.startTime = other.getStartTime();
        this.cancelled = other.isCancelled();
        this.courseExecutionAggregateId = other.getCourseExecutionAggregateId();
        this.courseExecutionVersion = other.getCourseExecutionVersion();
        setTournamentParticipants(other.getTournamentParticipants().stream()
                .map(TournamentParticipant::new).collect(Collectors.toSet()));
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
            interInvariantParticipantExists(eventSubscriptions);
            interInvariantQuizAnswersExist(eventSubscriptions);
            interInvariantTournamentInfo(eventSubscriptions);
        }
        return eventSubscriptions;
    }

    @InterInvariant({ "PARTICIPANT_EXISTS", "PARTICIPANT_COURSE_EXECUTION" })
    private void interInvariantParticipantExists(Set<EventSubscription> eventSubscriptions) {
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
     * TOURNAMENT_INFO
     * this.startTime == Tournament(this.tournamentAggregateId).startTime &&
     * this.cancelled == Tournament(this.tournamentAggregateId).cancelled
     * — keeps the replicated scalars current, so ENROLL_UNTIL_START_TIME,
     * ANSWER_BEFORE_START and IS_CANCELED stay intra-aggregate after the split.
     * The same rule also refreshes the core TournamentSatellite's own copy; the
     * replica is read by both sides, so both roots subscribe.
     */
    @InterInvariant("TOURNAMENT_INFO")
    private void interInvariantTournamentInfo(Set<EventSubscription> eventSubscriptions) {
        eventSubscriptions.add(new TournamentSatelliteSatelliteSubscribesUpdateTournament(this));
    }

    /*
     * ----------------------------------------- INTRA-AGGREGATE INVARIANTS
     * -----------------------------------------
     */

    /*
     * UNIQUE_AS_PARTICIPANT
     * p1, p2: this.participants | p1.id != p2.id
     */
    public boolean invariantUniqueParticipant() {
        return this.tournamentParticipants.size() == this.tournamentParticipants.stream()
                .map(TournamentParticipant::getParticipantAggregateId)
                .distinct()
                .count();
    }

    /*
     * ENROLL_UNTIL_START_TIME
     * p : this.participants | p.enrollTime < this.startTime
     * (startTime is the replicated tournament scalar)
     */
    public boolean invariantParticipantsEnrolledBeforeStarTime() {
        for (TournamentParticipant p : this.tournamentParticipants) {
            if (this.startTime != null && p.getEnrollTime().isAfter(this.startTime)) {
                return false;
            }
        }
        return true;
    }

    /*
     * ANSWER_BEFORE_START
     * now < this.startTime => p: this.participant | p.answer.isEmpty
     * (startTime is the replicated tournament scalar)
     */
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

    /*
     * IS_CANCELED
     * prev.cancelled => final this.participants
     * The rule compares against getPrev() on this root's own version chain; the
     * cancelled flag it reads is the replicated tournament scalar.
     */
    private boolean invariantCancelledParticipantsAreFinal() {
        TournamentSatelliteSatellite prev = (TournamentSatelliteSatellite) getPrev();
        if (prev != null && prev.isCancelled()) {
            return this.tournamentParticipants.stream().map(TournamentParticipant::getParticipantAggregateId)
                    .collect(Collectors.toSet())
                    .equals(prev.getTournamentParticipants().stream()
                            .map(TournamentParticipant::getParticipantAggregateId).collect(Collectors.toSet()));
        }
        return true;
    }

    @Override
    public void verifyInvariants() {
        /*
         * DELETE invariant applies to all states
         */
        if (!invariantDeleteWhenNoParticipants()) {
            throw new QuizzesException(INVARIANT_BREAK, getTournamentAggregateId());
        }
        /*
         * All other invariants only apply to ACTIVE aggregates. INACTIVE aggregates
         * (e.g. after anonymisation) are excluded because their participant names may
         * be "ANONYMOUS".
         */
        if (getState() == AggregateState.ACTIVE) {
            if (!(invariantAnswerBeforeStart()
                    && invariantUniqueParticipant()
                    && invariantParticipantsEnrolledBeforeStarTime()
                    && invariantCancelledParticipantsAreFinal())) {
                throw new QuizzesException(INVARIANT_BREAK, getTournamentAggregateId());
            }
        }
    }

    public Integer getTournamentSatelliteAggregateId() {
        return tournamentSatelliteAggregateId;
    }

    public void setTournamentSatelliteAggregateId(Integer tournamentSatelliteAggregateId) {
        this.tournamentSatelliteAggregateId = tournamentSatelliteAggregateId;
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

    public Set<TournamentParticipant> getTournamentParticipants() {
        return tournamentParticipants;
    }

    public void setTournamentParticipants(Set<TournamentParticipant> tournamentParticipants) {
        // No checks: only called by constructors, not business logic.
        // Business operations use addParticipant().
        this.tournamentParticipants = tournamentParticipants;
        this.tournamentParticipants
                .forEach(tournamentParticipant -> tournamentParticipant.setTournamentSatelliteSatellite(this));
    }

    public void addParticipant(TournamentParticipant participant) {
        /*
         * ENROLL_UNTIL_START_TIME — enforced by
         * invariantParticipantsEnrolledBeforeStarTime() against the replicated
         * startTime.
         * IS_CANCELED — enforced by invariantCancelledParticipantsAreFinal().
         */
        this.tournamentParticipants.add(participant);
        participant.setTournamentSatelliteSatellite(this);
    }

    public TournamentParticipant findParticipant(Integer userAggregateId) {
        return this.tournamentParticipants.stream().filter(p -> p.getParticipantAggregateId().equals(userAggregateId))
                .findFirst()
                .orElse(null);
    }

    public boolean removeParticipant(TournamentParticipant participant) {
        /*
         * LEAVE_TOURNAMENT / IS_CANCELED — enforced by verifyInvariants() via
         * invariantCancelledParticipantsAreFinal().
         */
        return this.tournamentParticipants.remove(participant);
    }

    public TournamentSatelliteSatelliteDto buildDto() {
        TournamentSatelliteSatelliteDto dto = new TournamentSatelliteSatelliteDto();
        dto.setAggregateId(getAggregateId());
        dto.setVersion(getVersion());
        dto.setState(getState() == null ? null : getState().toString());
        dto.setTournamentSatelliteAggregateId(getTournamentSatelliteAggregateId());
        dto.setTournamentAggregateId(getTournamentAggregateId());
        dto.setStartTime(DateHandler.toISOString(getStartTime()));
        dto.setCancelled(isCancelled());
        dto.setCourseExecutionAggregateId(getCourseExecutionAggregateId());
        dto.setParticipants(getTournamentParticipants().stream()
                .map(TournamentParticipant::buildDto).collect(Collectors.toSet()));
        return dto;
    }
}
