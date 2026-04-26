package com.github.gabert.deepflow.test.common;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class AgentProcess {

    public static Path findAgentJar() {
        String prop = System.getProperty("deepflow.agent.jar");
        if (prop != null) {
            Path p = Path.of(prop);
            if (Files.exists(p)) return p;
        }

        Path cwd = Path.of("").toAbsolutePath();
        Path candidate = cwd;
        for (int i = 0; i < 5; i++) {
            Path jar = candidate.resolve("core/agent/target/deepflow-agent.jar");
            if (Files.exists(jar)) return jar;
            candidate = candidate.getParent();
            if (candidate == null) break;
        }

        throw new IllegalStateException(
                "Cannot find deepflow-agent.jar. Run 'mvn clean install' first. CWD=" + cwd);
    }

    public static Path writeConfig(Path dumpDir, String matchersInclude) throws IOException {
        return writeConfig(dumpDir, matchersInclude, Map.of());
    }

    public static Path writeConfig(Path dumpDir, String matchersInclude,
                                   Map<String, String> extra) throws IOException {
        Path configFile = dumpDir.resolve("deepagent.cfg");
        List<String> lines = new ArrayList<>();
        lines.add("session_dump_location=" + dumpDir.toString().replace('\\', '/'));
        lines.add("matchers_include=" + matchersInclude);
        lines.add("destination=file");
        lines.add("serialize_values=true");
        lines.add("expand_this=false");
        lines.add("propagate_request_id=true");
        for (var entry : extra.entrySet()) {
            lines.add(entry.getKey() + "=" + entry.getValue());
        }
        Files.write(configFile, lines);
        return configFile;
    }

    public static int runAndWait(Path agentJar, Path configFile, Path classpath,
                                 String mainClass, int timeoutSeconds) throws Exception {
        String javaCmd = ProcessHandle.current().info().command().orElse("java");

        List<String> cmd = new ArrayList<>();
        cmd.add(javaCmd);
        cmd.add("-javaagent:" + agentJar + "=config=" + configFile);
        cmd.add("-cp");
        cmd.add(classpath.toString());
        cmd.add(mainClass);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.inheritIO();
        Process process = pb.start();

        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("Process timed out after " + timeoutSeconds + "s");
        }
        return process.exitValue();
    }

    public static Process startSpringBoot(Path agentJar, Path configFile,
                                          Path appJar, int port) throws Exception {
        String javaCmd = ProcessHandle.current().info().command().orElse("java");

        List<String> cmd = new ArrayList<>();
        cmd.add(javaCmd);
        cmd.add("-javaagent:" + agentJar + "=config=" + configFile);
        cmd.add("-jar");
        cmd.add(appJar.toString());
        cmd.add("--server.port=" + port);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.inheritIO();
        return pb.start();
    }

    public static void waitForHealth(String healthUrl, int timeoutSeconds) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpURLConnection conn = (HttpURLConnection)
                        URI.create(healthUrl).toURL().openConnection();
                conn.setConnectTimeout(1000);
                conn.setReadTimeout(1000);
                if (conn.getResponseCode() == 200) return;
            } catch (IOException ignored) {
            }
            Thread.sleep(1000);
        }
        throw new RuntimeException("Health check timed out after " + timeoutSeconds + "s: " + healthUrl);
    }

    public static void shutdownSpringBoot(String baseUrl) {
        try {
            HttpURLConnection conn = (HttpURLConnection)
                    URI.create(baseUrl + "/actuator/shutdown").toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.getResponseCode();
        } catch (IOException ignored) {
        }
    }
}
