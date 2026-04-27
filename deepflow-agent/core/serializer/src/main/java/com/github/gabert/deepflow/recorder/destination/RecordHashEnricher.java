package com.github.gabert.deepflow.recorder.destination;

import com.github.gabert.deepflow.codec.Hasher;
import com.github.gabert.deepflow.recorder.destination.RecordRenderer.Result;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Augments a rendered {@link Result} by applying Merkle content hashes to the
 * JSON values of {@code TI}, {@code AR}, {@code AX}, and {@code RE} lines.
 * Other lines pass through unchanged.
 *
 * <p>This is the second of two cleanly separated steps in the post-render
 * pipeline. Step 1 (CBOR → JSON) lives in {@link RecordRenderer} via
 * {@link com.github.gabert.deepflow.codec.Codec#toReadableJson}. Step 2
 * (augment with {@code __meta__}) lives here. Both the agent-side
 * {@link FileDestination} and the server-side processor consume this output;
 * {@code .dft} files therefore carry the same hashed envelopes the
 * ClickHouse pipeline does.</p>
 *
 * <p>A {@code TI} line may carry either a full JSON object (when
 * {@code expand_this=true}) or a plain numeric object-id ref. Only the JSON
 * form is hashed; refs pass through. Hasher errors on a single line are
 * logged and the line passes through unchanged so a poison record does not
 * sink the batch.</p>
 */
public final class RecordHashEnricher {

    private static final Set<String> JSON_TAGS = Set.of("TI", "AR", "AX", "RE");
    private static final char DELIMITER = ';';

    private RecordHashEnricher() {}

    public static Result enrich(Result result) {
        List<String> enriched = new ArrayList<>(result.lines().size());
        for (String line : result.lines()) {
            enriched.add(enrichLine(line));
        }
        return new Result(result.threadName(), enriched);
    }

    private static String enrichLine(String line) {
        int sep = line.indexOf(DELIMITER);
        if (sep < 0) return line;

        String tag = line.substring(0, sep);
        if (!JSON_TAGS.contains(tag)) return line;

        String value = line.substring(sep + 1);
        if (!isJsonObjectOrArray(value)) return line;

        try {
            return tag + DELIMITER + Hasher.hash(value);
        } catch (IOException | RuntimeException e) {
            System.err.println("[DeepFlow] Hasher failed on " + tag + " line: " + e.getMessage());
            return line;
        }
    }

    private static boolean isJsonObjectOrArray(String value) {
        if (value.isEmpty()) return false;
        char first = value.charAt(0);
        return first == '{' || first == '[';
    }
}
