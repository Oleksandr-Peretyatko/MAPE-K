package org.anonymous.msrefactor.quizzes.commands.tournament;

import org.anonymous.msrefactor.ms.messaging.Command;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.TopicDto;
import org.anonymous.msrefactor.quizzes.microservices.tournament.aggregate.TournamentDto;

import java.util.Set;

public class UpdateTournamentCommand extends Command {
    private final TournamentDto tournamentDto;
    private final Set<TopicDto> topicDtos;

    public UpdateTournamentCommand(UnitOfWork unitOfWork, String serviceName, TournamentDto tournamentDto,
            Set<TopicDto> topicDtos) {
        super(unitOfWork, serviceName, tournamentDto.getAggregateId());
        this.tournamentDto = tournamentDto;
        this.topicDtos = topicDtos;
    }

    public TournamentDto getTournamentDto() {
        return tournamentDto;
    }

    public Set<TopicDto> getTopicDtos() {
        return topicDtos;
    }
}
