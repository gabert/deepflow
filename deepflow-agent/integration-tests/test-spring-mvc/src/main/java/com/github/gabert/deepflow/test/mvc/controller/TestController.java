package com.github.gabert.deepflow.test.mvc.controller;

import com.github.gabert.deepflow.test.mvc.service.WorkService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
public class TestController {
    private final WorkService workService;

    public TestController(WorkService workService) {
        this.workService = workService;
    }

    @GetMapping("/sync")
    public Map<String, String> sync() {
        String result = workService.syncWork("hello");
        return Map.of("result", result);
    }

    @PostMapping("/async-fire-and-forget")
    public Map<String, String> asyncFireAndForget() {
        workService.fireAndForget("task1");
        return Map.of("status", "submitted");
    }

    @PostMapping("/async-with-result")
    public Map<String, String> asyncWithResult() throws Exception {
        CompletableFuture<String> future = workService.asyncWithResult("task2");
        String result = future.get();
        return Map.of("result", result);
    }

    @PostMapping("/fan-out")
    public Map<String, String> fanOut() throws Exception {
        CompletableFuture<String> f1 = workService.asyncWithResult("fan1");
        CompletableFuture<String> f2 = workService.asyncWithResult("fan2");
        CompletableFuture<String> f3 = workService.asyncWithResult("fan3");
        CompletableFuture.allOf(f1, f2, f3).get();
        return Map.of("r1", f1.get(), "r2", f2.get(), "r3", f3.get());
    }
}
