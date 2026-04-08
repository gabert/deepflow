package com.github.gabert.deepflow.recorder.destination;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class HttpDestination implements Destination {
    private static final int DEFAULT_FLUSH_THRESHOLD = 64 * 1024;
    private static final String DEFAULT_SERVER_URL = "http://localhost:8099/records";

    private final HttpClient httpClient;
    private final URI serverUri;
    private final int flushThreshold;
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

    public HttpDestination(Map<String, String> config) {
        String url = config.getOrDefault("http_server_url", DEFAULT_SERVER_URL);
        this.serverUri = URI.create(url);
        this.flushThreshold = Integer.parseInt(
                config.getOrDefault("http_flush_threshold", String.valueOf(DEFAULT_FLUSH_THRESHOLD)));
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public void accept(byte[] record) {
        buffer.writeBytes(record);
        if (buffer.size() >= flushThreshold) {
            sendBuffer();
        }
    }

    @Override
    public void flush() throws IOException {
        sendBuffer();
    }

    @Override
    public void close() throws IOException {
        sendBuffer();
    }

    private void sendBuffer() {
        if (buffer.size() == 0) return;

        byte[] payload = buffer.toByteArray();
        buffer.reset();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(serverUri)
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .timeout(Duration.ofSeconds(10))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                System.err.println("[DeepFlow] HTTP destination error: " + response.statusCode()
                        + " — " + response.body());
            }
        } catch (IOException | InterruptedException e) {
            System.err.println("[DeepFlow] HTTP destination send failed: " + e.getMessage());
        }
    }
}
