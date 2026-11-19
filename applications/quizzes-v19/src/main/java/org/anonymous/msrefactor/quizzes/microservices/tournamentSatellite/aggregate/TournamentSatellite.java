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
import java.util.stream.Collectors;

import static org.anonymous.msrefactor.quizzes.microservices.exception.QuizzesErrorMessage.INVARIANT_BREAK;

/*
 * Satellite aggregate split out of Tournament: it owns the creator and the
 * participants (with their quiz-answer replicas). It references the core
 * Tournament by id only, and keeps local copies of the core's startTime and
 * cancelled scalars, refreshed by UpdateTournamentEvent, so the enrollment and
 * answer-before-start rules stay intra-aggregate.
 *
    INTRA-INVARIANTS:
        CREATOR_IS_FINAL
        UNIQUE_AS_PARTICIPANT
        ENROLL_UNTIL_START_TIME
        ANSWER_BEFORE_START
        LEAVE_TOURNAMENT
        IS_CANCELED
        DELETE
        CREATOR_PARTICIPANT_CONSISTENCY
    INTER-INVARIANTS:
        CREATOR_COURSE_EXECUTION
        PARTICIPANT_COURSE_EXECUTION
        QUIZ_QUIZ_ANSWER
        CREATOR_STUDENT
        PARTICIPANT_STUDENT
        NUMBER_OF_ANSWERED
        NUMBER_OF_CORRECT
        CREATOR_EXISTS
        PARTICIPANT_EXISTS
        QUIZ_ANSWER_EXISTS
        TOURNAMENT_INFO
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
    @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER, mappedBy = "tournamentSatellite")
    private Set<TournamentParticipant> tournamentParticipants = new HashSet<>();

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
            interInvariantCreatorExists(eventSubscriptions);
            interInvariantParticipantExists(eventSubscriptions);
            interInvariantQuizAnswersExist(eventSubscriptions);
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

    @InterInvariant({ "PARTICIPANT_EXISTS", "PARTICIPANT_COURSE_EXECUTION" })
    private void interInvariantParticipantExists(Set<EventSubscription> eventSubscriptions) {
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
     * TOURNAMENT_INFO
     * this.startTime == Tournament(this.tournamentAggregateId).startTime &&
     * this.cancelled == Tournament(this.tournamentAggregateId).cancelled
     * — keeps the replicated scalars current, so ENROLL_UNTIL_START_TIME and
     * ANSWER_BEFORE_START stay intra-aggregate after the split.
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
     * (startTime is the replicated core scalar)
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
     * (startTime is the replicated core scalar)
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
     * CREATOR_PARTICIPANT_CONSISTENCY
     */
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
     * IS_CANCELED
     * prev.cancelled => final this.participants
     * Duplicated from the core's invariantCancelledFieldsAreFinal: the rule reads
     * collections on both sides of the split and compares against getPrev(), and
     * two version chains have no single prev — so each root keeps its own half.
     */
    private boolean invariantCancelledParticipantsAreFinal() {
        TournamentSatellite prev = (TournamentSatellite) getPrev();
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
         * (e.g. after anonymisation) are excluded because their creator/participant
         * names may be "ANONYMOUS".
         */
        if (getState() == AggregateState.ACTIVE) {
            if (!(invariantAnswerBeforeStart()
                    && invariantUniqueParticipant()
                    && invariantParticipantsEnrolledBeforeStarTime()
                    && invariantCreatorParticipantConsistency()
                    && invariantCreatorIsNotAnonymous()
                    && invariantCancelledParticipantsAreFinal())) {
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

    public Set<TournamentParticipant> getTournamentParticipants() {
        return tournamentParticipants;
    }

    public void setTournamentParticipants(Set<TournamentParticipant> tournamentParticipants) {
        // No checks: only called by constructors, not business logic.
        // Business operations use addParticipant().
        this.tournamentParticipants = tournamentParticipants;
        this.tournamentParticipants
                .forEach(tournamentParticipant -> tournamentParticipant.setTournamentSatellite(this));
    }

    public void addParticipant(TournamentParticipant participant) {
        /*
         * ENROLL_UNTIL_START_TIME — enforced by
         * invariantParticipantsEnrolledBeforeStarTime() against the replicated
         * startTime.
         * IS_CANCELED — enforced by invariantCancelledParticipantsAreFinal().
         */
        this.tournamentParticipants.add(participant);
        participant.setTournamentSatellite(this);
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
        dto.setParticipants(getTournamentParticipants().stream()
                .map(TournamentParticipant::buildDto).collect(Collectors.toSet()));
        return dto;
    }
}
