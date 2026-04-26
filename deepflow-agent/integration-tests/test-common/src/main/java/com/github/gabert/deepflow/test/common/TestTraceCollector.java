package com.github.gabert.deepflow.test.common;

import java.util.List;

/**
 * Accesses the agent's {@code TestDestination} via reflection so the test
 * module does not need a compile dependency on the shaded agent JAR.
 *
 * <p>Requires the agent to be attached with {@code destination=test}.</p>
 */
public class TestTraceCollector {
    private static final String DESTINATION_CLASS =
            "com.github.gabert.deepflow.recorder.destination.TestDestination";

    @SuppressWarnings("unchecked")
    public static List<String> getLines() {
        try {
            Class<?> clazz = Class.forName(DESTINATION_CLASS);
            return (List<String>) clazz.getMethod("getLines").invoke(null);
        } catch (Exception e) {
            throw new RuntimeException(
                    "TestDestination not available — is the agent attached with destination=test?", e);
        }
    }

    public static void clear() {
        try {
            Class<?> clazz = Class.forName(DESTINATION_CLASS);
            clazz.getMethod("clear").invoke(null);
        } catch (Exception e) {
            throw new RuntimeException("TestDestination not available", e);
        }
    }

    public static TraceData collect() {
        return TraceFileParser.parseLines(getLines());
    }
}
