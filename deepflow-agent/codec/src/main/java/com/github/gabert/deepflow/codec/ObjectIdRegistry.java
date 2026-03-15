package com.github.gabert.deepflow.codec;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

// ─────────────────────────────────────────────────────────────
// ObjectIdRegistry
//
// Assigns a stable, unique long ID to every object instance
// seen during serialization.
//
// Why not System.identityHashCode()?
//   identityHashCode is not unique — two live objects can share
//   the same value. This registry uses == (raw JVM pointer
//   comparison) as the true equality, making IDs guaranteed
//   unique for all live objects.
//
// Memory model:
//   Keys are WeakReferences — the registry never prevents GC.
//   When an object is GC'd its entry is removed via
//   ReferenceQueue. The AtomicLong counter never reuses values,
//   so a new object at the same memory address always gets a
//   fresh ID — no confusion with the dead object's history.
// ─────────────────────────────────────────────────────────────
public final class ObjectIdRegistry {

   private static final AtomicLong COUNTER = new AtomicLong(0);
   private static final ReferenceQueue<Object> GC_QUEUE = new ReferenceQueue<>();
   private static final ConcurrentHashMap<IdentityWeakRef, Long> MAP = new ConcurrentHashMap<>();

   private ObjectIdRegistry() {
   }

   public static long idOf(Object o) {
      expungeStale();

      // Lookup key: holds a strong ref so object cannot be GC'd during lookup
      IdentityWeakRef lookupKey = new IdentityWeakRef(o, null);
      Long existing = MAP.get(lookupKey);
      if (existing != null)
         return existing;

      // Storage key: enrolled in GC queue so entry is cleaned up after GC
      IdentityWeakRef storageKey = new IdentityWeakRef(o, GC_QUEUE);
      long id = COUNTER.incrementAndGet();
      Long prev = MAP.putIfAbsent(storageKey, id);
      return prev != null ? prev : id;
   }

   // Called on every idOf() — drains GC'd entries from the map.
   // Keeps memory footprint proportional to live tracked objects only.
   private static void expungeStale() {
      Reference<?> ref;
      while ((ref = GC_QUEUE.poll()) != null) {
         MAP.remove(ref);
      }
   }

   // ── Key type ──────────────────────────────────────────────
   // hashCode() uses identityHashCode — only to find the right
   // bucket in ConcurrentHashMap. Collision here is harmless.
   //
   // equals() uses == — raw JVM pointer comparison.
   // This is the critical line: two objects with the same
   // identityHashCode but different memory addresses are
   // correctly identified as different objects.
   //
   // identityHash is cached as a plain int so hashCode() still
   // works correctly after the referent has been GC'd
   // (WeakReference.get() would return null at that point).
   public static final class IdentityWeakRef extends WeakReference<Object> {

      private final int identityHash;

      IdentityWeakRef(Object referent, ReferenceQueue<Object> queue) {
         super(referent, queue);
         this.identityHash = System.identityHashCode(referent);
      }

      @Override
      public int hashCode() {
         return identityHash; // bucket finder only — not the object's true identity
      }

      @Override
      public boolean equals(Object other) {
         if (!(other instanceof IdentityWeakRef that))
            return false;
         Object mine = this.get();
         Object theirs = that.get();
         // == compares raw JVM memory addresses — unambiguous identity
         return mine != null && mine == theirs;
      }
   }
}
