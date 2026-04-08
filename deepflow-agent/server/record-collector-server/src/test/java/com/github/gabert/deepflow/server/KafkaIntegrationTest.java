package com.github.gabert.deepflow.server;

import com.github.gabert.deepflow.codec.Codec;
import com.github.gabert.deepflow.recorder.record.RecordWriter;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class KafkaIntegrationTest {

    private static final String TOPIC = "deepflow-records";
    private static final String SIGNATURE = "com.example::TestClass.testMethod() -> void [public]";
    private static final String THREAD = "test-thread";

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    private static RecordCollectorServer server;

    @BeforeAll
    static void startServer() throws Exception {
        ServerConfig config = ServerConfig.load(new String[]{
                "server_port=0",
                "kafka_bootstrap_servers=" + kafka.getBootstrapServers(),
                "kafka_topic=" + TOPIC
        });
        server = new RecordCollectorServer(config);
        server.start();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void simpleRecordArrivesInKafka() throws Exception {
        byte[] entry = RecordWriter.logEntrySimple(
                "test-session-001", SIGNATURE, THREAD,
                System.currentTimeMillis(), 42, 0);
        byte[] exit = RecordWriter.logExitSimple(
                "test-session-001", THREAD,
                System.currentTimeMillis());
        byte[] payload = concat(entry, exit);

        try (KafkaConsumer<String, byte[]> consumer = createConsumer()) {
            int httpStatus = postRecords(payload);
            assertEquals(200, httpStatus, "POST /records should return 200");

            byte[] received = pollOneMessage(consumer);
            assertNotNull(received, "Should receive a message from Kafka");
            assertArrayEquals(payload, received, "Kafka message should match sent payload byte-for-byte");
        }
    }

    @Test
    void fullRecordWithArgsArrivesInKafka() throws Exception {
        byte[] args = Codec.encode(new Object[]{"hello", 123});
        byte[] ret = Codec.encode("result");
        byte[] entry = RecordWriter.logEntry(
                "test-session-002", SIGNATURE, THREAD,
                System.currentTimeMillis(), 10, 0, null, args);
        byte[] exit = RecordWriter.logExit(
                "test-session-002", THREAD,
                System.currentTimeMillis(), ret, false);
        byte[] payload = concat(entry, exit);

        try (KafkaConsumer<String, byte[]> consumer = createConsumer()) {
            int httpStatus = postRecords(payload);
            assertEquals(200, httpStatus);

            byte[] received = pollOneMessage(consumer);
            assertNotNull(received);
            assertArrayEquals(payload, received);
        }
    }

    private int postRecords(byte[] payload) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + server.getPort() + "/records"))
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return response.statusCode();
    }

    private KafkaConsumer<String, byte[]> createConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + System.nanoTime());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());

        KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(props);
        consumer.subscribe(Collections.singletonList(TOPIC));
        // Force partition assignment before sending data
        consumer.poll(Duration.ofSeconds(5));
        return consumer;
    }

    private byte[] pollOneMessage(KafkaConsumer<String, byte[]> consumer) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, byte[]> records = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, byte[]> record : records) {
                return record.value();
            }
        }
        return null;
    }

    private static byte[] concat(byte[]... arrays) {
        int totalLength = 0;
        for (byte[] array : arrays) {
            totalLength += array.length;
        }
        byte[] result = new byte[totalLength];
        int pos = 0;
        for (byte[] array : arrays) {
            System.arraycopy(array, 0, result, pos, array.length);
            pos += array.length;
        }
        return result;
    }
}
