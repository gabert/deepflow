package com.github.gabert.deepflow.processor;

import com.github.gabert.deepflow.config.ConfigLoader;

import java.io.IOException;
import java.util.Map;

public class ProcessorConfig {
    private static final String DEFAULT_KAFKA_BOOTSTRAP_SERVERS = "localhost:9092";
    private static final String DEFAULT_KAFKA_TOPIC = "deepflow-records";
    private static final String DEFAULT_KAFKA_GROUP_ID = "deepflow-processor";
    private static final String DEFAULT_CLICKHOUSE_URL = "http://localhost:8123";
    private static final String DEFAULT_CLICKHOUSE_DATABASE = "deepflow";
    private static final String DEFAULT_CLICKHOUSE_USER = "deepflow";
    private static final String DEFAULT_CLICKHOUSE_PASSWORD = "deepflow";

    private final String kafkaBootstrapServers;
    private final String kafkaTopic;
    private final String kafkaGroupId;
    private final String clickhouseUrl;
    private final String clickhouseDatabase;
    private final String clickhouseUser;
    private final String clickhousePassword;

    private ProcessorConfig(Map<String, String> configMap) {
        this.kafkaBootstrapServers = configMap.getOrDefault(
                "kafka_bootstrap_servers", DEFAULT_KAFKA_BOOTSTRAP_SERVERS);
        this.kafkaTopic = configMap.getOrDefault(
                "kafka_topic", DEFAULT_KAFKA_TOPIC);
        this.kafkaGroupId = configMap.getOrDefault(
                "kafka_group_id", DEFAULT_KAFKA_GROUP_ID);
        this.clickhouseUrl = configMap.getOrDefault(
                "clickhouse_url", DEFAULT_CLICKHOUSE_URL);
        this.clickhouseDatabase = configMap.getOrDefault(
                "clickhouse_database", DEFAULT_CLICKHOUSE_DATABASE);
        this.clickhouseUser = configMap.getOrDefault(
                "clickhouse_user", DEFAULT_CLICKHOUSE_USER);
        this.clickhousePassword = configMap.getOrDefault(
                "clickhouse_password", DEFAULT_CLICKHOUSE_PASSWORD);
    }

    public String getKafkaBootstrapServers() {
        return kafkaBootstrapServers;
    }

    public String getKafkaTopic() {
        return kafkaTopic;
    }

    public String getKafkaGroupId() {
        return kafkaGroupId;
    }

    public String getClickhouseUrl() {
        return clickhouseUrl;
    }

    public String getClickhouseDatabase() {
        return clickhouseDatabase;
    }

    public String getClickhouseUser() {
        return clickhouseUser;
    }

    public String getClickhousePassword() {
        return clickhousePassword;
    }

    public static ProcessorConfig load(String[] args) throws IOException {
        Map<String, String> argMap = ConfigLoader.parseCliArgs(args);
        return new ProcessorConfig(ConfigLoader.mergeWithFile(argMap));
    }
}
