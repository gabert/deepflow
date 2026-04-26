package com.github.gabert.deepflow.test.common;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class TraceData {
    private final List<TraceBlock> blocks;

    public TraceData(List<TraceBlock> blocks) {
        this.blocks = List.copyOf(blocks);
    }

    public List<TraceBlock> blocks() {
        return blocks;
    }

    public Set<Long> allRequestIds() {
        return blocks.stream()
                .map(TraceBlock::requestId)
                .filter(id -> id > 0)
                .collect(Collectors.toSet());
    }

    public Set<Long> requestIdsForMethod(String methodSubstring) {
        return blocks.stream()
                .filter(b -> "ENTRY".equals(b.type()))
                .filter(b -> b.method() != null && b.method().contains(methodSubstring))
                .map(TraceBlock::requestId)
                .collect(Collectors.toSet());
    }

    public List<String> methodsForRequestId(long requestId) {
        return blocks.stream()
                .filter(b -> "ENTRY".equals(b.type()) && b.requestId() == requestId)
                .map(TraceBlock::method)
                .filter(Objects::nonNull)
                .toList();
    }

    public Set<String> threadsForRequestId(long requestId) {
        return blocks.stream()
                .filter(b -> b.requestId() == requestId)
                .map(TraceBlock::threadName)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    public List<TraceBlock> entries() {
        return blocks.stream()
                .filter(b -> "ENTRY".equals(b.type()))
                .toList();
    }
}
