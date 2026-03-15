package com.github.gabert.deepflow.codec.envelope;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import com.fasterxml.jackson.databind.type.ArrayType;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.fasterxml.jackson.databind.type.MapType;

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
