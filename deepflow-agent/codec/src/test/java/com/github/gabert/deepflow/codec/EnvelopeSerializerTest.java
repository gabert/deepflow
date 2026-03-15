package com.github.gabert.deepflow.codec;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;

/**
 * JUnit 5 tests for {@link ObjectIdRegistry}, {@link ClassNameCache}, and {@link EnvelopeSerializer}.
 *
 * <h2>Package access</h2> All production classes under test are package-private. These tests live in the same package
 * ({@code com.github.gabert.deepflow.codec}) so no reflection is needed to access them — except for the one test that
 * deliberately probes a private field of {@link ObjectIdRegistry.IdentityWeakRef} to simulate a hash collision.
 *
 * <h2>CBOR field key constants (from {@link FieldIds})</h2>
 *
 * <pre>
 *   1 = OBJECT_ID    unique stable id for this object instance
 *   2 = CLASS_NAME   runtime fully-qualified class name
 *   3 = VALUE        serialized object content (POJO fields / map / array)
 *   4 = REF_ID       id of an already-seen object (cycle back-reference)
 *   5 = CYCLE_REF    boolean flag marking this node as a cycle reference
 * </pre>
 *
 * When CBOR bytes are deserialised back to a plain {@code Map} the Jackson CBOR factory produces {@code Integer} keys
 * for small field-id values. The private {@code get(Map, int)} helper tries both {@code Integer} and {@code String} so
 * the navigation helpers stay correct if a different mapper configuration is used.
 */
class EnvelopeSerializerTest {

   // ─────────────────────────────────────────────────────────────────────────
   // Test fixtures — minimal inner static classes
   // ─────────────────────────────────────────────────────────────────────────

   /** Two-field POJO with a nullable {@code next} pointer for building cycles. */
   static class Node {

      public String label;
      public Node next;

      Node(String label) {
         this.label = label;
      }
   }

   /**
    * Container whose {@code payload} field is declared as {@code Object}. Used to verify that erased-type fields still
    * produce a correct envelope with the runtime class name.
    */
   static class ObjectHolder {

      public Object payload;

      ObjectHolder(Object payload) {
         this.payload = payload;
      }
   }

   /** Leaf value placed into {@link ObjectHolder} so we can assert its name. */
   static class Leaf {

      public int value;

      Leaf(int value) {
         this.value = value;
      }
   }

   // ─────────────────────────────────────────────────────────────────────────
   // Private helpers — CBOR navigation
   // ─────────────────────────────────────────────────────────────────────────

   /** Serialise {@code value} with the envelope CBOR mapper. */
   private static byte[] toCbor(Object value) throws Exception {
      return EnvelopeDemo.toCbor(value);
   }

   /**
    * Deserialise raw CBOR bytes to a plain {@code Map} using a bare {@link ObjectMapper} with {@link CBORFactory} (no
    * envelope module). Integer field-id keys and nested structures are then accessible via ordinary Map lookups.
    */
   @SuppressWarnings("unchecked")
   private static Map<Object, Object> cborToMap(byte[] cbor) throws Exception {
      return new ObjectMapper(new CBORFactory()).readValue(new ByteArrayInputStream(cbor), Map.class);
   }

   /**
    * Retrieve a value from {@code map} by integer field-id key, trying {@code Integer} first (CBOR native) then
    * {@code String} (JSON fallback).
    */
   private static Object get(Map<Object, Object> map, int key) {
      Object v = map.get(key);
      return v != null ? v : map.get(String.valueOf(key));
   }

   /**
    * Navigate {@code envelope → VALUE (key 3)} and return it as a Map. The VALUE entry holds the serialised POJO fields
    * with String keys.
    */
   @SuppressWarnings("unchecked")
   private static Map<Object, Object> valueMap(Map<Object, Object> envelope) {
      return (Map<Object, Object>) get(envelope, FieldIds.VALUE);
   }

   /**
    * Navigate {@code outerEnvelope → VALUE → fieldName} and return the inner envelope Map. Each object-typed POJO field
    * is itself wrapped in an envelope with integer keys, so the returned Map uses integer keys.
    */
   @SuppressWarnings("unchecked")
   private static Map<Object, Object> fieldEnvelope(Map<Object, Object> outerEnvelope, String fieldName) {
      return (Map<Object, Object>) valueMap(outerEnvelope).get(fieldName);
   }

   /** Extract {@code OBJECT_ID (key 1)} from an envelope as a {@code long}. */
   private static long objectId(Map<Object, Object> envelope) {
      Object id = get(envelope, FieldIds.OBJECT_ID);
      assertNotNull(id, "OBJECT_ID (key 1) must be present in the envelope");
      return ((Number) id).longValue();
   }

   /** Extract {@code REF_ID (key 4)} from a cycle-reference node as a {@code long}. */
   private static long refId(Map<Object, Object> cycleNode) {
      Object id = get(cycleNode, FieldIds.REF_ID);
      assertNotNull(id, "REF_ID (key 4) must be present in the cycle-reference node");
      return ((Number) id).longValue();
   }

   /** Return {@code true} when {@code node} carries {@code CYCLE_REF (key 5)}. */
   private static boolean isCycleRef(Map<Object, Object> node) {
      return get(node, FieldIds.CYCLE_REF) != null;
   }

   // =========================================================================
   // Tests 1 & 2 — ObjectIdRegistry
   // =========================================================================

   @Nested
   @DisplayName("ObjectIdRegistry")
   class ObjectIdRegistryTests {

      // ── Test 1: same instance always returns same id ──────────────────

      @Test
      @DisplayName("same instance returns the same id on every call")
      void sameInstanceAlwaysReturnsSameId() {
         Object obj = new Object();

         long first = ObjectIdRegistry.idOf(obj);
         long second = ObjectIdRegistry.idOf(obj);
         long third = ObjectIdRegistry.idOf(obj);

         assertEquals(first, second, "Second call to idOf() must return the same id as the first");
         assertEquals(first, third, "Third call to idOf() must return the same id as the first");
      }

      @Test
      @DisplayName("id is stable after the object's content is mutated between calls")
      void idRemainsStableAfterMutation() {
         // Identity is based on JVM memory address, not on object content.
         // Mutating a field must not change the registered id.
         Node node = new Node("original");
         long before = ObjectIdRegistry.idOf(node);
         node.label = "mutated";
         long after = ObjectIdRegistry.idOf(node);

         assertEquals(before, after, "Mutating observable state must not change the object's registered id");
      }

      // ── Test 2: two different instances — even with same identityHashCode ─
      //
      // IdentityWeakRef uses System.identityHashCode() only as a hash-bucket
      // finder.  Its equals() method uses == (raw JVM pointer comparison), so
      // two different objects that happen to share the same identityHashCode
      // must never be confused and must receive distinct ids.
      //
      // This is verified in two complementary ways:
      //
      //   (a) White-box: forcibly give two IdentityWeakRef keys the same
      //       identityHash via reflection, then verify equals() still returns
      //       false — proving the == guard works correctly.
      //
      //   (b) Black-box: allocate enough objects to make birthday-paradox
      //       hash collisions statistically likely, then verify idOf() produces
      //       a unique id for every one of them.

      @Test
      @DisplayName("IdentityWeakRef.equals() uses == not identityHashCode (white-box collision test)")
      void identityWeakRefEqualsUsesReferenceIdentity() throws Exception {
         Object a = new Object();
         Object b = new Object(); // distinct JVM instance

         // Two refs wrapping the same referent must be equal
         var ref1a = new ObjectIdRegistry.IdentityWeakRef(a, null);
         var ref1b = new ObjectIdRegistry.IdentityWeakRef(a, null);
         assertEquals(ref1a, ref1b, "Two IdentityWeakRef wrappers for the same object must be equal");

         // Force ref2 (wrapping b) to share identityHash with ref1a (wrapping a),
         // directly simulating a hash-bucket collision in ConcurrentHashMap.
         var ref2 = new ObjectIdRegistry.IdentityWeakRef(b, null);
         Field hashField = ObjectIdRegistry.IdentityWeakRef.class.getDeclaredField("identityHash");
         hashField.setAccessible(true);
         hashField.set(ref2, hashField.get(ref1a)); // impose same hash as a

         // Despite identical hashCode(), the referents differ (a != b), so
         // equals() must return false — the == branch in equals() protects us.
         assertNotEquals(
            ref1a,
            ref2,
            "IdentityWeakRef with the same identityHash but a different referent "
               + "must NOT be equal; == governs equality, not hashCode()");
      }

      @Test
      @DisplayName("distinct instances always receive distinct ids even under hash collisions (black-box)")
      void largePopulationAllGetUniqueIds() {
         // Allocating 10 000 objects makes hash collisions in the 32-bit
         // identityHashCode space virtually certain via the birthday paradox,
         // exercising the == path in IdentityWeakRef.equals() under real
         // registry load.
         int count = 10_000;
         Object[] objects = new Object[count];
         long[] ids = new long[count];

         for (int i = 0; i < count; i++) {
            objects[i] = new Object();
            ids[i] = ObjectIdRegistry.idOf(objects[i]);
         }

         Set<Long> seen = new HashSet<>();
         for (int i = 0; i < count; i++) {
            assertTrue(
               seen.add(ids[i]),
               "Duplicate id " + ids[i] + " at index " + i
                  + " — the registry must assign a unique id to every distinct instance");
         }
      }
   }

   // =========================================================================
   // Tests 3 – 6 — EnvelopeSerializer
   // =========================================================================

   @Nested
   @DisplayName("EnvelopeSerializer")
   class EnvelopeSerializerTests {

      // ── Test 3: cycle detection, no StackOverflowError ────────────────

      @Test
      @DisplayName("direct cycle A → B → A does not throw StackOverflowError")
      void directCycleDoesNotCauseStackOverflow() {
         Node a = new Node("A");
         Node b = new Node("B");
         a.next = b;
         b.next = a; // cycle

         assertDoesNotThrow(() -> toCbor(a), "Serializing a two-node cycle must not throw StackOverflowError");
      }

      @Test
      @DisplayName("three-node cycle A → B → C → A does not throw StackOverflowError")
      void indirectThreeNodeCycleDoesNotCauseStackOverflow() {
         Node a = new Node("A");
         Node b = new Node("B");
         Node c = new Node("C");
         a.next = b;
         b.next = c;
         c.next = a; // three-node cycle

         assertDoesNotThrow(() -> toCbor(a), "Three-node cycle must not cause StackOverflowError");
      }

      @Test
      @DisplayName("the cycle-reference node carries CYCLE_REF = true")
      void cycleReferenceNodeHasCycleRefFlag() throws Exception {
         Node a = new Node("A");
         Node b = new Node("B");
         a.next = b;
         b.next = a;

         Map<Object, Object> aEnv = cborToMap(toCbor(a));
         Map<Object, Object> bEnv = fieldEnvelope(aEnv, "next");
         Map<Object, Object> cycleNode = fieldEnvelope(bEnv, "next");

         assertTrue(isCycleRef(cycleNode), "The back-reference node must carry CYCLE_REF (key 5) = true");
      }

      // ── Test 4: refId points to the correct objectId ──────────────────

      @Test
      @DisplayName("refId in the cycle node equals the objectId of the already-seen object")
      void refIdMatchesObjectIdOfAlreadySeenObject() throws Exception {
         Node a = new Node("A");
         Node b = new Node("B");
         a.next = b;
         b.next = a; // b.next back-references a

         Map<Object, Object> aEnv = cborToMap(toCbor(a));

         // objectId emitted for 'a' at the root of the envelope tree
         long aObjectId = objectId(aEnv);

         // Navigate to the cycle node that represents b.next
         Map<Object, Object> bEnv = fieldEnvelope(aEnv, "next");
         Map<Object, Object> cycleNode = fieldEnvelope(bEnv, "next");

         assertEquals(
            aObjectId,
            refId(cycleNode),
            "refId in the cycle node must equal a's objectId, proving the "
               + "back-pointer is correctly resolved to the already-serialised instance");
      }

      @Test
      @DisplayName("refId in a three-node cycle points back to the root's objectId")
      void refIdInThreeNodeCyclePointsToRoot() throws Exception {
         Node a = new Node("A");
         Node b = new Node("B");
         Node c = new Node("C");
         a.next = b;
         b.next = c;
         c.next = a; // c.next back-references a

         Map<Object, Object> aEnv = cborToMap(toCbor(a));
         long aId = objectId(aEnv);

         Map<Object, Object> bEnv = fieldEnvelope(aEnv, "next");
         Map<Object, Object> cEnv = fieldEnvelope(bEnv, "next");
         Map<Object, Object> cycleNode = fieldEnvelope(cEnv, "next");

         assertEquals(aId, refId(cycleNode), "In a three-node cycle the refId must point back to the root");
      }

      // ── Test 5: Object-typed fields get className captured ────────────
      //
      // When a field is declared as Object, the delegate serializer resolves
      // to a generic Object handler (handledType == Object).  EnvelopeSerializer
      // detects this and re-resolves by the actual runtime class, so CLASS_NAME
      // is always the concrete type — never "java.lang.Object".

      @Test
      @DisplayName("Object-typed field: className is the runtime type, not 'java.lang.Object'")
      void objectTypedFieldGetsRuntimeClassName() throws Exception {
         Leaf leaf = new Leaf(42);
         ObjectHolder holder = new ObjectHolder(leaf);

         Map<Object, Object> holderEnv = cborToMap(toCbor(holder));
         Map<Object, Object> payloadEnv = fieldEnvelope(holderEnv, "payload");

         assertNotNull(payloadEnv, "Object-typed 'payload' must be wrapped in an envelope");

         String className = (String) get(payloadEnv, FieldIds.CLASS_NAME);

         assertEquals(
            Leaf.class.getName(),
            className,
            "CLASS_NAME must be the runtime type '" + Leaf.class.getName() + "', not 'java.lang.Object'; actual: "
               + className);
      }

      @Test
      @DisplayName("Object-typed field: objectId is present and positive")
      void objectTypedFieldCarriesObjectId() throws Exception {
         Map<Object, Object> holderEnv = cborToMap(toCbor(new ObjectHolder(new Leaf(7))));
         Map<Object, Object> payloadEnv = fieldEnvelope(holderEnv, "payload");

         assertNotNull(payloadEnv, "payload must be wrapped in an envelope");
         Object id = get(payloadEnv, FieldIds.OBJECT_ID);
         assertNotNull(id, "OBJECT_ID must be present on an Object-typed field");
         assertTrue(((Number) id).longValue() > 0, "OBJECT_ID must be a positive long");
      }

      // ── Test 6: same instance referenced twice → refId on second ──────
      //
      // The 'seen' IdentityHashMap is scoped to one top-level writeValue()
      // call.  The first occurrence of an object is fully serialised
      // (objectId + className + value).  Any subsequent occurrence within the
      // same call must be emitted as a cycle-reference node (refId + cycleRef)
      // whose refId equals the objectId from the first occurrence.

      @Test
      @DisplayName("second reference to same instance becomes a refId node pointing at the first")
      void sameInstanceReferencedTwiceProducesRefIdOnSecondOccurrence() throws Exception {
         // 'shared' will appear twice — once via a.next, once via b.next.
         Node shared = new Node("shared");
         Node a = new Node("A");
         Node b = new Node("B");
         a.next = shared;
         b.next = shared; // exact same JVM instance

         // Wrap in a Map so both a and b fall under one top-level writeValue(),
         // meaning the 'seen' set is shared across both branches.
         Map<String, Node> root = Map.of("a", a, "b", b);

         Map<Object, Object> rootEnv = cborToMap(toCbor(root));
         Map<Object, Object> rootValue = valueMap(rootEnv);

         @SuppressWarnings("unchecked")
         Map<Object, Object> aEnv = (Map<Object, Object>) rootValue.get("a");
         @SuppressWarnings("unchecked")
         Map<Object, Object> bEnv = (Map<Object, Object>) rootValue.get("b");

         Map<Object, Object> aNext = fieldEnvelope(aEnv, "next");
         Map<Object, Object> bNext = fieldEnvelope(bEnv, "next");

         boolean aIsRef = isCycleRef(aNext);
         boolean bIsRef = isCycleRef(bNext);

         // Exactly one branch must hold the full envelope; the other the ref node.
         assertTrue(
            aIsRef ^ bIsRef,
            "Exactly one occurrence of 'shared' must be a refId node " + "(aIsRef=" + aIsRef + ", bIsRef=" + bIsRef
               + ")");

         // fullEnv = whichever branch is NOT the ref node
         Map<Object, Object> fullEnv = !aIsRef ? aNext : bNext;
         // refNode = whichever branch IS the ref node
         Map<Object, Object> refNode = aIsRef ? aNext : bNext;

         assertEquals(
            objectId(fullEnv),
            refId(refNode),
            "refId in the back-reference node must equal the objectId of the "
               + "fully-serialised 'shared' instance — they represent the same object");
      }
   }

   // =========================================================================
   // Test 7 — ClassNameCache
   // =========================================================================

   @Nested
   @DisplayName("ClassNameCache")
   class ClassNameCacheTests {

      @Test
      @DisplayName("INSTANCE is a singleton — always the exact same object reference")
      void instanceIsSingleton() {
         // Static final field: two reads must return the same reference.
         assertSame(
            ClassNameCache.INSTANCE,
            ClassNameCache.INSTANCE,
            "ClassNameCache.INSTANCE must always be the same object reference");
      }

      @Test
      @DisplayName("returns the correct fully-qualified name for JDK types")
      void returnsCorrectNameForJdkTypes() {
         assertEquals("java.lang.String", ClassNameCache.INSTANCE.get(String.class));
         assertEquals("java.lang.Integer", ClassNameCache.INSTANCE.get(Integer.class));
         assertEquals("java.util.ArrayList", ClassNameCache.INSTANCE.get(java.util.ArrayList.class));
      }

      @Test
      @DisplayName("returns the correct binary name for inner test-fixture classes")
      void returnsCorrectNameForInnerClasses() {
         // Inner static classes have a '$'-separated binary name.
         assertEquals(Node.class.getName(), ClassNameCache.INSTANCE.get(Node.class));
         assertEquals(Leaf.class.getName(), ClassNameCache.INSTANCE.get(Leaf.class));
      }

      @Test
      @DisplayName("repeated calls for the same Class return the identical cached String instance")
      void repeatedCallsReturnSameStringInstance() {
         // ClassValue.get() caches the result of computeValue().
         // The same Class key must always return the *identical* String object,
         // not merely an equal one — proving Class.getName() is not called
         // on every lookup.
         String first = ClassNameCache.INSTANCE.get(Node.class);
         String second = ClassNameCache.INSTANCE.get(Node.class);
         String third = ClassNameCache.INSTANCE.get(Node.class);

         assertSame(
            first,
            second,
            "Second get() must return the same String instance as the first "
               + "(ClassValue must cache the result of computeValue())");
         assertSame(first, third, "Third get() must also return the same cached String instance");
      }

      @Test
      @DisplayName("different classes produce different cached names")
      void differentClassesGetDifferentCachedNames() {
         assertNotEquals(
            ClassNameCache.INSTANCE.get(Node.class),
            ClassNameCache.INSTANCE.get(Leaf.class),
            "Node and Leaf have different class names; the cache must not conflate them");
      }
   }

   // =========================================================================
   // Test 8 — Full round-trip: CBOR → deserialised Map → validate structure
   // =========================================================================

   @Nested
   @DisplayName("Full round-trip (CBOR serialised → deserialised → envelope structure correct)")
   class RoundTripTests {

      @Test
      @DisplayName("simple object: envelope contains objectId, className, and value with correct content")
      void simpleObjectEnvelopeIsComplete() throws Exception {
         Node node = new Node("hello");

         Map<Object, Object> env = cborToMap(toCbor(node));

         // --- OBJECT_ID ---
         Object idObj = get(env, FieldIds.OBJECT_ID);
         assertNotNull(idObj, "Envelope must contain OBJECT_ID (key 1)");
         assertTrue(((Number) idObj).longValue() > 0, "OBJECT_ID must be positive");

         // --- CLASS_NAME ---
         Object className = get(env, FieldIds.CLASS_NAME);
         assertNotNull(className, "Envelope must contain CLASS_NAME (key 2)");
         assertEquals(Node.class.getName(), className, "CLASS_NAME must be Node's fully-qualified binary name");

         // --- VALUE ---
         Object value = get(env, FieldIds.VALUE);
         assertNotNull(value, "Envelope must contain VALUE (key 3)");
         assertInstanceOf(Map.class, value, "VALUE must be a Map for a POJO");

         @SuppressWarnings("unchecked")
         Map<Object, Object> valueMap = (Map<Object, Object>) value;
         assertEquals(
            "hello",
            valueMap.get("label"),
            "VALUE Map must contain the 'label' field with the correct value");
      }

      @Test
      @DisplayName("objectId is stable: same instance serialised independently gives the same id each time")
      void sameInstanceSerializedIndependentlyGivesSameId() throws Exception {
         Node node = new Node("stable");

         long id1 = objectId(cborToMap(toCbor(node)));
         long id2 = objectId(cborToMap(toCbor(node)));

         assertEquals(
            id1,
            id2,
            "The same instance must produce the same objectId across " + "independent top-level serialisation calls");
      }

      @Test
      @DisplayName("two distinct instances produce different objectIds even with identical content")
      void twoDistinctInstancesProduceDifferentObjectIds() throws Exception {
         Node n1 = new Node("same-label");
         Node n2 = new Node("same-label"); // equal content, different instance

         long id1 = objectId(cborToMap(toCbor(n1)));
         long id2 = objectId(cborToMap(toCbor(n2)));

         assertNotEquals(
            id1,
            id2,
            "Distinct instances must receive different objectIds even when " + "their content is identical");
      }

      @Test
      @DisplayName("cyclic graph round-trip: refId in cycle node equals the root's objectId")
      void cyclicGraphRefIdMatchesRootObjectId() throws Exception {
         Node root = new Node("root");
         Node child = new Node("child");
         root.next = child;
         child.next = root; // child.next back-references root

         Map<Object, Object> rootEnv = cborToMap(toCbor(root));
         long rootId = objectId(rootEnv);

         Map<Object, Object> childEnv = fieldEnvelope(rootEnv, "next");
         Map<Object, Object> cycleNode = fieldEnvelope(childEnv, "next");

         assertTrue(isCycleRef(cycleNode), "child.next must be deserialised as a cycle-reference node");
         assertEquals(
            rootId,
            refId(cycleNode),
            "refId must equal root's objectId, correctly identifying the cycle destination");
      }

      @Test
      @DisplayName("nested object: child envelope carries its own distinct objectId and correct className")
      void nestedObjectHasOwnEnvelope() throws Exception {
         Node parent = new Node("parent");
         Node child = new Node("child");
         parent.next = child;

         Map<Object, Object> parentEnv = cborToMap(toCbor(parent));
         Map<Object, Object> childEnv = fieldEnvelope(parentEnv, "next");

         assertNotEquals(objectId(parentEnv), objectId(childEnv), "Parent and child must have different objectIds");
         assertEquals(
            Node.class.getName(),
            get(childEnv, FieldIds.CLASS_NAME),
            "Child envelope must carry the correct className");
      }

      @Test
      @DisplayName("CBOR output is non-empty and deserialises without error")
      void cborOutputIsNonEmptyAndDecodable() throws Exception {
         byte[] cbor = toCbor(new Node("decodable"));

         assertTrue(cbor.length > 0, "CBOR output must not be empty");
         assertFalse(cborToMap(cbor).isEmpty(), "Decoded Map must contain at least the envelope keys");
      }
   }
}
