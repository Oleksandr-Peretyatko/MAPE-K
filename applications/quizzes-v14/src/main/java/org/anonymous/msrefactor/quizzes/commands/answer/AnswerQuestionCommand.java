package org.anonymous.msrefactor.quizzes.commands.answer;

import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;
import org.anonymous.msrefactor.quizzes.microservices.answer.aggregate.QuestionAnswerDto;
import org.anonymous.msrefactor.quizzes.microservices.question.aggregate.QuestionDto;

public class AnswerQuestionCommand extends Command {
    private Integer quizAggregateId;
    private Integer userAggregateId;
    private QuestionAnswerDto userAnswerDto;
    private QuestionDto questionDto;

    public AnswerQuestionCommand(UnitOfWork unitOfWork, String serviceName, Integer quizAggregateId, Integer userAggregateId, QuestionAnswerDto userAnswerDto, QuestionDto questionDto) {
        super(unitOfWork, serviceName, quizAggregateId);
        this.quizAggregateId = quizAggregateId;
        this.userAggregateId = userAggregateId;
        this.userAnswerDto = userAnswerDto;
        this.questionDto = questionDto;
    }

    public Integer getQuizAggregateId() { return quizAggregateId; }
    public Integer getUserAggregateId() { return userAggregateId; }
    public QuestionAnswerDto getUserAnswerDto() { return userAnswerDto; }
    public QuestionDto getQuestionDto() { return questionDto; }
}
