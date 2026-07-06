# CBOR Codec — Java Implementation Notes

The codec module is the agent's reference implementation of the CBOR
identity envelope. The wire-level contract -- field IDs, envelope shape,
cycle-reference shape, truncation marker, identity guarantees -- lives
in the spec:

- [CBOR-ENVELOPE.md](../../../../spec/CBOR-ENVELOPE.md) -- envelope shape and field IDs
- [IDENTITY-MODEL.md](../../../../spec/IDENTITY-MODEL.md) -- the cross-language identity contract
- [HASHING.md](../../../../spec/HASHING.md) -- the post-render hashing pass

This document covers only how the Java agent satisfies that contract.

## ObjectIdRegistry

The agent assigns each captured live instance a stable positive `int64`
ID, monotonically increasing, never reused -- the requirements of
[CBOR-ENVELOPE.md §3.1](../../../../spec/CBOR-ENVELOPE.md). The Java implementation:

- A `ConcurrentHashMap<IdentityWeakRef, Long>` keyed by an
  identity-equality wrapper around the live object.
- A custom `IdentityWeakRef` whose `equals()` is reference equality
  (`==`) and whose `hashCode()` is the cached `System.identityHashCode`.
- A monotonic `AtomicLong` counter starting at 1.
- A `ReferenceQueue` drained on every `idOf()` call: when the JVM has
  GC'd the wrapped instance, its registry entry is removed and the ID
  is gone forever (the counter never re-issues it).

`System.identityHashCode` alone would not work as the map key -- it is
not unique across distinct live objects. Hence the wrapper.

This is the natural weak-reference path called out in
[IDENTITY-MODEL.md §2](../../../../spec/IDENTITY-MODEL.md). Lookup is amortized
O(1); memory cost is proportional to live tracked objects only.

## EnvelopeSerializer (Jackson hook)

CBOR encoding goes through Jackson's `cbor-dataformat`. The agent
replaces Jackson's default object serializer with `EnvelopeSerializer`,
which:

1. Computes `OBJECT_ID` via `ObjectIdRegistry.idOf(value)`.
2. Reads `CLASS_NAME` from the value's runtime class.
3. Detects already-seen instances within the current top-level encode
   call via an `IdentityHashMap`; emits a cycle reference instead of
   recursing.
4. Asks `JpaProxyResolvers.active()` whether the value is an
   unwrappable proxy (see below) -- if so, continues with the unwrapped
   object.
5. Falls through to Jackson's default field-by-field serialization for
   the `VALUE` content.

`EnvelopeModifier` decides at registration time whether a given Java
type should be envelope-wrapped or written as a bare CBOR primitive.
The rule per [CBOR-ENVELOPE.md §5](../../../../spec/CBOR-ENVELOPE.md): wrap
anything with mutable state and identity (POJOs, Maps, Collections,
arrays); leave primitives, Strings, boxed primitives, and enums bare.

### Runtime type resolution

When a field is declared as `Object` (e.g. `Object[] args`), Jackson's
default serializer resolves at compile time and loses the runtime type.
`EnvelopeSerializer` re-resolves the serializer by `value.getClass()`
on each value, so the envelope captures the actual runtime
`CLASS_NAME` and a per-instance `OBJECT_ID`.

## JPA proxy integration

The codec exposes a static slot, `JpaProxyResolvers.setActive(...)`,
that the agent populates on first instrumented method entry from the
`JpaProxyResolver` SPI (see [SPI docs](../reference/jpa-proxy-resolver.md)).

`EnvelopeSerializer` calls the resolver before falling back to the
generic proxy-detection check. If the resolver unwraps the proxy, the
real object is serialized normally. If the resolver returns `null`,
the generic `isProxy(value)` check decides whether to emit `<proxy>`
or to attempt full serialization.

## Readable JSON conversion

The codec exposes the decode and the render as two separate calls:

- `Codec.decode(byte[] cbor) -> Object` — turns the wire bytes back
  into a tree of maps, lists, and scalars. Envelope maps still carry
  the integer field IDs.
- `Codec.toReadableJson(Object decoded) -> String` — humanizes that
  decoded tree to JSON text. Integer field IDs are renamed per
  [HASHING.md §3](../../../../spec/HASHING.md): `1 -> object_id`, `2 -> class`,
  `3 -> value`, `4 -> ref_id`, `5 -> cycle_ref`. The `VALUE` field is
  flattened — a CBOR map becomes sibling keys; **a CBOR array becomes
  a sibling key `"items"`**; a scalar becomes a sibling key `"value"`.

The file destination's pipeline is:
`bytes -> Codec.decode -> humanize -> [optional RecordHashEnricher
inject __meta__] -> JSON line in .dft`.

## Worked example

```java
Codec.encode(new Object[]{"hello", myPerson})
```

where `myPerson` is a `Person` with `name="John"`. Decoded to
readable JSON via `Codec.toReadableJson()`:

```json
{
  "object_id": 100,
  "class": "java.lang.Object[]",
  "value": [
    "hello",
    {
      "object_id": 101,
      "class": "com.example.Person",
      "name": "John"
    }
  ]
}
```

`"hello"` is bare (String -- not envelope-wrapped). `myPerson` is
envelope-wrapped. The outer `Object[]` is also envelope-wrapped --
arrays carry identity per the ARGUMENTS shape in
[CBOR-ENVELOPE.md §6](../../../../spec/CBOR-ENVELOPE.md).

## Key source files

All under `arachna-trace-shared/codec/src/main/java/com/github/gabert/arachna/trace/`:

- `codec/Codec.java` — public facade (`encode`, `decode`, `toReadableJson`,
  JPA proxy hook getter/setter)
- `codec/Hasher.java` — Merkle content hash + own_hash, `__meta__`
  envelope construction (used by the renderer module on the consumer
  side; lives here because everyone shares the same hashing rules)
- `codec/AgentRun.java` — cross-platform per-process producer identity
  record + the seven canonical transport header names. Shared between
  agent (writes) and infra (reads) — single definition prevents drift.
- `codec/envelope/EnvelopeSerializer.java` — the Jackson custom serializer
  (object id assignment, cycle detection, JPA proxy unwrap, runtime
  type re-resolution)
- `codec/envelope/EnvelopeModifier.java` — per-type wrap-or-bare decision
- `codec/envelope/ObjectIdRegistry.java` — weak-ref-backed identity map.
  `IdentityWeakRef` is a public nested class inside it, not a
  separate file.
- `recorder/record/` — binary wire-format record types (MS/ME/AR/AX/RE/etc.)
  and the `RecordReader` / `RecordWriter` codecs for them. Lives in this
  module because the wire format is part of the shared contract.
- `config/ConfigLoader.java` — agent config file parsing (used by the
  agent at startup; lives here as a stable cross-module utility).
