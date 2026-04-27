package com.github.gabert.deepflow.processor;

import com.github.gabert.deepflow.recorder.destination.RecordRenderer.Result;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class RecordParserTest {

    @Test
    void singleCallProducesOneParsedCall() {
        Result r = result(
                "TS;1000",
                "SI;sess-1",
                "MS;com.example.Foo.bar()V",
                "TN;main",
                "RI;42",
                "CL;55",
                "TI;7",
                "AR;{\"__meta__\":{\"id\":7,\"class\":\"X\",\"hash\":\"abc\"}}",
                "RT;VOID",
                "TE;1500",
                "TN;main",
                "RI;42");

        List<ParsedCall> calls = RecordParser.parse(r);

        assertEquals(1, calls.size());
        ParsedCall c = calls.get(0);
        assertEquals("sess-1", c.sessionId());
        assertEquals(42L, c.requestId());
        assertEquals("main", c.threadName());
        assertEquals(1000L, c.tsInMillis());
        assertEquals(1500L, c.tsOutMillis());
        assertEquals("com.example.Foo.bar()V", c.signature());
        assertEquals(55, c.callerLine());
        assertEquals("VOID", c.returnType());
        assertEquals(7L, c.thisIdRef());
        assertNull(c.thisJson());
    }

    @Test
    void nestedCallsAreEmittedInPostOrder() {
        // Outer A calls inner B. Inner ends first, outer ends second.
        Result r = result(
                "TS;1000", "MS;A.outer()V", "TN;t", "RI;1", "CL;10",
                "AR;{}",
                "TS;1100", "MS;B.inner()V", "TN;t", "RI;1", "CL;20",
                "AR;{}",
                "RT;VOID", "TE;1200", "TN;t", "RI;1",
                "RT;VOID", "TE;1300", "TN;t", "RI;1");

        List<ParsedCall> calls = RecordParser.parse(r);

        assertEquals(2, calls.size());
        // Inner completes first
        assertEquals("B.inner()V", calls.get(0).signature());
        assertEquals(1100L, calls.get(0).tsInMillis());
        assertEquals(1200L, calls.get(0).tsOutMillis());
        // Outer second
        assertEquals("A.outer()V", calls.get(1).signature());
        assertEquals(1000L, calls.get(1).tsInMillis());
        assertEquals(1300L, calls.get(1).tsOutMillis());
    }

    @Test
    void valueReturnIsCaptured() {
        Result r = result(
                "TS;1", "MS;F.f()I", "TN;t", "RI;1", "CL;1",
                "RT;VALUE", "RE;42",
                "TE;2", "TN;t", "RI;1");
        ParsedCall c = RecordParser.parse(r).get(0);
        assertEquals("VALUE", c.returnType());
        assertEquals("42", c.returnJson());
    }

    @Test
    void exceptionReturnIsCaptured() {
        Result r = result(
                "TS;1", "MS;F.f()V", "TN;t", "RI;1", "CL;1",
                "RT;EXCEPTION", "RE;{\"class\":\"java.lang.RuntimeException\"}",
                "TE;2", "TN;t", "RI;1");
        ParsedCall c = RecordParser.parse(r).get(0);
        assertEquals("EXCEPTION", c.returnType());
        assertNotNull(c.returnJson());
    }

    @Test
    void argsExitIsCapturedSeparately() {
        Result r = result(
                "TS;1", "MS;F.f()V", "TN;t", "RI;1", "CL;1",
                "AR;{\"v\":1}",
                "AX;{\"v\":2}",
                "RT;VOID", "TE;2", "TN;t", "RI;1");
        ParsedCall c = RecordParser.parse(r).get(0);
        assertEquals("{\"v\":1}", c.argsJson());
        assertEquals("{\"v\":2}", c.argsExitJson());
    }

    @Test
    void thisAsFullJsonGoesIntoThisJsonNotRef() {
        Result r = result(
                "TS;1", "MS;F.f()V", "TN;t", "RI;1", "CL;1",
                "TI;{\"__meta__\":{\"id\":99,\"class\":\"X\",\"hash\":\"a\"}}",
                "RT;VOID", "TE;2", "TN;t", "RI;1");
        ParsedCall c = RecordParser.parse(r).get(0);
        assertNull(c.thisIdRef());
        assertNotNull(c.thisJson());
    }

    @Test
    void staticMethodHasNeitherThisRefNorJson() {
        Result r = result(
                "TS;1", "MS;F.staticThing()V", "TN;t", "RI;1", "CL;1",
                "AR;{}",
                "RT;VOID", "TE;2", "TN;t", "RI;1");
        ParsedCall c = RecordParser.parse(r).get(0);
        assertNull(c.thisIdRef());
        assertNull(c.thisJson());
    }

    @Test
    void unmatchedTeWithEmptyStackIsIgnored() {
        Result r = result("TE;1", "TN;t", "RI;1");
        assertEquals(0, RecordParser.parse(r).size());
    }

    @Test
    void unmatchedMsWithoutTeIsDropped() {
        // Truncated stream — agent crashed before TE was emitted.
        Result r = result(
                "TS;1", "MS;F.f()V", "TN;t", "RI;1", "CL;1");
        assertEquals(0, RecordParser.parse(r).size());
    }

    @Test
    void agentOrderTeBeforeRtAttachesReturnToCorrectCall() {
        // Real wire order from RequestRecorder.recordExit():
        //   METHOD_END, RETURN, ARGUMENTS_EXIT
        // i.e. TE comes BEFORE the call's own RT/RE/AX.
        Result r = result(
                "TS;1000", "MS;F.f()I", "TN;t", "RI;1", "CL;1",
                "AR;{}",
                "TE;2000", "TN;t", "RI;1",
                "RT;VALUE", "RE;42",
                "AX;{}");
        List<ParsedCall> calls = RecordParser.parse(r);
        assertEquals(1, calls.size());
        ParsedCall c = calls.get(0);
        assertEquals(2000L, c.tsOutMillis());
        assertEquals("VALUE", c.returnType());
        assertEquals("42", c.returnJson());
        assertEquals("{}", c.argsExitJson());
    }

    @Test
    void agentOrderNestedDoesNotLeakReturnToParent() {
        // Outer A calls inner B. Inner exits (TE first, then RT/RE), then outer exits.
        Result r = result(
                "TS;1000", "MS;A.outer()V", "TN;t", "RI;1", "CL;10",
                "AR;{}",
                "TS;1100", "MS;B.inner()I", "TN;t", "RI;1", "CL;20",
                "AR;{}",
                "TE;1200", "TN;t", "RI;1", "RT;VALUE", "RE;7",
                "TE;1300", "TN;t", "RI;1", "RT;VOID");
        List<ParsedCall> calls = RecordParser.parse(r);
        assertEquals(2, calls.size());
        // Inner gets its OWN return — no leakage to parent.
        ParsedCall inner = calls.get(0);
        assertEquals("B.inner()I", inner.signature());
        assertEquals("VALUE", inner.returnType());
        assertEquals("7", inner.returnJson());
        // Outer gets ITS OWN return.
        ParsedCall outer = calls.get(1);
        assertEquals("A.outer()V", outer.signature());
        assertEquals("VOID", outer.returnType());
        assertNull(outer.returnJson());
    }

    @Test
    void versionBannerIsIgnored() {
        Result r = result(
                "VR;1.2",
                "TS;1", "MS;F.f()V", "TN;t", "RI;1", "CL;1",
                "RT;VOID", "TE;2", "TN;t", "RI;1");
        assertEquals(1, RecordParser.parse(r).size());
    }

    private static Result result(String... lines) {
        return new Result("test-thread", List.of(lines));
    }
}
