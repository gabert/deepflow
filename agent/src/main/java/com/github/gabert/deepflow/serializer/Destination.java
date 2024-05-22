package com.github.gabert.deepflow.serializer;

public interface Destination {
    void send(String line, String threadName);
}
