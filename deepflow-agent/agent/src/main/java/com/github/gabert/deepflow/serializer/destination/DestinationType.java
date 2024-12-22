package com.github.gabert.deepflow.serializer.destination;

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
    INFLUX_DB{
        @Override
        public Destination createDestination(Map<String, String> configMap, String sessionId) {
            return new InfluxDBDestination(configMap, sessionId);
        }
    };

    public abstract Destination createDestination(Map<String, String> configMap, String sessionId);
}
