package com.github.gabert.deepflow.codec;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import com.github.gabert.deepflow.codec.envelope.EnvelopeModule;
import com.github.gabert.deepflow.codec.envelope.FieldIds;

public final class Codec {

   private static final ObjectMapper CBOR_ENCODER;
   private static final ObjectMapper CBOR_DECODER;
   private static final ObjectMapper JSON_MAPPER;

   static {
      CBOR_ENCODER = new ObjectMapper(new CBORFactory());
      CBOR_ENCODER.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
      CBOR_ENCODER.registerModule(new EnvelopeModule());

      CBOR_DECODER = new ObjectMapper(new CBORFactory());

      JSON_MAPPER = new ObjectMapper(new JsonFactory());
      JSON_MAPPER.enable(SerializationFeature.INDENT_OUTPUT);
   }

   private Codec() {
   }

   /**
    * Encode a Java object to CBOR bytes with envelope wrapping
    * (object identity, cycle detection, runtime type capture).
    */
   public static byte[] encode(Object value) throws IOException {
      return CBOR_ENCODER.writeValueAsBytes(value);
   }

   /**
    * Decode CBOR envelope bytes to a Java object structure.
    * Returns nested Maps/Lists with integer keys matching {@link FieldIds} constants.
    */
   public static Object decode(byte[] cbor) throws IOException {
      return CBOR_DECODER.readValue(new ByteArrayInputStream(cbor), Object.class);
   }

   /**
    * Convert a decoded Java object to a human-readable JSON string,
    * replacing envelope integer keys with their field names.
    */
   public static String toReadableJson(Object decoded) throws IOException {
      return JSON_MAPPER.writeValueAsString(humanize(decoded));
   }

   @SuppressWarnings("unchecked")
   private static Object humanize(Object obj) {
      if (obj instanceof Map<?, ?> map) {
         LinkedHashMap<String, Object> result = new LinkedHashMap<>();
         for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = resolveKey(entry.getKey());
            result.put(key, humanize(entry.getValue()));
         }
         return result;
      }
      if (obj instanceof List<?> list) {
         List<Object> result = new ArrayList<>(list.size());
         for (Object item : list) {
            result.add(humanize(item));
         }
         return result;
      }
      return obj;
   }

   private static final Map<String, String> FIELD_NAMES = Map.of(
           String.valueOf(FieldIds.OBJECT_ID), "object_id",
           String.valueOf(FieldIds.CLASS_NAME), "class",
           String.valueOf(FieldIds.VALUE), "value",
           String.valueOf(FieldIds.REF_ID), "ref_id",
           String.valueOf(FieldIds.CYCLE_REF), "cycle_ref"
   );

   private static String resolveKey(Object key) {
      String keyStr = String.valueOf(key);
      String name = FIELD_NAMES.get(keyStr);
      return name != null ? name : keyStr;
   }
}
