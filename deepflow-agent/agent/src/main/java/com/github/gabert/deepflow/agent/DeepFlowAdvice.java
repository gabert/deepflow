package com.github.gabert.deepflow.agent;

import com.github.gabert.deepflow.codec.Codec;
import com.github.gabert.deepflow.codec.envelope.ObjectIdRegistry;
import com.github.gabert.deepflow.recorder.RecordBuffer;
import com.github.gabert.deepflow.recorder.RecordDrainer;
import com.github.gabert.deepflow.recorder.RecordWriter;
import com.github.gabert.deepflow.recorder.UnboundedRecordBuffer;
import com.github.gabert.deepflow.serializer.DataFormatter;
import com.github.gabert.deepflow.serializer.MethodLogger;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public class DeepFlowAdvice {
    public static AgentConfig CONFIG;
    public static MethodLogger METHOD_LOGGER;
    public static RecordBuffer RECORD_BUFFER;
    private static RecordDrainer RECORD_DRAINER;
    private static boolean EXPAND_THIS;
    private static final StackWalker STACK_WALKER = StackWalker.getInstance();
    private static final ThreadLocal<Integer> CALL_DEPTH = ThreadLocal.withInitial(() -> 0);

    public static void setup(AgentConfig agentConfig) {
        CONFIG = agentConfig;
        METHOD_LOGGER = new MethodLogger(agentConfig.getDestination());
        setupRecorder(agentConfig);
    }

    @Advice.OnMethodEnter
    public static void onEnter(@Advice.Origin Method method,
                               @Advice.This(optional = true) Object self,
                               @Advice.AllArguments Object[] allArguments) {

        METHOD_LOGGER.logEntry(method, allArguments);
        recordEntry(method, self, allArguments);
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(@Advice.Origin Method method,
                              @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object returned,
                              @Advice.Thrown Throwable throwable) {

        METHOD_LOGGER.logExit(method, returned, throwable);
        recordExit(method, returned, throwable);
    }

    // --- Recorder setup ---

    private static void setupRecorder(AgentConfig agentConfig) {
        try {
            String dumpLocation = agentConfig.getSessionDumpLocation();
            String sessionId = agentConfig.getSessionId();

            if (dumpLocation == null) {
                System.err.println("session_dump_location not configured. Binary recording disabled.");
                return;
            }

            Path sessionDir = Paths.get(dumpLocation, "SESSION-" + sessionId);
            Files.createDirectories(sessionDir);
            Path outputFile = sessionDir.resolve(sessionId + "-recorder.dft");

            BufferedWriter writer = Files.newBufferedWriter(outputFile,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);

            EXPAND_THIS = agentConfig.isExpandThis();
            RECORD_BUFFER = new UnboundedRecordBuffer();
            RECORD_DRAINER = new RecordDrainer(RECORD_BUFFER, writer);
            RECORD_DRAINER.start();

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                RECORD_DRAINER.stop();
                try {
                    writer.close();
                } catch (IOException e) {
                    System.err.println("Error closing recorder writer.");
                    e.printStackTrace();
                }
            }));
        } catch (Exception e) {
            System.err.println("Failed to initialize recorder. Binary recording disabled.");
            e.printStackTrace();
            RECORD_BUFFER = null;
            RECORD_DRAINER = null;
        }
    }

    // --- Record entry ---

    public static void recordEntry(Method method, Object self, Object[] allArguments) {
        if (RECORD_BUFFER == null) return;
        try {
            String signature = DataFormatter.transformMethodSignature(method);
            String threadName = Thread.currentThread().getName();
            long timestamp = System.currentTimeMillis();
            int depth = CALL_DEPTH.get();
            CALL_DEPTH.set(depth + 1);
            int callerLine = STACK_WALKER
                    .walk(s -> s.skip(1).findFirst())
                    .map(StackWalker.StackFrame::getLineNumber)
                    .orElse(0);

            byte[] argsCbor = Codec.encode(allArguments);
            byte[] record;
            if (self != null && EXPAND_THIS) {
                byte[] thisInstanceCbor = Codec.encode(self);
                record = RecordWriter.logEntry(signature, threadName, timestamp, callerLine, depth, thisInstanceCbor, argsCbor);
            } else if (self != null) {
                long thisId = ObjectIdRegistry.idOf(self);
                record = RecordWriter.logEntryWithThisRef(signature, threadName, timestamp, callerLine, depth, thisId, argsCbor);
            } else {
                record = RecordWriter.logEntry(signature, threadName, timestamp, callerLine, depth, null, argsCbor);
            }
            RECORD_BUFFER.offer(record);
        } catch (Exception e) {
            System.err.println("Error recording entry.");
            e.printStackTrace();
        }
    }

    // --- Record exit ---

    public static void recordExit(Method method, Object returned, Throwable throwable) {
        if (RECORD_BUFFER == null) return;
        try {
            String threadName = Thread.currentThread().getName();
            long timestamp = System.currentTimeMillis();
            int depth = CALL_DEPTH.get();
            CALL_DEPTH.set(Math.max(0, depth - 1));

            if (throwable != null) {
                byte[] excCbor = Codec.encode(buildExceptionData(throwable));
                byte[] record = RecordWriter.logExitException(threadName, timestamp, excCbor);
                RECORD_BUFFER.offer(record);
            } else {
                boolean isVoid = Void.TYPE.equals(method.getGenericReturnType());
                byte[] returnCbor = isVoid ? null : Codec.encode(returned);
                byte[] record = RecordWriter.logExit(threadName, timestamp, returnCbor, isVoid);
                RECORD_BUFFER.offer(record);
            }
        } catch (Exception e) {
            System.err.println("Error recording exit.");
            e.printStackTrace();
        }
    }

    public static Map<String, Object> buildExceptionData(Throwable throwable) {
        List<String> stacktrace = Stream.of(throwable.getStackTrace())
                .map(StackTraceElement::toString)
                .toList();
        return Map.of(
                "message", String.valueOf(throwable.getMessage()),
                "stacktrace", stacktrace
        );
    }
}