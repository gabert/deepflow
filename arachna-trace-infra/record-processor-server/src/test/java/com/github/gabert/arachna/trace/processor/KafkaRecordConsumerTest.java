package com.github.gabert.arachna.trace.processor;

import com.github.gabert.arachna.trace.codec.AgentRun;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Direct tests for {@link KafkaRecordConsumer#extractAgentRun(Headers)} — the
 * pure-function part of the consumer. The Kafka poll loop itself rides the
 * collector's integration test; here we pin the header → metadata mapping
 * including the must-be-null branches that signal "drop this batch".
 */
class KafkaRecordConsumerTest {

    private static final UUID RUN_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void allHeadersProducePopulatedMetadata() {
        Headers headers = new RecordHeaders()
                .add(AgentRun.Headers.AGENT_RUN_ID,    RUN_ID.toString().getBytes(StandardCharsets.UTF_8))
                .add(AgentRun.Headers.HOSTNAME,        "host-a".getBytes(StandardCharsets.UTF_8))
                .add(AgentRun.Headers.AGENT_VERSION,   "0.0.3".getBytes(StandardCharsets.UTF_8))
                .add(AgentRun.Headers.CODE_VERSION,    "rev-abc".getBytes(StandardCharsets.UTF_8))
                .add(AgentRun.Headers.ENV,             "staging".getBytes(StandardCharsets.UTF_8))
                .add(AgentRun.Headers.PROCESS_PID,     "12345".getBytes(StandardCharsets.UTF_8))
                .add(AgentRun.Headers.STARTED_AT_MS,   "1700000000000".getBytes(StandardCharsets.UTF_8));

        AgentRun m = KafkaRecordConsumer.extractAgentRun(headers);
        assertNotNull(m);
        assertEquals(RUN_ID, m.agentRunId());
        assertEquals("host-a", m.hostname());
        assertEquals("0.0.3", m.agentVersion());
        assertEquals("rev-abc", m.codeVersion());
        assertEquals("staging", m.env());
        assertEquals(12345L, m.processPid());
        assertEquals(1700000000000L, m.startedAtMillis());
    }

    @Test
    void missingAgentRunIdReturnsNull() {
        // KafkaRecordConsumer uses null as the "drop this batch" signal. The
        // contract: no run id, no metadata, no insert.
        Headers headers = new RecordHeaders()
                .add(AgentRun.Headers.HOSTNAME, "host-a".getBytes(StandardCharsets.UTF_8));

        assertNull(KafkaRecordConsumer.extractAgentRun(headers));
    }

    @Test
    void malformedAgentRunIdReturnsNull() {
        // Same "drop this batch" contract — a non-UUID value must not be
        // silently coerced; treat as missing.
        Headers headers = new RecordHeaders()
                .add(AgentRun.Headers.AGENT_RUN_ID, "not-a-uuid".getBytes(StandardCharsets.UTF_8));

        assertNull(KafkaRecordConsumer.extractAgentRun(headers));
    }

    @Test
    void missingNumericHeadersDefaultToZero() {
        // process_pid / started_at are required by ClickHouse columns; absent or
        // malformed values default to 0 rather than throwing, so a partially-
        // populated header set still flows through.
        Headers headers = new RecordHeaders()
                .add(AgentRun.Headers.AGENT_RUN_ID, RUN_ID.toString().getBytes(StandardCharsets.UTF_8));

        AgentRun m = KafkaRecordConsumer.extractAgentRun(headers);
        assertNotNull(m);
        assertEquals(0L, m.processPid());
        assertEquals(0L, m.startedAtMillis());
    }

    @Test
    void malformedNumericHeadersDefaultToZero() {
        Headers headers = new RecordHeaders()
                .add(AgentRun.Headers.AGENT_RUN_ID,  RUN_ID.toString().getBytes(StandardCharsets.UTF_8))
                .add(AgentRun.Headers.PROCESS_PID,   "not-a-number".getBytes(StandardCharsets.UTF_8))
                .add(AgentRun.Headers.STARTED_AT_MS, "also-bad".getBytes(StandardCharsets.UTF_8));

        AgentRun m = KafkaRecordConsumer.extractAgentRun(headers);
        assertEquals(0L, m.processPid());
        assertEquals(0L, m.startedAtMillis());
    }
}
