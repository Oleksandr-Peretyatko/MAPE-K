package org.anonymous.msrefactor.ms.versioning.command;

import org.anonymous.msrefactor.ms.messaging.Command;

public class GetVersionCommand extends Command {
    public GetVersionCommand() {
        super(null, "version", null);
    }
}
