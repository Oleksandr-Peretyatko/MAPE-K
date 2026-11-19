package org.anonymous.msrefactor.quizzes.commands.answer;

import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;

public class RemoveQuestionFromQuizAnswerCommand extends Command {
    private Integer answerAggregateId;
    private Integer questionAggregateId;
    private Long aggregateVersion;

    public RemoveQuestionFromQuizAnswerCommand(UnitOfWork unitOfWork, String serviceName, Integer answerAggregateId, Integer questionAggregateId, Long aggregateVersion) {
        super(unitOfWork, serviceName, answerAggregateId);
        this.answerAggregateId = answerAggregateId;
        this.questionAggregateId = questionAggregateId;
        this.aggregateVersion = aggregateVersion;
    }

    public Integer getAnswerAggregateId() { return answerAggregateId; }
    public Integer getQuestionAggregateId() { return questionAggregateId; }
    public Long getAggregateVersion() { return aggregateVersion; }
}
