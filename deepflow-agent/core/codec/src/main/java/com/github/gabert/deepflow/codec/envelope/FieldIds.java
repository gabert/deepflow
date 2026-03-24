package com.github.gabert.deepflow.codec.envelope;

// ─────────────────────────────────────────────────────────────
// FieldIds
// Integer keys for CBOR fields.
// Avoids writing string field names on every object — saves
// space and improves write speed in binary payloads.
// The deserializer must use the same constants to decode.
// ─────────────────────────────────────────────────────────────
public final class FieldIds {

   public static final int OBJECT_ID = 1; // stable unique id of this object instance
   public static final int CLASS_NAME = 2; // runtime class name
   public static final int VALUE = 3; // the serialized object content
   public static final int REF_ID = 4; // cycle back-reference: id of already-seen object
   public static final int CYCLE_REF = 5; // boolean flag marking this node as a cycle reference

   private FieldIds() {
   }
}
