package com.github.gabert.deepflow.test.plain;

import com.github.gabert.deepflow.test.plain.scenario.Scenarios;

/**
 * Runner — NOT in the instrumented package, so it does not get a request ID.
 * Each Scenarios method enters at depth 0 and becomes its own root call.
 */
public class Main {
    public static void main(String[] args) throws Exception {
        Scenarios s = new Scenarios();

        // Scenarios 1-6: each is an independent root call
        s.threadPoolExecute();
        s.executorServiceSubmit();
        s.completableFutureSupply();
        s.completableFutureChain();
        s.forkJoinSubmit();
        s.nestedExecutors();

        // Scenario 7: sequential roots on the same thread
        s.sequentialRoot1();
        s.sequentialRoot2();

        // Scenario 8: parallel independent roots on plain Thread (not pooled)
        Thread t1 = new Thread(() -> s.independentRoot1(), "indie-thread-1");
        Thread t2 = new Thread(() -> s.independentRoot2(), "indie-thread-2");
        t1.start();
        t2.start();
        t1.join();
        t2.join();

        // Give the agent drainer time to flush
        Thread.sleep(2000);
    }
}
