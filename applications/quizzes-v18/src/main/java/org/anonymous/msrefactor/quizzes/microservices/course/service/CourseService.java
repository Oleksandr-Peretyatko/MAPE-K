package org.anonymous.msrefactor.quizzes.microservices.course.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.anonymous.msrefactor.ms.aggregate.Aggregate;
import org.anonymous.msrefactor.ms.aggregate.AggregateIdGeneratorService;
import org.anonymous.msrefactor.ms.exception.SimulatorErrorMessage;
import org.anonymous.msrefactor.ms.exception.SimulatorException;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWorkService;
import org.anonymous.msrefactor.quizzes.events.CreateQuestionEvent;
import org.anonymous.msrefactor.quizzes.events.DeleteQuestionEvent;
import org.anonymous.msrefactor.quizzes.events.DeleteTopicEvent;
import org.anonymous.msrefactor.quizzes.events.UpdateQuestionEvent;
import org.anonymous.msrefactor.quizzes.events.UpdateTopicEvent;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.Course;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.CourseCustomRepository;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.CourseDto;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.CourseFactory;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.Question;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.QuestionDto;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.QuestionTopic;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.Topic;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.TopicDto;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.CourseExecutionDto;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class CourseService {
    @Autowired
    private AggregateIdGeneratorService aggregateIdGeneratorService;

    private final UnitOfWorkService<UnitOfWork> unitOfWorkService;

    private final CourseCustomRepository courseRepository;

    @Autowired
    private CourseFactory courseFactory;

    public CourseService(UnitOfWorkService unitOfWorkService, CourseCustomRepository courseRepository) {
        this.unitOfWorkService = unitOfWorkService;
        this.courseRepository = courseRepository;
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public CourseDto getCourseById(Integer aggregateId, UnitOfWork unitOfWorkWorkService) {
        return courseFactory.createCourseDto(
                (Course) unitOfWorkService.aggregateLoadAndRegisterRead(aggregateId, unitOfWorkWorkService));
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public CourseExecutionDto getAndOrCreateCourseRemote(CourseExecutionDto courseExecutionDto, UnitOfWork unitOfWork) {
        Course course = getCourseByName(courseExecutionDto.getName(), unitOfWork);
        if (course == null) {
            Integer aggregateId = aggregateIdGeneratorService.getNewAggregateId();
            course = courseFactory.createCourse(aggregateId, courseExecutionDto);
            unitOfWorkService.registerChanged(course, unitOfWork);
        }
        courseExecutionDto.setCourseAggregateId(course.getAggregateId());
        courseExecutionDto.setName(course.getName());
        courseExecutionDto.setType(course.getType().toString());
        courseExecutionDto.setCourseVersion(course.getVersion());
        return courseExecutionDto;
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public CourseExecutionDto getCourseByNameRemote(CourseExecutionDto courseExecutionDto, UnitOfWork unitOfWork) {
        Course course = getCourseByName(courseExecutionDto.getName(), unitOfWork);
        if (course != null) {
            courseExecutionDto.setCourseAggregateId(course.getAggregateId());
            courseExecutionDto.setName(course.getName());
            courseExecutionDto.setType(course.getType().toString());
            courseExecutionDto.setCourseVersion(course.getVersion());
        }
        // courseAggregateId remains null if course not found
        return courseExecutionDto;
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public CourseExecutionDto createCourseRemote(CourseExecutionDto courseExecutionDto, UnitOfWork unitOfWork) {
        Integer aggregateId = aggregateIdGeneratorService.getNewAggregateId();
        Course course = courseFactory.createCourse(aggregateId, courseExecutionDto);
        unitOfWorkService.registerChanged(course, unitOfWork);
        courseExecutionDto.setCourseAggregateId(course.getAggregateId());
        courseExecutionDto.setName(course.getName());
        courseExecutionDto.setType(course.getType().toString());
        courseExecutionDto.setCourseVersion(course.getVersion());
        return courseExecutionDto;
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void deleteCourse(Integer courseAggregateId, UnitOfWork unitOfWork) {
        Course course = (Course) unitOfWorkService.aggregateLoadAndRegisterRead(courseAggregateId, unitOfWork);
        course.remove();
        unitOfWorkService.registerChanged(course, unitOfWork);
    }

    // CANNOT_DELETE_LAST_EXECUTION_WITH_CONTENT
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void incrementCourseExecutionCount(Integer courseAggregateId, UnitOfWork unitOfWork) {
        // The course may have been created in this same UoW (not yet in DB) — update it in-place
        Course pendingCourse = unitOfWork.getAggregatesToCommit().stream()
                .filter(a -> a instanceof Course && courseAggregateId.equals(a.getAggregateId()))
                .map(a -> (Course) a)
                .findFirst()
                .orElse(null);
        if (pendingCourse != null) {
            pendingCourse.setCourseExecutionCount(pendingCourse.getCourseExecutionCount() + 1);
            return;
        }
        Course oldCourse = (Course) unitOfWorkService.aggregateLoadAndRegisterRead(courseAggregateId, unitOfWork);
        Course newCourse = courseFactory.createCourseFromExisting(oldCourse);
        newCourse.setCourseExecutionCount(newCourse.getCourseExecutionCount() + 1);
        unitOfWorkService.registerChanged(newCourse, unitOfWork);
    }

    // CANNOT_DELETE_LAST_EXECUTION_WITH_CONTENT
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void decrementCourseExecutionCount(Integer courseAggregateId, UnitOfWork unitOfWork) {
        Course oldCourse = (Course) unitOfWorkService.aggregateLoadAndRegisterRead(courseAggregateId, unitOfWork);
        Course newCourse = courseFactory.createCourseFromExisting(oldCourse);
        newCourse.setCourseExecutionCount(Math.max(0, newCourse.getCourseExecutionCount() - 1));
        unitOfWorkService.registerChanged(newCourse, unitOfWork);
    }

    private Course getCourseByName(String courseName, UnitOfWork unitOfWork) {
        return courseRepository.findCourseIdByName(courseName)
                .map(id -> (Course) unitOfWorkService.aggregateLoadAndRegisterRead(id, unitOfWork))
                .orElse(null);
    }

    /************************************************
     * QUESTION MEMBERS (formerly the Question aggregate)
     ************************************************/

    private Course loadCourseByQuestionId(Integer questionAggregateId, UnitOfWork unitOfWork) {
        Integer courseAggregateId = courseRepository.findCourseIdByQuestionId(questionAggregateId)
                .orElseThrow(() -> new SimulatorException(SimulatorErrorMessage.AGGREGATE_NOT_FOUND, questionAggregateId));
        return (Course) unitOfWorkService.aggregateLoadAndRegisterRead(courseAggregateId, unitOfWork);
    }

    private Question getQuestionOrThrow(Course course, Integer questionAggregateId) {
        Question question = course.getQuestionByQuestionId(questionAggregateId);
        if (question == null) {
            throw new SimulatorException(SimulatorErrorMessage.AGGREGATE_NOT_FOUND, questionAggregateId);
        }
        return question;
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public QuestionDto getQuestionById(Integer questionAggregateId, UnitOfWork unitOfWork) {
        Course course = loadCourseByQuestionId(questionAggregateId, unitOfWork);
        return new QuestionDto(getQuestionOrThrow(course, questionAggregateId));
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public List<QuestionDto> findQuestionsByCourseAggregateId(Integer courseAggregateId, UnitOfWork unitOfWork) {
        Course course = (Course) unitOfWorkService.aggregateLoadAndRegisterRead(courseAggregateId, unitOfWork);
        return course.getQuestions().stream()
                .filter(q -> q.getState() != Aggregate.AggregateState.DELETED)
                .map(QuestionDto::new)
                .collect(Collectors.toList());
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public QuestionDto createQuestion(Integer courseAggregateId, QuestionDto questionDto, List<TopicDto> topics,
            UnitOfWork unitOfWork) {
        Course oldCourse = (Course) unitOfWorkService.aggregateLoadAndRegisterRead(courseAggregateId, unitOfWork);
        Course newCourse = courseFactory.createCourseFromExisting(oldCourse);

        Integer questionId = aggregateIdGeneratorService.getNewAggregateId();
        List<QuestionTopic> questionTopics = topics.stream()
                .map(QuestionTopic::new)
                .collect(Collectors.toList());

        Question question = new Question(questionId, questionDto, questionTopics);
        newCourse.addQuestion(question);
        unitOfWorkService.registerChanged(newCourse, unitOfWork);
        // same publisher id as before the merge: the course's aggregate id (was read
        // from the deleted QuestionCourse replica, now from the root itself)
        unitOfWorkService.registerEvent(new CreateQuestionEvent(newCourse.getAggregateId()), unitOfWork);
        return new QuestionDto(question);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void updateQuestion(QuestionDto questionDto, UnitOfWork unitOfWork) {
        Course oldCourse = loadCourseByQuestionId(questionDto.getAggregateId(), unitOfWork);
        Course newCourse = courseFactory.createCourseFromExisting(oldCourse);
        Question question = getQuestionOrThrow(newCourse, questionDto.getAggregateId());
        question.update(questionDto);
        unitOfWorkService.registerChanged(newCourse, unitOfWork);
        // the member keeps publishing with its retained identifier so external
        // subscriptions (QuizQuestion in execution) keep matching
        unitOfWorkService.registerEvent(
                new UpdateQuestionEvent(question.getQuestionId(), question.getTitle(), question.getContent()),
                unitOfWork);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void removeQuestion(Integer questionAggregateId, UnitOfWork unitOfWork) {
        Course oldCourse = loadCourseByQuestionId(questionAggregateId, unitOfWork);
        Course newCourse = courseFactory.createCourseFromExisting(oldCourse);
        Question question = getQuestionOrThrow(newCourse, questionAggregateId);
        question.setState(Aggregate.AggregateState.DELETED);
        unitOfWorkService.registerChanged(newCourse, unitOfWork);
        unitOfWorkService.registerEvent(new DeleteQuestionEvent(newCourse.getAggregateId()), unitOfWork);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void updateQuestionTopics(Integer questionAggregateId, Set<QuestionTopic> topics, UnitOfWork unitOfWork) {
        Course oldCourse = loadCourseByQuestionId(questionAggregateId, unitOfWork);
        Course newCourse = courseFactory.createCourseFromExisting(oldCourse);
        Question question = getQuestionOrThrow(newCourse, questionAggregateId);
        question.setQuestionTopics(topics);
        unitOfWorkService.registerChanged(newCourse, unitOfWork);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public List<QuestionDto> findQuestionsByTopicIds(List<Integer> topicIds, UnitOfWork unitOfWork) {
        Set<Integer> courseAggregateIds = courseRepository.findCourseIdsWithQuestionsOfTopics(topicIds);
        List<QuestionDto> questionDtos = new ArrayList<>();
        for (Integer courseAggregateId : courseAggregateIds) {
            Course course = (Course) unitOfWorkService.aggregateLoadAndRegisterRead(courseAggregateId, unitOfWork);
            course.getQuestions().stream()
                    .filter(q -> q.getState() != Aggregate.AggregateState.DELETED)
                    .filter(q -> q.getQuestionTopics().stream()
                            .anyMatch(qt -> topicIds.contains(qt.getTopicId())))
                    .map(QuestionDto::new)
                    .forEach(questionDtos::add);
        }
        return questionDtos;
    }

    /************************************************
     * TOPIC MEMBERS (formerly the Topic aggregate)
     ************************************************/

    private Course loadCourseByTopicId(Integer topicAggregateId, UnitOfWork unitOfWork) {
        Integer courseAggregateId = courseRepository.findCourseIdByTopicId(topicAggregateId)
                .orElseThrow(() -> new SimulatorException(SimulatorErrorMessage.AGGREGATE_NOT_FOUND, topicAggregateId));
        return (Course) unitOfWorkService.aggregateLoadAndRegisterRead(courseAggregateId, unitOfWork);
    }

    private Topic getTopicOrThrow(Course course, Integer topicAggregateId) {
        Topic topic = course.getTopicByTopicId(topicAggregateId);
        if (topic == null) {
            throw new SimulatorException(SimulatorErrorMessage.AGGREGATE_NOT_FOUND, topicAggregateId);
        }
        return topic;
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public TopicDto getTopicById(Integer topicAggregateId, UnitOfWork unitOfWork) {
        Course course = loadCourseByTopicId(topicAggregateId, unitOfWork);
        return new TopicDto(getTopicOrThrow(course, topicAggregateId), course);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public TopicDto createTopic(Integer courseAggregateId, TopicDto topicDto, UnitOfWork unitOfWork) {
        Course oldCourse = (Course) unitOfWorkService.aggregateLoadAndRegisterRead(courseAggregateId, unitOfWork);
        Course newCourse = courseFactory.createCourseFromExisting(oldCourse);
        Topic topic = new Topic(aggregateIdGeneratorService.getNewAggregateId(), topicDto.getName());
        newCourse.addTopic(topic);
        unitOfWorkService.registerChanged(newCourse, unitOfWork);
        return new TopicDto(topic, newCourse);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public List<TopicDto> findTopicsByCourseId(Integer courseAggregateId, UnitOfWork unitOfWork) {
        Course course = (Course) unitOfWorkService.aggregateLoadAndRegisterRead(courseAggregateId, unitOfWork);
        return course.getTopics().stream()
                .filter(t -> t.getState() != Aggregate.AggregateState.DELETED)
                .map(t -> new TopicDto(t, course))
                .collect(Collectors.toList());
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void updateTopic(TopicDto topicDto, UnitOfWork unitOfWork) {
        Course oldCourse = loadCourseByTopicId(topicDto.getAggregateId(), unitOfWork);
        Course newCourse = courseFactory.createCourseFromExisting(oldCourse);
        Topic topic = getTopicOrThrow(newCourse, topicDto.getAggregateId());
        topic.setName(topicDto.getName());
        unitOfWorkService.registerChanged(newCourse, unitOfWork);
        // the member keeps publishing with its retained identifier so external
        // subscriptions (TournamentTopic in execution) keep matching
        unitOfWorkService.registerEvent(new UpdateTopicEvent(topic.getTopicId(), topic.getName()), unitOfWork);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void deleteTopic(Integer topicAggregateId, UnitOfWork unitOfWork) {
        Course oldCourse = loadCourseByTopicId(topicAggregateId, unitOfWork);
        Course newCourse = courseFactory.createCourseFromExisting(oldCourse);
        Topic topic = getTopicOrThrow(newCourse, topicAggregateId);
        topic.setState(Aggregate.AggregateState.DELETED);
        unitOfWorkService.registerChanged(newCourse, unitOfWork);
        unitOfWorkService.registerEvent(new DeleteTopicEvent(topic.getTopicId()), unitOfWork);
    }
}
