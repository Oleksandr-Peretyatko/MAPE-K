package org.anonymous.msrefactor.quizzes.commands.tournamentSatellite;

import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;

public class UpdateParticipantAnswerCommand extends Command {
    private Integer satelliteAggregateId;
    private Integer studentAggregateId;
    private Integer quizAnswerAggregateId;
    private Integer questionAggregateId;
    private boolean correct;
    private Long eventVersion;

    public UpdateParticipantAnswerCommand(UnitOfWork unitOfWork, String serviceName, Integer satelliteAggregateId,
            Integer studentAggregateId, Integer quizAnswerAggregateId, Integer questionAggregateId, boolean correct,
            Long eventVersion) {
        super(unitOfWork, serviceName, satelliteAggregateId);
        this.satelliteAggregateId = satelliteAggregateId;
        this.studentAggregateId = studentAggregateId;
        this.quizAnswerAggregateId = quizAnswerAggregateId;
        this.questionAggregateId = questionAggregateId;
        this.correct = correct;
        this.eventVersion = eventVersion;
    }

    public Integer getSatelliteAggregateId() { return satelliteAggregateId; }
    public Integer getStudentAggregateId() { return studentAggregateId; }
    public Integer getQuizAnswerAggregateId() { return quizAnswerAggregateId; }
    public Integer getQuestionAggregateId() { return questionAggregateId; }
    public boolean isCorrect() { return correct; }
    public Long getEventVersion() { return eventVersion; }
}
