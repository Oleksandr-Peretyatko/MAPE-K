package org.anonymous.msrefactor.quizzes.microservices.tournament.aggregate;

import jakarta.persistence.*;
import org.anonymous.msrefactor.ms.aggregate.Aggregate;
import org.anonymous.msrefactor.ms.aggregate.EventSubscription;
import org.anonymous.msrefactor.ms.aggregate.InterInvariant;
import org.anonymous.msrefactor.ms.utils.DateHandler;
import org.anonymous.msrefactor.quizzes.microservices.exception.QuizzesException;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.CourseExecutionDto;
import org.anonymous.msrefactor.quizzes.microservices.quiz.aggregate.QuizDto;
import org.anonymous.msrefactor.quizzes.microservices.topic.aggregate.TopicDto;
import org.anonymous.msrefactor.quizzes.microservices.tournament.notification.subscribe.*;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static org.anonymous.msrefactor.quizzes.microservices.exception.QuizzesErrorMessage.INVARIANT_BREAK;

/* each version of the tournament is a new instance of the tournament*/
/*
    INTRA-INVARIANTS:
    Intra-Invariants (Eventual Consistency check on set to APPROVED) (Apply to ACTIVE states)
        COURSE_EXECUTION_IS_FINAL
        QUIZ_IS_FINAL
        START_BEFORE_END_TIME
        FINAL_AFTER_START
        AFTER_END
        IS_CANCELED
        TOURNAMENT_NUMBER_OF_QUESTIONS_POSITIVE
        TOURNAMENT_MUST_HAVE_ONE_TOPIC
        TOURNAMENT_MAX_QUESTIONS
    INTER-INVARIANTS:
        NUMBER_OF_QUESTIONS
        QUIZ_TOPICS
        START_TIME_AVAILABLE_DATE
        END_TIME_CONCLUSION_DATE
        QUIZ_COURSE_EXECUTION
        TOPIC_COURSE_EXECUTION
        INACTIVE_PROPAGATION ????
        COURSE_EXECUTION_EXISTS
        TOPIC_EXISTS
        QUIZ_EXISTS
    (creator/participant rules — UNIQUE_AS_PARTICIPANT, ENROLL_UNTIL_START_TIME,
     ANSWER_BEFORE_START, DELETE, CREATOR_PARTICIPANT_CONSISTENCY, CREATOR_IS_FINAL,
     CREATOR_EXISTS, PARTICIPANT_EXISTS, QUIZ_ANSWER_EXISTS — moved to the
     TournamentSatellite aggregate by the split; DELETE is enforced across the
     boundary by the RemoveTournament saga, IS_CANCELED is duplicated on both roots)
 */
@Entity
public abstract class Tournament extends Aggregate {
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
     * COURSE_EXECUTION_IS_FINAL
     * final this.courseExecution.id
     */
    @OneToOne(cascade = CascadeType.ALL, mappedBy = "tournament")
    private TournamentCourseExecution tournamentCourseExecution;
    @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER, mappedBy = "tournament")
    private Set<TournamentTopic> tournamentTopics = new HashSet<>();
    /*
     * QUIZ_IS_FINAL
     * final this.tournamentQuiz.id
     */
    @OneToOne(cascade = CascadeType.ALL, mappedBy = "tournament")
    private TournamentQuiz tournamentQuiz;

    public Tournament() {
    }

    public Tournament(Integer aggregateId, TournamentDto tournamentDto,
            CourseExecutionDto courseExecutionDto, Set<TopicDto> topicDtos, QuizDto quizDto) {
        super(aggregateId);
        setAggregateType(getClass().getSimpleName());
        setStartTime(DateHandler.toLocalDateTime(tournamentDto.getStartTime()));
        setEndTime(DateHandler.toLocalDateTime(tournamentDto.getEndTime()));
        setNumberOfQuestions(tournamentDto.getNumberOfQuestions());
        setCancelled(tournamentDto.isCancelled());

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
        // Copy plain fields directly (bypasses setters so lastModifiedTime is not
        // stamped on copy)
        this.startTime = other.getStartTime();
        this.endTime = other.getEndTime();
        this.numberOfQuestions = other.getNumberOfQuestions();
        this.cancelled = other.isCancelled();
        this.lastModifiedTime = other.getLastModifiedTime();

        setTournamentCourseExecution(new TournamentCourseExecution(other.getTournamentCourseExecution()));
        setTournamentTopics(other.getTournamentTopics().stream().map(TournamentTopic::new).collect(Collectors.toSet()));

        setTournamentQuiz(new TournamentQuiz(other.getTournamentQuiz()));
    }

    @Override
    public void remove() {
        /*
         * DELETE (this.state == DELETED => participants.empty) moved with the
         * participants to TournamentSatellite; the RemoveTournament saga checks it
         * across the boundary before this runs.
         */
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

    /*
     * START_BEFORE_END_TIME
     * this.startTime < this.endTime
     */
    public boolean invariantStartTimeBeforeEndTime() {
        return this.startTime.isBefore(this.endTime);
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
     * FINAL_AFTER_START
     * now > prev.startTime => final this.startTime && final this.endTime &&
     * final this.numberOfQuestions && final this.tournamentTopics && final
     * this.cancelled
     * The mutation time is captured in lastModifiedTime by each setter before
     * applying the change.
     */
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
     * IS_CANCELED
     * prev.cancelled => final this.startTime && final this.endTime &&
     * final this.numberOfQuestions && final this.tournamentTopics &&
     * final this.cancelled
     * (the participants half of this rule is duplicated on TournamentSatellite —
     * a getPrev() rule cannot span two version chains)
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
        /*
         * Invariants only apply to ACTIVE aggregates (class-level comment:
         * "Apply to ACTIVE states").
         */
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
        /*
         * FINAL_AFTER_START / IS_CANCELED — enforced by verifyInvariants() using
         * lastModifiedTime
         */
        setLastModifiedTime(DateHandler.now());
        this.endTime = endTime;
    }

    public Integer getNumberOfQuestions() {
        return this.numberOfQuestions;
    }

    public void setNumberOfQuestions(Integer numberOfQuestions) {
        /*
         * FINAL_AFTER_START / IS_CANCELED — enforced by verifyInvariants() using
         * lastModifiedTime
         */
        setLastModifiedTime(DateHandler.now());
        this.numberOfQuestions = numberOfQuestions;
    }

    public boolean isCancelled() {
        return this.cancelled;
    }

    public void setCancelled(boolean cancelled) {
        /*
         * FINAL_AFTER_START / IS_CANCELED — enforced by verifyInvariants() using
         * lastModifiedTime
         */
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
        /*
         * FINAL_AFTER_START / IS_CANCELED — enforced by verifyInvariants() using
         * lastModifiedTime
         */
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
