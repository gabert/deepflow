package com.github.gabert.deepflow.config;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Shared parsing for the agent and server configurations. Each config class
 * supplies its own input format (single {@code &}-separated string for the
 * agent's {@code -javaagent:...=...} argument, {@code String[]} argv for the
 * servers) and its own field schema; this utility provides the common
 * file-format parsing and the {@code config=path} file-merge step.
 *
 * <p>File format: line-oriented, {@code #}-prefixed comment lines, and
 * {@code key=value} pairs split on the first {@code =}.</p>
 */
public final class ConfigLoader {

    private ConfigLoader() {}

    /** Parse a single string of {@code key=value} pairs separated by {@code &}. */
    public static Map<String, String> parseAgentArgs(String agentArgs) {
        Map<String, String> map = new HashMap<>();
        if (agentArgs == null) return map;
        for (String arg : agentArgs.split("&")) {
            putKeyValue(map, arg);
        }
        return map;
    }

    /** Parse {@code String[] argv} where each entry is {@code key=value}. */
    public static Map<String, String> parseCliArgs(String[] args) {
        Map<String, String> map = new HashMap<>();
        for (String arg : args) {
            putKeyValue(map, arg);
        }
        return map;
    }

    /**
     * If {@code argMap} contains a {@code config} key pointing at a file, load
     * that file and return a merged map where args override file values.
     * If no {@code config} key, returns {@code argMap} unchanged.
     */
    public static Map<String, String> mergeWithFile(Map<String, String> argMap) throws IOException {
        if (!argMap.containsKey("config")) return argMap;
        Map<String, String> merged = new HashMap<>(loadFile(argMap.get("config")));
        merged.putAll(argMap);
        return merged;
    }

    private static Map<String, String> loadFile(String filePath) throws IOException {
        Map<String, String> map = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("#")) continue;
                putKeyValue(map, line);
            }
        }
        return map;
    }

    private static void putKeyValue(Map<String, String> map, String pair) {
        int eq = pair.indexOf('=');
        if (eq < 0) return;
        String key = pair.substring(0, eq).trim();
        String value = pair.substring(eq + 1).trim();
        if (!key.isEmpty()) {
            map.put(key, value);
        }
    }

    /**
     * Parse a comma-separated list of emit tags into an unmodifiable upper-cased
     * set. {@code MS} is always included regardless of input — it's the
     * structural method-start marker that downstream parsers rely on.
     *
     * @param tagsValue user-supplied {@code emit_tags} value, or null for default
     * @param defaultValue default to use when {@code tagsValue} is null
     */
    public static Set<String> parseEmitTags(String tagsValue, String defaultValue) {
        String input = tagsValue != null ? tagsValue : defaultValue;
        Set<String> tags = new LinkedHashSet<>();
        tags.add("MS");
        for (String tag : input.split(",")) {
            String t = tag.trim().toUpperCase();
            if (!t.isEmpty()) tags.add(t);
        }
        return Collections.unmodifiableSet(tags);
    }
}

