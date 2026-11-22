package org.anonymous.msrefactor.ms.versioning;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Profile;

@Profile("version-service")
@SpringBootApplication(scanBasePackages = {
        "org.anonymous.msrefactor.ms.versioning",
        "org.anonymous.msrefactor.ms.messaging",
        "org.anonymous.msrefactor.ms.exception"
})
public class VersionServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(VersionServiceApplication.class, args);
    }
}
