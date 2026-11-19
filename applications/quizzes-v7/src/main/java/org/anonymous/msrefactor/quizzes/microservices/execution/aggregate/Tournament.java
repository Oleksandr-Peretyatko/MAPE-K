package org.anonymous.msrefactor.quizzes.microservices.execution.aggregate;

import jakarta.persistence.*;
import org.anonymous.msrefactor.ms.aggregate.Aggregate;
import org.anonymous.msrefactor.ms.utils.DateHandler;
import org.anonymous.msrefactor.quizzes.microservices.exception.QuizzesException;
import org.anonymous.msrefactor.quizzes.microservices.topic.aggregate.TopicDto;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static org.anonymous.msrefactor.quizzes.microservices.exception.QuizzesErrorMessage.INVARIANT_BREAK;

/*
    Member entity of Execution (merged from the former Tournament aggregate).
    The membership side (creator + participants) lives in TournamentSatellite /
    TournamentSatelliteSatellite; the member keeps only creatorAggregateId (a denormalized scalar
    used to decide whether an anonymize/remove event affects the creator and must set this
    tournament INACTIVE).

    INTRA-INVARIANTS (verified from Execution.verifyInvariants(), per tournament member)
        START_BEFORE_END_TIME
        FINAL_AFTER_START
        IS_CANCELED            (duplicated with satellite; this side: topics + scalars)
        TOURNAMENT_NUMBER_OF_QUESTIONS_POSITIVE
        TOURNAMENT_MUST_HAVE_ONE_TOPIC
        TOURNAMENT_MAX_QUESTIONS
    COURSE_EXECUTION_EXISTS is intra-aggregate now: removing the owning execution invalidates its
    tournaments in the same unit of work (ExecutionService.removeCourseExecution).
    QUIZ_EXISTS is intra-aggregate now: the tournament's quiz is a Quiz member of the same root.
    TOPIC_EXISTS stays an inter-invariant, registered from Execution.getEventSubscriptions().
 */
@Entity
public class Tournament {
    @Id
    @GeneratedValue
    private Long id;
    // entity identity: the former aggregateId value. Not an aggregate id any more.
    private Integer tournamentId;
    @ManyToOne
    private Execution execution;

    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer numberOfQuestions;
    private boolean cancelled;
    private LocalDateTime lastModifiedTime;
    /*
     * Denormalized id of the creator (the authoritative creator entity lives in
     * TournamentSatellite). Used only by the event-processing methods to decide whether an event
     * about a user concerns this tournament's creator.
     */
    private Integer creatorAggregateId;
    // fetched by secondary select for the same reason as Execution.tournaments (cartesian join)
    @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER, mappedBy = "tournament")
    @org.hibernate.annotations.Fetch(org.hibernate.annotations.FetchMode.SELECT)
    private Set<TournamentTopic> tournamentTopics = new HashSet<>();
    // identity of the Quiz member (of the same owning execution) backing this tournament
    private Integer quizId;
    @Enumerated(EnumType.STRING)
    private Aggregate.AggregateState state;

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

        setCreatorAggregateId(creatorDto.getAggregateId());

        Set<TournamentTopic> tournamentTopics = topicDtos.stream()
                .map(TournamentTopic::new)
                .collect(Collectors.toSet());
        setTournamentTopics(tournamentTopics);

        setQuizId(quizDto.getAggregateId());
    }

    public Tournament(Tournament other) {
        setTournamentId(other.getTournamentId());
        setState(other.getState());
        // Copy plain fields directly (bypasses setters so lastModifiedTime is not stamped on copy)
        this.startTime = other.getStartTime();
        this.endTime = other.getEndTime();
        this.numberOfQuestions = other.getNumberOfQuestions();
        this.cancelled = other.isCancelled();
        this.lastModifiedTime = other.getLastModifiedTime();
        this.creatorAggregateId = other.getCreatorAggregateId();
        this.quizId = other.getQuizId();

        this.tournamentTopics = other.getTournamentTopics().stream().map(TournamentTopic::new)
                .collect(Collectors.toSet());
        this.tournamentTopics.forEach(tournamentTopic -> tournamentTopic.setTournament(this));
    }

    /*
     * ----------------------------------------- INTRA-AGGREGATE INVARIANTS
     * -----------------------------------------
     */

    public boolean invariantStartTimeBeforeEndTime() {
        return this.startTime.isBefore(this.endTime);
    }

    public boolean invariantMustHaveOneTopic() {
        return this.tournamentTopics.size() >= 1;
    }

    public boolean invariantNumberOfQuestionsPositive() {
        return this.numberOfQuestions != null && this.numberOfQuestions > 0;
    }

    public boolean invariantNumberOfQuestionsAtMost30() {
        return this.numberOfQuestions == null || this.numberOfQuestions <= 30;
    }

    /*
     * prev is the matching tournament member of the previous Execution version (supplied by the
     * root).
     */
    private boolean invariantFinalAfterStart(Tournament prev) {
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
     * IS_CANCELED (duplicated with the satellite; this side enforces the topics + scalars)
     */
    private boolean invariantCancelledFieldsAreFinal(Tournament prev) {
        if (prev != null && prev.isCancelled()) {
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

    public void verifyInvariants(Tournament prev) {
        if (!(invariantStartTimeBeforeEndTime()
                && invariantFinalAfterStart(prev)
                && invariantCancelledFieldsAreFinal(prev)
                && invariantMustHaveOneTopic()
                && invariantNumberOfQuestionsPositive()
                && invariantNumberOfQuestionsAtMost30())) {
            throw new QuizzesException(INVARIANT_BREAK, getTournamentId());
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Integer getTournamentId() {
        return tournamentId;
    }

    public void setTournamentId(Integer tournamentId) {
        this.tournamentId = tournamentId;
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public Execution getExecution() {
        return execution;
    }

    public void setExecution(Execution execution) {
        this.execution = execution;
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

    public Integer getCreatorAggregateId() {
        return creatorAggregateId;
    }

    public void setCreatorAggregateId(Integer creatorAggregateId) {
        this.creatorAggregateId = creatorAggregateId;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalDateTime startTime) {
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

    public Set<TournamentTopic> getTournamentTopics() {
        return tournamentTopics;
    }

    public void setTournamentTopics(Set<TournamentTopic> topics) {
        setLastModifiedTime(DateHandler.now());
        this.tournamentTopics = topics;
        this.tournamentTopics.forEach(tournamentTopic -> tournamentTopic.setTournament(this));
    }

    public Integer getQuizId() {
        return quizId;
    }

    public void setQuizId(Integer quizId) {
        this.quizId = quizId;
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
