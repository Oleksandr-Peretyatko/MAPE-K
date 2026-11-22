package org.anonymous.msrefactor.quizzes;

public enum ServiceMapping {
    USER("user"),
    EXECUTION("execution"),
    COURSE("course"),
    ANSWER("answer");

    private final String serviceName;

    ServiceMapping(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getServiceName() {
        return serviceName;
    }
}
