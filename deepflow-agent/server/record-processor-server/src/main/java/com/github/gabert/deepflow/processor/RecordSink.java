package com.github.gabert.deepflow.processor;

import com.github.gabert.deepflow.recorder.destination.RecordRenderer;

public interface RecordSink extends AutoCloseable {
    void accept(RecordRenderer.Result result);

    @Override
    void close();
}
