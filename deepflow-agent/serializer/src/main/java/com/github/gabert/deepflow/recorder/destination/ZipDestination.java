package com.github.gabert.deepflow.recorder.destination;

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ZipDestination implements Destination {
    private final Path zipFilePath;
    private final String sessionId;
    private final Map<String, List<String>> threadLines = new LinkedHashMap<>();

    public ZipDestination(Map<String, String> config) {
        String dumpLocation = config.get("session_dump_location");
        if (dumpLocation == null) {
            throw new IllegalArgumentException("session_dump_location is required for zip destination");
        }
        this.sessionId = config.get("session_id");
        this.zipFilePath = Paths.get(dumpLocation).resolve("SESSION-" + sessionId + ".zip");
    }

    @Override
    public void accept(byte[] record) {
        RecordRenderer.Result result = RecordRenderer.render(record);
        if (result.threadName() == null) return;

        threadLines.computeIfAbsent(result.threadName(), k -> new ArrayList<>())
                   .addAll(result.lines());
    }

    @Override
    public void flush() throws IOException {
        // No-op: all writing happens on close
    }

    @Override
    public void close() throws IOException {
        Files.createDirectories(zipFilePath.getParent());

        try (ZipOutputStream zos = new ZipOutputStream(
                new BufferedOutputStream(Files.newOutputStream(zipFilePath)))) {
            zos.setLevel(Deflater.DEFLATED);

            for (Map.Entry<String, List<String>> entry : threadLines.entrySet()) {
                String entryName = sessionId + "-" + entry.getKey() + ".dft";
                zos.putNextEntry(new ZipEntry(entryName));

                BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(zos, StandardCharsets.UTF_8));
                for (String line : entry.getValue()) {
                    writer.write(line);
                    writer.newLine();
                }
                writer.flush();
                zos.closeEntry();
            }
        }
        threadLines.clear();
    }
}
