package com.github.gabert.deepflow.agent;

import java.io.*;
import java.util.*;


public class AgentConfig {
    private final String dumpLocation;
    private final List<String> matchersInclude = new ArrayList<>();
    private final List<String> matchersExclude = new ArrayList<>();
    private final String triggerOn;

    private AgentConfig(Map<String, String> configMap) {
        this.dumpLocation = configMap.get("session_dump_location");

        String matcherInclude = configMap.getOrDefault("matchers_include", "com.,org.");
        this.matchersInclude.addAll(Arrays.stream(matcherInclude.split(","))
                .map(String::trim)
                .toList());

        String matcherExclude = configMap.getOrDefault("matchers_exclude", "com.,org.");
        this.matchersExclude.addAll(Arrays.stream(matcherExclude.split(","))
                .map(String::trim)
                .toList());

        this.triggerOn = configMap.getOrDefault("trigger_on", null);
    }

    public String getDumpLocation() {
        return dumpLocation;
    }

    public List<String> getMatchersInclude() {
        return matchersInclude;
    }

    public List<String> getMatchersExclude() {
        return matchersExclude;
    }

    public String getTriggerOn() {
        return triggerOn;
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
