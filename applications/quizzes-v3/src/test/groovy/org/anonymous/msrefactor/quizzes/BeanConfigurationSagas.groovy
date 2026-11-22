package org.anonymous.msrefactor.quizzes

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.resilience4j.retry.RetryRegistry
import org.mockito.Mockito
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.cloud.stream.function.StreamBridge
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.PropertySource
import org.anonymous.msrefactor.ms.aggregate.AggregateIdGeneratorService
import org.anonymous.msrefactor.ms.aggregate.EventApplicationService
import org.anonymous.msrefactor.ms.impairment.ImpairmentService
import org.anonymous.msrefactor.ms.messaging.MessagingObjectMapperProvider
import org.anonymous.msrefactor.ms.messaging.local.LocalCommandGateway
import org.anonymous.msrefactor.ms.messaging.local.LocalCommandService
import org.anonymous.msrefactor.ms.messaging.stream.CommandResponseAggregator
import org.anonymous.msrefactor.ms.messaging.stream.StreamCommandGateway
import org.anonymous.msrefactor.ms.monitoring.TraceService
import org.anonymous.msrefactor.ms.notification.EventService
import org.anonymous.msrefactor.ms.transaction.sagas.messaging.SagaCommandHandler
import org.anonymous.msrefactor.ms.transaction.sagas.unitOfWork.SagaUnitOfWorkService
import org.anonymous.msrefactor.ms.versioning.CentralizedVersionService
import org.anonymous.msrefactor.ms.versioning.IVersionService
import org.anonymous.msrefactor.ms.versioning.VersionCommandHandler
import org.anonymous.msrefactor.ms.versioning.VersionServiceClient
import org.anonymous.msrefactor.quizzes.microservices.answer.aggregate.sagas.factories.SagasQuizAnswerFactory
import org.anonymous.msrefactor.quizzes.microservices.answer.aggregate.sagas.repositories.QuizAnswerCustomRepositorySagas
import org.anonymous.msrefactor.quizzes.microservices.answer.coordination.eventProcessing.QuizAnswerEventProcessing
import org.anonymous.msrefactor.quizzes.microservices.answer.coordination.functionalities.QuizAnswerFunctionalities
import org.anonymous.msrefactor.quizzes.microservices.answer.messaging.AnswerCommandHandler
import org.anonymous.msrefactor.quizzes.microservices.answer.notification.handling.QuizAnswerEventHandling
import org.anonymous.msrefactor.quizzes.microservices.answer.service.QuizAnswerService
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.sagas.factories.SagasCourseFactory
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.sagas.repositories.CourseCustomRepositorySagas
import org.anonymous.msrefactor.quizzes.microservices.course.messaging.CourseCommandHandler
import org.anonymous.msrefactor.quizzes.microservices.course.service.CourseService
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.CourseExecutionRepository
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.sagas.factories.SagasCourseExecutionFactory
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.sagas.repositories.CourseExecutionCustomRepositorySagas
import org.anonymous.msrefactor.quizzes.microservices.execution.coordination.eventProcessing.ExecutionEventProcessing
import org.anonymous.msrefactor.quizzes.microservices.execution.coordination.functionalities.ExecutionFunctionalities
import org.anonymous.msrefactor.quizzes.microservices.execution.messaging.ExecutionCommandHandler
import org.anonymous.msrefactor.quizzes.microservices.execution.notification.handling.CourseExecutionEventHandling
import org.anonymous.msrefactor.quizzes.microservices.execution.service.ExecutionService
import org.anonymous.msrefactor.quizzes.microservices.question.aggregate.QuestionRepository
import org.anonymous.msrefactor.quizzes.microservices.question.aggregate.sagas.factories.SagasQuestionFactory
import org.anonymous.msrefactor.quizzes.microservices.question.coordination.eventProcessing.QuestionEventProcessing
import org.anonymous.msrefactor.quizzes.microservices.question.coordination.functionalities.QuestionFunctionalities
import org.anonymous.msrefactor.quizzes.microservices.question.messaging.QuestionCommandHandler
import org.anonymous.msrefactor.quizzes.microservices.question.notification.handling.QuestionEventHandling
import org.anonymous.msrefactor.quizzes.microservices.question.service.QuestionService
import org.anonymous.msrefactor.quizzes.microservices.topic.aggregate.TopicRepository
import org.anonymous.msrefactor.quizzes.microservices.topic.aggregate.sagas.factories.SagasTopicFactory
import org.anonymous.msrefactor.quizzes.microservices.topic.coordination.functionalities.TopicFunctionalities
import org.anonymous.msrefactor.quizzes.microservices.topic.messaging.TopicCommandHandler
import org.anonymous.msrefactor.quizzes.microservices.topic.service.TopicService
import org.anonymous.msrefactor.quizzes.microservices.tournament.aggregate.sagas.factories.SagasTournamentFactory
import org.anonymous.msrefactor.quizzes.microservices.tournament.aggregate.sagas.repositories.TournamentCustomRepositorySagas
import org.anonymous.msrefactor.quizzes.microservices.tournament.coordination.eventProcessing.TournamentEventProcessing
import org.anonymous.msrefactor.quizzes.microservices.tournament.coordination.functionalities.TournamentFunctionalities
import org.anonymous.msrefactor.quizzes.microservices.tournament.messaging.TournamentCommandHandler
import org.anonymous.msrefactor.quizzes.microservices.tournament.notification.handling.TournamentEventHandling
import org.anonymous.msrefactor.quizzes.microservices.tournament.service.TournamentService
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.aggregate.sagas.factories.SagasTournamentSatelliteFactory
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.aggregate.sagas.repositories.TournamentSatelliteCustomRepositorySagas
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.coordination.eventProcessing.TournamentSatelliteEventProcessing
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.coordination.functionalities.TournamentSatelliteFunctionalities
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.messaging.TournamentSatelliteCommandHandler
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.notification.handling.TournamentSatelliteEventHandling
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatellite.service.TournamentSatelliteService
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.aggregate.sagas.factories.SagasTournamentSatelliteSatelliteFactory
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.aggregate.sagas.repositories.TournamentSatelliteSatelliteCustomRepositorySagas
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.coordination.eventProcessing.TournamentSatelliteSatelliteEventProcessing
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.coordination.functionalities.TournamentSatelliteSatelliteFunctionalities
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.messaging.TournamentSatelliteSatelliteCommandHandler
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.notification.handling.TournamentSatelliteSatelliteEventHandling
import org.anonymous.msrefactor.quizzes.microservices.tournamentSatelliteSatellite.service.TournamentSatelliteSatelliteService
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.UserRepository
import org.anonymous.msrefactor.quizzes.microservices.user.aggregate.sagas.factories.SagasUserFactory
import org.anonymous.msrefactor.quizzes.microservices.user.coordination.functionalities.UserFunctionalities
import org.anonymous.msrefactor.quizzes.microservices.user.messaging.UserCommandHandler
import org.anonymous.msrefactor.quizzes.microservices.user.service.UserService

@TestConfiguration
@PropertySource("classpath:application-test.properties")
class BeanConfigurationSagas {
    @Bean
    AggregateIdGeneratorService aggregateIdGeneratorService() {
        return new AggregateIdGeneratorService()
    }

    @Bean
    IVersionService versionService(LocalCommandGateway commandGateway) {
        return new VersionServiceClient(commandGateway)
    }

    @Bean
    CentralizedVersionService centralizedVersionService() {
        return new CentralizedVersionService()
    }

    @Bean
    EventApplicationService eventApplicationService() {
        return new EventApplicationService()
    }

    @Bean
    EventService eventService() {
        return new EventService()
    }

    @Bean
    SagaUnitOfWorkService unitOfWorkService() {
        return new SagaUnitOfWorkService()
    }

    @Bean
    ExecutionFunctionalities executionFunctionalities() {
        return new ExecutionFunctionalities()
    }

    @Bean
    ExecutionEventProcessing executionEventProcessing() {
        return new ExecutionEventProcessing()
    }

    @Bean
    UserFunctionalities userFunctionalities() {
        return new UserFunctionalities()
    }

    @Bean
    TopicFunctionalities topicFunctionalities() {
        return new TopicFunctionalities()
    }

    @Bean
    QuestionFunctionalities questionFunctionalities() {
        return new QuestionFunctionalities()
    }

    @Bean
    QuestionEventProcessing questionEventProcessing() {
        return new QuestionEventProcessing()
    }

    @Bean
    QuizAnswerFunctionalities answerFunctionalities() {
        return new QuizAnswerFunctionalities()
    }

    @Bean
    QuizAnswerEventProcessing answerEventProcessing() {
        return new QuizAnswerEventProcessing()
    }

    @Bean
    TournamentFunctionalities tournamentFunctionalities() {
        return new TournamentFunctionalities()
    }

    @Bean
    TournamentEventProcessing tournamentEventProcessing() {
        return new TournamentEventProcessing()
    }

    @Bean
    TournamentSatelliteFunctionalities tournamentSatelliteFunctionalities() {
        return new TournamentSatelliteFunctionalities()
    }

    @Bean
    TournamentSatelliteEventProcessing tournamentSatelliteEventProcessing() {
        return new TournamentSatelliteEventProcessing()
    }

    @Bean
    TournamentSatelliteCustomRepositorySagas tournamentSatelliteCustomRepositorySagas() {
        return new TournamentSatelliteCustomRepositorySagas()
    }

    @Bean
    SagasTournamentSatelliteFactory sagasTournamentSatelliteFactory() {
        return new SagasTournamentSatelliteFactory()
    }

    @Bean
    TournamentSatelliteService tournamentSatelliteService(SagaUnitOfWorkService unitOfWorkService, TournamentSatelliteCustomRepositorySagas tournamentSatelliteRepository) {
        return new TournamentSatelliteService(unitOfWorkService, tournamentSatelliteRepository)
    }

    @Bean
    TournamentSatelliteEventHandling tournamentSatelliteEventDetection() {
        return new TournamentSatelliteEventHandling()
    }

    @Bean
    TournamentSatelliteCommandHandler tournamentSatelliteCommandHandler() {
        return new TournamentSatelliteCommandHandler()
    }

    @Bean
    TournamentSatelliteSatelliteFunctionalities tournamentSatelliteSatelliteFunctionalities() {
        return new TournamentSatelliteSatelliteFunctionalities()
    }

    @Bean
    TournamentSatelliteSatelliteEventProcessing tournamentSatelliteSatelliteEventProcessing() {
        return new TournamentSatelliteSatelliteEventProcessing()
    }

    @Bean
    TournamentSatelliteSatelliteCustomRepositorySagas tournamentSatelliteSatelliteCustomRepositorySagas() {
        return new TournamentSatelliteSatelliteCustomRepositorySagas()
    }

    @Bean
    SagasTournamentSatelliteSatelliteFactory sagasTournamentSatelliteSatelliteFactory() {
        return new SagasTournamentSatelliteSatelliteFactory()
    }

    @Bean
    TournamentSatelliteSatelliteService tournamentSatelliteSatelliteService(SagaUnitOfWorkService unitOfWorkService, TournamentSatelliteSatelliteCustomRepositorySagas tournamentSatelliteSatelliteRepository) {
        return new TournamentSatelliteSatelliteService(unitOfWorkService, tournamentSatelliteSatelliteRepository)
    }

    @Bean
    TournamentSatelliteSatelliteEventHandling tournamentSatelliteSatelliteEventDetection() {
        return new TournamentSatelliteSatelliteEventHandling()
    }

    @Bean
    TournamentSatelliteSatelliteCommandHandler tournamentSatelliteSatelliteCommandHandler() {
        return new TournamentSatelliteSatelliteCommandHandler()
    }

    @Bean
    CourseCustomRepositorySagas courseCustomRepositorySagas() {
        return new CourseCustomRepositorySagas()
    }

    @Bean
    CourseExecutionCustomRepositorySagas courseExecutionCustomRepositorySagas() {
        return new CourseExecutionCustomRepositorySagas()
    }

    @Bean
    TournamentCustomRepositorySagas tournamentCustomRepositorySagas() {
        return new TournamentCustomRepositorySagas()
    }

    @Bean
    QuizAnswerCustomRepositorySagas quizAnswerCustomRepositorySagas() {
        return new QuizAnswerCustomRepositorySagas()
    }

    @Bean
    SagasQuizAnswerFactory sagasQuizAnswerFactory() {
        return new SagasQuizAnswerFactory()
    }

    @Bean
    SagasCourseFactory sagasCourseFactory() {
        return new SagasCourseFactory()
    }

    @Bean
    SagasCourseExecutionFactory sagasCourseExecutionFactory() {
        return new SagasCourseExecutionFactory()
    }

    @Bean
    SagasQuestionFactory sagasQuestionFactory() {
        return new SagasQuestionFactory()
    }

    @Bean
    SagasTopicFactory sagasTopicFactory() {
        return new SagasTopicFactory()
    }

    @Bean
    SagasTournamentFactory sagasTournamentFactory() {
        return new SagasTournamentFactory()
    }

    @Bean
    SagasUserFactory sagasUserFactory() {
        return new SagasUserFactory()
    }

    @Bean
    CourseService courseService(SagaUnitOfWorkService unitOfWorkService, CourseCustomRepositorySagas courseRepository) {
        return new CourseService(unitOfWorkService, courseRepository)
    }

    @Bean
    QuizAnswerService answerService(SagaUnitOfWorkService unitOfWorkService, QuizAnswerCustomRepositorySagas quizAnswerRepository) {
        return new QuizAnswerService(unitOfWorkService, quizAnswerRepository)
    }

    @Bean
    TournamentService tournamentService(SagaUnitOfWorkService unitOfWorkService, TournamentCustomRepositorySagas tournamentRepository) {
        return new TournamentService(unitOfWorkService, tournamentRepository)
    }

    @Bean
    ExecutionService executionService(SagaUnitOfWorkService unitOfWorkService, CourseExecutionRepository courseExecutionRepository, CourseExecutionCustomRepositorySagas courseExecutionCustomRepository) {
        return new ExecutionService(unitOfWorkService, courseExecutionRepository, courseExecutionCustomRepository)
    }

    @Bean
    UserService userService(SagaUnitOfWorkService unitOfWorkService, UserRepository userRepository) {
        return new UserService(unitOfWorkService, userRepository)
    }

    @Bean
    TopicService topicService(SagaUnitOfWorkService unitOfWorkService, TopicRepository topicRepository) {
        return new TopicService(unitOfWorkService, topicRepository)
    }

    @Bean
    QuestionService questionService(SagaUnitOfWorkService unitOfWorkService, QuestionRepository questionRepository) {
        return new QuestionService(unitOfWorkService, questionRepository)
    }

    @Bean
    CourseExecutionEventHandling courseExecutionEventDetection() {
        return new CourseExecutionEventHandling()
    }

    @Bean
    QuestionEventHandling questionEventDetection() {
        return new QuestionEventHandling()
    }

    @Bean
    QuizAnswerEventHandling answerEventDetection() {
        return new QuizAnswerEventHandling()
    }

    @Bean
    TournamentEventHandling tournamentEventDetection() {
        return new TournamentEventHandling()
    }

    @Bean
    ImpairmentService ImpairmentService() {
        return new ImpairmentService()
    }

    @Bean
    RetryRegistry retryRegistry() {
        return RetryRegistry.ofDefaults()
    }

    @Bean
    MessagingObjectMapperProvider messagingObjectMapperProvider() {
        return new MessagingObjectMapperProvider(new ObjectMapper().findAndRegisterModules())
    }

    @Bean
    LocalCommandService localCommandService(ApplicationContext applicationContext, MessagingObjectMapperProvider mapperProvider) {
        return new LocalCommandService(applicationContext, mapperProvider)
    }

    @Bean
    LocalCommandGateway commandGateway(ApplicationContext applicationContext, RetryRegistry registry, LocalCommandService localCommandService, MessagingObjectMapperProvider mapperProvider) {
        return new LocalCommandGateway(applicationContext, registry, localCommandService, mapperProvider)
    }

    @Bean
    StreamBridge streamBridge() {
        def mock = Mockito.mock(StreamBridge.class)
        Mockito.when(mock.send(Mockito.anyString(), Mockito.any())).thenReturn(true)
        return mock
    }

    @Bean
    CommandResponseAggregator commandResponseAggregator() {
        return new CommandResponseAggregator()
    }


    @Bean
    StreamCommandGateway streamCommandGateway(LocalCommandGateway commandGateway) {
        def mock = Mockito.mock(StreamCommandGateway.class)

        // Delegate basic send(command) to the in-memory CommandGateway
        Mockito.when(mock.send(Mockito.any())).thenAnswer(inv -> commandGateway.send(inv.getArgument(0)))

        return mock
    }

    @Bean
    TraceService TraceService() {
        return new TraceService()
    }

    // Command Handlers
    @Bean
    SagaCommandHandler sagaCommandHandler() {
        return new SagaCommandHandler()
    }

    @Bean
    VersionCommandHandler versionCommandHandler() {
        return new VersionCommandHandler()
    }

    @Bean
    UserCommandHandler userCommandHandler() {
        return new UserCommandHandler()
    }

    @Bean
    TournamentCommandHandler tournamentCommandHandler() {
        return new TournamentCommandHandler()
    }

    @Bean
    QuestionCommandHandler questionCommandHandler() {
        return new QuestionCommandHandler()
    }

    @Bean
    TopicCommandHandler topicCommandHandler() {
        return new TopicCommandHandler()
    }

    @Bean
    ExecutionCommandHandler executionCommandHandler() {
        return new ExecutionCommandHandler()
    }

    @Bean
    CourseCommandHandler courseCommandHandler() {
        return new CourseCommandHandler()
    }

    @Bean
    AnswerCommandHandler answerCommandHandler() {
        return new AnswerCommandHandler()
    }

}
