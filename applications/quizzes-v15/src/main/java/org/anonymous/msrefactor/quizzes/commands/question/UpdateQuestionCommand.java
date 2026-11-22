package org.anonymous.msrefactor.quizzes.commands.question;

import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;
import org.anonymous.msrefactor.quizzes.microservices.question.aggregate.QuestionDto;

public class UpdateQuestionCommand extends Command {
    private QuestionDto questionDto;

    public UpdateQuestionCommand(UnitOfWork unitOfWork, String serviceName, QuestionDto questionDto) {
        super(unitOfWork, serviceName, questionDto.getAggregateId());
        this.questionDto = questionDto;
    }

    public QuestionDto getQuestionDto() {
        return questionDto;
    }
}
