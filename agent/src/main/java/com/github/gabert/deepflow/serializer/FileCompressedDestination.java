package com.github.gabert.deepflow.serializer;

import com.github.gabert.deepflow.agent.AgentConfig;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.Deflater;

/*
 * Based on the line length the FileCompressedDestination class reduces file size by cca 40%
 * Buffering lines can enhance the compression ratio even more. Buffering needs to be implemented per thread.
 * Shutdown hook must force to empty buffer for all threads buffer
 */
public class FileCompressedDestination implements Destination {
    private final Map<String, Path> dumpPaths = new HashMap<>();
    private final String dumpFileName;

    public FileCompressedDestination(AgentConfig config, String sessionId) {
        String dumpLocation = Paths.get(config.getDumpLocation(), "SESSION-" + sessionId).toString();
        this.dumpFileName = Paths.get(dumpLocation,sessionId + "-{THREAD_NAME}.dfb").toString();

        try {
            ensurePath(dumpLocation);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void send(String lines, String threadName) {
        Path filePath = dumpPaths.compute(threadName, (k, v) -> (v == null)
                ? Paths.get(dumpFileName.replace("{THREAD_NAME}", threadName))
                : v);
        try {
            writeCompressedEntry(lines, filePath);
        } catch (IOException e) {
            System.out.println(" --------------- START: AGENT FILE DESTINATION ERROR ---------------");
            e.printStackTrace();
            System.out.println(" ---------------  END: AGENT FILE DESTINATION ERROR  ---------------");
        }
    }

    private static void ensurePath(String directoryPath) throws IOException {
       Path path = Paths.get(directoryPath);
        if (!Files.exists(path)) {
            Files.createDirectories(path);
        }
    }

    private static byte[] compress(String line) throws IOException {
        byte[] data = line.getBytes();
        Deflater deflater = new Deflater();
        deflater.setInput(data);
        deflater.finish();

        byte[] buffer = new byte[1024];
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream(data.length)) {
            while (!deflater.finished()) {
                int count = deflater.deflate(buffer);
                outputStream.write(buffer, 0, count);
            }
            return outputStream.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static void writeCompressedEntry(String entry, Path filePath) throws IOException {
        byte[] compressedData = compress(entry);
        byte[] lengthBytes = intToBytes(compressedData.length);

        Files.write(filePath, lengthBytes,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);

        Files.write(filePath, compressedData,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    private static byte[] intToBytes(int value) {
        return new byte[] {
                (byte) (value >> 24),
                (byte) (value >> 16),
                (byte) (value >> 8),
                (byte) value
        };
    }
}
