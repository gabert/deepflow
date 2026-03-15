package com.github.gabert.deepflow.codec.envelope;

// ─────────────────────────────────────────────────────────────
// ClassNameCache
//
// Class.getName() allocates a new String on every call.
// On a hot serialization path with thousands of objects this
// adds up. ClassValue stores one String per Class, computed
// once, with zero lock contention on reads.
// ─────────────────────────────────────────────────────────────
public final class ClassNameCache extends ClassValue<String> {

   public static final ClassNameCache INSTANCE = new ClassNameCache();

   private ClassNameCache() {
   }

   @Override
   protected String computeValue(Class<?> c) {
      if (c.isArray()) {
         return computeValue(c.getComponentType()) + "[]";
      }
      return c.getName();
   }
}
