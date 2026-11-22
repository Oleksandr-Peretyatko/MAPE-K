package org.anonymous.msrefactor.ms.versioning.command;

import org.anonymous.msrefactor.ms.messaging.Command;

public class DecrementVersionCommand extends Command {
    public DecrementVersionCommand() {
        super(null, "version", null);
    }
}
