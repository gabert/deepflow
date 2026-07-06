package com.github.gabert.arachna.trace.agent;

import com.github.gabert.arachna.trace.agent.recording.MethodSignatureFormatter;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The instrumentation inventory is only useful if its lines are
 * byte-identical to the signatures the recorder puts in traces —
 * otherwise diffing inventory against observed signatures produces
 * garbage. This pins {@link InstrumentationInventory#format} against
 * {@link MethodSignatureFormatter#format} for every method shape the
 * formatter distinguishes: primitives, boxed types, arrays (primitive
 * and object), nested classes, generics (erasure), varargs, statics.
 */
class InstrumentationInventoryFormatTest {

    @SuppressWarnings("unused")
    static class Sample {
        public void plain() {}
        public int primitives(int a, double b, boolean c) { return 0; }
        public String objects(String s, Map<String, List<Integer>> m) { return s; }
        public long[] arrays(byte[] raw, String[][] grid) { return new long[0]; }
        public static Sample statics(Sample self) { return self; }
        protected final Object modifiers(Object o) { return o; }
        public void varargs(String first, Object... rest) {}
        <T> T generics(T value) { return value; }
        public Nested nested(Nested n) { return n; }

        static class Nested {}
    }

    @Test
    void formatsIdenticallyToTheReflectiveFormatter() {
        TypeDescription type = TypeDescription.ForLoadedType.of(Sample.class);
        int compared = 0;
        for (Method reflective : Sample.class.getDeclaredMethods()) {
            if (reflective.isSynthetic()) continue;
            String expected = MethodSignatureFormatter.format(reflective);
            MethodDescription described = type.getDeclaredMethods().stream()
                    .filter(m -> m.isMethod())
                    .filter(m -> descriptorOf(reflective).equals(m.asDefined().getDescriptor())
                            && reflective.getName().equals(m.getName()))
                    .findFirst().orElseThrow();
            assertEquals(expected, InstrumentationInventory.format(described),
                    "format mismatch for " + reflective);
            compared++;
        }
        assertTrue(compared >= 8, "expected to compare all Sample methods, got " + compared);
    }

    private static String descriptorOf(Method method) {
        return net.bytebuddy.jar.asm.Type.getMethodDescriptor(method);
    }
}
