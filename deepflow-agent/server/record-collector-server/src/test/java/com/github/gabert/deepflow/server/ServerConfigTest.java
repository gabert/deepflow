package com.github.gabert.deepflow.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ServerConfigTest {

    // --- Defaults ---

    @Test
    void defaultValues() throws IOException {
        ServerConfig config = ServerConfig.load(new String[]{});

        assertEquals(8099, config.getServerPort());
        assertEquals(10 * 1024 * 1024, config.getMaxContentLength());
        assertEquals("localhost:9092", config.getKafkaBootstrapServers());
        assertEquals("deepflow-records", config.getKafkaTopic());
    }

    // --- CLI overrides ---

    @Test
    void cliOverridesDefaults() throws IOException {
        ServerConfig config = ServerConfig.load(new String[]{
                "server_port=9090",
                "max_content_length=5242880",
                "kafka_bootstrap_servers=broker1:9092,broker2:9092",
                "kafka_topic=my-topic"
        });

        assertEquals(9090, config.getServerPort());
        assertEquals(5242880, config.getMaxContentLength());
        assertEquals("broker1:9092,broker2:9092", config.getKafkaBootstrapServers());
        assertEquals("my-topic", config.getKafkaTopic());
    }

    // --- Config file ---

    @Test
    void loadFromConfigFile(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("server.cfg");
        Files.writeString(configFile, """
                # Server config
                server_port=7070
                max_content_length=2097152
                """);

        ServerConfig config = ServerConfig.load(new String[]{
                "config=" + configFile.toAbsolutePath()
        });

        assertEquals(7070, config.getServerPort());
        assertEquals(2097152, config.getMaxContentLength());
    }

    // --- CLI overrides config file ---

    @Test
    void cliOverridesConfigFile(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("server.cfg");
        Files.writeString(configFile, "server_port=7070\n");

        ServerConfig config = ServerConfig.load(new String[]{
                "config=" + configFile.toAbsolutePath(),
                "server_port=9999"
        });

        assertEquals(9999, config.getServerPort());
    }
}
