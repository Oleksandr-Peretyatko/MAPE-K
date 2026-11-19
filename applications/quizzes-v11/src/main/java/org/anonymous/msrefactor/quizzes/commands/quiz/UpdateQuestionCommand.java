package org.anonymous.msrefactor.quizzes.commands.quiz;

import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;

public class UpdateQuestionCommand extends Command {
    private final Integer quizAggregateId;
    private final Integer questionAggregateId;
    private final String title;
    private final String content;
    private final Long aggregateVersion;

    public UpdateQuestionCommand(UnitOfWork unitOfWork,
            String serviceName,
            Integer quizAggregateId,
            Integer questionAggregateId,
            String title,
            String content,
            Long aggregateVersion) {
        super(unitOfWork, serviceName, quizAggregateId);
        this.quizAggregateId = quizAggregateId;
        this.questionAggregateId = questionAggregateId;
        this.title = title;
        this.content = content;
        this.aggregateVersion = aggregateVersion;
    }

    public Integer getQuizAggregateId() {
        return quizAggregateId;
    }

    public Integer getQuestionAggregateId() {
        return questionAggregateId;
    }

    public String getTitle() {
        return title;
    }

    public String getContent() {
        return content;
    }

    public Long getAggregateVersion() {
        return aggregateVersion;
    }
}
