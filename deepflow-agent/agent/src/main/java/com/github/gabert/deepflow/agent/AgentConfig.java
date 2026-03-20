package com.github.gabert.deepflow.agent;

import java.io.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;


public class AgentConfig {
    private final List<String> matchersInclude = new ArrayList<>();
    private final List<String> matchersExclude = new ArrayList<>();

    private final String sessionDumpLocation;
    private final String sessionId;
    private final boolean expandThis;
    private final String destination;

    private AgentConfig(Map<String, String> configMap) {
        String matcherInclude = configMap.getOrDefault("matchers_include", "");
        if (!matcherInclude.isEmpty()) {
            this.matchersInclude.addAll(Arrays.stream(matcherInclude.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList());
        }

        String matcherExclude = configMap.getOrDefault("matchers_exclude", "");
        if (!matcherExclude.isEmpty()) {
            this.matchersExclude.addAll(Arrays.stream(matcherExclude.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList());
        }

        this.sessionDumpLocation = configMap.get("session_dump_location");
        this.sessionId = generateSessionId();
        this.expandThis = Boolean.parseBoolean(configMap.getOrDefault("expand_this", "false"));
        this.destination = configMap.getOrDefault("destination", "zip");
    }

    public List<String> getMatchersInclude() {
        return matchersInclude;
    }

    public List<String> getMatchersExclude() {
        return matchersExclude;
    }

    public String getSessionDumpLocation() {
        return sessionDumpLocation;
    }

    public String getSessionId() {
        return sessionId;
    }

    public boolean isExpandThis() {
        return expandThis;
    }

    public String getDestination() {
        return destination;
    }

    public static AgentConfig getInstance(String agentArgs) throws IOException {
        Map<String, String> configMapParams = loadFromString(agentArgs);

        Map<String, String> configMapFile = configMapParams.containsKey("config")
                ? loadFromConfigFile(configMapParams.get("config"))
                : new HashMap<>();


        Map<String, String> configMap = new HashMap<>();

        configMap.putAll(configMapFile);
        configMap.putAll(configMapParams);

        return new AgentConfig(configMap);
    }

    private static Map<String, String> loadFromString(String agentArgs) {
        Map<String, String> configMapParams = new HashMap<>();

        if (agentArgs == null) {
            return configMapParams;
        }

        for (String arg : agentArgs.split("&")) {
            String[] keyValue = arg.split("=");
            if (keyValue.length == 2) {
                String key = keyValue[0].trim();
                String value = keyValue[1].trim();
                configMapParams.put(key, value);
            }
        }

        return configMapParams;
    }

    private static String generateSessionId() {
        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
        return now.format(formatter);
    }

    private static Map<String, String> loadFromConfigFile(String filePath) throws IOException {
        Map<String, String> configMapFile = new HashMap<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;

            while ((line = reader.readLine()) != null) {
                line = line.trim();

                if (line.startsWith("#")) {
                    continue;
                }

                if (line.contains("=")) {
                    String[] parts = line.split("=", 2);
                    configMapFile.put(parts[0].trim(), parts[1].trim());
                }
            }
        }

        return configMapFile;
    }
}
