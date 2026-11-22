package org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {
        "org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite",
        "org.anonymous.msrefactor.ms" })
@EnableJpaRepositories({
        "org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite",
        "org.anonymous.msrefactor.ms" })
@EntityScan({
        "org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite",
        "org.anonymous.msrefactor.quizzes.events",
        "org.anonymous.msrefactor.ms" })
@EnableScheduling
@Profile("tournament-satellite-satellite-service")
public class TournamentSatelliteSatelliteServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(TournamentSatelliteSatelliteServiceApplication.class, args);
    }
}
