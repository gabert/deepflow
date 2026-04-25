# CBOR Codec and Envelope Format

The codec module serializes Java objects (method arguments, return values,
`this` instances, exception data) into CBOR with identity envelopes. The
format preserves object identity and handles reference cycles.

## Envelope structure

### Normal object

```json
{
  "object_id": 42,
  "class": "com.example.Person",
  "value": { "name": "John", "age": 30 }
}
```

Binary CBOR uses integer keys for compactness:

| Key | Constant | Type | Description |
|-----|----------|------|-------------|
| `1` | `OBJECT_ID` | `int64` | Stable unique ID for this instance |
| `2` | `CLASS_NAME` | `string` | Runtime fully-qualified class name |
| `3` | `VALUE` | any | The serialized object content |

### Cycle back-reference

When an object is encountered a second time during the same serialization
call (detected via `IdentityHashMap` in `EnvelopeSerializer`):

```json
{
  "ref_id": 42,
  "cycle_ref": true
}
```

| Key | Constant | Type | Description |
|-----|----------|------|-------------|
| `4` | `REF_ID` | `int64` | Object ID of the already-serialized instance |
| `5` | `CYCLE_REF` | `boolean` | Always `true` |

This prevents `StackOverflowError` on cyclic object graphs.

### Readable JSON conversion

`Codec.toReadableJson()` replaces integer keys with human-readable names
(`object_id`, `class`, `value`, `ref_id`, `cycle_ref`) for text output in
`.dft` files.

## What gets wrapped

`EnvelopeModifier` decides based on runtime type. The rule: **wrap anything
with mutable state and identity.**

| Wrapped | Not wrapped |
|---------|-------------|
| POJOs, Maps, Collections, arrays | Primitives, String, boxed primitives, enums |

Unwrapped types are immutable or have no meaningful object identity. Wrapping
them would add overhead without enabling mutation detection or identity
tracking.

## Object identity (ObjectIdRegistry)

### Why not System.identityHashCode()?

`identityHashCode()` is not unique -- the JVM can assign the same hash to
two distinct live objects. `ObjectIdRegistry` uses an `AtomicLong` counter
(starting at 1, never reused) to guarantee uniqueness. Object identity is
determined by reference equality (`==`).

### Memory management

The registry holds weak references to tracked objects. When an object is
garbage collected, its entry is removed via a `ReferenceQueue` (drained on
every `idOf()` call). A new object at the same memory address gets a fresh,
distinct ID.

## Runtime type resolution

When a field is declared as `Object` (e.g. `Object[] args`), Jackson's
default serializer loses type information. `EnvelopeSerializer` detects this
and re-resolves the serializer by the value's runtime class, ensuring the
envelope captures the correct `CLASS_NAME` and `OBJECT_ID`.

## JPA proxy integration

During serialization, `EnvelopeSerializer` checks
`Codec.getJpaProxyResolver()` before the default proxy detection. If a
resolver returns a non-null unwrapped object, serialization continues with
the real object. Otherwise, the default `isProxy()` check emits a `<proxy>`
marker.

See [JPA Proxy Resolver SPI](../spi/jpa-proxy-resolver.md).

## Example

Given `foo("hello", myPerson)` where `myPerson` is a `Person` with
`name="John"`:

```java
Codec.encode(new Object[]{"hello", myPerson})
```

Decoded to readable JSON:

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

`"hello"` is a String (unwrapped). `myPerson` is a POJO (wrapped with
envelope).
