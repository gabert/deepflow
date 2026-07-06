package com.github.gabert.arachna.trace.processor;

import com.github.gabert.arachna.trace.codec.AgentRun;
import com.github.gabert.arachna.trace.recorder.record.TraceRecord;

import java.util.List;

public interface RecordSink extends AutoCloseable {
    /**
     * Process one Kafka batch, already decoded to typed records.
     *
     * @param records   the batch's trace records, in wire order
     * @param agentRun  agent-run identity carried on the Kafka message
     *                  headers — never {@code null}. Batches without the
     *                  required headers are rejected by
     *                  {@link KafkaRecordConsumer} before any sink is called.
     */
    void accept(List<TraceRecord> records, AgentRun agentRun);

    @Override
    void close();
}
