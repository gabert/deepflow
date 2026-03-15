package com.github.gabert.deepflow.codec;

import java.io.IOException;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.IdentityHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import com.fasterxml.jackson.databind.ser.ContextualSerializer;
import com.fasterxml.jackson.databind.ser.ResolvableSerializer;
import com.fasterxml.jackson.databind.type.ArrayType;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.fasterxml.jackson.databind.type.MapType;

// ─────────────────────────────────────────────────────────────
// FieldIds
// Integer keys for CBOR fields.
// Avoids writing string field names on every object — saves
// space and improves write speed in binary payloads.
// The deserializer must use the same constants to decode.
// ─────────────────────────────────────────────────────────────
final class FieldIds {

   static final int OBJECT_ID = 1; // stable unique id of this object instance
   static final int CLASS_NAME = 2; // runtime class name
   static final int VALUE = 3; // the serialized object content
   static final int REF_ID = 4; // cycle back-reference: id of already-seen object
   static final int CYCLE_REF = 5; // boolean flag marking this node as a cycle reference

   private FieldIds() {
   }
}

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
final class ObjectIdRegistry {

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
   static final class IdentityWeakRef extends WeakReference<Object> {

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

// ─────────────────────────────────────────────────────────────
// ClassNameCache
//
// Class.getName() allocates a new String on every call.
// On a hot serialization path with thousands of objects this
// adds up. ClassValue stores one String per Class, computed
// once, with zero lock contention on reads.
// ─────────────────────────────────────────────────────────────
final class ClassNameCache extends ClassValue<String> {

   static final ClassNameCache INSTANCE = new ClassNameCache();

   private ClassNameCache() {
   }

   @Override
   protected String computeValue(Class<?> c) {
      return c.getName();
   }
}

// ─────────────────────────────────────────────────────────────
// EnvelopeModule
//
// Jackson SimpleModule that installs EnvelopeModifier into the
// ObjectMapper. Register this module on any ObjectMapper
// (JSON or CBOR) to enable envelope wrapping.
// ─────────────────────────────────────────────────────────────
final class EnvelopeModule extends SimpleModule {

   public EnvelopeModule() {
      super("EnvelopeModule");
   }

   @Override
   public void setupModule(SetupContext context) {
      super.setupModule(context);
      context.addBeanSerializerModifier(new EnvelopeModifier());
   }
}

// ─────────────────────────────────────────────────────────────
// EnvelopeModifier
//
// BeanSerializerModifier intercepts serializer selection for
// every type Jackson encounters. For each eligible type it
// wraps the default serializer in an EnvelopeSerializer.
//
// All four modifier methods are overridden so that POJOs,
// Maps, Collections, and arrays are all wrapped — method
// arguments can be any of these types.
//
// Note: Object.class exclusion has been intentionally removed.
// Previously excluded to avoid infinite dispatch loops, but
// the correct fix is to re-resolve by runtime type inside
// EnvelopeSerializer.serialize() when the declared type is
// Object. This ensures that Object-typed method arguments
// (common in generic code) still get their className and
// objectId captured correctly.
// ─────────────────────────────────────────────────────────────
final class EnvelopeModifier extends BeanSerializerModifier {

   @Override
   public JsonSerializer<?> modifySerializer(SerializationConfig config,
                                             BeanDescription beanDesc,
                                             JsonSerializer<?> serializer) {
      return maybeWrap(beanDesc.getType(), serializer);
   }

   @Override
   public JsonSerializer<?> modifyMapSerializer(SerializationConfig config,
                                                MapType type,
                                                BeanDescription beanDesc,
                                                JsonSerializer<?> serializer) {
      return maybeWrap(type, serializer);
   }

   @Override
   public JsonSerializer<?> modifyCollectionSerializer(SerializationConfig config,
                                                       CollectionType type,
                                                       BeanDescription beanDesc,
                                                       JsonSerializer<?> serializer) {
      return maybeWrap(type, serializer);
   }

   @Override
   public JsonSerializer<?> modifyArraySerializer(SerializationConfig config,
                                                  ArrayType valueType,
                                                  BeanDescription beanDesc,
                                                  JsonSerializer<?> serializer) {
      return maybeWrap(valueType, serializer);
   }

   private JsonSerializer<?> maybeWrap(JavaType type, JsonSerializer<?> serializer) {
      // Already wrapped — do not double-wrap
      if (serializer instanceof EnvelopeSerializer)
         return serializer;

      if (!shouldWrap(type.getRawClass()))
         return serializer;

      return new EnvelopeSerializer(serializer);
   }

   private boolean shouldWrap(Class<?> c) {
      if (c.isPrimitive())
         return false;
      if (c == String.class)
         return false;
      if (Number.class.isAssignableFrom(c))
         return false;
      if (c == Boolean.class || c == Character.class)
         return false;
      if (c.isEnum())
         return false;
      if (c.getName().startsWith("com.fasterxml.jackson."))
         return false;
      return true; // wrap POJOs, Maps, Collections, arrays, Object
   }
}

// ─────────────────────────────────────────────────────────────
// EnvelopeSerializer
//
// Wraps every eligible object in a tracing envelope:
//
//   Normal object:
//   {
//     OBJECT_ID:  <stable unique long>,
//     CLASS_NAME: <runtime class name>,
//     VALUE:      <serialized object content>
//   }
//
//   Cycle back-reference (object already being serialized):
//   {
//     REF_ID:    <id of the already-seen object>,
//     CYCLE_REF: true
//   }
//
// Three responsibilities:
//
//   1. Object identity
//      ObjectIdRegistry.idOf() returns a stable unique long
//      for each object instance. The same instance always gets
//      the same id for its entire lifetime. Different instances
//      always get different ids even if their identityHashCode
//      collides.
//
//   2. Cycle detection
//      An IdentityHashMap<Object, Long> "seen" set is stored in
//      SerializerProvider attributes for the duration of one
//      top-level serialization call. If an object is encountered
//      a second time (cycle), a REF_ID node is emitted instead
//      of recursing — preventing StackOverflowError.
//
//   3. Runtime type resolution for Object-typed fields
//      When the declared type is Object (e.g. Map<String,Object>
//      values, or method arguments declared as Object), the
//      delegate serializer resolves to a generic Object handler
//      that loses type information. We detect this and re-resolve
//      by the actual runtime class so that className and objectId
//      are always captured correctly.
// ─────────────────────────────────────────────────────────────
final class EnvelopeSerializer extends JsonSerializer<Object> implements ContextualSerializer, ResolvableSerializer {

   // Delegate: the original Jackson serializer for this type.
   // We call it to produce the VALUE portion of the envelope.
   // Suppression is safe: EnvelopeModifier always passes a
   // serializer that was resolved for the declared type, so
   // the unchecked cast is correct at runtime.
   @SuppressWarnings("unchecked")
   private final JsonSerializer<Object> delegate;

   @SuppressWarnings("unchecked")
   EnvelopeSerializer(JsonSerializer<?> delegate) {
      this.delegate = (JsonSerializer<Object>) delegate;
   }

   // ResolvableSerializer: must be forwarded so that delegate
   // serializers that rely on resolve() (e.g. BeanSerializer)
   // are fully initialized. Skipping this causes NPEs on
   // complex object graphs.
   @Override
   public void resolve(SerializerProvider provider) throws JsonMappingException {
      if (delegate instanceof ResolvableSerializer rs) {
         rs.resolve(provider);
      }
   }

   // ContextualSerializer: must be forwarded so that
   // annotations on fields (@JsonInclude, @JsonView, etc.)
   // are applied to the delegate before it is used.
   @Override
   public JsonSerializer<?> createContextual(SerializerProvider prov,
                                             BeanProperty property) throws JsonMappingException {
      JsonSerializer<?> del = prov.handlePrimaryContextualization(delegate, property);
      if (del instanceof EnvelopeSerializer)
         return del;
      return new EnvelopeSerializer(del);
   }

   @Override
   public void serialize(Object value, JsonGenerator gen, SerializerProvider serializers) throws IOException {

      if (value == null) {
         gen.writeNull();
         return;
      }

      // ── Cycle detection ───────────────────────────────────
      // Retrieve or create the seen-set for this serialization.
      // SerializerProvider attributes are scoped to one top-level
      // writeValue() call, so the seen-set is automatically fresh
      // for each captured method call.
      @SuppressWarnings("unchecked")
      IdentityHashMap<Object, Long> seen = (IdentityHashMap<Object, Long>) serializers.getAttribute("seen");
      if (seen == null) {
         seen = new IdentityHashMap<>();
         serializers.setAttribute("seen", seen);
      }

      long id = ObjectIdRegistry.idOf(value);

      if (seen.containsKey(value)) {
         // This object is already being serialized higher up in the
         // call stack — emit a back-reference instead of recursing.
         gen.writeStartObject();
         gen.writeFieldId(FieldIds.REF_ID);
         gen.writeNumber(id);
         gen.writeFieldId(FieldIds.CYCLE_REF);
         gen.writeBoolean(true);
         gen.writeEndObject();
         return;
      }

      seen.put(value, id);

      // ── Runtime type resolution ───────────────────────────
      // If the delegate was resolved for Object.class (happens
      // when the declared type is Object, e.g. in Map<String,Object>
      // or Object[] method arguments), re-resolve by the actual
      // runtime class. This ensures className and objectId are
      // always correct regardless of how the field was declared.
      JsonSerializer<Object> resolvedDelegate = delegate;
      Class<?> handledType = delegate.handledType();
      if (handledType == null || handledType == Object.class) {
         @SuppressWarnings("unchecked")
         JsonSerializer<Object> runtimeSerializer =
               (JsonSerializer<Object>) serializers.findValueSerializer(value.getClass());
         resolvedDelegate = runtimeSerializer;
      }

      // ── Emit envelope ─────────────────────────────────────
      gen.writeStartObject();
      gen.writeFieldId(FieldIds.OBJECT_ID);
      gen.writeNumber(id);
      gen.writeFieldId(FieldIds.CLASS_NAME);
      gen.writeString(ClassNameCache.INSTANCE.get(value.getClass()));
      gen.writeFieldId(FieldIds.VALUE);
      resolvedDelegate.serialize(value, gen, serializers);
      gen.writeEndObject();
   }
}
