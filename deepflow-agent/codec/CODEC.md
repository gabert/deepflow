# CBOR Codec and Envelope Format

This document specifies the CBOR encoding format used by the Deepflow agent to
serialize Java objects (method arguments, return values, `this` instances, and
exception data). The format is defined in the `codec` Maven module.

## Overview

The codec uses [CBOR (RFC 8949)](https://cbor.io/) as the binary encoding,
implemented via Jackson's `jackson-dataformat-cbor` library. On top of standard
CBOR serialization, every eligible object is wrapped in an **envelope** that
captures object identity, runtime type, and handles reference cycles.

## Envelope structure

### Normal object

Every object that passes the wrapping criteria is serialized as a CBOR map with
integer keys:

```
{
  1: <object_id>,       // int64 — stable unique ID for this instance
  2: <class_name>,      // string — runtime fully-qualified class name
  3: <value>            // the serialized object content
}
```

### Cycle back-reference

When an object is encountered a second time during the same serialization call
(i.e., a reference cycle), it is emitted as:

```
{
  4: <ref_id>,          // int64 — object_id of the already-serialized instance
  5: true               // boolean — marks this node as a cycle reference
}
```

This prevents `StackOverflowError` on cyclic object graphs and allows consumers
to reconstruct the reference structure.

## Field IDs

Integer keys are used instead of string field names to reduce payload size and
improve serialization speed. The constants are defined in `FieldIds.java`:

| Key | Constant     | Type     | Description                                      |
|-----|--------------|----------|--------------------------------------------------|
| `1` | `OBJECT_ID`  | `int64`  | Stable unique ID for this object instance        |
| `2` | `CLASS_NAME` | `string` | Runtime fully-qualified Java class name          |
| `3` | `VALUE`      | any      | The serialized object content (fields, entries)  |
| `4` | `REF_ID`     | `int64`  | Object ID of an already-seen instance (cycle)    |
| `5` | `CYCLE_REF`  | `boolean`| Always `true` — marks this node as a back-reference |

A valid envelope always contains either keys `{1, 2, 3}` (normal object) or
keys `{4, 5}` (cycle reference). These two sets are mutually exclusive.

## Wrapping criteria

Not all values are wrapped in an envelope. The `EnvelopeModifier` decides based
on the value's runtime type:

| Type                  | Wrapped? | Rationale                              |
|-----------------------|----------|----------------------------------------|
| Primitives (`int`, `boolean`, ...) | No | No object identity to track  |
| `String`              | No       | Immutable, no meaningful identity      |
| `Number` subclasses (`Integer`, `Long`, ...) | No | Boxed primitives     |
| `Boolean`, `Character`| No       | Boxed primitives                       |
| Enums                 | No       | Singleton instances                    |
| POJOs                 | **Yes**  | Have identity and mutable state        |
| `Map` instances       | **Yes**  | Have identity and mutable state        |
| `Collection` instances| **Yes**  | Have identity and mutable state        |
| Arrays                | **Yes**  | Have identity and mutable state        |

Unwrapped values are written directly as their CBOR representation (number,
string, boolean, etc.) without an envelope.

## Object identity (`OBJECT_ID`)

### How IDs are assigned

Object IDs are assigned by `ObjectIdRegistry`, a global registry that maps
each live Java object to a unique `long` value.

- IDs are assigned via an `AtomicLong` counter starting at 1, incrementing
  for each new object. IDs are never reused.
- Object identity is determined by **reference equality** (`==`), not by
  `equals()` or `identityHashCode()`.
- Two objects with identical content but different memory addresses always
  receive different IDs.
- The same instance always receives the same ID for its entire lifetime,
  regardless of mutations to its fields.

### Why not `System.identityHashCode()`?

`identityHashCode()` is **not unique** — the JVM can assign the same hash to
two distinct live objects (hash collision). `ObjectIdRegistry` uses
`IdentityHashMap`-style reference equality (`==`) as the true comparator,
with `identityHashCode` only used for hash-bucket distribution.

### Memory management

The registry holds **weak references** to tracked objects. When an object is
garbage collected, its entry is automatically removed via a `ReferenceQueue`.
The counter never resets, so a new object allocated at the same memory address
as a GC'd object receives a fresh, distinct ID.

## Class name format

Class names are produced by `ClassNameCache`, which caches `Class.getName()`
results. The format follows Java's binary class name convention:

| Java type                | Class name in envelope                        |
|--------------------------|-----------------------------------------------|
| `com.example.Foo`        | `com.example.Foo`                             |
| `com.example.Foo.Inner`  | `com.example.Foo$Inner`                       |
| `String[]`               | `java.lang.String[]`                          |
| `int[][]`                | `int[][]`                                     |
| `java.util.ArrayList`    | `java.util.ArrayList`                         |

Array types use `[]` suffix notation (the cache recursively resolves component
types), not the JVM internal `[L...;` encoding.

## VALUE field content

The content of the `VALUE` field (key `3`) depends on the object type:

### POJOs

Serialized as a CBOR map with **string keys** (field names). All fields are
included regardless of visibility (`FIELD` visibility is set to `ANY`).
Nested objects that meet the wrapping criteria are themselves wrapped in
envelopes.

```
{
  1: 42,                          // OBJECT_ID
  2: "com.example.Person",        // CLASS_NAME
  3: {                            // VALUE — POJO fields as string-keyed map
    "name": "John",               //   String field — not wrapped
    "age": 30,                    //   int field — not wrapped
    "address": {                  //   Object field — wrapped in its own envelope
      1: 43,
      2: "com.example.Address",
      3: {
        "town": "London"
      }
    }
  }
}
```

### Maps

Serialized as a CBOR map preserving the original key-value entries. Keys and
values that meet the wrapping criteria are individually wrapped.

### Collections and arrays

Serialized as a CBOR array. Each element that meets the wrapping criteria is
wrapped in its own envelope.

```
{
  1: 44,                          // OBJECT_ID
  2: "java.lang.Object[]",        // CLASS_NAME
  3: [                            // VALUE — array elements
    "hello",                      //   String — not wrapped
    42,                           //   int — not wrapped
    {                             //   Object — wrapped
      1: 45,
      2: "com.example.Foo",
      3: { "field": "value" }
    }
  ]
}
```

## Runtime type resolution

When a field is declared as `Object` (e.g. `Object[] args`, `Map<String, Object>`),
Jackson's default serializer loses type information. The `EnvelopeSerializer`
detects this case and re-resolves the serializer by the value's **runtime class**.
This ensures that the envelope always captures the correct `CLASS_NAME` and
`OBJECT_ID`, even for erased-type fields.

## Decoding

`Codec.decode(byte[])` deserializes CBOR bytes back to a nested Java structure
of `Map<Object, Object>` and `List<Object>`. Map keys in the decoded structure
are **integers** (matching `FieldIds` constants) for envelope fields, and
**strings** for POJO field names.

To access envelope fields from a decoded map:

```java
Map<?, ?> envelope = (Map<?, ?>) Codec.decode(bytes);
long objectId  = ((Number) envelope.get(1)).longValue();   // FieldIds.OBJECT_ID
String className = (String) envelope.get(2);                // FieldIds.CLASS_NAME
Object value     = envelope.get(3);                         // FieldIds.VALUE
```

`Codec.toReadableJson(Object)` converts a decoded structure to a human-readable
JSON string, replacing integer envelope keys with descriptive names:

| Integer key | JSON key     |
|-------------|--------------|
| `1`         | `"object_id"`|
| `2`         | `"class"`    |
| `3`         | `"value"`    |
| `4`         | `"ref_id"`   |
| `5`         | `"cycle_ref"`|

## Example: full encoded method arguments

Given a Java method call `foo("hello", myPerson)` where `myPerson` is a
`Person` with `name="John"`:

```
Codec.encode(new Object[]{"hello", myPerson})
```

Decodes to (shown as readable JSON):

```json
{
  "object_id": 100,
  "class": "java.lang.Object[]",
  "value": [
    "hello",
    {
      "object_id": 101,
      "class": "com.example.Person",
      "value": {
        "name": "John"
      }
    }
  ]
}
```
