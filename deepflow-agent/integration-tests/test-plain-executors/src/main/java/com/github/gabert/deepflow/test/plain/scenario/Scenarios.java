package com.github.gabert.deepflow.test.plain.scenario;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class Scenarios {
    private final Work work = new Work();

    // --- Scenario 1: ThreadPoolExecutor.execute(Runnable) ---

    public void threadPoolExecute() throws Exception {
        work.doWork("root");
        ExecutorService exec = Executors.newSingleThreadExecutor();
        CountDownLatch latch = new CountDownLatch(1);
        exec.execute(() -> {
            work.doWork("in-pool");
            latch.countDown();
        });
        latch.await(5, TimeUnit.SECONDS);
        exec.shutdown();
    }

    // --- Scenario 2: ExecutorService.submit(Callable) ---

    public void executorServiceSubmit() throws Exception {
        work.doWork("root");
        ExecutorService exec = Executors.newSingleThreadExecutor();
        Future<String> future = exec.submit(() -> work.compute("in-callable"));
        future.get(5, TimeUnit.SECONDS);
        exec.shutdown();
    }

    // --- Scenario 3: CompletableFuture.supplyAsync ---

    public void completableFutureSupply() throws Exception {
        work.doWork("root");
        CompletableFuture.supplyAsync(() -> work.compute("in-supply"))
                .get(5, TimeUnit.SECONDS);
    }

    // --- Scenario 4: CompletableFuture chain ---

    public void completableFutureChain() throws Exception {
        work.doWork("root");
        CompletableFuture
                .supplyAsync(() -> work.compute("stage1"))
                .thenApplyAsync(s -> work.compute("stage2"))
                .thenApplyAsync(s -> work.compute("stage3"))
                .get(5, TimeUnit.SECONDS);
    }

    // --- Scenario 5: ForkJoinPool.submit(Callable) ---

    public void forkJoinSubmit() throws Exception {
        work.doWork("root");
        ForkJoinPool pool = new ForkJoinPool(2);
        Future<String> f = pool.submit(() -> work.compute("in-forkjoin"));
        f.get(5, TimeUnit.SECONDS);
        pool.shutdown();
    }

    // --- Scenario 6: Nested executor submission (outer → inner) ---

    public void nestedExecutors() throws Exception {
        work.doWork("root");
        ExecutorService outer = Executors.newSingleThreadExecutor();
        ExecutorService inner = Executors.newSingleThreadExecutor();
        CountDownLatch done = new CountDownLatch(1);

        outer.execute(() -> {
            work.doWork("outer-pool");
            CountDownLatch innerDone = new CountDownLatch(1);
            inner.execute(() -> {
                work.doWork("inner-pool");
                innerDone.countDown();
            });
            try {
                innerDone.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            done.countDown();
        });

        done.await(10, TimeUnit.SECONDS);
        outer.shutdown();
        inner.shutdown();
    }

    // --- Scenario 7: Sequential roots (same thread, different request IDs) ---

    public void sequentialRoot1() {
        work.doWork("seq-1a");
        work.doWork("seq-1b");
    }

    public void sequentialRoot2() {
        work.doWork("seq-2a");
        work.doWork("seq-2b");
    }

    // --- Scenario 8: Parallel independent roots (plain Thread, not pooled) ---

    public void independentRoot1() {
        work.doWork("independent-1");
    }

    public void independentRoot2() {
        work.doWork("independent-2");
    }
}
