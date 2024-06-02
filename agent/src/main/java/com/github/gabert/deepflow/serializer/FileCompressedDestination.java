package com.github.gabert.deepflow.serializer;

import com.github.gabert.deepflow.agent.AgentConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.Deflater;

public class FileCompressedDestination implements Destination {
    private final Map<String, Path> dumpPaths = new HashMap<>();
    private final String dumpLocation;
    private final String dumpFileName;

    public FileCompressedDestination(AgentConfig config, String sessionId) {
        this.dumpLocation = Paths.get(config.getDumpLocation(), "SESSION-" + sessionId).toString();
        this.dumpFileName = Paths.get(this.dumpLocation,sessionId + "-{THREAD_NAME}.dmp").toString();

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
            Files.write(filePath, compress(lines),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
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

    private static byte[] compress(String line) {
        byte[] data = line.getBytes();
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        deflater.setInput(data);
        deflater.finish();

        byte[] buffer = new byte[1024];
        int count = deflater.deflate(buffer);
        deflater.end();

        byte[] output = new byte[count];
        System.arraycopy(buffer, 0, output, 0, count);
        return output;
    }
}
