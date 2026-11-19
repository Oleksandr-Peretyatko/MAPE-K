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
import org.anonymous.msrefactor.quizzes.events.UpdateQuestionEvent;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.Course;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.CourseCustomRepository;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.CourseDto;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.CourseFactory;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.Question;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.QuestionDto;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.QuestionTopic;
import org.anonymous.msrefactor.quizzes.microservices.execution.aggregate.CourseExecutionDto;
import org.anonymous.msrefactor.quizzes.microservices.topic.aggregate.TopicDto;

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
     * QUESTION (merged from the former Question aggregate, merge course <- question)
     ************************************************/

    // Resolve the owning course of an absorbed Question member, then navigate to it. Only an ACTIVE
    // question is visible; anything else (no owning course, non-ACTIVE member) is AGGREGATE_NOT_FOUND,
    // preserving the former Question-aggregate load semantics (layer 2.2).
    private Question loadActiveQuestionForRead(Integer questionAggregateId, UnitOfWork unitOfWork) {
        Integer courseId = courseRepository.findCourseIdByQuestionId(questionAggregateId)
                .orElseThrow(() -> new SimulatorException(SimulatorErrorMessage.AGGREGATE_NOT_FOUND, questionAggregateId));
        Course course = (Course) unitOfWorkService.aggregateLoadAndRegisterRead(courseId, unitOfWork);
        Question question = course.findQuestion(questionAggregateId);
        if (question == null || question.getState() != Aggregate.AggregateState.ACTIVE) {
            throw new SimulatorException(SimulatorErrorMessage.AGGREGATE_NOT_FOUND, questionAggregateId);
        }
        return question;
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public QuestionDto getQuestionById(Integer aggregateId, UnitOfWork unitOfWork) {
        return new QuestionDto(loadActiveQuestionForRead(aggregateId, unitOfWork));
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public List<QuestionDto> findQuestionsByCourseAggregateId(Integer courseAggregateId, UnitOfWork unitOfWork) {
        Course course = (Course) unitOfWorkService.aggregateLoadAndRegisterRead(courseAggregateId, unitOfWork);
        return course.getQuestions().stream()
                .filter(question -> question.getState() == Aggregate.AggregateState.ACTIVE)
                .map(QuestionDto::new)
                .collect(Collectors.toList());
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public QuestionDto createQuestion(Integer courseAggregateId, QuestionDto questionDto, List<TopicDto> topics,
            UnitOfWork unitOfWork) {
        Course oldCourse = (Course) unitOfWorkService.aggregateLoadAndRegisterRead(courseAggregateId, unitOfWork);
        Course newCourse = courseFactory.createCourseFromExisting(oldCourse);

        List<QuestionTopic> questionTopics = topics.stream()
                .map(QuestionTopic::new)
                .collect(Collectors.toList());

        Integer questionId = aggregateIdGeneratorService.getNewAggregateId();
        Question question = new Question(questionId, questionDto, questionTopics);
        newCourse.addQuestion(question);
        unitOfWorkService.registerChanged(newCourse, unitOfWork);
        unitOfWorkService.registerEvent(new CreateQuestionEvent(courseAggregateId), unitOfWork);
        return new QuestionDto(question);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void updateQuestion(QuestionDto questionDto, UnitOfWork unitOfWork) {
        Integer courseId = courseRepository.findCourseIdByQuestionId(questionDto.getAggregateId())
                .orElseThrow(() -> new SimulatorException(SimulatorErrorMessage.AGGREGATE_NOT_FOUND, questionDto.getAggregateId()));
        Course oldCourse = (Course) unitOfWorkService.aggregateLoadAndRegisterRead(courseId, unitOfWork);
        Course newCourse = courseFactory.createCourseFromExisting(oldCourse);
        Question question = newCourse.getQuestion(questionDto.getAggregateId());
        question.update(questionDto);
        unitOfWorkService.registerChanged(newCourse, unitOfWork);
        unitOfWorkService.registerEvent(
                new UpdateQuestionEvent(question.getQuestionId(), question.getTitle(), question.getContent()),
                unitOfWork);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void removeQuestion(Integer questionAggregateId, UnitOfWork unitOfWork) {
        Integer courseId = courseRepository.findCourseIdByQuestionId(questionAggregateId)
                .orElseThrow(() -> new SimulatorException(SimulatorErrorMessage.AGGREGATE_NOT_FOUND, questionAggregateId));
        Course oldCourse = (Course) unitOfWorkService.aggregateLoadAndRegisterRead(courseId, unitOfWork);
        Course newCourse = courseFactory.createCourseFromExisting(oldCourse);
        Question question = newCourse.getQuestion(questionAggregateId);
        question.setState(Aggregate.AggregateState.DELETED);
        unitOfWorkService.registerChanged(newCourse, unitOfWork);
        // DeleteQuestionEvent keeps the owning course id as its publisher id, exactly as before the
        // merge (events keyed on the course stream — layer 2.5).
        unitOfWorkService.registerEvent(new DeleteQuestionEvent(courseId), unitOfWork);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void updateQuestionTopics(Integer questionAggregateId, Set<QuestionTopic> topics, UnitOfWork unitOfWork) {
        Integer courseId = courseRepository.findCourseIdByQuestionId(questionAggregateId)
                .orElseThrow(() -> new SimulatorException(SimulatorErrorMessage.AGGREGATE_NOT_FOUND, questionAggregateId));
        Course oldCourse = (Course) unitOfWorkService.aggregateLoadAndRegisterRead(courseId, unitOfWork);
        Course newCourse = courseFactory.createCourseFromExisting(oldCourse);
        Question question = newCourse.getQuestion(questionAggregateId);
        question.setQuestionTopics(topics);
        unitOfWorkService.registerChanged(newCourse, unitOfWork);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public List<QuestionDto> findQuestionsByTopicIds(List<Integer> topicIds, UnitOfWork unitOfWork) {
        Set<Integer> courseIds = courseRepository.findCourseIdsByQuestionTopicIds(topicIds);
        List<QuestionDto> result = new ArrayList<>();
        for (Integer courseId : courseIds) {
            Course course = (Course) unitOfWorkService.aggregateLoadAndRegisterRead(courseId, unitOfWork);
            for (Question question : course.getQuestions()) {
                if (question.getState() != Aggregate.AggregateState.ACTIVE) {
                    continue;
                }
                boolean matches = question.getQuestionTopics().stream()
                        .anyMatch(qt -> topicIds.contains(qt.getTopicAggregateId()));
                if (matches) {
                    result.add(new QuestionDto(question));
                }
            }
        }
        return result;
    }

    /************************************************
     * EVENT PROCESSING (merged from the former Question aggregate)
     * The subscriber root is now the Course, so these operate over all of its Question members.
     ************************************************/

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void updateTopic(Integer courseAggregateId, Integer topicAggregateId, String topicName, Long aggregateVersion, UnitOfWork unitOfWork) {
        Course oldCourse = (Course) unitOfWorkService.aggregateLoadAndRegisterRead(courseAggregateId, unitOfWork);
        Course newCourse = courseFactory.createCourseFromExisting(oldCourse);
        for (Question question : newCourse.getQuestions()) {
            if (question.getState() != Aggregate.AggregateState.ACTIVE) {
                continue;
            }
            QuestionTopic questionTopic = question.findTopic(topicAggregateId);
            if (questionTopic != null) {
                questionTopic.setTopicName(topicName);
                questionTopic.setTopicVersion(aggregateVersion);
            }
        }
        unitOfWorkService.registerChanged(newCourse, unitOfWork);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void removeTopic(Integer courseAggregateId, Integer topicAggregateId, Long aggregateVersion, UnitOfWork unitOfWork) {
        Course oldCourse = (Course) unitOfWorkService.aggregateLoadAndRegisterRead(courseAggregateId, unitOfWork);
        Course newCourse = courseFactory.createCourseFromExisting(oldCourse);
        for (Question question : newCourse.getQuestions()) {
            if (question.getState() != Aggregate.AggregateState.ACTIVE) {
                continue;
            }
            QuestionTopic questionTopic = question.findTopic(topicAggregateId);
            if (questionTopic != null && questionTopic.getTopicVersion() < aggregateVersion) {
                questionTopic.setState(Aggregate.AggregateState.INACTIVE);
            }
        }
        unitOfWorkService.registerChanged(newCourse, unitOfWork);
    }
}
