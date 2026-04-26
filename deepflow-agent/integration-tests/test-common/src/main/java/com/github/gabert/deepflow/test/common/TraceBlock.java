package com.github.gabert.deepflow.test.common;

import java.util.Map;

public record TraceBlock(
        String type,
        String method,
        long requestId,
        String threadName,
        String sourceFile,
        Map<String, String> tags
) {}
