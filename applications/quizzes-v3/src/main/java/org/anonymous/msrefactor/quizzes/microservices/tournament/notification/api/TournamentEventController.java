package org.anonymous.msrefactor.quizzes.microservices.tournament.notification.api;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.anonymous.msrefactor.quizzes.microservices.tournament.notification.handling.TournamentEventHandling;

@RestController
public class TournamentEventController {
    @Autowired
    private TournamentEventHandling tournamentEventHandling;

    @PostMapping("/events/topic/update")
    public void processUpdateTopic() {
        tournamentEventHandling.handleUpdateTopicEvents();
    }
}
