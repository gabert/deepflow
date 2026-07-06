package com.github.gabert.arachna.trace.codec.envelope;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.IdentityHashMap;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.ContextualSerializer;
import com.fasterxml.jackson.databind.ser.ResolvableSerializer;
import com.github.gabert.arachna.trace.spi.jpaproxy.JpaProxyResolver;

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

   // ── Cycle-detection scope ───────────────────────────────
   // One seen-map per thread, scoped by envelope-nesting depth: the
   // outermost envelope node opens the scope, every nested envelope node
   // shares the map, and the map is emptied when the outermost node
   // completes (or unwinds exceptionally). Equivalent to the previous
   // SerializerProvider-attribute scoping — every envelope node in one
   // top-level encode sits under a single outermost envelope node, since
   // unwrapped types (String, Number, Boolean, Character, enums) have no
   // children — but without a per-node attribute-map lookup.
   //
   // Long-running-agent memory discipline: after an unusually large object
   // graph, the map is discarded instead of cleared so a one-off huge
   // serialization does not pin peak table capacity on the thread forever.
   private static final int SEEN_TRIM_THRESHOLD = 4096;

   private static final ThreadLocal<SeenScope> SEEN = ThreadLocal.withInitial(SeenScope::new);

   private static final class SeenScope {
      private IdentityHashMap<Object, Long> map = new IdentityHashMap<>();
      private int depth;

      IdentityHashMap<Object, Long> enter() {
         depth++;
         return map;
      }

      void exit() {
         if (--depth == 0) {
            if (map.size() > SEEN_TRIM_THRESHOLD) {
               map = new IdentityHashMap<>();
            } else {
               map.clear();
            }
         }
      }
   }

   @Override
   public void serialize(Object value, JsonGenerator gen, SerializerProvider serializers) throws IOException {

      if (value == null) {
         gen.writeNull();
         return;
      }

      SeenScope scope = SEEN.get();
      IdentityHashMap<Object, Long> seen = scope.enter();
      try {
         serializeTracked(value, gen, serializers, seen);
      } finally {
         scope.exit();
      }
   }

   private void serializeTracked(Object value, JsonGenerator gen, SerializerProvider serializers,
                                 IdentityHashMap<Object, Long> seen) throws IOException {

      long id = ObjectIdRegistry.idOf(value);

      // Single map operation instead of containsKey + put: put returns the
      // previous mapping, which is non-null exactly when the object is
      // already being serialized higher up. Re-putting the same id for a
      // repeated object is a no-op semantically — idOf is stable per
      // instance — so the emitted bytes are identical to the two-step form.
      if (seen.put(value, id) != null) {
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

      // ── JPA proxy / wrapper resolution ──────────────────
      // If a JpaProxyResolver is configured, give it first shot at
      // unwrapping the object. This handles both entity proxies
      // (HibernateProxy) and collection wrappers (PersistentBag).
      JpaProxyResolver resolver = JpaProxyResolvers.active();
      if (resolver != null) {
         Object resolved = resolver.resolve(value);
         if (resolved != null) {
            value = resolved;
            seen.put(value, id);
         }
      }

      // ── Proxy fallback ─────────────────────────────────
      // If still a proxy after resolution (resolver absent, returned
      // null, or doesn't handle this proxy type), emit <proxy> marker.
      if (isProxy(value.getClass())) {
         emitProxyMarker(value, id, gen);
         return;
      }

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

   private static void emitProxyMarker(Object value, long id, JsonGenerator gen) throws IOException {
      gen.writeStartObject();
      gen.writeFieldId(FieldIds.OBJECT_ID);
      gen.writeNumber(id);
      gen.writeFieldId(FieldIds.CLASS_NAME);
      gen.writeString(ClassNameCache.INSTANCE.get(value.getClass().getSuperclass()));
      gen.writeFieldId(FieldIds.VALUE);
      gen.writeString("<proxy>");
      gen.writeEndObject();
   }

   // Bytecode-generated proxies carry synthetic name markers: CGLIB/ByteBuddy
   // use a double-dollar segment ("$$EnhancerByCGLIB$$", "$$SpringCGLIB$$"),
   // Hibernate's ByteBuddy proxies use "$HibernateProxy$". A plain nested
   // class that extends its enclosing class (Foo$Sub extends Foo) has a
   // single-dollar name and must NOT be treated as a proxy.
   //
   // Proxy-ness is a property of the Class, so the string scans run once
   // per class via ClassValue instead of once per serialized node; entries
   // are released when the class is unloaded.
   private static final ClassValue<Boolean> PROXY_CLASS = new ClassValue<>() {
      @Override
      protected Boolean computeValue(Class<?> cls) {
         if (Proxy.isProxyClass(cls)) return Boolean.TRUE;
         String name = cls.getName();
         return name.contains("$$") || name.contains("$HibernateProxy$");
      }
   };

   private static boolean isProxy(Class<?> cls) {
      return PROXY_CLASS.get(cls);
   }
}
