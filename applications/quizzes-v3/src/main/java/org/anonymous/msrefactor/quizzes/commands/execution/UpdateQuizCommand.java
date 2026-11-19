package org.anonymous.msrefactor.quizzes.commands.execution;

import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.QuizDto;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.QuizQuestion;

import java.util.Set;

public class UpdateQuizCommand extends Command {
    private QuizDto quizDto;
    private Set<QuizQuestion> quizQuestions;

    public UpdateQuizCommand(UnitOfWork unitOfWork, String serviceName, QuizDto quizDto,
            Set<QuizQuestion> quizQuestions) {
        super(unitOfWork, serviceName, quizDto.getAggregateId());
        this.quizDto = quizDto;
        this.quizQuestions = quizQuestions;
    }

    public QuizDto getQuizDto() {
        return quizDto;
    }

    public Set<QuizQuestion> getQuizQuestions() {
        return quizQuestions;
    }
}
