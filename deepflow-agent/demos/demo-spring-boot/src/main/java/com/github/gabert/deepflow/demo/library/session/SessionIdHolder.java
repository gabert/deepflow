package com.github.gabert.deepflow.demo.library.session;

public final class SessionIdHolder {
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private SessionIdHolder() {}

    public static void set(String sessionId) {
        CURRENT.set(sessionId);
    }

    public static String get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
