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

   private static Object humanize(Object obj) {
      if (obj instanceof Map<?, ?> map) return humanizeMap(map);
      if (obj instanceof List<?> list) return humanizeList(list);
      return obj;
   }

   private static List<Object> humanizeList(List<?> list) {
      List<Object> result = new ArrayList<>(list.size());
      for (Object item : list) result.add(humanize(item));
      return result;
   }

   private static Object humanizeMap(Map<?, ?> map) {
      if (hasField(map, FieldIds.REF_ID))                  return humanizeCycleRef(map);
      if (hasField(map, FieldIds.OBJECT_ID)
              && hasField(map, FieldIds.CLASS_NAME))       return humanizeEnvelope(map);
      return humanizeRegularMap(map);
   }

   /** Cycle back-reference: rename the field-id keys to readable names, pass through. */
   private static Map<String, Object> humanizeCycleRef(Map<?, ?> map) {
      LinkedHashMap<String, Object> result = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
         String name = renameCycleRefKey(String.valueOf(entry.getKey()));
         result.put(name, name.equals("ref_id") || name.equals("cycle_ref")
                 ? entry.getValue()
                 : humanize(entry.getValue()));
      }
      return result;
   }

   private static String renameCycleRefKey(String key) {
      if (key.equals(String.valueOf(FieldIds.REF_ID))) return "ref_id";
      if (key.equals(String.valueOf(FieldIds.CYCLE_REF))) return "cycle_ref";
      return key;
   }

   /** Envelope node: flatten object_id/class with the inner value fields as siblings. */
   private static Map<String, Object> humanizeEnvelope(Map<?, ?> map) {
      LinkedHashMap<String, Object> result = new LinkedHashMap<>();
      result.put("object_id", getField(map, FieldIds.OBJECT_ID));
      result.put("class", getField(map, FieldIds.CLASS_NAME));

      Object rawValue = getField(map, FieldIds.VALUE);
      if (rawValue instanceof Map<?, ?> valueMap) {
         // POJO / Map: inline fields as siblings of object_id/class.
         for (Map.Entry<?, ?> entry : valueMap.entrySet()) {
            result.put(String.valueOf(entry.getKey()), humanize(entry.getValue()));
         }
      } else if (rawValue instanceof List<?> valueList) {
         // Collection / array: put under "items".
         result.put("items", humanizeList(valueList));
      } else {
         // Scalar (e.g. "<proxy>" string).
         result.put("value", rawValue);
      }
      return result;
   }

   /** Plain map (no envelope or cycle markers): just humanize keys and values. */
   private static Map<String, Object> humanizeRegularMap(Map<?, ?> map) {
      LinkedHashMap<String, Object> result = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : map.entrySet()) {
         result.put(String.valueOf(entry.getKey()), humanize(entry.getValue()));
      }
      return result;
   }

   /** CBOR field ids may decode as Integer or String depending on the path. */
   private static boolean hasField(Map<?, ?> map, int fieldId) {
      return map.containsKey(Integer.valueOf(fieldId))
              || map.containsKey(String.valueOf(fieldId));
   }

   private static Object getField(Map<?, ?> map, int fieldId) {
      Object value = map.get(Integer.valueOf(fieldId));
      return value != null ? value : map.get(String.valueOf(fieldId));
   }
}
