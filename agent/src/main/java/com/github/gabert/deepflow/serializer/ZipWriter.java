package com.github.gabert.deepflow.serializer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.zip.*;

public class ZipWriter implements Closeable {
    private final ZipOutputStream zipOutputStream;
    private final BufferedWriter writer;
    private Boolean isClosed = Boolean.FALSE;

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

        Runtime.getRuntime().addShutdownHook(new Thread(this::close));
    }

    public void write(String line) throws IOException {
        if (isClosed) {
            throw new IOException("Zip file is already closed.");
        }

        try {
            this.writer.write(line);
            // TODO: Consider flushing periodicity
            this.writer.flush();
        } catch (IOException e) {
            this.close();
            throw e;
        }
    }

    @Override
    public void close()  {
        this.isClosed = Boolean.TRUE;

        try {
            writer.flush();
            zipOutputStream.closeEntry();
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static Path zipFilePath(Path filePath) {
        Path parentDirectory = filePath.getParent();
        String zipFileName = filePath.getFileName().toString().replaceFirst(".dft", ".dfz");
        return parentDirectory.resolve(zipFileName);
    }
}