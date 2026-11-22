package org.anonymous.msrefactor.quizzes;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.PropertySource;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.anonymous.msrefactor.ms.notification.EventService;

@PropertySource({ "classpath:application.yaml" })
@EnableJpaRepositories(basePackages = { "org.anonymous.msrefactor.ms.*",
		"org.anonymous.msrefactor.quizzes.*" })
@EntityScan(basePackages = { "org.anonymous.msrefactor.ms.*", "org.anonymous.msrefactor.quizzes.*" })
@EnableTransactionManagement
@EnableJpaAuditing
@EnableScheduling
@EnableRetry
@SpringBootApplication(scanBasePackages = { "org.anonymous.msrefactor.ms.*",
		"org.anonymous.msrefactor.quizzes.*" })
public class QuizzesSimulator implements InitializingBean {
	@Autowired
	private EventService eventService;

	public static void main(String[] args) {
		SpringApplication.run(QuizzesSimulator.class, args);
	}

	@Override
	public void afterPropertiesSet() {
		// Run on startup
		eventService.clearEventsAtApplicationStartUp();
	}

}
