package com.github.gabert.deepflow.processor;

import com.github.gabert.deepflow.recorder.destination.RecordRenderer;

public class LoggingSink implements RecordSink {

    @Override
    public void accept(RecordRenderer.Result result) {
        for (String line : result.lines()) {
            System.out.println(line);
        }
    }

    @Override
    public void close() {}
}
