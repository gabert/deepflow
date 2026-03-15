package com.github.gabert.deepflow.recorder;

import com.github.gabert.deepflow.codec.Codec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RecordDrainerTest {

    private static final String SIG_HANDLE = "com.example::OrderService.handle(com.example::Order) -> com.example::Result [public]";
    private static final String SIG_SAVE = "com.example::OrderDao.save(com.example::Order) -> int [public]";

    // --- Full pipeline test ---

    @Test
    void fullPipelineProducesReadableOutput() throws Exception {
        StringWriter output = new StringWriter();
        RecordBuffer buffer = new UnboundedRecordBuffer();
        RecordDrainer drainer = new RecordDrainer(buffer, output);

        drainer.start();

        // Simulate: handle() calls save(), save() returns, handle() returns
        buffer.offer(RecordWriter.logEntry(SIG_HANDLE, "http-worker-1", 1000L, 42,
                0, null, Codec.encode(new Object[]{"orderData"})));
        buffer.offer(RecordWriter.logEntry(SIG_SAVE, "http-worker-1", 1001L, 55,
                1, null, Codec.encode(new Object[]{"orderData"})));
        buffer.offer(RecordWriter.logExit("http-worker-1", 1002L, Codec.encode(1), false));
        buffer.offer(RecordWriter.logExit("http-worker-1", 1003L, Codec.encode(Map.of("status", "OK")), false));

        // Give drainer time to process, then stop
        Thread.sleep(100);
        drainer.stop();

        String text = output.toString();

        // Verify the output contains all expected tags in order
        assertTrue(text.contains("MS;" + SIG_HANDLE));
        assertTrue(text.contains("TN;http-worker-1"));
        assertTrue(text.contains("CD;0"));
        assertTrue(text.contains("CD;1"));
        assertTrue(text.contains("TS;1000"));
        assertTrue(text.contains("CL;42"));
        assertTrue(text.contains("MS;" + SIG_SAVE));
        assertTrue(text.contains("TS;1001"));
        assertTrue(text.contains("CL;55"));

        // Verify entry/exit ordering: handle starts before save, save ends before handle
        int handleStart = text.indexOf("MS;" + SIG_HANDLE);
        int saveStart = text.indexOf("MS;" + SIG_SAVE);
        int firstTE = text.indexOf("TE;1002");
        int secondTE = text.indexOf("TE;1003");
        assertTrue(handleStart < saveStart);
        assertTrue(saveStart < firstTE);
        assertTrue(firstTE < secondTE);

        // Verify return types present
        long valueCount = text.lines().filter(l -> l.equals("RT;VALUE")).count();
        assertEquals(2, valueCount);
    }

    @Test
    void voidReturnRendersCorrectly() throws Exception {
        StringWriter output = new StringWriter();
        RecordBuffer buffer = new UnboundedRecordBuffer();
        RecordDrainer drainer = new RecordDrainer(buffer, output);

        drainer.start();
        buffer.offer(RecordWriter.logExit("main", 5000L, null, true));
        Thread.sleep(50);
        drainer.stop();

        String text = output.toString();
        assertTrue(text.contains("RT;VOID"));
        assertFalse(text.contains("RE;"));
        assertTrue(text.contains("TN;main"));
    }

    @Test
    void exceptionRendersCorrectly() throws Exception {
        StringWriter output = new StringWriter();
        RecordBuffer buffer = new UnboundedRecordBuffer();
        RecordDrainer drainer = new RecordDrainer(buffer, output);

        drainer.start();
        byte[] excCbor = Codec.encode(Map.of("message", "NullPointerException",
                "stacktrace", List.of("at com.example.Foo.bar(Foo.java:10)")));
        buffer.offer(RecordWriter.logExitException("main", 6000L, excCbor));
        Thread.sleep(50);
        drainer.stop();

        String text = output.toString();
        assertTrue(text.contains("RT;EXCEPTION"));
        assertTrue(text.contains("RE;"));
        assertTrue(text.contains("NullPointerException"));
        assertTrue(text.contains("TN;main"));
    }

    @Test
    void writesToFile(@TempDir Path tempDir) throws Exception {
        Path outputFile = tempDir.resolve("trace-output.dft");
        FileWriter fileWriter = new FileWriter(outputFile.toFile());
        RecordBuffer buffer = new UnboundedRecordBuffer();
        RecordDrainer drainer = new RecordDrainer(buffer, fileWriter);

        drainer.start();
        buffer.offer(RecordWriter.logEntry(SIG_HANDLE, "main", 1000L, 10,
                0, null, Codec.encode(new Object[]{"test"})));
        buffer.offer(RecordWriter.logExit("main", 1001L, Codec.encode("done"), false));
        Thread.sleep(50);
        drainer.stop();
        fileWriter.close();

        List<String> lines = Files.readAllLines(outputFile);
        assertFalse(lines.isEmpty());
        assertTrue(lines.get(0).startsWith("MS;"));
        assertTrue(lines.get(1).startsWith("TN;main"));
        assertTrue(lines.get(2).startsWith("CD;0"));

        // Print to console so you can see the output
        System.out.println("--- trace output ---");
        lines.forEach(System.out::println);
        System.out.println("--- end ---");
    }
}