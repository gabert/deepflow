package com.github.gabert.arachna.trace.codec.envelope;

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

   // Reusable per-thread lookup key so the hit path allocates nothing.
   // idOf() runs once per envelope node during serialization; allocating a
   // WeakReference per lookup (the previous design) made every hit produce
   // reference-object garbage, which the GC processes in a dedicated phase —
   // the cost scales with allocation rate, not liveness. The key holds a
   // strong ref only for the duration of the map probe and is cleared
   // immediately after so it never delays collection of the probed object.
   private static final ThreadLocal<LookupKey> LOOKUP_KEY = ThreadLocal.withInitial(LookupKey::new);

   // Pre-condition: caller must not pass null. EnvelopeSerializer guards
   // null at the top of its serialize() method, so this method never sees
   // a null in production. Passing null here would create a degenerate
   // IdentityWeakRef whose referent is already cleared, causing every
   // subsequent lookup to allocate a fresh id — a slow leak. Asserting on
   // null in production code would just shift the problem; the upstream
   // guard is the contract.
   public static long idOf(Object o) {
      expungeStale();

      LookupKey lookupKey = LOOKUP_KEY.get();
      Long existing;
      try {
         existing = MAP.get(lookupKey.set(o));
      } finally {
         lookupKey.clear();
      }
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

   // ── Lookup key (never stored) ─────────────────────────────
   // Probe-side counterpart of IdentityWeakRef: plain object, no Reference
   // machinery. ConcurrentHashMap.get/remove only ever call equals on the
   // *argument* key against stored keys, so the asymmetric equals (an
   // IdentityWeakRef never equals a LookupKey) is safe — LookupKey instances
   // are never inserted into the map. Stale-entry removal in expungeStale
   // is unaffected: it removes by the very same IdentityWeakRef instance,
   // which ConcurrentHashMap matches by reference before calling equals.
   private static final class LookupKey {
      private Object referent;
      private int identityHash;

      LookupKey set(Object o) {
         this.referent = o;
         this.identityHash = System.identityHashCode(o);
         return this;
      }

      void clear() {
         this.referent = null;
      }

      @Override
      public int hashCode() {
         return identityHash;
      }

      @Override
      public boolean equals(Object other) {
         return other instanceof IdentityWeakRef that && that.get() == referent;
      }
   }
}
