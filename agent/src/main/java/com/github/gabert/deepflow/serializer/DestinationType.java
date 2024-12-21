package com.github.gabert.deepflow.serializer;

import java.util.Map;

public enum DestinationType {
    FILE {
        @Override
        public Destination createDestination(Map<String, String> configMap, String sessionId) {
            return new FileDestination(configMap, sessionId);
        }
    },
    COMPRESSED_FILE {
        @Override
        public Destination createDestination(Map<String, String> configMap, String sessionId) {
            return new CompressedFileDestination(configMap, sessionId);
        }
    },
    INFLUX_DB{
        @Override
        public Destination createDestination(Map<String, String> configMap, String sessionId) {
            return new InfluxDBDestination(configMap, sessionId);
        }
    };

    public abstract Destination createDestination(Map<String, String> configMap, String sessionId);
}
