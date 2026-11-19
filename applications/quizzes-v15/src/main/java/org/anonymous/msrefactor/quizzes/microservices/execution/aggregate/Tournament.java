package org.anonymous.msrefactor.quizzes.microservices.execution.aggregate;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import org.anonymous.msrefactor.ms.aggregate.Aggregate;
import org.anonymous.msrefactor.ms.utils.DateHandler;
import org.anonymous.msrefactor.quizzes.microservices.topic.aggregate.TopicDto;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/*
    Member entity of the Execution aggregate (formerly the Tournament aggregate root).
    Its intra-invariants are enforced by Execution.verifyInvariants():
        START_BEFORE_END_TIME
        UNIQUE_AS_PARTICIPANT
        ENROLL_UNTIL_START_TIME
        ANSWER_BEFORE_START
        FINAL_AFTER_START
        IS_CANCELED
        DELETE
        CREATOR_PARTICIPANT_CONSISTENCY
        TOURNAMENT_NUMBER_OF_QUESTIONS_POSITIVE
        TOURNAMENT_MUST_HAVE_ONE_TOPIC
        TOURNAMENT_MAX_QUESTIONS
        CREATOR_IS_FINAL / COURSE_EXECUTION_IS_FINAL / QUIZ_IS_FINAL
    COURSE_EXECUTION_EXISTS, CREATOR_EXISTS, PARTICIPANT_EXISTS and QUIZ_EXISTS are
    intra-aggregate now: the tournament belongs to exactly one Execution root, which
    propagates student anonymisation/disenrollment/rename and execution removal to its
    tournaments synchronously (ExecutionService).
 */
@Entity
public class Tournament {
    @Id
    @GeneratedValue
    private Integer id;
    /*
     * Entity identity inside the Execution aggregate: the value of the old tournament
     * aggregateId. Commands, DTOs and REST paths keep resolving it.
     */
    private Integer tournamentId;
    @Enumerated(EnumType.STRING)
    private Aggregate.AggregateState state;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer numberOfQuestions;
    private boolean cancelled;
    /*
     * Timestamp captured at mutation time so that FINAL_AFTER_START and IS_CANCELED
     * can be verified post-hoc inside verifyInvariants() without calling
     * DateHandler.now() there.
     */
    private LocalDateTime lastModifiedTime;
    /*
     * CREATOR_IS_FINAL
     * final this.creator.id
     */
    @OneToOne(cascade = CascadeType.ALL, mappedBy = "tournament")
    private TournamentCreator tournamentCreator;
    @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER, mappedBy = "tournament")
    private Set<TournamentParticipant> tournamentParticipants = new HashSet<>();
    @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER, mappedBy = "tournament")
    private Set<TournamentTopic> tournamentTopics = new HashSet<>();
    /*
     * QUIZ_IS_FINAL
     * Entity identity of the quiz member (same Execution root) this tournament runs.
     * Replaces the deleted TournamentQuiz replica.
     */
    private Integer quizId;
    @ManyToOne
    private Execution execution;

    public Tournament() {
    }

    public Tournament(Integer tournamentId, TournamentDto tournamentDto, UserDto creatorDto,
            Set<TopicDto> topicDtos, QuizDto quizDto) {
        setTournamentId(tournamentId);
        setState(Aggregate.AggregateState.ACTIVE);
        setStartTime(DateHandler.toLocalDateTime(tournamentDto.getStartTime()));
        setEndTime(DateHandler.toLocalDateTime(tournamentDto.getEndTime()));
        setNumberOfQuestions(tournamentDto.getNumberOfQuestions());
        setCancelled(tournamentDto.isCancelled());

        setTournamentCreator(new TournamentCreator(creatorDto.getAggregateId(), creatorDto.getName(),
                creatorDto.getUsername(), creatorDto.getVersion()));

        Set<TournamentTopic> tournamentTopics = topicDtos.stream()
                .map(TournamentTopic::new)
                .collect(Collectors.toSet());
        setTournamentTopics(tournamentTopics);

        setQuizId(quizDto.getAggregateId());
    }

    public Tournament(Tournament other) {
        setTournamentId(other.getTournamentId());
        setState(other.getState());
        // Copy plain fields directly (bypasses setters so lastModifiedTime is not
        // stamped on copy)
        this.startTime = other.getStartTime();
        this.endTime = other.getEndTime();
        this.numberOfQuestions = other.getNumberOfQuestions();
        this.cancelled = other.isCancelled();
        this.lastModifiedTime = other.getLastModifiedTime();
        this.quizId = other.getQuizId();

        setTournamentCreator(new TournamentCreator(other.getTournamentCreator()));
        setTournamentTopics(other.getTournamentTopics().stream().map(TournamentTopic::new).collect(Collectors.toSet()));
        setTournamentParticipants(
                other.getTournamentParticipants().stream().map(TournamentParticipant::new).collect(Collectors.toSet()));
    }

    /*
     * ----------------------------------------- INTRA-AGGREGATE INVARIANTS
     * ----------------------------------------- (called from Execution.verifyInvariants())
     */

    /*
     * START_BEFORE_END_TIME
     * this.startTime < this.endTime
     */
    public boolean invariantStartTimeBeforeEndTime() {
        return this.startTime.isBefore(this.endTime);
    }

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
     */
    public boolean invariantParticipantsEnrolledBeforeStarTime() {
        for (TournamentParticipant p : this.tournamentParticipants) {
            if (p.getEnrollTime().isAfter(this.startTime)) {
                return false;
            }
        }
        return true;
    }

    /*
     * ANSWER_BEFORE_START
     * now < this.startTime => p: this.participant | p.answer.isEmpty
     */
    public boolean invariantAnswerBeforeStart() {
        if (DateHandler.now().isBefore(this.startTime)) {
            for (TournamentParticipant t : this.tournamentParticipants) {
                if (t.getParticipantAnswer().getQuizAnswerAggregateId() != null) {
                    return false;
                }
            }
        }
        return true;
    }

    /*
     * TOURNAMENT_MUST_HAVE_ONE_TOPIC
     * this.tournamentTopics.size() >= 1
     */
    public boolean invariantMustHaveOneTopic() {
        return this.tournamentTopics.size() >= 1;
    }

    /*
     * TOURNAMENT_NUMBER_OF_QUESTIONS_POSITIVE
     * this.numberOfQuestions > 0
     */
    public boolean invariantNumberOfQuestionsPositive() {
        return this.numberOfQuestions != null && this.numberOfQuestions > 0;
    }

    /*
     * TOURNAMENT_MAX_QUESTIONS
     * this.numberOfQuestions <= 30
     */
    public boolean invariantNumberOfQuestionsAtMost30() {
        return this.numberOfQuestions == null || this.numberOfQuestions <= 30;
    }

    /*
     * DELETE
     * this.state == DELETED => this.participants.empty
     */
    public boolean invariantDeleteWhenNoParticipants() {
        if (getState() == Aggregate.AggregateState.DELETED) {
            return getTournamentParticipants().size() == 0;
        }
        return true;
    }

    /*
     * CREATOR_PARTICIPANT_CONSISTENCY
     */
    public boolean invariantCreatorParticipantConsistency() {
        return this.tournamentParticipants.stream()
                .noneMatch(p -> p.getParticipantAggregateId().equals(this.tournamentCreator.getCreatorAggregateId())
                        && (!p.getParticipantVersion().equals(this.tournamentCreator.getCreatorVersion())
                                || !p.getParticipantName().equals(this.tournamentCreator.getCreatorName())
                                || !p.getParticipantUsername().equals(this.tournamentCreator.getCreatorUsername())));
    }

    public boolean invariantCreatorIsNotAnonymous() {
        return !tournamentCreator.getCreatorName().equals("ANONYMOUS")
                && !tournamentCreator.getCreatorUsername().equals("ANONYMOUS");
    }

    /*
     * FINAL_AFTER_START
     * now > prev.startTime => final this.startTime && final this.endTime &&
     * final this.numberOfQuestions && final this.tournamentTopics && final
     * this.cancelled
     * prevTournament is this tournament as it was in the previous version of the
     * Execution root.
     */
    public boolean invariantFinalAfterStart(Tournament prev) {
        if (prev != null && prev.getStartTime() != null && this.lastModifiedTime != null
                && this.lastModifiedTime.isAfter(prev.getStartTime())) {
            return Objects.equals(this.startTime, prev.getStartTime())
                    && Objects.equals(this.endTime, prev.getEndTime())
                    && Objects.equals(this.numberOfQuestions, prev.getNumberOfQuestions())
                    && Objects.equals(this.cancelled, prev.isCancelled())
                    && this.tournamentTopics.stream().map(TournamentTopic::getTopicAggregateId)
                            .collect(Collectors.toSet())
                            .equals(prev.getTournamentTopics().stream().map(TournamentTopic::getTopicAggregateId)
                                    .collect(Collectors.toSet()));
        }
        return true;
    }

    /*
     * IS_CANCELED
     * prev.cancelled => final this.startTime && final this.endTime &&
     * final this.numberOfQuestions && final this.tournamentTopics &&
     * final this.cancelled && final this.participants
     */
    public boolean invariantCancelledFieldsAreFinal(Tournament prev) {
        if (prev != null && prev.isCancelled()) {
            return Objects.equals(this.startTime, prev.getStartTime())
                    && Objects.equals(this.endTime, prev.getEndTime())
                    && Objects.equals(this.numberOfQuestions, prev.getNumberOfQuestions())
                    && Objects.equals(this.cancelled, prev.isCancelled())
                    && this.tournamentTopics.stream().map(TournamentTopic::getTopicAggregateId)
                            .collect(Collectors.toSet())
                            .equals(prev.getTournamentTopics().stream().map(TournamentTopic::getTopicAggregateId)
                                    .collect(Collectors.toSet()))
                    && this.tournamentParticipants.stream().map(TournamentParticipant::getParticipantAggregateId)
                            .collect(Collectors.toSet())
                            .equals(prev.getTournamentParticipants().stream()
                                    .map(TournamentParticipant::getParticipantAggregateId).collect(Collectors.toSet()));
        }
        return true;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public Integer getId() {
        return id;
    }

    public Integer getTournamentId() {
        return tournamentId;
    }

    public void setTournamentId(Integer tournamentId) {
        this.tournamentId = tournamentId;
    }

    public Aggregate.AggregateState getState() {
        return state;
    }

    public void setState(Aggregate.AggregateState state) {
        this.state = state;
    }

    public LocalDateTime getLastModifiedTime() {
        return lastModifiedTime;
    }

    public void setLastModifiedTime(LocalDateTime lastModifiedTime) {
        this.lastModifiedTime = lastModifiedTime;
    }

    public void cancel() {
        this.cancelled = true;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalDateTime startTime) {
        /*
         * FINAL_AFTER_START / IS_CANCELED — enforced by verifyInvariants() using
         * lastModifiedTime
         */
        setLastModifiedTime(DateHandler.now());
        this.startTime = startTime;
    }

    public LocalDateTime getEndTime() {
        return this.endTime;
    }

    public void setEndTime(LocalDateTime endTime) {
        setLastModifiedTime(DateHandler.now());
        this.endTime = endTime;
    }

    public Integer getNumberOfQuestions() {
        return this.numberOfQuestions;
    }

    public void setNumberOfQuestions(Integer numberOfQuestions) {
        setLastModifiedTime(DateHandler.now());
        this.numberOfQuestions = numberOfQuestions;
    }

    public boolean isCancelled() {
        return this.cancelled;
    }

    public void setCancelled(boolean cancelled) {
        setLastModifiedTime(DateHandler.now());
        this.cancelled = cancelled;
    }

    public TournamentCreator getTournamentCreator() {
        return tournamentCreator;
    }

    public void setTournamentCreator(TournamentCreator tournamentCreator) {
        this.tournamentCreator = tournamentCreator;
        tournamentCreator.setTournament(this);
    }

    public Set<TournamentParticipant> getTournamentParticipants() {
        return tournamentParticipants;
    }

    public void setTournamentParticipants(Set<TournamentParticipant> tournamentParticipants) {
        // No checks: only called by constructors/merge, not business logic.
        // Business operations use addParticipant().
        this.tournamentParticipants = tournamentParticipants;
        this.tournamentParticipants.forEach(tournamentParticipant -> tournamentParticipant.setTournament(this));
    }

    public void addParticipant(TournamentParticipant participant) {
        /*
         * ENROLL_UNTIL_START_TIME — enforced by
         * invariantParticipantsEnrolledBeforeStarTime()
         * IS_CANCELED — enforced by invariantCancelledFieldsAreFinal().
         */
        this.tournamentParticipants.add(participant);
        participant.setTournament(this);
    }

    public Set<TournamentTopic> getTournamentTopics() {
        return tournamentTopics;
    }

    public void setTournamentTopics(Set<TournamentTopic> topics) {
        setLastModifiedTime(DateHandler.now());
        this.tournamentTopics = topics;
        this.tournamentTopics.forEach(tournamentTopic -> tournamentTopic.setTournament(this));
    }

    public Integer getQuizId() {
        return this.quizId;
    }

    public void setQuizId(Integer quizId) {
        this.quizId = quizId;
    }

    @JsonIgnore
    public Execution getExecution() {
        return execution;
    }

    public void setExecution(Execution execution) {
        this.execution = execution;
    }

    public TournamentParticipant findParticipant(Integer userAggregateId) {
        return this.tournamentParticipants.stream().filter(p -> p.getParticipantAggregateId().equals(userAggregateId))
                .findFirst()
                .orElse(null);
    }

    public boolean removeParticipant(TournamentParticipant participant) {
        /*
         * LEAVE_TOURNAMENT / AFTER_END / IS_CANCELED — enforced by verifyInvariants()
         * via invariantCancelledFieldsAreFinal().
         */
        return this.tournamentParticipants.remove(participant);
    }

    public TournamentTopic findTopic(Integer topicAggregateId) {
        return getTournamentTopics().stream()
                .filter(t -> topicAggregateId.equals(t.getTopicAggregateId()))
                .findFirst()
                .orElse(null);
    }

    public void removeTopic(TournamentTopic tournamentTopic) {
        this.tournamentTopics.remove(tournamentTopic);
    }
}
