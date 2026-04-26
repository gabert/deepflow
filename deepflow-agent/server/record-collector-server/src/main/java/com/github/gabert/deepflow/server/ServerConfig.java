package com.github.gabert.deepflow.server;

import com.github.gabert.deepflow.config.ConfigLoader;

import java.io.IOException;
import java.util.Map;

public class ServerConfig {
    private static final int DEFAULT_PORT = 8099;
    private static final int DEFAULT_MAX_CONTENT_LENGTH = 10 * 1024 * 1024;
    private static final String DEFAULT_KAFKA_BOOTSTRAP_SERVERS = "localhost:9092";
    private static final String DEFAULT_KAFKA_TOPIC = "deepflow-records";

    private final int serverPort;
    private final int maxContentLength;
    private final String kafkaBootstrapServers;
    private final String kafkaTopic;

    private ServerConfig(Map<String, String> configMap) {
        this.serverPort = Integer.parseInt(
                configMap.getOrDefault("server_port", String.valueOf(DEFAULT_PORT)));
        this.maxContentLength = Integer.parseInt(
                configMap.getOrDefault("max_content_length", String.valueOf(DEFAULT_MAX_CONTENT_LENGTH)));
        this.kafkaBootstrapServers = configMap.getOrDefault(
                "kafka_bootstrap_servers", DEFAULT_KAFKA_BOOTSTRAP_SERVERS);
        this.kafkaTopic = configMap.getOrDefault(
                "kafka_topic", DEFAULT_KAFKA_TOPIC);
    }

    public int getServerPort() {
        return serverPort;
    }

    public int getMaxContentLength() {
        return maxContentLength;
    }

    public String getKafkaBootstrapServers() {
        return kafkaBootstrapServers;
    }

    public String getKafkaTopic() {
        return kafkaTopic;
    }

    public static ServerConfig load(String[] args) throws IOException {
        Map<String, String> argMap = ConfigLoader.parseCliArgs(args);
        return new ServerConfig(ConfigLoader.mergeWithFile(argMap));
    }
}
