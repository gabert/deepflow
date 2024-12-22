package com.github.gabert.deepflow.serializer.destination;

import java.io.IOException;
import java.util.Map;

public class KafkaDestination implements Destination {
    public KafkaDestination(Map<String, String> configMap, String sessionId) {
        throw new UnsupportedOperationException("Kafka Destination not yet supported");
    }

    @Override
    public void send(String line, String threadName) throws IOException {

    }
}
