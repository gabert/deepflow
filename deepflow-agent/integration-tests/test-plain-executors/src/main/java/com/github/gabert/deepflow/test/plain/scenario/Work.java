package com.github.gabert.deepflow.test.plain.scenario;

public class Work {

    public String doWork(String label) {
        return "done:" + label;
    }

    public String compute(String input) {
        return "computed:" + input;
    }
}
