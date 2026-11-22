package org.anonymous.msrefactor.quizzes.commands.question;

import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;
import org.anonymous.msrefactor.quizzes.microservices.question.aggregate.QuestionCourse;
import org.anonymous.msrefactor.quizzes.microservices.question.aggregate.QuestionDto;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.TopicDto;

import java.util.List;

public class CreateQuestionCommand extends Command {
    private QuestionCourse course;
    private QuestionDto questionDto;
    private List<TopicDto> topics;

    public CreateQuestionCommand(UnitOfWork unitOfWork, String serviceName, QuestionCourse course, QuestionDto questionDto, List<TopicDto> topics) {
        super(unitOfWork, serviceName, null);
        this.course = course;
        this.questionDto = questionDto;
        this.topics = topics;
    }

    public QuestionCourse getCourse() { return course; }
    public QuestionDto getQuestionDto() { return questionDto; }
    public List<TopicDto> getTopics() { return topics; }
}
