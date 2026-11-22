package org.anonymous.msrefactor.rt.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AppConfig {

    @Value("${rt.cors.allowed-origins:http://localhost:3000}")
    private String[] allowedOrigins;

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/**")
                        .allowedOrigins(allowedOrigins)
                        .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                        .allowedHeaders("*")
                        .allowCredentials(false)
                        .maxAge(3600);
            }
        };
    }

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    /**
     * Shared mapper. Defining the bean here means Spring Boot's auto-configured mapper backs off,
     * so {@code spring.jackson.*} in application.yml does NOT apply: every setting must be made
     * on this instance. It parses both HTTP request bodies and the JSON columns in rt-db.
     *
     * <p>{@code FAIL_ON_UNKNOWN_PROPERTIES} is off because stored rows outlive the record shapes
     * that wrote them: analyses, plan payloads, plan versions and snapshots written by older builds
     * still carry {@code aiCandidates}, {@code selectedRecommendation}, {@code recommendationSetId},
     * {@code jmeterScenarios} and similar fields that no longer exist. Rejecting them would turn
     * every old iteration into an empty stub.
     *
     * <p>{@code READ_UNKNOWN_ENUM_VALUES_AS_NULL} matters for {@code OperationType}: a stored
     * candidate naming an operation that has since left the catalogue deserializes with a null
     * operation and is filtered out in {@code AnalyzeService.dropUnsupportedOperations}, instead of
     * throwing and taking the entire stored analysis down with it.
     */
    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.enable(com.fasterxml.jackson.databind.DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL);
        return mapper;
    }

    /** Single-threaded executor so only one Execute pipeline runs at a time. */
    @Bean(name = "executeTaskExecutor")
    public Executor executeTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.setThreadNamePrefix("execute-");
        executor.initialize();
        return executor;
    }
}
