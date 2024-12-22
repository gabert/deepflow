package com.github.gabert.deepflow.serializer;

import com.github.gabert.deepflow.serializer.destination.Destination;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.lang.reflect.Method;
import java.time.LocalTime;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class MethodLogger {
    private static final Gson GSON_DATA;
    private static final Gson GSON_EXCEPTION;
    private final Destination destination;

    static {
        GSON_DATA = new GsonBuilder()
                .registerTypeAdapterFactory(new MetaIdTypeAdapterFactory())
                .create();
        GSON_EXCEPTION = new Gson();
    }

    public MethodLogger(Destination destination) {
        this.destination = destination;
    }

    public void logEntry(Method method, Object[] allArguments) {
        LocalTime ts = LocalTime.now();

        String argsData = Stream.of(allArguments).
                map(GSON_DATA::toJson).
                collect(Collectors.joining(", "));

        String methodSignature = DataFormatter.transformMethodSignature(method);
        StackTraceElement[] stackTraceElements = Thread.currentThread().getStackTrace();
        int callerLine = stackTraceElements.length >= 3 ? stackTraceElements[2].getLineNumber() : 0;

        StringBuilder logBuilder = new StringBuilder();

        logBuilder.append(DataFormatter.formatLine("MS", methodSignature))
                        .append(DataFormatter.formatLine("TS", ts))
                        .append(DataFormatter.formatLine("CL", callerLine))
                        .append(DataFormatter.formatLine("AR", "[" + argsData + "]"));

        sendToDestination(logBuilder.toString());
    }

    public void logExit(Method method, Object returned, Throwable throwable, Object[] allArguments) {
        StringBuilder logBuilder = new StringBuilder();

        if (Void.TYPE.equals(method.getGenericReturnType())) {
            logBuilder.append(DataFormatter.formatLine("RT", "VOID"));
        } else if (throwable != null) {
            logBuilder.append(DataFormatter.formatLine("RT" , "EXCEPTION"));
        } else {
            logBuilder.append(DataFormatter.formatLine("RT", "VALUE"));
        }

        if (throwable != null) {
            String exceptionString = GSON_EXCEPTION.toJson(new DataFormatter.ExceptionInfo(throwable));
            logBuilder.append(DataFormatter.formatLine("RE", exceptionString));
        } else {
            logBuilder.append(DataFormatter.formatLine("RE", GSON_DATA.toJson(returned)));
        }

        LocalTime te = LocalTime.now();

        String methodSignature = DataFormatter.transformMethodSignature(method);

        logBuilder.append(DataFormatter.formatLine("TE", te));
        logBuilder.append(DataFormatter.formatLine("ME", methodSignature));

        sendToDestination(logBuilder.toString());
    }

    private void sendToDestination(String data) {
        String threadName = Thread.currentThread().getName();

        try {
            destination.send(data, threadName);
        } catch (Exception e) {
            // Do not interrupt the instrumented program due to the error.
            System.err.println("Error during send data to destination.");
            e.printStackTrace();
        }
    }
}
