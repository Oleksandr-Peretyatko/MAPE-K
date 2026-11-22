package org.anonymous.msrefactor.quizzes.microservices.tournament.aggregate;

import jakarta.persistence.*;
import org.anonymous.msrefactor.ms.aggregate.Aggregate;
import org.anonymous.msrefactor.ms.aggregate.EventSubscription;
import org.anonymous.msrefactor.ms.aggregate.InterInvariant;
import org.anonymous.msrefactor.ms.utils.DateHandler;
import org.anonymous.msrefactor.quizzes.microservices.exception.QuizzesException;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.CourseExecutionDto;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.QuizDto;
import org.anonymous.msrefactor.quizzes.microservices.topic.aggregate.TopicDto;
import org.anonymous.msrefactor.quizzes.microservices.tournament.notification.subscribe.*;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static org.anonymous.msrefactor.quizzes.microservices.exception.QuizzesErrorMessage.INVARIANT_BREAK;

/* each version of the tournament is a new instance of the tournament*/
/*
    After the SPLIT_AGGREGATE operation the membership side (creator + participants) lives in
    TournamentSatellite. The core keeps only creatorAggregateId (a denormalized scalar used to decide
    whether an anonymize/remove event affects the creator and must set this tournament INACTIVE).

    INTRA-INVARIANTS (core side):
        START_BEFORE_END_TIME
        FINAL_AFTER_START
        IS_CANCELED            (duplicated with satellite; core side: topics + scalars)
        TOURNAMENT_NUMBER_OF_QUESTIONS_POSITIVE
        TOURNAMENT_MUST_HAVE_ONE_TOPIC
        TOURNAMENT_MAX_QUESTIONS
    INTER-INVARIANTS (core side):
        COURSE_EXECUTION_EXISTS
        TOPIC_EXISTS
        QUIZ_EXISTS
 */
@Entity
public abstract class Tournament extends Aggregate {
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
    @OneToOne(cascade = CascadeType.ALL, mappedBy = "tournament")
    private TournamentCourseExecution tournamentCourseExecution;
    @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER, mappedBy = "tournament")
    private Set<TournamentTopic> tournamentTopics = new HashSet<>();
    @OneToOne(cascade = CascadeType.ALL, mappedBy = "tournament")
    private TournamentQuiz tournamentQuiz;

    public Tournament() {
    }

    public Tournament(Integer aggregateId, TournamentDto tournamentDto, UserDto creatorDto,
            CourseExecutionDto courseExecutionDto, Set<TopicDto> topicDtos, QuizDto quizDto) {
        super(aggregateId);
        setAggregateType(getClass().getSimpleName());
        setStartTime(DateHandler.toLocalDateTime(tournamentDto.getStartTime()));
        setEndTime(DateHandler.toLocalDateTime(tournamentDto.getEndTime()));
        setNumberOfQuestions(tournamentDto.getNumberOfQuestions());
        setCancelled(tournamentDto.isCancelled());

        setCreatorAggregateId(creatorDto.getAggregateId());
        setTournamentCourseExecution(new TournamentCourseExecution(courseExecutionDto));

        Set<TournamentTopic> tournamentTopics = topicDtos.stream()
                .map(TournamentTopic::new)
                .collect(Collectors.toSet());
        setTournamentTopics(tournamentTopics);

        setTournamentQuiz(new TournamentQuiz(quizDto.getAggregateId(), quizDto.getVersion()));
    }

    /* used to update the tournament by creating new versions */
    public Tournament(Tournament other) {
        super(other);
        this.startTime = other.getStartTime();
        this.endTime = other.getEndTime();
        this.numberOfQuestions = other.getNumberOfQuestions();
        this.cancelled = other.isCancelled();
        this.lastModifiedTime = other.getLastModifiedTime();
        this.creatorAggregateId = other.getCreatorAggregateId();

        setTournamentCourseExecution(new TournamentCourseExecution(other.getTournamentCourseExecution()));
        setTournamentTopics(other.getTournamentTopics().stream().map(TournamentTopic::new).collect(Collectors.toSet()));
        setTournamentQuiz(new TournamentQuiz(other.getTournamentQuiz()));
    }

    @Override
    public void remove() {
        super.remove();
    }

    @Override
    public Set<EventSubscription> getEventSubscriptions() {
        Set<EventSubscription> eventSubscriptions = new HashSet<>();
        if (this.getState() == AggregateState.ACTIVE) {
            interInvariantCourseExecutionExists(eventSubscriptions);
            interInvariantTopicsExist(eventSubscriptions);
            interInvariantQuizExists(eventSubscriptions);
        }
        return eventSubscriptions;
    }

    @InterInvariant("COURSE_EXECUTION_EXISTS")
    private void interInvariantCourseExecutionExists(Set<EventSubscription> eventSubscriptions) {
        eventSubscriptions.add(new TournamentSubscribesDeleteCourseExecution(this.getTournamentCourseExecution()));
    }

    @InterInvariant("TOPIC_EXISTS")
    private void interInvariantTopicsExist(Set<EventSubscription> eventSubscriptions) {
        for (TournamentTopic tournamentTopic : this.tournamentTopics) {
            eventSubscriptions.add(new TournamentSubscribesDeleteTopic(tournamentTopic));
            eventSubscriptions.add(new TournamentSubscribesUpdateTopic(tournamentTopic));
        }
    }

    @InterInvariant("QUIZ_EXISTS")
    private void interInvariantQuizExists(Set<EventSubscription> eventSubscriptions) {
        eventSubscriptions.add(new TournamentSubscribesInvalidateQuiz(this.getTournamentQuiz()));
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

    private boolean invariantFinalAfterStart() {
        Tournament prev = (Tournament) getPrev();
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
     * IS_CANCELED (duplicated with the satellite; the core enforces the topics + scalars side)
     */
    private boolean invariantCancelledFieldsAreFinal() {
        Tournament prev = (Tournament) getPrev();
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

    @Override
    public void verifyInvariants() {
        if (getState() == AggregateState.ACTIVE) {
            if (!(invariantStartTimeBeforeEndTime()
                    && invariantFinalAfterStart()
                    && invariantCancelledFieldsAreFinal()
                    && invariantMustHaveOneTopic()
                    && invariantNumberOfQuestionsPositive()
                    && invariantNumberOfQuestionsAtMost30())) {
                throw new QuizzesException(INVARIANT_BREAK, getAggregateId());
            }
        }
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

    public TournamentCourseExecution getTournamentCourseExecution() {
        return this.tournamentCourseExecution;
    }

    public void setTournamentCourseExecution(TournamentCourseExecution tournamentCourseExecution) {
        this.tournamentCourseExecution = tournamentCourseExecution;
        this.tournamentCourseExecution.setTournament(this);
    }

    public Set<TournamentTopic> getTournamentTopics() {
        return tournamentTopics;
    }

    public void setTournamentTopics(Set<TournamentTopic> topics) {
        setLastModifiedTime(DateHandler.now());
        this.tournamentTopics = topics;
        this.tournamentTopics.forEach(tournamentTopic -> tournamentTopic.setTournament(this));
    }

    public TournamentQuiz getTournamentQuiz() {
        return this.tournamentQuiz;
    }

    public void setTournamentQuiz(TournamentQuiz tournamentQuiz) {
        this.tournamentQuiz = tournamentQuiz;
        this.tournamentQuiz.setTournament(this);
    }

    // this setVersion is special because the quiz is created in the same
    // transaction and we want to have its version upon commit
    @Override
    public void setVersion(Long version) {
        if (this.tournamentQuiz != null && this.tournamentQuiz.getQuizVersion() == null) {
            this.tournamentQuiz.setQuizVersion(version);
        }
        super.setVersion(version);
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
