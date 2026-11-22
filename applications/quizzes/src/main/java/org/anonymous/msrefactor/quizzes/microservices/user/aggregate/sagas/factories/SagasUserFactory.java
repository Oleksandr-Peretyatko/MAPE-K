package org.anonymous.msrefactor.quizzes.microservices.user.aggregate.sagas.factories;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.User;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserDto;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserFactory;
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.sagas.SagaUser;

@Service
@Profile("sagas")
public class SagasUserFactory implements UserFactory {

    @Override
    public User createUser(Integer aggregateId, UserDto userDto) {
        return new SagaUser(aggregateId, userDto);
    }

    @Override
    public User createUserFromExisting(User existingUser) {
        return new SagaUser((SagaUser) existingUser);
    }

    @Override
    public UserDto createUserDto(User user) {
        return new UserDto(user);
    }
}