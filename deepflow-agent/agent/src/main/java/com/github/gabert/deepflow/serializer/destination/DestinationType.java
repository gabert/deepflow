package com.github.gabert.deepflow.serializer.destination;

import java.util.Arrays;
import java.util.Map;

public enum DestinationType {
    FILE {
        @Override
        public Destination createDestination(Map<String, String> configMap, String sessionId) {
            return new FileDestination(configMap, sessionId);
        }
    },
    ZIP {
        @Override
        public Destination createDestination(Map<String, String> configMap, String sessionId) {
            return new ZipDestination(configMap, sessionId);
        }
    },
    KAFKA {
        @Override
        public Destination createDestination(Map<String, String> configMap, String sessionId) {
            return new KafkaDestination(configMap, sessionId);
        }
    },
    INFLUX_DB{
        @Override
        public Destination createDestination(Map<String, String> configMap, String sessionId) {
            return new InfluxDBDestination(configMap, sessionId);
        }
    };

    public static DestinationType fromString(String value) {
        for (DestinationType type : DestinationType.values()) {
            if (type.name().equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("\nInvalid destination type: " + value +
                ". \nValid types are: " + getValidDestinations());
    }

    public static String getValidDestinations() {
        return String.join(" | ", Arrays.stream(DestinationType.values())
                .map(type -> type.name().toLowerCase())
                .toArray(String[]::new));    }

    public abstract Destination createDestination(Map<String, String> configMap, String sessionId);
}
