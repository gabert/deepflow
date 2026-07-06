package com.github.gabert.arachna.trace.agent;

import com.github.gabert.arachna.trace.config.ConfigLoader;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;


public class AgentConfig {

    private final List<String> matchersInclude;
    private final List<String> matchersExclude;

    private final String sessionDumpLocation;
    private final String sessionResolver;
    private final String jpaProxyResolver;
    private final boolean expandThis;
    private final boolean serializeValues;
    private final boolean propagateRequestId;
    private final boolean parameterNames;
    private final int maxValueSize;
    private final int bufferMaxRecordsPerThread;
    private final String instrumentationInventory;
    private final String destination;
    private final Set<String> emitTags;
    private final String codeVersion;
    private final String env;
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
        this.parameterNames = Boolean.parseBoolean(configMap.getOrDefault("parameter_names", "true"));
        this.maxValueSize = Integer.parseInt(configMap.getOrDefault("max_value_size", "0"));
        this.bufferMaxRecordsPerThread = Integer.parseInt(
                configMap.getOrDefault("buffer_max_records_per_thread", "0"));
        this.instrumentationInventory = configMap.get("instrumentation_inventory");
        this.destination = configMap.getOrDefault("destination", "file");
        this.emitTags = ConfigLoader.parseEmitTags(configMap.get("emit_tags"), ConfigLoader.DEFAULT_EMIT_TAGS);
        this.codeVersion = configMap.get("code_version");
        this.env = configMap.get("env");
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

    /**
     * Whether AR/AX should be encoded as a name-keyed map (parameter
     * names from {@code MethodParameters} or {@code LocalVariableTable})
     * or as a positional array with {@code arg0..argN} keys. The latter
     * keeps the {@link com.github.gabert.arachna.trace.agent.recording.ParameterNamesResolver}
     * cache empty and skips reading any class bytes — useful when memory
     * footprint is the dominant constraint. Default {@code true}.
     */
    public boolean isParameterNames() {
        return parameterNames;
    }

    public int getMaxValueSize() {
        return maxValueSize;
    }

    /**
     * Per-thread record-buffer cap. {@code 0} (default) means unbounded —
     * memory grows during bursts and is reclaimed once the drainer catches
     * up. A positive value protects the host application's heap when the
     * drain side stalls: a thread's shard at the cap drops new records,
     * loudly (stderr + shutdown summary), never silently.
     */
    public int getBufferMaxRecordsPerThread() {
        return bufferMaxRecordsPerThread;
    }

    /**
     * Path to write the instrumentation inventory to (one line per
     * instrumented method, trace-signature format), or {@code null}
     * (default) to disable. Input to the liveness-sweep workflow — see
     * {@link InstrumentationInventory}.
     */
    public String getInstrumentationInventory() {
        return instrumentationInventory;
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

    public String getCodeVersion() {
        return codeVersion;
    }

    public String getEnv() {
        return env;
    }

    public Map<String, String> getConfigMap() {
        return configMap;
    }

    /** Parse the -javaagent argument string (and any config file it references) into a config. */
    public static AgentConfig from(String agentArgs) throws IOException {
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
}
