package com.github.gabert.deepflow.serializer.destination;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ZipWriter implements Closeable {
    private final ZipOutputStream zipOutputStream;
    private final BufferedWriter writer;
    private volatile boolean isClosed = false;

    public ZipWriter(String zipEntryFile) throws IOException {
        Path zipEntryFilePath = Paths.get(zipEntryFile);
        String zipEntryName = zipEntryFilePath.getFileName().toString();
        Path zipFilePath = zipFilePath(zipEntryFilePath);

        Path parentDir = zipFilePath.toAbsolutePath().getParent();
        if (parentDir != null && !Files.exists(parentDir)) {
            Files.createDirectories(parentDir);
        }

        FileOutputStream fos = new FileOutputStream(zipFilePath.toAbsolutePath().toString());
        this.zipOutputStream = new ZipOutputStream(new BufferedOutputStream(fos));
        this.zipOutputStream.setLevel(Deflater.DEFLATED);
        this.zipOutputStream.putNextEntry(new ZipEntry(zipEntryName));
        this.writer = new BufferedWriter(new OutputStreamWriter(zipOutputStream, StandardCharsets.UTF_8));
    }

    public synchronized void write(String line) throws IOException {
        ensureOpen();
        this.writer.write(line);
        this.writer.flush();
    }

    @Override
    public synchronized void close() {
        if (isClosed) {
            return; // Already closed
        }
        isClosed = true;

        try {
            writer.flush(); // Ensure all data is written
        } catch (IOException e) {
            e.printStackTrace();
        }

        try {
            zipOutputStream.closeEntry();
            writer.close(); // Closes underlying streams
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private synchronized void ensureOpen() throws IOException {
        if (isClosed) {
            throw new IOException("Zip file is already closed.");
        }
    }

    private static Path zipFilePath(Path filePath) {
        Path parentDirectory = filePath.getParent();
        String zipFileName = filePath.getFileName().toString().replaceFirst(".dft", ".dfz");
        return parentDirectory.resolve(zipFileName);
    }
}