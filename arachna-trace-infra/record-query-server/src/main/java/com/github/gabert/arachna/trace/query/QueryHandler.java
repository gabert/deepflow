package com.github.gabert.arachna.trace.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Single Netty handler that owns the read-only HTTP API for the UI.
 *
 * <p>This class is intentionally thin: URL routing and response writing
 * only. The actual SQL and JSON walking lives in three sibling classes
 * grouped by endpoint family — {@link SessionsApi}, {@link ObjectsApi},
 * {@link AnalysisApi}. SQL is concentrated server-side (not in the UI)
 * so the browser stays a thin renderer and credentials never leave the
 * server.</p>
 *
 * <p>Endpoints (all return {@code application/json}):
 * <ul>
 *   <li>{@code GET /api/sessions} — list sessions, most recent first</li>
 *   <li>{@code GET /api/sessions/{id}/threads} — threads in a session</li>
 *   <li>{@code GET /api/sessions/{id}/requests} — requests in a session</li>
 *   <li>{@code GET /api/sessions/{id}/calltree?thread=...&request_id=...}
 *       — paired call rows for one (session, thread, request)</li>
 *   <li>{@code GET /api/sessions/{id}/size} — storage footprint summary</li>
 *   <li>{@code GET /api/sessions/{id}/requests/{rid}/payloads}
 *       — every payload row in one request</li>
 *   <li>{@code GET /api/calls/{call_id}/payloads}
 *       — TI/AR/AX/RE payload rows for a single call</li>
 *   <li>{@code GET /api/objects/{object_id}/history}
 *       — every payload row that mentions the given object id</li>
 *   <li>{@code GET /api/analysis/mutations?session_id=...&request_id=...}
 *       — within-call argument mutations (AR vs AX own_hash diff)</li>
 *   <li>{@code GET /api/analysis/value-search?session_id=...[&request_id=...]&value=...[&mode=substring]}
 *       — every appearance of a scalar value in a session/request</li>
 *   <li>{@code GET /api/analysis/behavior-diff?session_a=...&session_b=...}
 *       — behavioral diff between two sessions (hash-based)</li>
 *   <li>{@code GET /api/analysis/observed-signatures?session_id=...}
 *       — distinct observed signatures, for the liveness sweep</li>
 * </ul>
 */
public class QueryHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SessionsApi sessions;
    private final ObjectsApi objects;
    private final AnalysisApi analysis;
    private final BehaviorDiffApi behaviorDiff;
    private final String corsOrigin;

    public QueryHandler(ClickHouseClient ch, String corsOrigin) {
        this.sessions = new SessionsApi(ch);
        this.objects = new ObjectsApi(ch);
        this.analysis = new AnalysisApi(ch);
        this.behaviorDiff = new BehaviorDiffApi(ch);
        this.corsOrigin = corsOrigin;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (req.method() == HttpMethod.OPTIONS) {
            sendNoContent(ctx, req);
            return;
        }
        if (req.method() != HttpMethod.GET) {
            sendError(ctx, req, HttpResponseStatus.METHOD_NOT_ALLOWED, "GET only");
            return;
        }

        QueryStringDecoder decoder = new QueryStringDecoder(req.uri());
        String path = decoder.path();

        try {
            Object body = dispatch(path, decoder.parameters());
            if (body == null) {
                sendError(ctx, req, HttpResponseStatus.NOT_FOUND, "no route for " + path);
            } else {
                sendJson(ctx, req, HttpResponseStatus.OK, body);
            }
        } catch (IllegalArgumentException e) {
            sendError(ctx, req, HttpResponseStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            sendError(ctx, req, HttpResponseStatus.INTERNAL_SERVER_ERROR, msg);
        }
    }

    private Object dispatch(String path, Map<String, List<String>> params) throws Exception {
        if (path.equals("/api/health")) {
            return Map.of("status", "ok");
        }
        if (path.equals("/api/sessions")) {
            return sessions.listSessions();
        }
        String[] parts = path.split("/");
        // /api/sessions/{id}/{threads|calltree|requests|size|object-trace}
        if (parts.length == 5 && parts[1].equals("api") && parts[2].equals("sessions")) {
            String sessionId = parts[3];
            switch (parts[4]) {
                case "threads": return sessions.listThreads(sessionId);
                case "calltree": return sessions.callTree(sessionId, params);
                case "requests": return sessions.listRequests(sessionId);
                case "size": return sessions.sessionSize(sessionId);
                case "object-trace": return sessions.objectTrace(sessionId, params);
                case "object-payloads": return sessions.objectPayloads(sessionId, params);
                case "exception-calls": return sessions.exceptionCalls(sessionId);
                default: return null;
            }
        }
        // /api/calls/{id}/payloads
        if (parts.length == 5 && parts[1].equals("api") && parts[2].equals("calls")
                && parts[4].equals("payloads")) {
            return sessions.callPayloads(parts[3]);
        }
        // /api/sessions/{id}/requests/{rid}/payloads
        if (parts.length == 7 && parts[1].equals("api") && parts[2].equals("sessions")
                && parts[4].equals("requests") && parts[6].equals("payloads")) {
            return sessions.requestPayloads(parts[3], parts[5]);
        }
        // /api/objects/{id}/history
        if (parts.length == 5 && parts[1].equals("api") && parts[2].equals("objects")
                && parts[4].equals("history")) {
            return objects.objectHistory(parts[3]);
        }
        // /api/analysis/mutations?session_id=...&request_id=...
        if (path.equals("/api/analysis/mutations")) {
            return analysis.mutations(params);
        }
        // /api/analysis/value-search?session_id=...[&request_id=...]&value=...
        if (path.equals("/api/analysis/value-search")) {
            return analysis.valueSearch(params);
        }
        // /api/analysis/behavior-diff?session_a=...&session_b=...
        if (path.equals("/api/analysis/behavior-diff")) {
            return behaviorDiff.behaviorDiff(params);
        }
        // /api/analysis/observed-signatures?session_id=...
        if (path.equals("/api/analysis/observed-signatures")) {
            return behaviorDiff.observedSignatures(params);
        }
        return null;
    }

    // --- response writers --------------------------------------------------

    private void sendJson(ChannelHandlerContext ctx, FullHttpRequest req,
                          HttpResponseStatus status, Object body) {
        byte[] bytes;
        try {
            bytes = MAPPER.writeValueAsBytes(body);
        } catch (Exception e) {
            sendError(ctx, req, HttpResponseStatus.INTERNAL_SERVER_ERROR, e.getMessage());
            return;
        }
        FullHttpResponse resp = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(bytes));
        resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
        resp.headers().set(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
        addCors(resp);
        write(ctx, req, resp);
    }

    private void sendError(ChannelHandlerContext ctx, FullHttpRequest req,
                           HttpResponseStatus status, String message) {
        try {
            sendJson(ctx, req, status, Map.of("error", message == null ? status.reasonPhrase() : message));
        } catch (Exception ignored) {
            FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status,
                    Unpooled.wrappedBuffer(status.reasonPhrase().getBytes(StandardCharsets.UTF_8)));
            addCors(resp);
            write(ctx, req, resp);
        }
    }

    private void sendNoContent(ChannelHandlerContext ctx, FullHttpRequest req) {
        FullHttpResponse resp = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.NO_CONTENT);
        resp.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
        addCors(resp);
        write(ctx, req, resp);
    }

    private void addCors(FullHttpResponse resp) {
        resp.headers().set("Access-Control-Allow-Origin", corsOrigin);
        resp.headers().set("Access-Control-Allow-Methods", "GET, OPTIONS");
        resp.headers().set("Access-Control-Allow-Headers", "Content-Type");
        resp.headers().set("Access-Control-Max-Age", "600");
    }

    private void write(ChannelHandlerContext ctx, FullHttpRequest req, FullHttpResponse resp) {
        boolean keepAlive = io.netty.handler.codec.http.HttpUtil.isKeepAlive(req);
        if (keepAlive) {
            resp.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            ctx.writeAndFlush(resp);
        } else {
            ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
        }
    }
}
