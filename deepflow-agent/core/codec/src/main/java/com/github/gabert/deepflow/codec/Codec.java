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
import com.github.gabert.deepflow.jpaproxy.JpaProxyResolver;

public final class Codec {

   private static volatile JpaProxyResolver jpaProxyResolver;
   private static final ObjectMapper CBOR_ENCODER;
   private static final ObjectMapper CBOR_DECODER;
   private static final ObjectMapper JSON_MAPPER;

   static {
      CBOR_ENCODER = new ObjectMapper(new CBORFactory());
      CBOR_ENCODER.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
      CBOR_ENCODER.registerModule(new EnvelopeModule());

      CBOR_DECODER = new ObjectMapper(new CBORFactory());

      JSON_MAPPER = new ObjectMapper(new JsonFactory());
   }

   private Codec() {
   }

   public static void setJpaProxyResolver(JpaProxyResolver resolver) {
      jpaProxyResolver = resolver;
   }

   public static JpaProxyResolver getJpaProxyResolver() {
      return jpaProxyResolver;
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
         return humanizeMap(map);
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

   private static Object humanizeMap(Map<?, ?> map) {
      String objectIdKey = String.valueOf(FieldIds.OBJECT_ID);
      String classKey = String.valueOf(FieldIds.CLASS_NAME);
      String valueKey = String.valueOf(FieldIds.VALUE);
      String refIdKey = String.valueOf(FieldIds.REF_ID);
      String cycleRefKey = String.valueOf(FieldIds.CYCLE_REF);

      // Cycle back-reference — pass through with readable keys
      if (map.containsKey(Integer.valueOf(FieldIds.REF_ID))
              || map.containsKey(refIdKey)) {
         LinkedHashMap<String, Object> result = new LinkedHashMap<>();
         for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (key.equals(String.valueOf(FieldIds.REF_ID)) || key.equals(refIdKey))
               result.put("ref_id", entry.getValue());
            else if (key.equals(String.valueOf(FieldIds.CYCLE_REF)) || key.equals(cycleRefKey))
               result.put("cycle_ref", entry.getValue());
            else
               result.put(key, humanize(entry.getValue()));
         }
         return result;
      }

      // Envelope: flatten object_id/class inline with the value fields
      Object rawObjectId = map.get(Integer.valueOf(FieldIds.OBJECT_ID));
      if (rawObjectId == null) rawObjectId = map.get(objectIdKey);
      Object rawClassName = map.get(Integer.valueOf(FieldIds.CLASS_NAME));
      if (rawClassName == null) rawClassName = map.get(classKey);
      Object rawValue = map.get(Integer.valueOf(FieldIds.VALUE));
      if (rawValue == null) rawValue = map.get(valueKey);

      if (rawObjectId != null && rawClassName != null) {
         // This is an envelope node — flatten it
         LinkedHashMap<String, Object> result = new LinkedHashMap<>();
         result.put("object_id", rawObjectId);
         result.put("class", rawClassName);

         if (rawValue instanceof Map<?, ?> valueMap) {
            // POJO / Map: inline all fields as siblings of object_id/class
            for (Map.Entry<?, ?> entry : valueMap.entrySet()) {
               result.put(String.valueOf(entry.getKey()), humanize(entry.getValue()));
            }
         } else if (rawValue instanceof List<?> valueList) {
            // Collection / array: put under "items" key
            List<Object> items = new ArrayList<>(valueList.size());
            for (Object item : valueList) {
               items.add(humanize(item));
            }
            result.put("items", items);
         } else {
            // Scalar (e.g. "<proxy>" string)
            result.put("value", rawValue);
         }
         return result;
      }

      // Not an envelope — regular map, just humanize keys/values
      LinkedHashMap<String, Object> result = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
         result.put(String.valueOf(entry.getKey()), humanize(entry.getValue()));
      }
      return result;
   }
}
