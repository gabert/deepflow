package com.github.gabert.deepflow.codec;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import com.github.gabert.deepflow.codec.envelope.EnvelopeModule;

public final class Codec {

   private static final ObjectMapper CBOR_ENCODER;
   private static final ObjectMapper CBOR_DECODER;
   private static final ObjectMapper JSON_MAPPER;

   static {
      CBOR_ENCODER = new ObjectMapper(new CBORFactory());
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
    * Convert a decoded Java object (from {@link #decode}) to a pretty-printed JSON string.
    */
   public static String toJson(Object decoded) throws IOException {
      return JSON_MAPPER.writeValueAsString(decoded);
   }
}
