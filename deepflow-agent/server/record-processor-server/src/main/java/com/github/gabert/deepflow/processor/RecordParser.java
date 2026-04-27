package com.github.gabert.deepflow.processor;

import com.github.gabert.deepflow.recorder.destination.RecordRenderer.Result;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Walks a rendered (and hash-enriched) {@link Result} line stream and pairs
 * up {@code TS}/{@code TE} markers via a stack to emit one {@link ParsedCall}
 * per method invocation.
 *
 * <p>The agent emits the exit records in the order
 * {@code METHOD_END, RETURN, ARGUMENTS_EXIT}, so the wire stream presents
 * {@code TE} <em>before</em> the call's own {@code RT}, {@code RE}, and
 * {@code AX} lines. To handle this we use a "pending" slot: on {@code TE} we
 * pop the top builder into {@code pending} and continue applying
 * {@code RT}/{@code RE}/{@code AX} to it. {@code pending} is finalized and
 * emitted when the next {@code TS} or {@code TE} arrives, or at end of
 * stream. The trailing {@code TN}/{@code RI} that the {@code METHOD_END}
 * record also emits are duplicates and are skipped.</p>
 */
public final class RecordParser {

    private RecordParser() {}

    public static List<ParsedCall> parse(Result result) {
        Deque<Builder> stack = new ArrayDeque<>();
        List<ParsedCall> completed = new ArrayList<>();
        Builder pending = null;

        for (String line : result.lines()) {
            int sep = line.indexOf(';');
            if (sep < 0) continue;
            String tag = line.substring(0, sep);
            String value = line.substring(sep + 1);

            switch (tag) {
                case "VR" -> { /* version banner — ignore */ }
                case "TS" -> {
                    if (pending != null) {
                        completed.add(pending.build());
                        pending = null;
                    }
                    Builder b = new Builder();
                    b.tsIn = parseLongOrZero(value);
                    stack.push(b);
                }
                case "TE" -> {
                    if (pending != null) {
                        completed.add(pending.build());
                        pending = null;
                    }
                    if (stack.isEmpty()) break;
                    pending = stack.pop();
                    pending.tsOut = parseLongOrZero(value);
                }
                case "TN", "RI" -> {
                    // After a TE, METHOD_END emits duplicate TN/RI; drop them.
                    if (pending != null) break;
                    if (!stack.isEmpty()) apply(stack.peek(), tag, value);
                }
                case "RT", "RE", "AX" -> {
                    // Belong to the call that just hit TE (if any).
                    if (pending != null) {
                        apply(pending, tag, value);
                    } else if (!stack.isEmpty()) {
                        apply(stack.peek(), tag, value);
                    }
                }
                default -> {
                    if (!stack.isEmpty()) apply(stack.peek(), tag, value);
                }
            }
        }
        if (pending != null) completed.add(pending.build());
        return completed;
    }

    private static void apply(Builder b, String tag, String value) {
        switch (tag) {
            case "MS" -> b.signature = value;
            case "SI" -> b.sessionId = value;
            case "TN" -> b.threadName = value;
            case "RI" -> b.requestId = parseLongOrZero(value);
            case "CL" -> b.callerLine = parseIntOrZero(value);
            case "TI" -> setThis(b, value);
            case "AR" -> b.argsJson = value;
            case "AX" -> b.argsExitJson = value;
            case "RT" -> b.returnType = value;
            case "RE" -> b.returnJson = value;
            // unknown tag — drop
        }
    }

    private static void setThis(Builder b, String value) {
        if (looksLikeJson(value)) {
            b.thisJson = value;
        } else {
            try {
                b.thisIdRef = Long.parseLong(value);
            } catch (NumberFormatException ignored) {
                // unreadable — leave null
            }
        }
    }

    private static boolean looksLikeJson(String v) {
        if (v.isEmpty()) return false;
        char c = v.charAt(0);
        return c == '{' || c == '[';
    }

    private static long parseLongOrZero(String s) {
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return 0L; }
    }

    private static int parseIntOrZero(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }

    private static final class Builder {
        String sessionId;
        long requestId;
        String threadName;
        long tsIn;
        long tsOut;
        String signature;
        int callerLine;
        String returnType = "VOID";
        Long thisIdRef;
        String thisJson;
        String argsJson;
        String argsExitJson;
        String returnJson;

        ParsedCall build() {
            return new ParsedCall(
                    sessionId, requestId, threadName,
                    tsIn, tsOut, signature, callerLine,
                    returnType, thisIdRef, thisJson,
                    argsJson, argsExitJson, returnJson);
        }
    }
}
