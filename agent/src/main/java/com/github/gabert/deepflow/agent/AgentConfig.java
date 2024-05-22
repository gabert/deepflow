package com.github.gabert.deepflow.agent;

import java.io.*;
import java.util.*;


public class AgentConfig {
    private String dumpLocation;
    private final List<String> packageMatchers = new ArrayList<>();

    public String getDumpLocation() {
        return dumpLocation;
    }

    public List<String> getPackageMatchers() {
        return packageMatchers;
    }

    public static AgentConfig getInstance(String agentArgs) throws IOException {
        Map<String, String> configMapParams = loadFromString(agentArgs);

        Map<String, String> configMapFile = configMapParams.containsKey("config")
                ? loadFromConfigFile(configMapParams.get("config"))
                : new HashMap<>();


        Map<String, String> configMap = new HashMap<>();

        configMap.putAll(configMapFile);
        configMap.putAll(configMapParams);

        AgentConfig config = new AgentConfig();

        config.dumpLocation = configMap.get("session_dump_location");
        config.packageMatchers.addAll(Arrays.stream(configMap.get("package_matchers")
                .split(","))
                .map(String::trim)
                .toList());

        return config;
    }

    private static Map<String, String> loadFromString(String agentArgs) throws IOException {
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
                if (line.contains("=")) {
                    String[] parts = line.split("=", 2);
                    configMapFile.put(parts[0].trim(), parts[1].trim());
                }
            }
        }

        return configMapFile;
    }
}
