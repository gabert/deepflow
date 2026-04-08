package com.github.gabert.deepflow.server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;

import java.nio.charset.StandardCharsets;

public class RecordHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    static final String RECORDS_PATH = "/records";

    private final RecordForwarder forwarder;

    public RecordHandler(RecordForwarder forwarder) {
        this.forwarder = forwarder;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (!RECORDS_PATH.equals(request.uri())) {
            sendResponse(ctx, HttpResponseStatus.NOT_FOUND, "Not Found");
            return;
        }

        if (!HttpMethod.POST.equals(request.method())) {
            sendResponse(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "Method Not Allowed");
            return;
        }

        byte[] body = new byte[request.content().readableBytes()];
        request.content().readBytes(body);

        forwarder.send(body);

        sendResponse(ctx, HttpResponseStatus.OK, "OK");
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        ctx.close();
    }

    private static void sendResponse(ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
        ByteBuf content = Unpooled.copiedBuffer(message, StandardCharsets.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status, content);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }
}
