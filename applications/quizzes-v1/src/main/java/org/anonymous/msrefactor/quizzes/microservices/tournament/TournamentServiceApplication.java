package org.anonymous.msrefactor.quizzes.microservices.tournament;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.anonymous.msrefactor.ms.notification.EventService;

@Profile("tournament-service")
@SpringBootApplication(scanBasePackages = {
        "org.anonymous.msrefactor.quizzes.microservices.tournament",
        "org.anonymous.msrefactor.ms",
})
@EnableJpaRepositories(basePackages = {
        "org.anonymous.msrefactor.quizzes.microservices.tournament",
        "org.anonymous.msrefactor.ms",
})
@EntityScan(basePackages = {
        "org.anonymous.msrefactor.quizzes.microservices.tournament",
        "org.anonymous.msrefactor.quizzes.events",
        "org.anonymous.msrefactor.ms"
})

@EnableScheduling
public class TournamentServiceApplication implements InitializingBean {
    @Autowired
    private EventService eventService;

    public static void main(String[] args) {
        SpringApplication.run(TournamentServiceApplication.class, args);
    }

    @Override
    public void afterPropertiesSet() {
        // Run on startup
        eventService.clearEventsAtApplicationStartUp();
    }
}
