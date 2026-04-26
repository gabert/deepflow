package com.github.gabert.deepflow.test.webflux.handler;

import com.github.gabert.deepflow.test.webflux.service.ReactiveWorkService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

@RestController
public class TestHandler {
    private final ReactiveWorkService service;

    public TestHandler(ReactiveWorkService service) {
        this.service = service;
    }

    /**
     * Synchronous handler — everything runs on the Netty event loop thread.
     * Service call is nested within the handler, both share the same RI.
     */
    @GetMapping("/mono")
    public Mono<Map<String, String>> mono() {
        String result = service.blockingQuery("mono");
        return Mono.just(Map.of("result", result));
    }

    /**
     * Mono.fromCallable on boundedElastic — the callable runs on an elastic
     * thread pool (ScheduledThreadPoolExecutor). Request ID propagation depends
     * on whether ExecutorAdvice intercepts the scheduler's task submission.
     */
    @GetMapping("/blocking")
    public Mono<Map<String, String>> blocking() {
        return Mono.fromCallable(() -> service.blockingQuery("elastic"))
                .subscribeOn(Schedulers.boundedElastic())
                .map(r -> Map.of("result", r));
    }

    /**
     * Flux with publishOn — elements are processed on parallel scheduler threads.
     * Each transform() call should ideally share the handler's RI.
     */
    @GetMapping("/flux")
    public Flux<Map<String, String>> flux() {
        return Flux.range(1, 3)
                .publishOn(Schedulers.parallel())
                .map(i -> Map.of("value", service.transform(i)));
    }

    /**
     * Reactive chain — two blocking calls on boundedElastic via flatMap.
     * Tests whether RI propagates through chained reactive operators.
     */
    @GetMapping("/chain")
    public Mono<Map<String, String>> chain() {
        return Mono.fromCallable(() -> service.blockingQuery("chain-1"))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(r -> Mono.fromCallable(() -> service.blockingQuery("chain-2"))
                        .subscribeOn(Schedulers.boundedElastic()))
                .map(r -> Map.of("result", r));
    }
}
