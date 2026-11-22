package org.anonymous.msrefactor.ms.versioning;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.anonymous.msrefactor.ms.messaging.CommandGateway;
import org.anonymous.msrefactor.ms.versioning.command.DecrementVersionCommand;
import org.anonymous.msrefactor.ms.versioning.command.GetNextVersionCommand;
import org.anonymous.msrefactor.ms.versioning.command.GetVersionCommand;
import org.anonymous.msrefactor.ms.versioning.command.IncrementVersionCommand;

@Service
@Primary
@Profile("(local | remote) & !test & !version-service & !distributed-version")
public class VersionServiceClient implements IVersionService {
    private static final Logger logger = LoggerFactory.getLogger(VersionServiceClient.class);

    private final CommandGateway commandGateway;

    public VersionServiceClient(CommandGateway commandGateway) {
        this.commandGateway = commandGateway;
    }

    @Override
    public Long getVersionNumber() {
        logger.info("Requesting version number via CommandGateway");
        return (Long) commandGateway.send(new GetVersionCommand());
    }

    @Override
    public Long getNextVersionNumber() {
        logger.info("Requesting next version number via CommandGateway");
        return (Long) commandGateway.send(new GetNextVersionCommand());
    }

    @Override
    public Long incrementAndGetVersionNumber() {
        logger.info("Requesting increment version via CommandGateway");
        return (Long) commandGateway.send(new IncrementVersionCommand());
    }

    @Override
    public void decrementVersionNumber() {
        logger.info("Requesting decrement version");
        commandGateway.send(new DecrementVersionCommand());
    }
}
