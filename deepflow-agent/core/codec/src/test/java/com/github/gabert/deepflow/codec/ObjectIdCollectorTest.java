package com.github.gabert.deepflow.codec;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObjectIdCollectorTest {

    @Test
    void singleEnvelopeYieldsOneId() throws IOException {
        String json = Hasher.hash("""
                {"object_id": 42, "class": "C", "v": 1}
                """);
        assertEquals(Set.of(42L), ObjectIdCollector.collect(json));
    }

    @Test
    void nestedEnvelopesAreAllCollected() throws IOException {
        String json = Hasher.hash("""
                {"object_id": 1, "class": "P",
                 "child": {"object_id": 2, "class": "C",
                           "grandchild": {"object_id": 3, "class": "GC", "v": "x"}}}
                """);
        assertEquals(Set.of(1L, 2L, 3L), ObjectIdCollector.collect(json));
    }

    @Test
    void listOfEnvelopesIsCollected() throws IOException {
        String json = Hasher.hash("""
                [{"object_id": 10, "class": "I"},
                 {"object_id": 11, "class": "I"},
                 {"object_id": 12, "class": "I"}]
                """);
        assertEquals(Set.of(10L, 11L, 12L), ObjectIdCollector.collect(json));
    }

    @Test
    void plainMapHasNoIds() throws IOException {
        String json = Hasher.hash("""
                {"object_id": 1, "class": "P",
                 "config": {"timeout": 30, "retries": 3}}
                """);
        // Only the root envelope has an id; "config" is a plain Map.
        assertEquals(Set.of(1L), ObjectIdCollector.collect(json));
    }

    @Test
    void duplicateIdsAreDeduplicated() throws IOException {
        // Same logical id appearing in two positions of the tree.
        String json = Hasher.hash("""
                {"object_id": 1, "class": "P",
                 "a": {"object_id": 7, "class": "X"},
                 "b": {"object_id": 7, "class": "X"}}
                """);
        Set<Long> ids = ObjectIdCollector.collect(json);
        assertEquals(2, ids.size(), "expected dedup of 7");
        assertTrue(ids.contains(1L));
        assertTrue(ids.contains(7L));
    }

    @Test
    void scalarTopLevelHasNoIds() throws IOException {
        assertEquals(Set.of(), ObjectIdCollector.collect("\"hello\""));
        assertEquals(Set.of(), ObjectIdCollector.collect("42"));
        assertEquals(Set.of(), ObjectIdCollector.collect("null"));
    }
}
