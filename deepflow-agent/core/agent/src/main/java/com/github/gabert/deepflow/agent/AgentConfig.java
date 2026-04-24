package com.github.gabert.deepflow.agent;

import java.io.*;
import java.util.*;
import java.util.LinkedHashSet;


public class AgentConfig {
    private static final String DEFAULT_EMIT_TAGS = "SI,TN,CI,PI,TS,CL,TI,AR,RT,RE,TE";

    private final List<String> matchersInclude = new ArrayList<>();
    private final List<String> matchersExclude = new ArrayList<>();

    private final String sessionDumpLocation;
    private final String sessionResolver;
    private final String jpaProxyResolver;
    private final boolean expandThis;
    private final boolean serializeValues;
    private final String destination;
    private final Set<String> emitTags;
    private final Map<String, String> configMap;

    private AgentConfig(Map<String, String> configMap) {
        this.configMap = Collections.unmodifiableMap(configMap);
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
        this.sessionResolver = configMap.getOrDefault("session_resolver", null);
        this.jpaProxyResolver = configMap.getOrDefault("jpa_proxy_resolver", null);
        this.expandThis = Boolean.parseBoolean(configMap.getOrDefault("expand_this", "false"));
        this.serializeValues = Boolean.parseBoolean(configMap.getOrDefault("serialize_values", "true"));
        this.destination = configMap.getOrDefault("destination", "file");

        String tagsValue = configMap.getOrDefault("emit_tags", DEFAULT_EMIT_TAGS);
        Set<String> tags = new LinkedHashSet<>();
        tags.add("MS");
        for (String tag : tagsValue.split(",")) {
            String t = tag.trim().toUpperCase();
            if (!t.isEmpty()) {
                tags.add(t);
            }
        }
        this.emitTags = Collections.unmodifiableSet(tags);

        publishSystemProperties(configMap);
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

    public String getSessionResolver() {
        return sessionResolver;
    }

    public String getJpaProxyResolver() {
        return jpaProxyResolver;
    }

    public boolean isExpandThis() {
        return expandThis;
    }

    public boolean isSerializeValues() {
        return serializeValues;
    }

    public String getDestination() {
        return destination;
    }

    public Set<String> getEmitTags() {
        return emitTags;
    }

    public boolean shouldEmit(String tag) {
        return emitTags.contains(tag);
    }

    public Map<String, String> getConfigMap() {
        return configMap;
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

    private static void publishSystemProperties(Map<String, String> configMap) {
        String sessionId = configMap.get("session_id");
        if (sessionId != null) {
            System.setProperty("deepflow.session_id", sessionId);
        }
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
