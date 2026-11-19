package org.anonymous.msrefactor.quizzes.commands.tournament;

import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.CourseExecutionDto;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.QuizDto;
import org.anonymous.msrefactor.quizzes.microservices.topic.aggregate.TopicDto;
import org.anonymous.msrefactor.quizzes.microservices.tournament.aggregate.TournamentDto;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;

import java.util.Set;

public class CreateTournamentCommand extends Command {
    private TournamentDto tournamentDto;
    private UserDto creatorDto;
    private CourseExecutionDto courseExecutionDto;
    private Set<TopicDto> topicDtos;
    private QuizDto quizDto;

    public CreateTournamentCommand(UnitOfWork unitOfWork, String serviceName, TournamentDto tournamentDto, UserDto creatorDto, CourseExecutionDto courseExecutionDto, Set<TopicDto> topicDtos, QuizDto quizDto) {
        super(unitOfWork, serviceName, null);
        this.tournamentDto = tournamentDto;
        this.creatorDto = creatorDto;
        this.courseExecutionDto = courseExecutionDto;
        this.topicDtos = topicDtos;
        this.quizDto = quizDto;
    }

    public TournamentDto getTournamentDto() { return tournamentDto; }
    public UserDto getCreatorDto() { return creatorDto; }
    public CourseExecutionDto getCourseExecutionDto() { return courseExecutionDto; }
    public Set<TopicDto> getTopicDtos() { return topicDtos; }
    public QuizDto getQuizDto() { return quizDto; }
}
