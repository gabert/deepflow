package com.github.gabert.deepflow.test.common;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TraceFileParser {

    public static TraceData parseLines(List<String> lines) {
        return new TraceData(parseFile(lines, "in-memory"));
    }

    public static TraceData parse(Path sessionDir) throws IOException {
        List<TraceBlock> allBlocks = new ArrayList<>();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(sessionDir, "*.dft")) {
            for (Path dftFile : stream) {
                String fileName = dftFile.getFileName().toString();
                List<String> lines = Files.readAllLines(dftFile);
                allBlocks.addAll(parseFile(lines, fileName));
            }
        }

        return new TraceData(allBlocks);
    }

    public static Path findSessionDir(Path dumpDir) throws IOException {
        try (var stream = Files.list(dumpDir)) {
            return stream
                    .filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().startsWith("SESSION-"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "No SESSION-* directory found in " + dumpDir));
        }
    }

    private static List<TraceBlock> parseFile(List<String> lines, String sourceFile) {
        List<TraceBlock> blocks = new ArrayList<>();
        Map<String, String> currentTags = null;
        String currentType = null;

        for (String line : lines) {
            int sep = line.indexOf(';');
            if (sep < 0) continue;

            String tag = line.substring(0, sep);
            String value = line.substring(sep + 1);

            if ("TS".equals(tag)) {
                if (currentTags != null) {
                    blocks.add(buildBlock(currentType, currentTags, sourceFile));
                }
                currentType = "ENTRY";
                currentTags = new LinkedHashMap<>();
                currentTags.put(tag, value);
            } else if ("TE".equals(tag)) {
                if (currentTags != null) {
                    blocks.add(buildBlock(currentType, currentTags, sourceFile));
                }
                currentType = "EXIT";
                currentTags = new LinkedHashMap<>();
                currentTags.put(tag, value);
            } else if ("VR".equals(tag)) {
                continue;
            } else {
                if (currentTags != null) {
                    currentTags.put(tag, value);
                }
            }
        }

        if (currentTags != null) {
            blocks.add(buildBlock(currentType, currentTags, sourceFile));
        }

        return blocks;
    }

    private static TraceBlock buildBlock(String type, Map<String, String> tags, String sourceFile) {
        String method = tags.get("MS");
        String ri = tags.get("RI");
        long requestId = ri != null ? Long.parseLong(ri) : 0;
        String threadName = tags.get("TN");
        return new TraceBlock(type, method, requestId, threadName, sourceFile, Map.copyOf(tags));
    }
}
