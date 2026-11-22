package org.anonymous.msrefactor.rt;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Refactor Tool Spring Boot application.
 *
 * <p>The application exposes five REST controller namespaces corresponding to the
 * MAPE-K loop phases:
 * <ul>
 *   <li>{@code /monitor/**}  — Monitor phase (fully implemented)</li>
 *   <li>{@code /analyze/**}  — Analyze phase (stub)</li>
 *   <li>{@code /plan/**}     — Plan phase (stub)</li>
 *   <li>{@code /execute/**}  — Execute phase (stub)</li>
 *   <li>{@code /knowledge/**}— Knowledge base (stub)</li>
 * </ul>
 *
 * <p>All phases run in a single JVM and Docker container. The controller boundary is
 * the separation of concerns between phases — each phase will be fully implemented
 * in successive iterations.
 */
@SpringBootApplication
public class RefactorToolApplication {

    public static void main(String[] args) {
        SpringApplication.run(RefactorToolApplication.class, args);
    }
}
