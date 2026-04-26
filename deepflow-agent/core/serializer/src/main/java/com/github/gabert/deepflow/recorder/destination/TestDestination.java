package com.github.gabert.deepflow.recorder.destination;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory destination for integration tests. Records are rendered to text
 * lines and stored in a static list accessible via {@link #getLines()}.
 *
 * <p>Usage: set {@code destination=test} in the agent config. The test code
 * calls {@link #clear()} before a scenario, runs the instrumented code, then
 * calls {@link #getLines()} to inspect the captured traces.</p>
 */
public class TestDestination implements Destination {
    private static final CopyOnWriteArrayList<String> LINES = new CopyOnWriteArrayList<>();

    public TestDestination(Map<String, String> config) {
    }

    @Override
    public void accept(byte[] record) {
        RecordRenderer.Result result = RecordRenderer.render(record);
        LINES.addAll(result.lines());
    }

    @Override
    public void flush() throws IOException {
    }

    @Override
    public void close() throws IOException {
    }

    public static List<String> getLines() {
        return List.copyOf(LINES);
    }

    public static void clear() {
        LINES.clear();
    }
}
