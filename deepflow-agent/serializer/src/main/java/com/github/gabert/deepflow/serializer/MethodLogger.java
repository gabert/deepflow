package com.github.gabert.deepflow.serializer;

import com.github.gabert.deepflow.serializer.destination.Destination;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.lang.reflect.Method;
import java.time.LocalTime;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class MethodLogger {
    private static final Gson GSON_DATA = GsonProvider.getGson();
    private static final Gson GSON_EXCEPTION = new Gson();
    private static final StackWalker STACK_WALKER = StackWalker.getInstance();
    private final Destination destination;

    public MethodLogger(Destination destination) {
        this.destination = destination;
    }

    public void logEntry(Method method, Object[] allArguments) {
        LocalTime ts = LocalTime.now();

        String argsData = Stream.of(allArguments).
                map(arg -> {
                    try {
                        return GSON_DATA.toJson(arg); // Attempt serialization
                    } catch (Exception e) {
                        // Log detailed information about the failure
                        System.err.println("Serialization failed!");
                        System.err.println("Method: " + DataFormatter.transformMethodSignature(method));
                        System.err.println("Problematic Argument Type: " + (arg != null ? arg.getClass().getName() : "null"));
                        System.err.println("Problematic Argument Value: " + arg);
                        e.printStackTrace();
                        return "\"<serialization error>\""; // Fallback for failed serialization
                    }
                }).
                collect(Collectors.joining(", "));

        String methodSignature = DataFormatter.transformMethodSignature(method);
        int callerLine = STACK_WALKER
                .walk(s -> s.skip(1).findFirst())
                .map(StackWalker.StackFrame::getLineNumber)
                .orElse(0);

        StringBuilder logBuilder = new StringBuilder();

        logBuilder.append(DataFormatter.formatLine("MS", methodSignature))
                        .append(DataFormatter.formatLine("TS", ts))
                        .append(DataFormatter.formatLine("CL", callerLine))
                        .append(DataFormatter.formatLine("AR", "[" + argsData + "]"));

        sendToDestination(logBuilder.toString());
    }

    public void logExit(Method method, Object returned, Throwable throwable) {
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
