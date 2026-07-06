package com.github.gabert.arachna.trace.codec.envelope;

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
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import com.github.gabert.arachna.trace.spi.jpaproxy.JpaProxyResolver;

/**
 * JUnit 5 tests for {@link ObjectIdRegistry}, {@link ClassNameCache}, and {@link EnvelopeSerializer}.
 *
 * <h2>Package access</h2> All production classes under test are package-private. These tests live in the same package
 * ({@code com.github.gabert.arachna.trace.codec.envelope}) so no reflection is needed to access them — except for the one
 * test that deliberately probes a private field of {@link ObjectIdRegistry.IdentityWeakRef} to simulate a hash collision.
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

   private static final ObjectMapper CBOR_MAPPER;

   static {
      CBOR_MAPPER = new ObjectMapper(new CBORFactory());
      CBOR_MAPPER.registerModule(new EnvelopeModule());
   }

   /** Serialise {@code value} with the envelope CBOR mapper. */
   private static byte[] toCbor(Object value) throws Exception {
      return CBOR_MAPPER.writeValueAsBytes(value);
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

      @Test
      @DisplayName("direct cycle A → B → A does not throw StackOverflowError")
      void directCycleDoesNotCauseStackOverflow() {
         Node a = new Node("A");
         Node b = new Node("B");
         a.next = b;
         b.next = a;

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
         c.next = a;

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

      @Test
      @DisplayName("refId in the cycle node equals the objectId of the already-seen object")
      void refIdMatchesObjectIdOfAlreadySeenObject() throws Exception {
         Node a = new Node("A");
         Node b = new Node("B");
         a.next = b;
         b.next = a;

         Map<Object, Object> aEnv = cborToMap(toCbor(a));
         long aObjectId = objectId(aEnv);

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
         c.next = a;

         Map<Object, Object> aEnv = cborToMap(toCbor(a));
         long aId = objectId(aEnv);

         Map<Object, Object> bEnv = fieldEnvelope(aEnv, "next");
         Map<Object, Object> cEnv = fieldEnvelope(bEnv, "next");
         Map<Object, Object> cycleNode = fieldEnvelope(cEnv, "next");

         assertEquals(aId, refId(cycleNode), "In a three-node cycle the refId must point back to the root");
      }

      // Note: this test only proves the OUTER envelope is shaped correctly
      // (CLASS_NAME is set from value.getClass() before delegation). It does
      // NOT prove the inner POJO content survives the re-resolved delegate
      // call — that is the job of objectTypedFieldRoundTripsInnerContent
      // below (VERIFY-1). Keep both: a regression in the outer-envelope path
      // and a regression in the inner-delegate path would surface separately.
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

      // VERIFY-1: this test catches a class of regression where the runtime
      // re-resolution path in EnvelopeSerializer correctly *names* the runtime
      // type but fails to *serialize its content*. The other two object-typed
      // tests only assert OBJECT_ID/CLASS_NAME on the outer envelope — those
      // are set unconditionally from value.getClass() regardless of which
      // delegate runs, so a buggy inner delegate would not break them. Only
      // round-tripping the inner POJO field ("value": 42) proves that the
      // re-resolved delegate is the un-wrapped POJO serializer, not a re-entry
      // into EnvelopeSerializer (which would emit a CYCLE_REF because the
      // value is already in the seen-set).
      @Test
      @DisplayName("Object-typed field: inner POJO content is preserved (round-trip)")
      @SuppressWarnings("unchecked")
      void objectTypedFieldRoundTripsInnerContent() throws Exception {
         Map<Object, Object> holderEnv = cborToMap(toCbor(new ObjectHolder(new Leaf(42))));
         Map<Object, Object> payloadEnv = fieldEnvelope(holderEnv, "payload");

         Object payloadValue = get(payloadEnv, FieldIds.VALUE);
         assertNotNull(payloadValue, "payload envelope must carry a VALUE entry");
         assertFalse(payloadEnv.containsKey(FieldIds.CYCLE_REF)
                  && payloadEnv.containsKey(String.valueOf(FieldIds.CYCLE_REF)),
               "payload must not collapse to a CYCLE_REF — that would indicate "
                  + "the runtime-resolved delegate re-entered EnvelopeSerializer "
                  + "and saw the value already in the seen-set");
         assertInstanceOf(Map.class, payloadValue,
               "Leaf is a POJO, so its VALUE must be a Map of fields — "
                  + "if VALUE is a Map carrying REF_ID+CYCLE_REF the delegate re-entered");
         Map<Object, Object> leafFields = (Map<Object, Object>) payloadValue;
         assertFalse(leafFields.containsKey(FieldIds.REF_ID)
                  || leafFields.containsKey(String.valueOf(FieldIds.REF_ID)),
               "VALUE must contain the Leaf's user fields, not a cycle ref");
         assertEquals(42, ((Number) leafFields.get("value")).intValue(),
               "Leaf.value must round-trip — the runtime-resolved delegate "
                  + "must actually serialize the POJO fields");
      }

      @Test
      @DisplayName("second reference to same instance becomes a refId node pointing at the first")
      void sameInstanceReferencedTwiceProducesRefIdOnSecondOccurrence() throws Exception {
         Node shared = new Node("shared");
         Node a = new Node("A");
         Node b = new Node("B");
         a.next = shared;
         b.next = shared;

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

         assertTrue(
            aIsRef ^ bIsRef,
            "Exactly one occurrence of 'shared' must be a refId node " + "(aIsRef=" + aIsRef + ", bIsRef=" + bIsRef
               + ")");

         Map<Object, Object> fullEnv = !aIsRef ? aNext : bNext;
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
         assertEquals(Node.class.getName(), ClassNameCache.INSTANCE.get(Node.class));
         assertEquals(Leaf.class.getName(), ClassNameCache.INSTANCE.get(Leaf.class));
      }

      @Test
      @DisplayName("repeated calls for the same Class return the identical cached String instance")
      void repeatedCallsReturnSameStringInstance() {
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
   // Test 8 — Full round-trip
   // =========================================================================

   @Nested
   @DisplayName("Full round-trip (CBOR serialised → deserialised → envelope structure correct)")
   class RoundTripTests {

      @Test
      @DisplayName("simple object: envelope contains objectId, className, and value with correct content")
      void simpleObjectEnvelopeIsComplete() throws Exception {
         Node node = new Node("hello");

         Map<Object, Object> env = cborToMap(toCbor(node));

         Object idObj = get(env, FieldIds.OBJECT_ID);
         assertNotNull(idObj, "Envelope must contain OBJECT_ID (key 1)");
         assertTrue(((Number) idObj).longValue() > 0, "OBJECT_ID must be positive");

         Object className = get(env, FieldIds.CLASS_NAME);
         assertNotNull(className, "Envelope must contain CLASS_NAME (key 2)");
         assertEquals(Node.class.getName(), className, "CLASS_NAME must be Node's fully-qualified binary name");

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
         Node n2 = new Node("same-label");

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
         child.next = root;

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

   // =========================================================================
   // Proxy handling — JPA resolver path AND <proxy> marker fallback
   // =========================================================================

   /** Marker interface for the JDK Proxy fixture below. */
   public interface Greeter {

      String greet();
   }

   /**
    * Test JpaProxyResolver fixture. Substitutes any Node with the same label as the trigger label to a fixed
    * replacement Node; returns null for everything else.
    *
    * <p>Why same-type substitution? Realistic JPA proxies are subclasses of the real entity (HibernateProxy extends the
    * mapped type), so the resolved value is type-compatible with the original delegate. Cross-type substitution would
    * stress the runtime re-resolution branch, but only fires when the field is Object-typed AND Jackson hasn't
    * contextualized the delegate to a more specific runtime type — fragile to test reliably. This fixture stays in the
    * type-safe lane that matches production usage.
    */
   static class TriggeredNodeResolver implements JpaProxyResolver {

      private final String triggerLabel;

      private final Node replacement;

      private int callCount;

      TriggeredNodeResolver(String triggerLabel, Node replacement) {
         this.triggerLabel = triggerLabel;
         this.replacement = replacement;
      }

      @Override
      public String name() {
         return "triggered-node";
      }

      @Override
      public Object resolve(Object proxy) {
         callCount++;
         if (proxy instanceof Node n && triggerLabel.equals(n.label)) {
            return replacement;
         }
         return null;
      }

      int callCount() {
         return callCount;
      }
   }

   @Nested
   @DisplayName("JPA proxy resolver branch")
   class JpaProxyResolverTests {

      // The resolver is global state in JpaProxyResolvers. Each test that touches it must
      // clear it in @AfterEach so subsequent unrelated tests aren't affected.
      @AfterEach
      void clearResolver() {
         JpaProxyResolvers.setActive(null);
      }

      @Test
      @DisplayName("resolver-returned value replaces the proxy in the serialized envelope")
      @SuppressWarnings("unchecked")
      void resolverNonNullSubstitutesValue() throws Exception {
         // Trigger label "proxy" → replacement Node{label: "real"}. Top-level
         // Node serialization with the resolver wired: the resolver fires on
         // the trigger, substitutes the replacement, and the replacement's
         // label is what lands in the envelope's VALUE.
         Node replacement = new Node("real");
         TriggeredNodeResolver resolver = new TriggeredNodeResolver("proxy", replacement);
         JpaProxyResolvers.setActive(resolver);

         Map<Object, Object> env = cborToMap(toCbor(new Node("proxy")));
         Map<Object, Object> fields = (Map<Object, Object>) get(env, FieldIds.VALUE);

         assertEquals("real", fields.get("label"),
            "label must be the replacement's, not the original Node's — "
               + "proves the resolved object is the one that got serialized");
         assertTrue(resolver.callCount() >= 1,
            "resolver.resolve() must have been called on the trigger Node");
      }

      @Test
      @DisplayName("resolver returning null falls through to normal serialization (no <proxy> marker)")
      void resolverReturnsNullFallsThroughToNormal() throws Exception {
         // A resolver that always returns null must NOT prevent normal
         // serialization of a non-proxy object. The Node should serialize as
         // a regular POJO envelope, not a <proxy> marker.
         JpaProxyResolvers.setActive(new JpaProxyResolver() {

            @Override
            public String name() {
               return "always-null";
            }

            @Override
            public Object resolve(Object proxy) {
               return null;
            }
         });

         Map<Object, Object> env = cborToMap(toCbor(new Node("real")));
         Object value = get(env, FieldIds.VALUE);
         assertInstanceOf(Map.class, value,
            "VALUE must be a POJO field map, not the <proxy> marker string");
      }
   }

   @Nested
   @DisplayName("Proxy fallback — <proxy> marker emitted when no resolver handles the type")
   class ProxyMarkerTests {

      @AfterEach
      void clearResolver() {
         JpaProxyResolvers.setActive(null);
      }

      @Test
      @DisplayName("JDK dynamic proxy: VALUE is the <proxy> sentinel string")
      void jdkProxyEmitsProxyMarker() throws Exception {
         Greeter dyn = (Greeter) Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] { Greeter.class },
            (InvocationHandler) (proxy, method, args) -> "hi");
         // No JpaProxyResolver registered → the isProxy() fallback must fire.

         Map<Object, Object> env = cborToMap(toCbor(dyn));
         Object value = get(env, FieldIds.VALUE);
         assertEquals("<proxy>",
            value,
            "JDK Proxy.newProxyInstance(...) must trigger the <proxy> "
               + "marker emission path");
      }

      @Test
      @DisplayName("JDK dynamic proxy: CLASS_NAME is the proxy's superclass (java.lang.reflect.Proxy)")
      void jdkProxyClassNameComesFromSuperclass() throws Exception {
         // The marker emission uses value.getClass().getSuperclass(). For a
         // JDK Proxy.newProxyInstance(...) the synthetic class extends
         // java.lang.reflect.Proxy directly, so that's what's emitted —
         // human-readable instead of the synthetic "$Proxy42" name.
         Greeter dyn = (Greeter) Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] { Greeter.class },
            (InvocationHandler) (proxy, method, args) -> "hi");

         Map<Object, Object> env = cborToMap(toCbor(dyn));
         assertEquals("java.lang.reflect.Proxy",
            get(env, FieldIds.CLASS_NAME),
            "CLASS_NAME for a JDK proxy uses the superclass — java.lang.reflect.Proxy — not the synthetic $Proxy<n> name");
      }
   }

   // =========================================================================
   // ClassNameCache edge cases — arrays and primitives
   // =========================================================================

   @Nested
   @DisplayName("ClassNameCache — arrays and primitives")
   class ClassNameCacheEdgeCases {

      @Test
      @DisplayName("array of primitives: returns 'int[]' (component-name + '[]')")
      void primitiveArrayReturnsComponentNamePlusBrackets() {
         assertEquals("int[]", ClassNameCache.INSTANCE.get(int[].class));
         assertEquals("byte[]", ClassNameCache.INSTANCE.get(byte[].class));
      }

      @Test
      @DisplayName("array of reference types: returns 'java.lang.String[]'")
      void referenceArrayReturnsComponentNamePlusBrackets() {
         assertEquals("java.lang.String[]", ClassNameCache.INSTANCE.get(String[].class));
         assertEquals(Node.class.getName() + "[]", ClassNameCache.INSTANCE.get(Node[].class));
      }

      @Test
      @DisplayName("multi-dimensional array: brackets stack correctly")
      void multidimensionalArrayBracketsStack() {
         assertEquals("java.lang.String[][]", ClassNameCache.INSTANCE.get(String[][].class));
         assertEquals("int[][][]", ClassNameCache.INSTANCE.get(int[][][].class));
      }

      @Test
      @DisplayName("primitive Class objects: returns the Java keyword name")
      void primitiveClassReturnsKeyword() {
         // Class.getName() returns "int" for int.class — not "java.lang.Integer".
         // The cache must propagate that without adding decoration.
         assertEquals("int", ClassNameCache.INSTANCE.get(int.class));
         assertEquals("void", ClassNameCache.INSTANCE.get(void.class));
      }
   }

   // =========================================================================
   // EnvelopeModifier — wrap/skip contract for primitives, Strings, enums, …
   // =========================================================================

   enum Color {
      RED,
      BLUE
   }

   /** Holder used to verify which field types get wrapped and which pass through. */
   static class Mixed {

      public String s = "hi";

      public int i = 7;

      public Integer boxedI = 7;

      public Color c = Color.RED;

      public Node n = new Node("inner");
   }

   @Nested
   @DisplayName("EnvelopeModifier — wrap/skip contract")
   class EnvelopeModifierTests {

      // Scalars (primitives, String, Number subclasses, Boolean, Character,
      // enums) must NOT be wrapped in envelopes — they pass through as raw
      // values. POJOs must be wrapped. This test asserts both rules on a
      // single Mixed bag so a regression in shouldWrap() is caught here.
      @Test
      @SuppressWarnings("unchecked")
      @DisplayName("primitives, String, Number, enum pass through; POJO fields are wrapped")
      void scalarsArePassedThroughAndPojosAreWrapped() throws Exception {
         Map<Object, Object> outer = cborToMap(toCbor(new Mixed()));
         Map<Object, Object> fields = (Map<Object, Object>) get(outer, FieldIds.VALUE);

         assertEquals("hi", fields.get("s"),
            "String must not be wrapped — value sits directly under the field");
         assertEquals(7, ((Number) fields.get("i")).intValue(),
            "Primitive int must not be wrapped");
         assertEquals(7, ((Number) fields.get("boxedI")).intValue(),
            "Boxed Number subclass must not be wrapped");
         assertEquals("RED", fields.get("c"),
            "Enum must not be wrapped — Jackson serializes the name string directly");

         assertInstanceOf(Map.class, fields.get("n"),
            "POJO field must be wrapped — value is an envelope Map");
         Map<Object, Object> nodeEnv = (Map<Object, Object>) fields.get("n");
         assertNotNull(get(nodeEnv, FieldIds.OBJECT_ID),
            "Wrapped POJO field must carry an OBJECT_ID");
      }
   }
}
