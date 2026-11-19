package org.anonymous.msrefactor.quizzes.microservices.course.coordination.webapi;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.anonymous.msrefactor.quizzes.microservices.course.aggregate.TopicDto;
import org.anonymous.msrefactor.quizzes.microservices.course.coordination.functionalities.CourseFunctionalities;

import java.util.List;

@RestController
public class CourseController {

    @Autowired
    private CourseFunctionalities courseFunctionalities;

    // Routes absorbed from TopicController (merge course <- topic); paths unchanged.
    @GetMapping("/courses/{courseAggregateId}/topics")
    public List<TopicDto> findTopicsByCourseAggregateId(@PathVariable Integer courseAggregateId) {
        return courseFunctionalities.findTopicsByCourseAggregateId(courseAggregateId);
    }

    @PostMapping("/courses/{courseAggregateId}/create")
    public TopicDto createTopic(@PathVariable Integer courseAggregateId, @RequestBody TopicDto topicDto) throws Exception {
        return courseFunctionalities.createTopic(courseAggregateId, topicDto);
    }

    @PostMapping("/topics/update")
    public void updateTopic(@RequestBody TopicDto topicDto) throws Exception {
        courseFunctionalities.updateTopic(topicDto);
    }

    @PostMapping("/topics/{topicAggregateId}/delete")
    public void deleteTopic(@PathVariable Integer topicAggregateId) throws Exception {
        courseFunctionalities.deleteTopic(topicAggregateId);
    }
}
