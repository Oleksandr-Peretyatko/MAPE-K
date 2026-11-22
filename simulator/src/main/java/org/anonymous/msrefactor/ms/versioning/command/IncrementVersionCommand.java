package org.anonymous.msrefactor.ms.versioning.command;

import org.anonymous.msrefactor.ms.messaging.Command;

public class IncrementVersionCommand extends Command {
    public IncrementVersionCommand() {
        super(null, "version", null);
    }
}
