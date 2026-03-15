package com.github.gabert.deepflow.recorder;

import com.github.gabert.deepflow.codec.Codec;
import com.github.gabert.deepflow.codec.envelope.FieldIds;

import java.io.IOException;
import java.io.Writer;
import java.util.Map;

/**
 * Drains records from a {@link RecordBuffer} and writes them as human-readable
 * text to a {@link Writer}. Runs in a background thread.
 *
 * <p>Output format uses {@code TAG;value} lines, comparable to the existing
 * {@code .dft} text format from MethodLogger.
 */
public final class RecordDrainer {
    private static final String DELIMITER = ";";

    private final RecordBuffer buffer;
    private final Writer writer;
    private final Thread thread;
    private volatile boolean running;

    // --- Public API ---

    public RecordDrainer(RecordBuffer buffer, Writer writer) {
        this.buffer = buffer;
        this.writer = writer;
        this.thread = new Thread(this::drainLoop, "record-drainer");
        this.thread.setDaemon(true);
    }

    public void start() {
        running = true;
        thread.start();
    }

    public void stop() {
        running = false;
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        drainRemaining();
    }

    // --- Drain loop ---

    private void drainLoop() {
        while (running) {
            byte[] record = buffer.poll();
            if (record != null) {
                writeRecord(record);
            } else {
                Thread.onSpinWait();
            }
        }
    }

    private void drainRemaining() {
        byte[] record;
        while ((record = buffer.poll()) != null) {
            writeRecord(record);
        }
        try {
            writer.flush();
        } catch (IOException e) {
            System.err.println("Error flushing drain writer.");
            e.printStackTrace();
        }
    }

    // --- Record rendering ---

    private void writeRecord(byte[] data) {
        var records = RecordReader.readAll(data);
        try {
            for (Record record : records) {
                renderRecord(record);
            }
        } catch (IOException e) {
            System.err.println("Error writing drained record.");
            e.printStackTrace();
        }
    }

    private void renderRecord(Record record) throws IOException {
        switch (record.type()) {
            case RecordType.METHOD_START      -> renderMethodStart(record);
            case RecordType.THIS_INSTANCE     -> renderThisInstance(record);
            case RecordType.THIS_INSTANCE_REF -> renderThisInstanceRef(record);
            case RecordType.ARGUMENTS         -> renderArguments(record);
            case RecordType.RETURN            -> renderReturn(record);
            case RecordType.EXCEPTION         -> renderException(record);
            case RecordType.METHOD_END        -> renderMethodEnd(record);
        }
    }

    private void renderMethodStart(Record record) throws IOException {
        MethodStartData meta = RecordReader.decodeMethodStart(record);
        writeLine("MS", meta.signature);
        writeLine("TN", meta.threadName);
        writeLine("CD", String.valueOf(meta.depth));
        writeLine("TS", String.valueOf(meta.timestamp));
        writeLine("CL", String.valueOf(meta.callerLine));
    }

    private void renderThisInstance(Record record) throws IOException {
        String json = decodeCborPayload(record.payload());
        writeLine("TI", json);
    }

    private void renderThisInstanceRef(Record record) throws IOException {
        long objectId = RecordReader.getLong(record.payload(), 0);
        writeLine("TI", String.valueOf(objectId));
    }

    private void renderArguments(Record record) throws IOException {
        String json = decodeArgumentsPayload(record.payload());
        writeLine("AR", json);
    }

    private void renderReturn(Record record) throws IOException {
        if (record.payload().length == 0) {
            writeLine("RT", "VOID");
        } else {
            writeLine("RT", "VALUE");
            String json = decodeCborPayload(record.payload());
            writeLine("RE", json);
        }
    }

    private void renderException(Record record) throws IOException {
        writeLine("RT", "EXCEPTION");
        String json = decodeCborPayload(record.payload());
        writeLine("RE", json);
    }

    private void renderMethodEnd(Record record) throws IOException {
        MethodEndData meta = RecordReader.decodeMethodEnd(record);
        writeLine("TN", meta.threadName);
        writeLine("TE", String.valueOf(meta.timestamp));
    }

    // --- Utilities ---

    private void writeLine(String tag, String value) throws IOException {
        writer.write(tag);
        writer.write(DELIMITER);
        writer.write(value);
        writer.write('\n');
    }

    private String decodeArgumentsPayload(byte[] payload) {
        try {
            Object decoded = Codec.decode(payload);
            if (decoded instanceof Map<?, ?> envelope) {
                Object args = getEnvelopeValue(envelope, FieldIds.VALUE);
                if (args != null) {
                    return Codec.toReadableJson(args);
                }
            }
            return Codec.toReadableJson(decoded);
        } catch (IOException e) {
            return "<decode error: " + e.getMessage() + ">";
        }
    }

    private Object getEnvelopeValue(Map<?, ?> envelope, int fieldId) {
        // CBOR decoder may return keys as Integer or String
        Object value = envelope.get(fieldId);
        if (value == null) {
            value = envelope.get(String.valueOf(fieldId));
        }
        return value;
    }

    private String decodeCborPayload(byte[] payload) {
        try {
            Object decoded = Codec.decode(payload);
            return Codec.toReadableJson(decoded);
        } catch (IOException e) {
            return "<decode error: " + e.getMessage() + ">";
        }
    }
}