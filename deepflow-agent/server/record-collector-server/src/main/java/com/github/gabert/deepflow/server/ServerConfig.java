package com.github.gabert.deepflow.server;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class ServerConfig {
    private static final int DEFAULT_PORT = 8099;
    private static final int DEFAULT_MAX_CONTENT_LENGTH = 10 * 1024 * 1024;

    private final int serverPort;
    private final int maxContentLength;

    private ServerConfig(Map<String, String> configMap) {
        this.serverPort = Integer.parseInt(
                configMap.getOrDefault("server_port", String.valueOf(DEFAULT_PORT)));
        this.maxContentLength = Integer.parseInt(
                configMap.getOrDefault("max_content_length", String.valueOf(DEFAULT_MAX_CONTENT_LENGTH)));
    }

    public int getServerPort() {
        return serverPort;
    }

    public int getMaxContentLength() {
        return maxContentLength;
    }

    public static ServerConfig load(String[] args) throws IOException {
        Map<String, String> configMap = new HashMap<>();

        for (String arg : args) {
            if (arg.contains("=")) {
                String[] parts = arg.split("=", 2);
                configMap.put(parts[0].trim(), parts[1].trim());
            }
        }

        if (configMap.containsKey("config")) {
            Map<String, String> fileConfig = loadFromFile(configMap.get("config"));
            fileConfig.putAll(configMap);
            configMap = fileConfig;
        }

        return new ServerConfig(configMap);
    }

    private static Map<String, String> loadFromFile(String filePath) throws IOException {
        Map<String, String> configMap = new HashMap<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("#") || !line.contains("=")) {
                    continue;
                }
                String[] parts = line.split("=", 2);
                configMap.put(parts[0].trim(), parts[1].trim());
            }
        }

        return configMap;
    }
}
