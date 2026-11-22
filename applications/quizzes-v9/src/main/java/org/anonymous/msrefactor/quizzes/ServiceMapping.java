package org.anonymous.msrefactor.quizzes;

public enum ServiceMapping {
    TOURNAMENT("tournament"),
    QUIZ("quiz"),
    USER("user"),
    EXECUTION("execution"),
    COURSE("course"),
    QUESTION("question"),
    ANSWER("answer");

    private final String serviceName;

    ServiceMapping(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getServiceName() {
        return serviceName;
    }
}
