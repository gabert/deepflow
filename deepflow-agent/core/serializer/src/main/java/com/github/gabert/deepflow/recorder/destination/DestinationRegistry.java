package com.github.gabert.deepflow.recorder.destination;

import java.util.Map;
import java.util.function.Function;

/**
 * Maps a destination name (the {@code destination} config value) to a
 * {@link Destination} factory. Adding a new destination is one entry in
 * {@link #FACTORIES}.
 */
public final class DestinationRegistry {

    private static final Map<String, Function<Map<String, String>, Destination>> FACTORIES = Map.of(
            "file", FileDestination::new,
            "http", HttpDestination::new,
            "test", TestDestination::new
    );

    private DestinationRegistry() {}

    public static Destination create(String type, Map<String, String> config) {
        Function<Map<String, String>, Destination> factory = FACTORIES.get(type);
        if (factory == null) {
            throw new IllegalArgumentException("Unknown destination type: " + type);
        }
        return factory.apply(config);
    }
}
