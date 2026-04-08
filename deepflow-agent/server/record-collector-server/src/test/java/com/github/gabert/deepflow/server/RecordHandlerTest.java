package com.github.gabert.deepflow.server;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class RecordHandlerTest {

    // --- Happy path ---

    @Test
    void postRecordsReturns200AndForwardsBytes() {
        byte[] body = randomBytes(64);
        List<byte[]> sent = new ArrayList<>();

        FullHttpResponse response = sendPost("/records", body, sent);

        assertEquals(HttpResponseStatus.OK, response.status());
        assertEquals(1, sent.size());
        assertArrayEquals(body, sent.get(0));
        response.release();
    }

    // --- Wrong path ---

    @Test
    void wrongPathReturns404() {
        List<byte[]> sent = new ArrayList<>();

        FullHttpResponse response = sendPost("/unknown", new byte[0], sent);

        assertEquals(HttpResponseStatus.NOT_FOUND, response.status());
        assertTrue(sent.isEmpty());
        response.release();
    }

    // --- Wrong method ---

    @Test
    void getMethodReturns405() {
        List<byte[]> sent = new ArrayList<>();
        RecordForwarder stub = stubForwarder(sent);
        EmbeddedChannel channel = new EmbeddedChannel(new RecordHandler(stub));
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/records");
        channel.writeInbound(request);

        FullHttpResponse response = channel.readOutbound();
        assertEquals(HttpResponseStatus.METHOD_NOT_ALLOWED, response.status());
        assertTrue(sent.isEmpty());
        response.release();
        channel.finish();
    }

    // --- Utilities ---

    private static byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        new Random().nextBytes(bytes);
        return bytes;
    }

    private static RecordForwarder stubForwarder(List<byte[]> sent) {
        return new RecordForwarder() {
            @Override
            public void send(byte[] rawRecords) {
                sent.add(rawRecords);
            }

            @Override
            public void close() {}
        };
    }

    private static FullHttpResponse sendPost(String uri, byte[] body, List<byte[]> sent) {
        RecordForwarder stub = stubForwarder(sent);
        EmbeddedChannel channel = new EmbeddedChannel(new RecordHandler(stub));
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, uri, Unpooled.wrappedBuffer(body));
        request.headers().set("Content-Type", "application/octet-stream");
        channel.writeInbound(request);
        FullHttpResponse response = channel.readOutbound();
        channel.finish();
        return response;
    }
}
