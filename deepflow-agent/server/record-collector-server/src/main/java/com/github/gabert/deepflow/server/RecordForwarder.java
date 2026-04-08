package com.github.gabert.deepflow.server;

public interface RecordForwarder extends AutoCloseable {
    void send(byte[] rawRecords);

    @Override
    void close();
}
