package com.github.gabert.deepflow.recorder.destination;

import java.io.Closeable;
import java.io.IOException;

public interface Destination extends Closeable {
    void accept(byte[] record);
    void flush() throws IOException;
}
