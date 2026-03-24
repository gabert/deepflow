package com.github.gabert.deepflow.server;

import com.github.gabert.deepflow.codec.Codec;
import com.github.gabert.deepflow.recorder.record.RecordWriter;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RecordHandlerTest {

    private static final String SIGNATURE = "com.example::Foo.bar(java.lang::String) -> void [public]";
    private static final String THREAD = "main";

    // --- Happy path ---

    @Test
    void postValidRecordReturns200() throws Exception {
        byte[] args = Codec.encode(new Object[]{"hello"});
        byte[] body = RecordWriter.logEntry(null, SIGNATURE, THREAD, 1000L, 10, 0, null, args);

        FullHttpResponse response = sendPost("/records", body);

        assertEquals(HttpResponseStatus.OK, response.status());
        response.release();
    }

    // --- Full method trace ---

    @Test
    void postFullMethodTraceReturns200() throws Exception {
        byte[] args = Codec.encode(new Object[]{"x"});
        byte[] ret = Codec.encode(42);
        byte[] entry = RecordWriter.logEntry(null, SIGNATURE, THREAD, 1000L, 10, 0, null, args);
        byte[] exit = RecordWriter.logExit(null, THREAD, 2000L, ret, false);
        byte[] body = concat(entry, exit);

        FullHttpResponse response = sendPost("/records", body);

        assertEquals(HttpResponseStatus.OK, response.status());
        response.release();
    }

    // --- Malformed data ---

    @Test
    void postMalformedDataReturns400() {
        byte[] body = new byte[]{0x01, 0x00, 0x00, 0x00, (byte) 0xFF};

        FullHttpResponse response = sendPost("/records", body);

        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());
        response.release();
    }

    // --- Wrong path ---

    @Test
    void wrongPathReturns404() {
        FullHttpResponse response = sendPost("/unknown", new byte[0]);

        assertEquals(HttpResponseStatus.NOT_FOUND, response.status());
        response.release();
    }

    // --- Wrong method ---

    @Test
    void getMethodReturns405() {
        EmbeddedChannel channel = new EmbeddedChannel(new RecordHandler());
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/records");
        channel.writeInbound(request);

        FullHttpResponse response = channel.readOutbound();
        assertEquals(HttpResponseStatus.METHOD_NOT_ALLOWED, response.status());
        response.release();
        channel.finish();
    }

    // --- Utilities ---

    private static FullHttpResponse sendPost(String uri, byte[] body) {
        EmbeddedChannel channel = new EmbeddedChannel(new RecordHandler());
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, uri, Unpooled.wrappedBuffer(body));
        request.headers().set("Content-Type", "application/octet-stream");
        channel.writeInbound(request);
        FullHttpResponse response = channel.readOutbound();
        channel.finish();
        return response;
    }

    private static byte[] concat(byte[]... arrays) {
        int totalLength = 0;
        for (byte[] array : arrays) {
            totalLength += array.length;
        }
        byte[] result = new byte[totalLength];
        int pos = 0;
        for (byte[] array : arrays) {
            System.arraycopy(array, 0, result, pos, array.length);
            pos += array.length;
        }
        return result;
    }
}
