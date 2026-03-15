package com.github.gabert.deepflow.recorder;

import java.io.IOException;

public interface Destination {
    void send(String line, String threadName) throws IOException;
}
