package org.anonymous.msrefactor.quizzes.microservices.quiz.coordination.webapi;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.anonymous.msrefactor.quizzes.microservices.quiz.aggregate.QuestionAnswerDto;
import org.anonymous.msrefactor.quizzes.microservices.quiz.aggregate.QuizDto;
import org.anonymous.msrefactor.quizzes.microservices.quiz.coordination.functionalities.QuizFunctionalities;

@RestController
public class QuizController {
    @Autowired
    private QuizFunctionalities quizFunctionalities;

    @PostMapping("/executions/{courseExecutionId}")
    public QuizDto createQuiz(@PathVariable Integer courseExecutionId, @RequestBody QuizDto quizDto) throws Exception {
        return quizFunctionalities.createQuiz(courseExecutionId, quizDto);
    }
    @PostMapping("/quizzes/update")
    public QuizDto updateQuiz(@RequestBody QuizDto quizDto) throws Exception {
        return quizFunctionalities.updateQuiz(quizDto);
    }

    @GetMapping("/quizzes/{quizAggregateId}")
    public QuizDto findQuiz(@PathVariable Integer quizAggregateId) {
        return quizFunctionalities.findQuiz(quizAggregateId);
    }

    // moved from QuizAnswerController (merge quiz <- answer); the path is unchanged
    @PostMapping("/quizzes/{quizAggregateId}/answer")
    public void answerQuestion(@PathVariable Integer quizAggregateId, @RequestParam Integer userAggregateId, @RequestBody QuestionAnswerDto questionAnswerDto) throws Exception {
        quizFunctionalities.answerQuestion(quizAggregateId, userAggregateId, questionAnswerDto);
    }
}
