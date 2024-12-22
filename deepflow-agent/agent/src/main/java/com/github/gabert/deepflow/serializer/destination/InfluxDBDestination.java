package com.github.gabert.deepflow.serializer.destination;

import java.io.IOException;
import java.util.Map;

public class InfluxDBDestination  implements Destination {
    public InfluxDBDestination(Map<String, String> configMap, String sessionId) {
        throw new UnsupportedOperationException("InfluxDB Destination not yet supported");
    }

    @Override
    public void send(String line, String threadName) throws IOException {
        
    }
}
