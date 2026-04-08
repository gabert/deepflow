package com.github.gabert.deepflow.server;

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
import java.util.Collections;
import java.util.Properties;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class KafkaIntegrationTest {

    private static final String TOPIC = "deepflow-records";

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
    void payloadArrivesInKafkaBytePerfect() throws Exception {
        byte[] payload = new byte[32];
        new Random().nextBytes(payload);

        try (KafkaConsumer<String, byte[]> consumer = createConsumer()) {
            int httpStatus = postRecords(payload);
            assertEquals(200, httpStatus, "POST /records should return 200");

            byte[] received = pollOneMessage(consumer);
            assertNotNull(received, "Should receive a message from Kafka");
            assertArrayEquals(payload, received, "Kafka message should match sent payload byte-for-byte");
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
}
