package com.github.gabert.deepflow.recorder.destination;

import com.github.gabert.deepflow.recorder.destination.RecordRenderer.Result;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecordHashEnricherTest {

    @Test
    void arLineWithJsonObjectIsHashed() {
        Result in = result("AR;{\"object_id\": 1, \"class\": \"User\", \"name\": \"Alice\"}");

        Result out = RecordHashEnricher.enrich(in);

        String line = out.lines().get(0);
        assertTrue(line.startsWith("AR;{"));
        assertTrue(line.contains("\"__meta__\""), "expected __meta__ in: " + line);
        assertFalse(line.contains("\"object_id\""), "object_id should be moved into __meta__");
    }

    @Test
    void axLineWithJsonObjectIsHashed() {
        Result in = result("AX;{\"object_id\": 5, \"class\": \"X\", \"v\": 42}");
        Result out = RecordHashEnricher.enrich(in);
        assertTrue(out.lines().get(0).contains("\"__meta__\""));
    }

    @Test
    void reLineWithJsonObjectIsHashed() {
        Result in = result("RE;{\"object_id\": 9, \"class\": \"R\"}");
        Result out = RecordHashEnricher.enrich(in);
        assertTrue(out.lines().get(0).contains("\"__meta__\""));
    }

    @Test
    void tiLineWithFullJsonIsHashed() {
        Result in = result("TI;{\"object_id\": 7, \"class\": \"T\", \"v\": 1}");
        Result out = RecordHashEnricher.enrich(in);
        assertTrue(out.lines().get(0).contains("\"__meta__\""));
    }

    @Test
    void tiLineWithObjectIdRefPassesThrough() {
        // expand_this=false produces TI;<numeric-id>
        Result in = result("TI;42");
        Result out = RecordHashEnricher.enrich(in);
        assertEquals("TI;42", out.lines().get(0));
    }

    @Test
    void nonJsonTagsPassThrough() {
        Result in = result(
                "MS;com.example.Foo.bar()V",
                "TS;1738000000000",
                "TN;main",
                "RI;100",
                "CL;42",
                "RT;VOID",
                "TE;1738000000050");
        Result out = RecordHashEnricher.enrich(in);
        assertEquals(in.lines(), out.lines());
    }

    @Test
    void arWithJsonArrayIsHashed() {
        // top-level array (e.g. multi-arg call serialised as a list)
        Result in = result("AR;[{\"object_id\": 1, \"class\": \"A\"}, {\"object_id\": 2, \"class\": \"B\"}]");
        Result out = RecordHashEnricher.enrich(in);
        String line = out.lines().get(0);
        assertTrue(line.startsWith("AR;["));
        // Each array element should have __meta__
        assertTrue(line.contains("\"__meta__\""));
    }

    @Test
    void malformedJsonPassesThroughUnchanged() {
        Result in = result("AR;{this is not valid JSON");
        Result out = RecordHashEnricher.enrich(in);
        assertEquals("AR;{this is not valid JSON", out.lines().get(0));
    }

    @Test
    void threadNameIsPreserved() {
        Result in = new Result("worker-1", List.of("MS;Foo.bar()V"));
        Result out = RecordHashEnricher.enrich(in);
        assertEquals("worker-1", out.threadName());
    }

    @Test
    void emptyLinesListProducesEmptyResult() {
        Result in = new Result("t", List.of());
        Result out = RecordHashEnricher.enrich(in);
        assertEquals(0, out.lines().size());
        assertEquals("t", out.threadName());
    }

    @Test
    void mixedBatchHashesOnlyJsonTags() {
        Result in = result(
                "MS;com.example.Foo.bar(LUser;)V",
                "TS;1000",
                "TI;{\"object_id\": 1, \"class\": \"Foo\"}",
                "AR;{\"object_id\": 2, \"class\": \"User\", \"name\": \"x\"}",
                "RT;VOID",
                "TE;2000");
        Result out = RecordHashEnricher.enrich(in);
        List<String> lines = out.lines();

        assertEquals("MS;com.example.Foo.bar(LUser;)V", lines.get(0));
        assertEquals("TS;1000", lines.get(1));
        assertTrue(lines.get(2).contains("__meta__"));
        assertTrue(lines.get(3).contains("__meta__"));
        assertEquals("RT;VOID", lines.get(4));
        assertEquals("TE;2000", lines.get(5));
    }

    private static Result result(String... lines) {
        return new Result("test-thread", List.of(lines));
    }
}
