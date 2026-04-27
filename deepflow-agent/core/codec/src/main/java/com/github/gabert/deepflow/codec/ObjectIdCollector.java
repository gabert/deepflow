package com.github.gabert.deepflow.codec;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Walks a {@link Hasher}-produced JSON tree and collects every {@code __meta__.id}
 * value found. Used to populate the {@code object_ids} column in the payloads
 * table for fast object-identity search.
 *
 * <p>Returns a {@link LinkedHashSet} so duplicate ids in different positions
 * are deduplicated; iteration order matches first-occurrence order.</p>
 */
public final class ObjectIdCollector {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ObjectIdCollector() {}

    public static Set<Long> collect(String hashedJson) throws IOException {
        Set<Long> ids = new LinkedHashSet<>();
        walk(MAPPER.readValue(hashedJson, Object.class), ids);
        return ids;
    }

    private static void walk(Object node, Set<Long> ids) {
        if (node instanceof Map<?, ?> map) {
            Object meta = map.get("__meta__");
            if (meta instanceof Map<?, ?> metaMap) {
                Object id = metaMap.get("id");
                if (id instanceof Number n) {
                    ids.add(n.longValue());
                }
            }
            for (Object value : map.values()) {
                walk(value, ids);
            }
        } else if (node instanceof List<?> list) {
            for (Object item : list) {
                walk(item, ids);
            }
        }
    }
}
