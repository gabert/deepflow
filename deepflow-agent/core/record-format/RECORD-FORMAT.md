# Binary Record Format

This document specifies the binary wire format used by the Deepflow agent to
record method traces. The format is defined in the `record-format` Maven module
and is the shared contract between any producer (the agent) and any consumer
(file destination, HTTP server, external tooling).

All multi-byte integers are **big-endian** (network byte order). Signedness is
specified per field in the payload layouts below.

## Frame structure

Every record is a self-contained frame:

```
+------+--------+-----------------+
| type | length |     payload     |
+------+--------+-----------------+
  1 B     4 B      `length` bytes
```

| Field    | Size    | Description                                    |
|----------|---------|------------------------------------------------|
| `type`   | 1 byte  | Record type code (see table below)             |
| `length` | 4 bytes | Big-endian `int32` — byte length of `payload`  |
| `payload`| variable| Type-specific content                          |

The **header** is always exactly **5 bytes** (`type` + `length`).

A stream of records is simply concatenated frames with no separators or stream
header. A reader consumes frames until the remaining bytes are fewer than 5
(the minimum header size).

## Record types

| Code   | Name               | Description                                      |
|--------|--------------------|--------------------------------------------------|
| `0x01` | METHOD_START       | Method entry metadata                            |
| `0x02` | ARGUMENTS          | Encoded method arguments                         |
| `0x03` | RETURN             | Encoded return value (or empty for void)         |
| `0x04` | EXCEPTION          | Encoded exception data                           |
| `0x05` | METHOD_END         | Method exit metadata                             |
| `0x06` | THIS_INSTANCE      | Full CBOR-encoded `this` object                  |
| `0x07` | THIS_INSTANCE_REF  | Object ID reference to a previously seen `this`  |
| `0x08` | ARGUMENTS_EXIT     | Encoded method arguments captured at exit         |
| `0x09` | VERSION            | Format version (major.minor)                     |

## Record grouping

Records are emitted in logical groups. One method invocation produces:

**Method entry** (emitted by `RecordWriter.logEntry` or `logEntryWithThisRef`):

```
METHOD_START
[THIS_INSTANCE | THIS_INSTANCE_REF]   ← optional, only for instance methods
ARGUMENTS
```

**Method exit — normal return** (emitted by `RecordWriter.logExit`):

```
RETURN
METHOD_END
```

**Method exit — normal return with exit args** (emitted by `RecordWriter.logExitWithArgs`):

```
RETURN
METHOD_END
ARGUMENTS_EXIT
```

**Method exit — exception** (emitted by `RecordWriter.logExitException`):

```
EXCEPTION
METHOD_END
```

A full method invocation therefore produces 4 records (no `this`), 5 records
(with `this`), and nested calls interleave naturally. When `AX` is enabled
via `emit_tags`, exit records include an additional `ARGUMENTS_EXIT` frame.

When `serialize_values=false`, only the structural records are emitted — no
CBOR-encoded data is captured:

**Method entry** (emitted by `RecordWriter.logEntrySimple`):

```
METHOD_START
```

**Method exit** (emitted by `RecordWriter.logExitSimple`):

```
METHOD_END
```

This mode is useful for dead code detection where only the call graph matters,
not the data flowing through it. It significantly reduces overhead since no
CBOR serialization occurs.

**Full serialization example** (`serialize_values=true`, the default):

```
METHOD_START (outer)         call_id=0, parent_call_id=-1
  ARGUMENTS (outer)
  METHOD_START (inner)       call_id=1, parent_call_id=0
    ARGUMENTS (inner)
    RETURN (inner)
    METHOD_END (inner)
  RETURN (outer)
  METHOD_END (outer)
```

## Payload layouts

### METHOD_START (0x01)

| Offset       | Size    | Type     | Field        | Description                               |
|--------------|---------|----------|--------------|-------------------------------------------|
| 0            | 2 bytes | `uint16` | sid_len      | Byte length of `session_id` (0 if no session) |
| 2            | I bytes | UTF-8    | session_id   | Logical session ID from `SessionIdResolver` (absent when sid_len = 0) |
| 2+I          | 2 bytes | `uint16` | sig_len      | Byte length of `signature`                |
| 4+I          | S bytes | UTF-8    | signature    | Method signature string (see format below)|
| 4+I+S        | 2 bytes | `uint16` | thread_len   | Byte length of `thread_name`              |
| 6+I+S        | T bytes | UTF-8    | thread_name  | Name of the executing thread              |
| 6+I+S+T      | 8 bytes | `int64`  | timestamp      | Nanoseconds (`System.nanoTime()`) at method entry |
| 14+I+S+T     | 4 bytes | `int32`  | caller_line    | Source line number of the call site (0 if unknown) |
| 18+I+S+T     | 4 bytes | `int32`  | depth          | Call depth (legacy, not rendered — use call_id/parent_call_id) |
| 22+I+S+T     | 8 bytes | `int64`  | call_id        | Unique ID for this method invocation (per thread) |
| 30+I+S+T     | 8 bytes | `int64`  | parent_call_id | Call ID of the caller (-1 for root calls) |

Where `I` = sid_len, `S` = sig_len, `T` = thread_len.

The session ID is provided by the `SessionIdResolver` SPI (see
[SESSION-RESOLVER-SPI.md](../../spi/session-resolver-api/SESSION-RESOLVER-SPI.md)).
When no resolver is configured or the resolver returns `null`, `sid_len` is 0
and no bytes are written for `session_id`.

**Signature format:** `package::ClassName.methodName(param::Types) -> return::Type [modifiers]`

Class names use `::` as separator between the last package segment and the
class name (e.g. `com.example::Foo`). Array types use `[]` suffix
(e.g. `java.lang::String[]`). Primitives have no package prefix (e.g. `int`,
`void`).

Example: `com.example::Service.handle(java.lang::String, int) -> void [public]`

### ARGUMENTS (0x02)

| Offset | Size    | Type  | Field | Description                |
|--------|---------|-------|-------|----------------------------|
| 0      | variable| bytes | cbor  | CBOR-encoded argument array |

The payload is the CBOR encoding (via `Codec.encode`) of the `Object[]`
argument array. Each element is wrapped in a CBOR envelope (see
[CODEC.md](../codec/CODEC.md) for envelope structure).

### RETURN (0x03)

| Offset | Size    | Type  | Field | Description                |
|--------|---------|-------|-------|----------------------------|
| 0      | variable| bytes | cbor  | CBOR-encoded return value   |

- **Void methods:** payload length is **0** (empty byte array).
- **Non-void methods:** payload is the CBOR encoding of the return value.

### EXCEPTION (0x04)

| Offset | Size    | Type  | Field | Description                           |
|--------|---------|-------|-------|---------------------------------------|
| 0      | variable| bytes | cbor  | CBOR-encoded exception data (Map)     |

The payload is the CBOR encoding of a `Map<String, Object>` with:

| Key          | Type            | Description                        |
|--------------|-----------------|------------------------------------|
| `"message"`  | `String`        | `throwable.getMessage()` (or `"null"`) |
| `"stacktrace"` | `List<String>` | Stack trace elements as strings    |

### METHOD_END (0x05)

| Offset  | Size    | Type     | Field       | Description                               |
|---------|---------|----------|-------------|-------------------------------------------|
| 0       | 2 bytes | `uint16` | sid_len     | Byte length of `session_id` (0 if no session) |
| 2       | I bytes | UTF-8    | session_id  | Logical session ID (absent when sid_len = 0) |
| 2+I     | 2 bytes | `uint16` | thread_len  | Byte length of `thread_name`              |
| 4+I     | T bytes | UTF-8    | thread_name | Name of the executing thread              |
| 4+I+T   | 8 bytes | `int64`  | timestamp   | Nanoseconds (`System.nanoTime()`) at method exit |

Where `I` = sid_len, `T` = thread_len.

### THIS_INSTANCE (0x06)

| Offset | Size    | Type  | Field | Description                         |
|--------|---------|-------|-------|-------------------------------------|
| 0      | variable| bytes | cbor  | Full CBOR-encoded `this` object     |

Emitted when `expand_this=true` in agent configuration. Contains the complete
CBOR envelope serialization of the `this` instance at method entry.

### THIS_INSTANCE_REF (0x07)

| Offset | Size    | Type    | Field     | Description                       |
|--------|---------|---------|-----------|-----------------------------------|
| 0      | 8 bytes | `int64` | object_id | Stable unique object ID from `ObjectIdRegistry` |

Emitted when `expand_this=false` (default). Contains only the numeric object
identity — a lightweight reference that allows correlating method calls on the
same instance without serializing the full object state.

### ARGUMENTS_EXIT (0x08)

| Offset | Size    | Type  | Field | Description                          |
|--------|---------|-------|-------|--------------------------------------|
| 0      | variable| bytes | cbor  | CBOR-encoded argument array at exit  |

Same format as ARGUMENTS. Emitted when `AX` is enabled in `emit_tags`.
Comparing the entry arguments (ARGUMENTS) with exit arguments (ARGUMENTS_EXIT)
reveals which arguments were mutated during the method call.

### VERSION (0x09)

| Offset | Size    | Type     | Field | Description           |
|--------|---------|----------|-------|-----------------------|
| 0      | 2 bytes | `uint16` | major | Format major version  |
| 2      | 2 bytes | `uint16` | minor | Format minor version  |

Emitted once at session start before any trace records. Current version: 1.0.

## Text rendering

The `RecordRenderer` converts binary records to semicolon-delimited text lines.
The output is a stream of strings that can be consumed by any destination —
written to `.dft` files, sent over a network, or processed in memory. The
mapping is:

| Binary record      | Text line(s)                                      |
|--------------------|---------------------------------------------------|
| VERSION            | `VR;<major>.<minor>`                              |
| METHOD_START       | `SI;<session_id>` (if present), `MS;<signature>`, `TN;<thread>`, `CI;<call_id>`, `PI;<parent_call_id>`, `TS;<timestamp>`, `CL;<caller_line>` |
| THIS_INSTANCE      | `TI;<decoded JSON>`                               |
| THIS_INSTANCE_REF  | `TI;<object_id>`                                  |
| ARGUMENTS          | `AR;<decoded JSON>`                               |
| ARGUMENTS_EXIT     | `AX;<decoded JSON>`                               |
| RETURN (void)      | `RT;VOID`                                         |
| RETURN (value)     | `RT;VALUE`, `RE;<decoded JSON>`                   |
| EXCEPTION          | `RT;EXCEPTION`, `RE;<decoded JSON>`               |
| METHOD_END         | `TN;<thread>`, `TE;<timestamp>`                   |

CBOR payloads are decoded via `Codec.decode` + `Codec.toReadableJson` to produce
the JSON representation in text lines.
