package org.anonymous.msrefactor.quizzes.microservices.course.coordination.webapi;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.QuestionDto;
import org.anonymous.msrefactor.quizzes.microservices.course.coordination.functionalities.CourseFunctionalities;

import java.util.List;

@RestController
public class CourseController {
    @Autowired
    private CourseFunctionalities courseFunctionalities;

    @GetMapping("/questions/{aggregateId}")
    public QuestionDto findQuestionByAggregateId(@PathVariable Integer aggregateId) {
        return courseFunctionalities.findQuestionByAggregateId(aggregateId);
    }

    @GetMapping("/courses/{courseAggregateId}/questions")
    public List<QuestionDto> findQuestionsByCourseAggregateId(@PathVariable Integer courseAggregateId) {
        return courseFunctionalities.findQuestionsByCourseAggregateId(courseAggregateId);
    }

    @PostMapping("/courses/{courseAggregateId}/questions/create")
    public QuestionDto createQuestion(@PathVariable Integer courseAggregateId, @RequestBody QuestionDto questionDto) throws Exception {
        return courseFunctionalities.createQuestion(courseAggregateId, questionDto);
    }

    @PostMapping("/questions/update")
    public void updateQuestion(@RequestBody QuestionDto questionDto) throws Exception {
        courseFunctionalities.updateQuestion(questionDto);
    }

    @PostMapping("/questions/{questionAggregateId}")
    public void removeQuestion(@PathVariable Integer questionAggregateId) throws Exception {
        courseFunctionalities.removeQuestion(questionAggregateId);
    }

    @PostMapping("/questions/{questionAggregateId}/updateTopics")
    public void updateQuestionTopics(@PathVariable Integer courseAggregateId, @RequestParam List<Integer> topicIds) throws Exception {
        courseFunctionalities.updateQuestionTopics(courseAggregateId, topicIds);
    }
}
