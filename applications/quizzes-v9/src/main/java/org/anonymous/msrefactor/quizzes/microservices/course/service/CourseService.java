package org.anonymous.msrefactor.quizzes.microservices.course.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.anonymous.msrefactor.ms.aggregate.AggregateIdGeneratorService;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWork;
import org.anonymous.msrefactor.ms.transaction.unitOfWork.UnitOfWorkService;
import org.anonymous.msrefactor.ms.exception.SimulatorErrorMessage;
import org.anonymous.msrefactor.ms.exception.SimulatorException;
import org.anonymous.msrefactor.quizzes.events.DeleteTopicEvent;
import org.anonymous.msrefactor.quizzes.events.UpdateTopicEvent;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.Course;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.CourseCustomRepository;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.CourseDto;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.CourseFactory;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.Topic;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.TopicDto;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.CourseExecutionDto;

import java.util.List;
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
    public void incrementCourseQuestionCount(Integer courseAggregateId, UnitOfWork unitOfWork) {
        Course oldCourse = (Course) unitOfWorkService.aggregateLoadAndRegisterRead(courseAggregateId, unitOfWork);
        Course newCourse = courseFactory.createCourseFromExisting(oldCourse);
        newCourse.setCourseQuestionCount(newCourse.getCourseQuestionCount() + 1);
        unitOfWorkService.registerChanged(newCourse, unitOfWork);
    }

    // CANNOT_DELETE_LAST_EXECUTION_WITH_CONTENT
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void decrementCourseQuestionCount(Integer courseAggregateId, UnitOfWork unitOfWork) {
        Course oldCourse = (Course) unitOfWorkService.aggregateLoadAndRegisterRead(courseAggregateId, unitOfWork);
        Course newCourse = courseFactory.createCourseFromExisting(oldCourse);
        newCourse.setCourseQuestionCount(Math.max(0, newCourse.getCourseQuestionCount() - 1));
        unitOfWorkService.registerChanged(newCourse, unitOfWork);
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

    // Absorbed from TopicService (merge course <- topic). The wire names keep
    // "topicAggregateId"; inside the Course aggregate the value is the member's topicId.
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public TopicDto getTopicById(Integer topicAggregateId, UnitOfWork unitOfWork) {
        Course course = loadCourseOwningTopic(topicAggregateId, unitOfWork);
        return new TopicDto(course.getTopic(topicAggregateId));
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public TopicDto createTopic(Integer courseAggregateId, TopicDto topicDto, UnitOfWork unitOfWork) {
        Course oldCourse = (Course) unitOfWorkService.aggregateLoadAndRegisterRead(courseAggregateId, unitOfWork);
        Course newCourse = courseFactory.createCourseFromExisting(oldCourse);
        Topic topic = new Topic(aggregateIdGeneratorService.getNewAggregateId(), topicDto.getName());
        newCourse.addTopic(topic);
        unitOfWorkService.registerChanged(newCourse, unitOfWork);
        return new TopicDto(topic);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public List<TopicDto> findTopicsByCourseId(Integer courseAggregateId, UnitOfWork unitOfWork) {
        Course course = (Course) unitOfWorkService.aggregateLoadAndRegisterRead(courseAggregateId, unitOfWork);
        return course.getTopics().stream()
                .filter(topic -> !topic.isDeleted())
                .map(TopicDto::new)
                .collect(Collectors.toList());
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void updateTopic(TopicDto topicDto, UnitOfWork unitOfWork) {
        Course oldCourse = loadCourseOwningTopic(topicDto.getAggregateId(), unitOfWork);
        Course newCourse = courseFactory.createCourseFromExisting(oldCourse);
        Topic topic = newCourse.getTopic(topicDto.getAggregateId());
        topic.setName(topicDto.getName());
        unitOfWorkService.registerChanged(newCourse, unitOfWork);
        unitOfWorkService.registerEvent(new UpdateTopicEvent(topic.getTopicId(), topic.getName()), unitOfWork);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void deleteTopic(Integer topicAggregateId, UnitOfWork unitOfWork) {
        Course oldCourse = loadCourseOwningTopic(topicAggregateId, unitOfWork);
        Course newCourse = courseFactory.createCourseFromExisting(oldCourse);
        Topic topic = newCourse.getTopic(topicAggregateId);
        topic.setDeleted(true);
        unitOfWorkService.registerChanged(newCourse, unitOfWork);
        unitOfWorkService.registerEvent(new DeleteTopicEvent(topic.getTopicId()), unitOfWork);
    }

    private Course loadCourseOwningTopic(Integer topicId, UnitOfWork unitOfWork) {
        Integer courseAggregateId = courseRepository.findCourseIdByTopicId(topicId)
                .orElseThrow(() -> new SimulatorException(SimulatorErrorMessage.AGGREGATE_NOT_FOUND, topicId));
        return (Course) unitOfWorkService.aggregateLoadAndRegisterRead(courseAggregateId, unitOfWork);
    }

    private Course getCourseByName(String courseName, UnitOfWork unitOfWork) {
        return courseRepository.findCourseIdByName(courseName)
                .map(id -> (Course) unitOfWorkService.aggregateLoadAndRegisterRead(id, unitOfWork))
                .orElse(null);
    }
}
