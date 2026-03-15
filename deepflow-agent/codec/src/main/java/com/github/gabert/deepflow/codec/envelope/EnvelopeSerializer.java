package com.github.gabert.deepflow.codec.envelope;

import java.io.IOException;
import java.util.IdentityHashMap;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.ContextualSerializer;
import com.fasterxml.jackson.databind.ser.ResolvableSerializer;

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
