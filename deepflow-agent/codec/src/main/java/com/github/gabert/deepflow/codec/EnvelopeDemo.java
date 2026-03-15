package com.github.gabert.deepflow.codec;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;

// ─────────────────────────────────────────────────────────────
// EnvelopeDemo
//
// Demonstrates:
// 1. Normal object graph serialization to CBOR
// 2. Circular reference handling (no StackOverflowError)
// 3. Same object instance referenced from two places
// 4. CBOR → JSON conversion for human-readable output
// 5. Object identity tracking across multiple captures
// ─────────────────────────────────────────────────────────────
public class EnvelopeDemo {

   // ── Domain model ─────────────────────────────────────────

   static class Person {

      public String name;
      public int age;
      public Person friend; // can create a cycle
      public Address address;
      public List<String> tags;

      Person(String name, int age) {
         this.name = name;
         this.age = age;
      }
   }

   static class Address {

      public String street;
      public String city;
      public Person resident; // back-pointer — cycle risk

      Address(String street, String city) {
         this.street = street;
         this.city = city;
      }
   }

   // ── ObjectMapper setup ────────────────────────────────────

   static final ObjectMapper CBOR_MAPPER;
   static final ObjectMapper JSON_MAPPER;

   static {
      CBOR_MAPPER = new ObjectMapper(new CBORFactory());
      CBOR_MAPPER.registerModule(new EnvelopeModule());

      JSON_MAPPER = new ObjectMapper(new JsonFactory());
      JSON_MAPPER.enable(SerializationFeature.INDENT_OUTPUT);
   }

   // ── Helpers ───────────────────────────────────────────────

   // Serialize object to CBOR bytes using envelope serializer
   static byte[] toCbor(Object value) throws Exception {
      return CBOR_MAPPER.writeValueAsBytes(value);
   }

   // Convert raw CBOR bytes → pretty printed JSON string.
   // Uses a plain ObjectMapper (no envelope module) so we get
   // a clean JSON representation of the CBOR structure.
   // Integer field keys (FieldIds) appear as-is in the output.
   static String cborToJson(byte[] cbor) throws Exception {
      Object tree = new ObjectMapper(new CBORFactory()).readValue(new ByteArrayInputStream(cbor), Object.class);
      return JSON_MAPPER.writeValueAsString(tree);
   }

   static void printSection(String title) {
      System.out.println();
      System.out.println("═".repeat(60));
      System.out.println("  " + title);
      System.out.println("═".repeat(60));
   }

   static void printFieldKey(String json) {
      // Replace integer field keys with readable names for display
      json = json.replace("\"1\"", "\"objectId\"");
      json = json.replace("\"2\"", "\"className\"");
      json = json.replace("\"3\"", "\"value\"");
      json = json.replace("\"4\"", "\"refId\"");
      json = json.replace("\"5\"", "\"cycleRef\"");
      System.out.println(json);
   }

   // ── Demo scenarios ────────────────────────────────────────

   public static void main(String[] args) throws Exception {

      // ── Scenario 1: Simple object, no cycles ──────────────
      printSection("Scenario 1 — Simple object, no cycles");

      Person alice = new Person("Alice", 30);
      alice.tags = List.of("admin", "beta");

      byte[] cbor1 = toCbor(alice);
      System.out.println("CBOR size: " + cbor1.length + " bytes");
      System.out.println();
      printFieldKey(cborToJson(cbor1));

      // ── Scenario 2: Object graph, no cycles ───────────────
      printSection("Scenario 2 — Object graph, no cycles");

      Address office = new Address("Hauptstrasse 1", "Vienna");
      alice.address = office;
      // Note: office.resident is null here — no cycle yet

      byte[] cbor2 = toCbor(alice);
      System.out.println("CBOR size: " + cbor2.length + " bytes");
      System.out.println();
      printFieldKey(cborToJson(cbor2));

      // ── Scenario 3: Circular reference Person ↔ Person ────
      printSection("Scenario 3 — Circular reference Person ↔ Person");
      System.out.println("alice.friend = bob");
      System.out.println("bob.friend   = alice  ← cycle back to alice");
      System.out.println();

      Person bob = new Person("Bob", 25);
      alice.friend = bob;
      bob.friend = alice; // ← cycle: bob → alice → bob → alice...

      byte[] cbor3 = toCbor(alice);
      System.out.println("CBOR size: " + cbor3.length + " bytes");
      System.out.println("No StackOverflowError — cycle was detected");
      System.out.println();
      printFieldKey(cborToJson(cbor3));
      System.out.println();
      System.out.println("▶ Notice: bob.friend shows refId pointing back to alice's objectId");
      System.out.println("  This means: bob.friend IS alice — the cycle is preserved, not lost");

      // ── Scenario 4: Address back-pointer to Person ─────────
      printSection("Scenario 4 — Address.resident points back to Person");
      System.out.println("alice.address.resident = alice  ← cycle through address");
      System.out.println();

      alice.friend = null; // reset to isolate this scenario
      bob.friend = null;
      office.resident = alice; // address points back to its resident

      byte[] cbor4 = toCbor(alice);
      System.out.println("CBOR size: " + cbor4.length + " bytes");
      System.out.println();
      printFieldKey(cborToJson(cbor4));
      System.out.println();
      System.out.println("▶ Notice: address.resident shows refId pointing back to alice");
      System.out.println("  The cycle alice → address → alice is broken correctly");

      // ── Scenario 5: Same instance referenced from two places
      printSection("Scenario 5 — Same Address instance shared by two Persons");
      System.out.println("alice.address = office");
      System.out.println("bob.address   = office  ← exact same instance");
      System.out.println();

      office.resident = null; // clear back-pointer for clarity
      alice.friend = bob;
      bob.address = office; // bob shares alice's address instance

      byte[] cbor5 = toCbor(alice);
      System.out.println("CBOR size: " + cbor5.length + " bytes");
      System.out.println();
      printFieldKey(cborToJson(cbor5));
      System.out.println();
      System.out.println("▶ Notice: alice.address is fully serialized with its objectId");
      System.out.println("  bob.address shows the same objectId as a refId");
      System.out.println("  This proves they are the SAME instance — not just equal content");

      // ── Scenario 6: Object identity across multiple captures
      printSection("Scenario 6 — Object identity across multiple captures");
      System.out.println("Capture the same alice instance three times.");
      System.out.println("Mutate alice between captures.");
      System.out.println("objectId must remain the same — only content changes.");
      System.out.println();

      alice.friend = null;
      bob.friend = null;
      bob.address = null;
      office.resident = null;
      alice.address = null;

      // First capture
      byte[] capture1 = toCbor(alice);
      long id1 = extractObjectId(capture1);
      System.out.printf("Capture 1: objectId=%-6d  name=%-10s  age=%d%n", id1, alice.name, alice.age);

      // Mutate alice
      alice.age = 31;

      // Second capture — same instance, different content
      byte[] capture2 = toCbor(alice);
      long id2 = extractObjectId(capture2);
      System.out.printf("Capture 2: objectId=%-6d  name=%-10s  age=%d%n", id2, alice.name, alice.age);

      // Mutate again
      alice.name = "Alice-Renamed";

      // Third capture
      byte[] capture3 = toCbor(alice);
      long id3 = extractObjectId(capture3);
      System.out.printf("Capture 3: objectId=%-6d  name=%-10s  age=%d%n", id3, alice.name, alice.age);

      // New object — must get a different id
      Person alice2 = new Person("Alice", 30); // same content as original alice
      byte[] capture4 = toCbor(alice2);
      long id4 = extractObjectId(capture4);
      System.out.printf(
         "Capture 4: objectId=%-6d  name=%-10s  age=%d  ← NEW instance, same content%n",
         id4,
         alice2.name,
         alice2.age);

      System.out.println();
      System.out.println("▶ Captures 1-3: same objectId → same instance, content changed between calls");
      System.out
            .println("▶ Capture 4:    different objectId → different instance, even though content matches capture 1");

      if (id1 == id2 && id2 == id3) {
         System.out.println("✓ PASS: alice always gets same objectId across mutations");
      } else {
         System.out.println("✗ FAIL: objectId changed — this should not happen");
      }

      if (id4 != id1) {
         System.out.println("✓ PASS: new alice2 instance gets different objectId");
      } else {
         System.out.println("✗ FAIL: alice2 got same objectId as alice — collision");
      }
   }

   // ── Utility: extract the top-level objectId from CBOR bytes
   // Jackson CBOR may deserialize integer keys as either Integer or String
   // depending on the mapper configuration — we check both.
   @SuppressWarnings("unchecked")
   static long extractObjectId(byte[] cbor) throws Exception {
      Map<Object, Object> map =
            new ObjectMapper(new CBORFactory()).readValue(new ByteArrayInputStream(cbor), Map.class);

      // Try Integer key first, then String key
      Object id = map.get(1);
      if (id == null)
         id = map.get("1");

      if (id instanceof Number n)
         return n.longValue();

      // Debug: print what keys actually came back so we can diagnose further
      System.err.println("DEBUG envelope keys: " + map.keySet());
      System.err.println("DEBUG envelope key types: ");
      map.keySet().forEach(k -> System.err.println("  " + k + " → " + k.getClass().getSimpleName()));

      throw new IllegalStateException("objectId not found in envelope. Keys: " + map.keySet());
   }
}
