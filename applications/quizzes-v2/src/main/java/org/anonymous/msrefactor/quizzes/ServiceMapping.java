package org.anonymous.msrefactor.quizzes;

public enum ServiceMapping {
    TOURNAMENT("tournament"),
    TOURNAMENT_SATELLITE("tournamentSatellite"),
    USER("user"),
    EXECUTION("execution"),
    COURSE("course"),
    QUESTION("question"),
    TOPIC("topic"),
    ANSWER("answer");

    private final String serviceName;

    ServiceMapping(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getServiceName() {
        return serviceName;
    }
}
