package com.github.gabert.deepflow.serializer.destination;

import java.io.IOException;
import java.util.Map;

public class InfluxDBDestination  implements Destination {
    public InfluxDBDestination(Map<String, String> configMap, String sessionId) {
    }

    @Override
    public void send(String line, String threadName) throws IOException {
        
    }
}
