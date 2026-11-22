package org.anonymous.msrefactor.ms.versioning.command;

import org.anonymous.msrefactor.ms.messaging.Command;

public class GetNextVersionCommand extends Command {
	public GetNextVersionCommand() {
		super(null, "version", null);
	}
}
