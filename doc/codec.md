# CBOR Codec and Envelope Format

This document specifies the CBOR encoding format used by the Deepflow agent to
serialize Java objects (method arguments, return values, `this` instances, and
exception data). The format is defined in the `codec` Maven module.

## Envelope structure

Every eligible object is wrapped in an envelope that captures object identity,
runtime type, and handles reference cycles. The codec uses
[CBOR (RFC 8949)](https://cbor.io/) as the binary encoding, implemented via
Jackson's `jackson-dataformat-cbor`.

### Normal object

```
{
  1: <object_id>,       // int64 — stable unique ID for this instance
  2: <class_name>,      // string — runtime fully-qualified class name
  3: <value>            // the serialized object content
}
```

### Cycle back-reference

When an object is encountered a second time during the same serialization call
(detected via `IdentityHashMap` in `EnvelopeSerializer`):

```
{
  4: <ref_id>,          // int64 — object_id of the already-serialized instance
  5: true               // boolean — marks this node as a cycle reference
}
```

This prevents `StackOverflowError` on cyclic object graphs and allows consumers
to reconstruct the reference structure.

### Field IDs

Integer keys are used instead of string field names to reduce CBOR payload size.
Defined in `FieldIds.java`:

| Key | Constant     | Type     | Description                                      |
|-----|--------------|----------|--------------------------------------------------|
| `1` | `OBJECT_ID`  | `int64`  | Stable unique ID for this object instance        |
| `2` | `CLASS_NAME` | `string` | Runtime fully-qualified Java class name          |
| `3` | `VALUE`      | any      | The serialized object content (fields, entries)  |
| `4` | `REF_ID`     | `int64`  | Object ID of an already-seen instance (cycle)    |
| `5` | `CYCLE_REF`  | `boolean`| Always `true` — marks this node as a back-reference |

A valid envelope always contains either keys `{1, 2, 3}` (normal object) or
keys `{4, 5}` (cycle reference). These two sets are mutually exclusive.

`Codec.toReadableJson()` replaces these integer keys with human-readable names
(`object_id`, `class`, `value`, `ref_id`, `cycle_ref`) for text output.

## What gets wrapped

Not all values get an envelope. `EnvelopeModifier` decides based on runtime
type — the rule is: **wrap anything with mutable state and identity**.

**Wrapped:** POJOs, Maps, Collections, arrays.
**Not wrapped:** primitives, String, boxed primitives (Number subclasses,
Boolean, Character), enums. These are written directly as their CBOR
representation.

The rationale: unwrapped types are either immutable or have no meaningful
object identity. Wrapping them would add overhead without enabling useful
mutation detection or identity tracking downstream.

## Object identity (`ObjectIdRegistry`)

### Why not `System.identityHashCode()`?

`identityHashCode()` is **not unique** — the JVM can assign the same hash to
two distinct live objects (hash collision). `ObjectIdRegistry` uses an
`AtomicLong` counter (starting at 1, never reused) to guarantee uniqueness.
Object identity is determined by **reference equality** (`==`), with
`identityHashCode` only used for hash-bucket distribution.

### Memory management

The registry holds **weak references** to tracked objects. When an object is
garbage collected, its entry is automatically removed via a `ReferenceQueue`
(drained on every `idOf()` call). A new object allocated at the same memory
address as a GC'd object receives a fresh, distinct ID.

## Runtime type resolution

When a field is declared as `Object` (e.g. `Object[] args`,
`Map<String, Object>`), Jackson's default serializer loses type information.
`EnvelopeSerializer` detects this case (delegate's `handledType()` is null or
`Object.class`) and re-resolves the serializer by the value's **runtime class**.
This ensures the envelope always captures the correct `CLASS_NAME` and
`OBJECT_ID`, even for erased-type fields.

## JPA proxy integration point

During serialization, `EnvelopeSerializer` checks `Codec.getJpaProxyResolver()`
**before** the default proxy detection. If a resolver is registered and returns
a non-null unwrapped object, serialization continues with the real object. If it
returns `null`, the default `isProxy()` check takes over and emits a `<proxy>`
marker. See [jpa-proxy-resolver-spi.md](jpa-proxy-resolver-spi.md).

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
