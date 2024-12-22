package com.github.gabert.deepflow.serializer;

import com.github.gabert.deepflow.serializer.destination.Destination;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.lang.reflect.Method;
import java.time.LocalTime;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class MethodLogger {
    private final Gson GSON_DATA;
    private final Gson GSON_EXCEPTION;
    private final Destination destination;

    static {
    }

    public MethodLogger(Destination destination) {
        this.destination = destination;
        this.GSON_DATA = new GsonBuilder()
                .registerTypeAdapterFactory(new MetaIdTypeAdapterFactory())
                .create();
        this.GSON_EXCEPTION = new Gson();
    }

    public void logEntry(Method method, Object[] allArguments) {
        LocalTime ts = LocalTime.now();

        String argsData = Stream.of(allArguments).
                map(GSON_DATA::toJson).
                collect(Collectors.joining(", "));

        String methodSignature = DataFormatter.transformMethodSignature(method);
        StackTraceElement[] stackTraceElements = Thread.currentThread().getStackTrace();
        int callerLine = stackTraceElements.length >= 3 ? stackTraceElements[2].getLineNumber() : 0;

        sendToDestination(DataFormatter.formatLine("MS", methodSignature));
        sendToDestination(DataFormatter.formatLine("TS", ts));
        sendToDestination(DataFormatter.formatLine("CL", callerLine));
        sendToDestination(DataFormatter.formatLine("AR", "[" + argsData + "]"));
    }

    public void logExit(Method method, Object returned, Throwable throwable, Object[] allArguments) {
        if (Void.TYPE.equals(method.getGenericReturnType())) {
            sendToDestination(DataFormatter.formatLine("RT", "VOID"));
        } else if (throwable != null) {
            sendToDestination(DataFormatter.formatLine("RT" , "EXCEPTION"));
        } else {
            sendToDestination(DataFormatter.formatLine("RT", "VALUE"));
        }

        if (throwable != null) {
            String exceptionString = GSON_EXCEPTION.toJson(new DataFormatter.ExceptionInfo(throwable));
            sendToDestination(DataFormatter.formatLine("RE", exceptionString));
        } else {
            sendToDestination(DataFormatter.formatLine("RE", GSON_DATA.toJson(returned)));
        }

        LocalTime te = LocalTime.now();

        String methodSignature = DataFormatter.transformMethodSignature(method);

        sendToDestination(DataFormatter.formatLine("TE", te));
        sendToDestination(DataFormatter.formatLine("ME", methodSignature));
    }

    private void sendToDestination(String data) {
        String threadName = Thread.currentThread().getName();

        try {
            destination.send(data, threadName);
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }
}
