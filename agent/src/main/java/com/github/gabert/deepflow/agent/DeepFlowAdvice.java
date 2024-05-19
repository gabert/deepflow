package com.github.gabert.deepflow.agent;

import com.github.gabert.deepflow.serializer.MetaIdTypeAdapterFactory;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Stream;


public class DeepFlowAdvice {
    public final static Map<String, Integer> COUNTER = new HashMap<>();
    public final static String FILE_NAME = "D:\\temp\\agent_log.dmp";
    public final static Gson GSON_DATA;
    public static final Gson GSON_EXCEPTION;
    public final static String DELIMITER = ";";


    static {
        GSON_EXCEPTION = new Gson();
        GSON_DATA = new GsonBuilder()
                .registerTypeAdapterFactory(new MetaIdTypeAdapterFactory())
                .create();
    }

    @Advice.OnMethodEnter
    public static void onEnter(@Advice.Origin String origin,
                               @Advice.AllArguments Object[] allArguments) {
        appendToFile("MS" + DELIMITER + LocalTime.now() + DELIMITER + origin);
        appendToFile("AR" + DELIMITER + GSON_DATA.toJson(allArguments));

        incrementCounter();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(@Advice.Origin String origin,
                              @Advice.Return(readOnly = false, typing = Assigner.Typing.DYNAMIC) Object returned,
                              @Advice.Thrown Throwable throwable) {

        decrementCounter();

        if (throwable != null) {
            String exceptionString = GSON_EXCEPTION.toJson(new ExceptionInfo(throwable));
            appendToFile("EX" + DELIMITER + exceptionString);
        } else {
            List<Object> values = returned == null ? Collections.EMPTY_LIST : List.of(returned);
            appendToFile("RE" + DELIMITER + GSON_DATA.toJson(values));
        }

        appendToFile("ME" + DELIMITER + LocalTime.now() + DELIMITER + origin);
    }

    public static void incrementCounter() {
        String threadName = Thread.currentThread().getName();
        COUNTER.compute(threadName, (k, v) -> (v == null) ? 0 : v + 1);
    }

    public static void decrementCounter() {
        String threadName = Thread.currentThread().getName();
        COUNTER.compute(threadName, (k, v) -> (v == null) ? 0 : v - 1);
    }

    public static void appendToFile(String data) {
        String threadName = Thread.currentThread().getName();
        COUNTER.compute(threadName, (k, v) -> (v == null) ? 0 : v);

        String filePath = FILE_NAME;
        String line = (COUNTER.get(threadName) + DELIMITER + threadName + DELIMITER + data + System.lineSeparator());

        try {
            Path path = Paths.get(filePath);
            Files.write(path, line.getBytes(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            // pass
        }
    }

    public static class ExceptionInfo {
        private final String message;
        private final List<String> stacktrace;

        public ExceptionInfo(Throwable exception) {
            this.message = exception.getMessage();
            this.stacktrace = getStackTraceAsString(exception);
        }

        private List<String> getStackTraceAsString(Throwable exception) {
            return Stream.of(exception.getStackTrace())
                    .map(StackTraceElement::toString)
                    .toList();
        }
    }
}