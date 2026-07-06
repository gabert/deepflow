package com.github.gabert.arachna.trace.processor;

import com.github.gabert.arachna.trace.codec.AgentRun;
import com.github.gabert.arachna.trace.recorder.record.RecordReader;
import com.github.gabert.arachna.trace.recorder.record.TraceRecord;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public class KafkaRecordConsumer implements AutoCloseable {
    private final KafkaConsumer<String, byte[]> consumer;
    private final RecordSink sink;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public KafkaRecordConsumer(ProcessorConfig config, RecordSink sink) {
        this.sink = sink;

        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, config.getKafkaBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, config.getKafkaGroupId());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");

        this.consumer = new KafkaConsumer<>(props);
        consumer.subscribe(List.of(config.getKafkaTopic()));
    }

    public void pollLoop() {
        try {
            while (running.get()) {
                ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, byte[]> record : records) {
                    processRecord(record);
                }
            }
        } catch (WakeupException e) {
            if (running.get()) {
                throw e;
            }
        }
    }

    public void shutdown() {
        running.set(false);
        consumer.wakeup();
    }

    @Override
    public void close() {
        consumer.close();
        sink.close();
    }

    private void processRecord(ConsumerRecord<String, byte[]> record) {
        try {
            AgentRun agentRun = extractAgentRun(record.headers());
            if (agentRun == null) {
                // Agent-run identity travels at the transport layer (Kafka
                // headers). A batch without it is malformed — almost certainly
                // a misconfigured producer. We cannot attribute the calls, so
                // drop the batch and surface the error operationally.
                System.err.println("[ArachnaTrace] Dropping batch: missing agent-run headers (expected "
                        + AgentRun.Headers.AGENT_RUN_ID + " on Kafka record headers)");
                return;
            }
            List<TraceRecord> records = RecordReader.readAll(record.value());
            sink.accept(records, agentRun);
        } catch (Exception e) {
            System.err.println("[ArachnaTrace] Failed to process record batch: " + e.getMessage());
        }
    }

    /**
     * Build an {@link AgentRun} from Kafka record headers, or {@code null}
     * if the {@code agent_run_id} header is absent. {@code agent_run_id} is the
     * required marker — without it we cannot attribute the batch.
     */
    static AgentRun extractAgentRun(Headers headers) {
        String runIdString = headerString(headers, AgentRun.Headers.AGENT_RUN_ID);
        if (runIdString == null) return null;

        UUID runId;
        try {
            runId = UUID.fromString(runIdString);
        } catch (IllegalArgumentException e) {
            return null;
        }

        String hostname     = headerString(headers, AgentRun.Headers.HOSTNAME);
        String agentVersion = headerString(headers, AgentRun.Headers.AGENT_VERSION);
        String codeVersion  = headerString(headers, AgentRun.Headers.CODE_VERSION);
        String env          = headerString(headers, AgentRun.Headers.ENV);
        long processPid     = headerLong(headers, AgentRun.Headers.PROCESS_PID);
        long startedAtMs    = headerLong(headers, AgentRun.Headers.STARTED_AT_MS);

        return new AgentRun(runId, hostname, agentVersion, codeVersion, env, processPid, startedAtMs);
    }

    private static String headerString(Headers headers, String name) {
        Header h = headers.lastHeader(name);
        return h != null ? new String(h.value(), StandardCharsets.UTF_8) : null;
    }

    private static long headerLong(Headers headers, String name) {
        String v = headerString(headers, name);
        if (v == null) return 0L;
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
