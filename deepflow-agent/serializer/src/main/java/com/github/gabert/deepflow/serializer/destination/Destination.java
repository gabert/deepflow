package com.github.gabert.deepflow.serializer.destination;

import java.io.IOException;

public interface Destination {
    void send(String line, String threadName) throws IOException;
}
