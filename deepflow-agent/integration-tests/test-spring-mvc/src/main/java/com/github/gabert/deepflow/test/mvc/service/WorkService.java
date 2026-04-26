package com.github.gabert.deepflow.test.mvc.service;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

@Service
public class WorkService {

    public String syncWork(String input) {
        return "sync:" + input;
    }

    @Async("asyncExecutor")
    public void fireAndForget(String input) {
        doHeavyWork(input);
    }

    @Async("asyncExecutor")
    public CompletableFuture<String> asyncWithResult(String input) {
        String result = doHeavyWork(input);
        return CompletableFuture.completedFuture(result);
    }

    public String doHeavyWork(String input) {
        return "heavy:" + input;
    }
}
