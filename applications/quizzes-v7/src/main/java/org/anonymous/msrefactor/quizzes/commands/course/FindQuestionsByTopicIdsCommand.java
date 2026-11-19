package org.anonymous.msrefactor.quizzes.commands.course;

import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;

import java.util.List;

public class FindQuestionsByTopicIdsCommand extends Command {
    private List<Integer> topicIds;

    public FindQuestionsByTopicIdsCommand(UnitOfWork unitOfWork, String serviceName, List<Integer> topicIds) {
        super(unitOfWork, serviceName, null);
        this.topicIds = topicIds;
    }

    public List<Integer> getTopicIds() {
        return topicIds;
    }
}
