# Value Truncation

Large serialized values (deeply nested objects, big collections, long strings)
can dominate trace files and slow down processing. The truncation feature
caps individual serialized payloads at a configurable byte size.

## Configuration

Set `max_value_size` in `deepagent.cfg`:

```properties
# Cap each serialized value at 8 KB
max_value_size=8192
```

A value of `0` (default) means no truncation.

## What gets truncated

Truncation applies to these five serialization points in the agent:

| Record | What is serialized | Tag |
|--------|-------------------|-----|
| Arguments at entry | `Object[] allArguments` | AR |
| Arguments at exit | `Object[] allArguments` (for mutation detection) | AX |
| Return value | return value object | RE |
| Exception data | `Map` with message and stacktrace | RE |
| This instance (expanded) | `this` object (when `expand_this=true`) | TI |

Each is checked independently. A method with small arguments but a large
return value will only truncate the return value.

## Truncation marker

When a CBOR-encoded value exceeds `max_value_size` bytes, the payload is
replaced with:

```json
{"__truncated": true, "original_size": 12345}
```

Where `original_size` is the byte count of the full CBOR encoding that was
discarded. This appears in the `.dft` output as:

```
AR;{"__truncated":true,"original_size":12345}
```

## How it works

The agent encodes the value normally via `Codec.encode()`, then checks the
byte array length. If it exceeds the limit, the encoded bytes are discarded
and replaced with the truncation marker (also CBOR-encoded via
`Codec.encode()`).

```java
private static byte[] encodeWithLimit(Object obj) throws IOException {
    byte[] encoded = Codec.encode(obj);
    if (MAX_VALUE_SIZE > 0 && encoded.length > MAX_VALUE_SIZE) {
        return Codec.encode(Map.of("__truncated", true, "original_size", encoded.length));
    }
    return encoded;
}
```

This means the full serialization cost is still paid -- truncation saves
I/O and storage, not CPU. If serialization itself is the bottleneck,
consider `serialize_values=false` or narrowing `emit_tags`.

## What is NOT truncated

- Method signatures (MS) -- always emitted in full
- Timestamps (TS, TE) -- fixed-size binary fields
- Request ID (RI) -- fixed-size binary field
- This instance ref (TI when `expand_this=false`) -- just an object ID number
- Structural records when `serialize_values=false` -- no serialization occurs

## Choosing a limit

| Value | Use case |
|-------|----------|
| `0` | No truncation (default). Full fidelity. |
| `8192` (8 KB) | Good for most debugging. Catches large collections early. |
| `32768` (32 KB) | More generous. Allows medium-sized collections through. |
| `131072` (128 KB) | Only truncates truly oversized payloads. |

The truncation marker itself is approximately 60 bytes, so it always fits
well within any reasonable limit.
