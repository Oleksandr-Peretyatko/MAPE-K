package org.anonymous.msrefactor.ms.messaging;

public interface CommandHandlerDecorator {
    Object handle(Command command, CommandHandler serviceCommandHandler);
}
