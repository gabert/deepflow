package com.github.gabert.deepflow.agent;

import com.github.gabert.deepflow.config.ConfigLoader;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;


public class AgentConfig {
    private static final String DEFAULT_EMIT_TAGS = "SI,TN,RI,TS,CL,TI,AR,RT,RE,TE";

    private final List<String> matchersInclude;
    private final List<String> matchersExclude;

    private final String sessionDumpLocation;
    private final String sessionResolver;
    private final String jpaProxyResolver;
    private final boolean expandThis;
    private final boolean serializeValues;
    private final boolean propagateRequestId;
    private final int maxValueSize;
    private final String destination;
    private final Set<String> emitTags;
    private final Map<String, String> configMap;

    private AgentConfig(Map<String, String> configMap) {
        this.configMap = Collections.unmodifiableMap(configMap);
        this.matchersInclude = parseCsv(configMap.getOrDefault("matchers_include", ""));
        this.matchersExclude = parseCsv(configMap.getOrDefault("matchers_exclude", ""));
        this.sessionDumpLocation = configMap.get("session_dump_location");
        this.sessionResolver = configMap.get("session_resolver");
        this.jpaProxyResolver = configMap.get("jpa_proxy_resolver");
        this.expandThis = Boolean.parseBoolean(configMap.getOrDefault("expand_this", "false"));
        this.serializeValues = Boolean.parseBoolean(configMap.getOrDefault("serialize_values", "true"));
        this.propagateRequestId = Boolean.parseBoolean(configMap.getOrDefault("propagate_request_id", "true"));
        this.maxValueSize = Integer.parseInt(configMap.getOrDefault("max_value_size", "0"));
        this.destination = configMap.getOrDefault("destination", "file");
        this.emitTags = ConfigLoader.parseEmitTags(configMap.get("emit_tags"), DEFAULT_EMIT_TAGS);

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

    public boolean isPropagateRequestId() {
        return propagateRequestId;
    }

    public int getMaxValueSize() {
        return maxValueSize;
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
        Map<String, String> args = ConfigLoader.parseAgentArgs(agentArgs);
        return new AgentConfig(ConfigLoader.mergeWithFile(args));
    }

    private static List<String> parseCsv(String csv) {
        if (csv.isEmpty()) return List.of();
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static void publishSystemProperties(Map<String, String> configMap) {
        String sessionId = configMap.get("session_id");
        if (sessionId != null) {
            System.setProperty("deepflow.session_id", sessionId);
        }
    }
}
