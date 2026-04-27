package com.github.gabert.deepflow.codec;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HasherTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void simpleEnvelopeProducesMetaWithIdClassAndHash() throws IOException {
        String json = """
                {"object_id": 1, "class": "com.example.User", "name": "Alice", "age": 30}
                """;

        Map<String, Object> result = parseJson(Hasher.hash(json));

        assertTrue(result.containsKey("__meta__"));
        Map<?, ?> meta = (Map<?, ?>) result.get("__meta__");
        assertEquals(1, meta.get("id"));
        assertEquals("com.example.User", meta.get("class"));
        assertNotNull(meta.get("hash"));
        assertTrue(meta.get("hash").toString().matches("[0-9a-f]{32}"),
                "hash must be 32-char hex MD5");

        assertFalse(result.containsKey("object_id"));
        assertFalse(result.containsKey("class"));
        assertEquals("Alice", result.get("name"));
        assertEquals(30, result.get("age"));
    }

    @Test
    void hashIsIndependentOfInputKeyOrder() throws IOException {
        String a = """
                {"object_id": 1, "class": "C", "x": 1, "y": 2, "z": 3}
                """;
        String b = """
                {"z": 3, "object_id": 1, "y": 2, "class": "C", "x": 1}
                """;
        assertEquals(rootHash(a), rootHash(b));
    }

    @Test
    void hashIsStableAcrossInvocations() throws IOException {
        String json = """
                {"object_id": 1, "class": "P", "child": {"object_id": 2, "class": "C", "v": 1}}
                """;
        assertEquals(rootHash(json), rootHash(json));
    }

    @Test
    void deeplyNestedChangePropagatesToRootHash() throws IOException {
        String before = """
                {"object_id": 1, "class": "P",
                 "child": {"object_id": 2, "class": "C",
                           "grandchild": {"object_id": 3, "class": "GC", "v": "original"}}}
                """;
        String after = """
                {"object_id": 1, "class": "P",
                 "child": {"object_id": 2, "class": "C",
                           "grandchild": {"object_id": 3, "class": "GC", "v": "modified"}}}
                """;
        assertNotEquals(rootHash(before), rootHash(after));
    }

    @Test
    void siblingHashesAreIndependent() throws IOException {
        String json1 = """
                {"object_id": 1, "class": "P",
                 "a": {"object_id": 2, "class": "C", "v": "x"},
                 "b": {"object_id": 3, "class": "C", "v": "y"}}
                """;
        String json2 = """
                {"object_id": 1, "class": "P",
                 "a": {"object_id": 2, "class": "C", "v": "x"},
                 "b": {"object_id": 3, "class": "C", "v": "DIFFERENT"}}
                """;

        Map<String, Object> r1 = parseJson(Hasher.hash(json1));
        Map<String, Object> r2 = parseJson(Hasher.hash(json2));

        // Sibling 'a' is unchanged in both — its hash should be identical.
        assertEquals(childHash(r1, "a"), childHash(r2, "a"));
        // Root must differ because sibling 'b' changed.
        assertNotEquals(metaHash(r1), metaHash(r2));
    }

    @Test
    void identityChangeAloneDoesNotChangeContentHash() throws IOException {
        // Same data, different object_ids → CH must match (CH is data-only, identity is in __meta__.id).
        String j1 = """
                {"object_id": 1, "class": "C", "v": "x"}
                """;
        String j2 = """
                {"object_id": 999, "class": "C", "v": "x"}
                """;
        assertEquals(rootHash(j1), rootHash(j2));
    }

    @Test
    void listOrderAffectsHash() throws IOException {
        String original = """
                {"object_id": 1, "class": "P",
                 "items": [{"object_id": 2, "class": "I", "v": "a"},
                           {"object_id": 3, "class": "I", "v": "b"}]}
                """;
        String reversed = """
                {"object_id": 1, "class": "P",
                 "items": [{"object_id": 3, "class": "I", "v": "b"},
                           {"object_id": 2, "class": "I", "v": "a"}]}
                """;
        assertNotEquals(rootHash(original), rootHash(reversed));
    }

    @Test
    void envelopeWithItemsListAddsMetaToEachChild() throws IOException {
        String json = """
                {"object_id": 1, "class": "java.util.ArrayList",
                 "items": [{"object_id": 2, "class": "I", "v": "a"},
                           {"object_id": 3, "class": "I", "v": "b"}]}
                """;

        Map<String, Object> result = parseJson(Hasher.hash(json));

        assertTrue(result.containsKey("__meta__"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
        assertEquals(2, items.size());
        assertEquals(2, ((Map<?, ?>) items.get(0).get("__meta__")).get("id"));
        assertEquals(3, ((Map<?, ?>) items.get(1).get("__meta__")).get("id"));
    }

    @Test
    void envelopeWithScalarValueIsHashed() throws IOException {
        String json = """
                {"object_id": 1, "class": "Proxy", "value": "<proxy>"}
                """;

        Map<String, Object> result = parseJson(Hasher.hash(json));

        assertTrue(result.containsKey("__meta__"));
        assertEquals("<proxy>", result.get("value"));
    }

    @Test
    void plainMapHasNoMeta() throws IOException {
        // 'config' is a Map<String, Object> field — humanized without envelope wrap.
        String json = """
                {"object_id": 1, "class": "Container",
                 "config": {"timeout": 30, "retries": 3}}
                """;

        Map<String, Object> result = parseJson(Hasher.hash(json));
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) result.get("config");

        assertFalse(config.containsKey("__meta__"));
        assertEquals(30, config.get("timeout"));
        assertEquals(3, config.get("retries"));
    }

    @Test
    void cycleRefPassesThroughUnchanged() throws IOException {
        String json = """
                {"object_id": 1, "class": "P",
                 "self": {"ref_id": 1, "cycle_ref": true}}
                """;

        Map<String, Object> result = parseJson(Hasher.hash(json));
        @SuppressWarnings("unchecked")
        Map<String, Object> self = (Map<String, Object>) result.get("self");

        assertEquals(1, self.get("ref_id"));
        assertEquals(true, self.get("cycle_ref"));
        assertFalse(self.containsKey("__meta__"));
    }

    @Test
    void scalarTopLevelPassesThrough() throws IOException {
        assertEquals("\"hello\"", Hasher.hash("\"hello\""));
        assertEquals("42", Hasher.hash("42"));
        assertEquals("null", Hasher.hash("null"));
    }

    @Test
    void hashWithRootForEnvelopeReturnsTheEnvelopesHash() throws IOException {
        String json = """
                {"object_id": 1, "class": "User", "name": "Alice"}
                """;
        Hasher.HashResult r = Hasher.hashWithRoot(json);
        Map<String, Object> hashed = parseJson(r.hashedJson());
        // rootHash equals the root envelope's __meta__.hash
        assertEquals(metaHash(hashed), r.rootHash());
    }

    @Test
    void hashWithRootForArraySynthesizesContainerHash() throws IOException {
        // top-level array of envelopes (typical AR shape)
        String json = """
                [{"object_id": 1, "class": "X", "v": "a"},
                 {"object_id": 2, "class": "X", "v": "b"}]
                """;
        Hasher.HashResult r = Hasher.hashWithRoot(json);
        assertNotNull(r.rootHash());
        assertTrue(r.rootHash().matches("[0-9a-f]{32}"));
    }

    @Test
    void rootHashOfArrayChangesIfAnyElementChanges() throws IOException {
        String j1 = """
                [{"object_id": 1, "class": "X", "v": "a"},
                 {"object_id": 2, "class": "X", "v": "b"}]
                """;
        String j2 = """
                [{"object_id": 1, "class": "X", "v": "a"},
                 {"object_id": 2, "class": "X", "v": "DIFFERENT"}]
                """;
        assertNotEquals(
                Hasher.hashWithRoot(j1).rootHash(),
                Hasher.hashWithRoot(j2).rootHash());
    }

    @Test
    void rootHashOfArrayUnchangedIfElementsContentSameButIdsDiffer() throws IOException {
        // Same content, different object_ids → content hash matches.
        String j1 = """
                [{"object_id": 1, "class": "X", "v": "a"},
                 {"object_id": 2, "class": "X", "v": "b"}]
                """;
        String j2 = """
                [{"object_id": 99, "class": "X", "v": "a"},
                 {"object_id": 100, "class": "X", "v": "b"}]
                """;
        assertEquals(
                Hasher.hashWithRoot(j1).rootHash(),
                Hasher.hashWithRoot(j2).rootHash());
    }

    @Test
    void rootHashChangesIfArrayOrderChanges() throws IOException {
        String original = """
                [{"object_id": 1, "class": "X", "v": "a"},
                 {"object_id": 2, "class": "X", "v": "b"}]
                """;
        String reversed = """
                [{"object_id": 1, "class": "X", "v": "b"},
                 {"object_id": 2, "class": "X", "v": "a"}]
                """;
        assertNotEquals(
                Hasher.hashWithRoot(original).rootHash(),
                Hasher.hashWithRoot(reversed).rootHash());
    }

    @Test
    void extractRootHashFromHashedMatchesHashWithRoot() throws IOException {
        // The two paths must agree: "hash and read root in one go"
        // and "hash, then extract root from the hashed JSON."
        String[] inputs = {
                "{\"object_id\": 1, \"class\": \"X\", \"v\": 1}",
                "[{\"object_id\": 1, \"class\": \"X\"}, {\"object_id\": 2, \"class\": \"X\"}]",
                "[\"a\", \"b\", \"c\"]",
                "{\"plain\": \"map\", \"no\": \"envelope\"}",
                "\"scalar\"",
                "42"
        };
        for (String in : inputs) {
            Hasher.HashResult r = Hasher.hashWithRoot(in);
            assertEquals(r.rootHash(), Hasher.extractRootHashFromHashed(r.hashedJson()),
                    "mismatch for: " + in);
        }
    }

    @Test
    void emptyEnvelopeStillGetsMeta() throws IOException {
        String json = """
                {"object_id": 7, "class": "Empty"}
                """;

        Map<String, Object> result = parseJson(Hasher.hash(json));

        assertTrue(result.containsKey("__meta__"));
        Map<?, ?> meta = (Map<?, ?>) result.get("__meta__");
        assertEquals(7, meta.get("id"));
        assertEquals("Empty", meta.get("class"));
        assertNotNull(meta.get("hash"));
        // Only __meta__, no other fields.
        assertEquals(1, result.size());
    }

    // --- helpers ---

    private static Map<String, Object> parseJson(String json) throws IOException {
        return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
    }

    private static String rootHash(String inputJson) throws IOException {
        return metaHash(parseJson(Hasher.hash(inputJson)));
    }

    private static String metaHash(Map<String, Object> hashed) {
        return (String) ((Map<?, ?>) hashed.get("__meta__")).get("hash");
    }

    private static String childHash(Map<String, Object> hashed, String childKey) {
        @SuppressWarnings("unchecked")
        Map<String, Object> child = (Map<String, Object>) hashed.get(childKey);
        return metaHash(child);
    }
}
