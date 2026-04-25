# Binary Record Format

The binary wire format is the shared contract between any producer (the agent)
and any consumer (file destination, HTTP server, external tooling). Defined
in the `record-format` Maven module.

All multi-byte integers are **big-endian** (network byte order).

## Frame structure

Every record is a self-contained frame:

```
+------+--------+-----------------+
| type | length |     payload     |
+------+--------+-----------------+
  1 B     4 B      `length` bytes
```

| Field | Size | Description |
|-------|------|-------------|
| `type` | 1 byte | Record type code |
| `length` | 4 bytes | Big-endian `int32` -- byte length of `payload` |
| `payload` | variable | Type-specific content |

The header is always 5 bytes. A stream of records is concatenated frames
with no separators. A reader consumes frames until fewer than 5 bytes remain.

## Record types

| Code | Name | Description |
|------|------|-------------|
| `0x01` | METHOD_START | Method entry metadata |
| `0x02` | ARGUMENTS | Encoded method arguments |
| `0x03` | RETURN | Encoded return value (or empty for void) |
| `0x04` | EXCEPTION | Encoded exception data |
| `0x05` | METHOD_END | Method exit metadata |
| `0x06` | THIS_INSTANCE | Full CBOR-encoded `this` object |
| `0x07` | THIS_INSTANCE_REF | Object ID reference for `this` |
| `0x08` | ARGUMENTS_EXIT | Encoded arguments at exit |
| `0x09` | VERSION | Format version |

## Record grouping

One method invocation produces record groups:

**Entry** (full serialization):

```
METHOD_START
[THIS_INSTANCE | THIS_INSTANCE_REF]   <- optional
ARGUMENTS
```

**Exit -- normal return:**

```
METHOD_END
RETURN
[ARGUMENTS_EXIT]                      <- optional (when AX enabled)
```

**Exit -- exception:**

```
METHOD_END
EXCEPTION
[ARGUMENTS_EXIT]                      <- optional
```

**Entry** (structural-only, `serialize_values=false`):

```
METHOD_START
```

**Exit** (structural-only):

```
METHOD_END
```

## Payload layouts

### METHOD_START (0x01)

| Offset | Size | Type | Field | Description |
|--------|------|------|-------|-------------|
| 0 | 2 bytes | `uint16` | sid_len | Byte length of session_id (0 if absent) |
| 2 | I bytes | UTF-8 | session_id | Logical session ID |
| 2+I | 2 bytes | `uint16` | sig_len | Byte length of signature |
| 4+I | S bytes | UTF-8 | signature | Method signature string |
| 4+I+S | 2 bytes | `uint16` | thread_len | Byte length of thread_name |
| 6+I+S | T bytes | UTF-8 | thread_name | Executing thread name |
| 6+I+S+T | 8 bytes | `int64` | timestamp | Nanoseconds (`System.nanoTime()`) |
| 14+I+S+T | 4 bytes | `int32` | caller_line | Source line of the call site |
| 18+I+S+T | 8 bytes | `int64` | request_id | Request ID grouping this call |

### METHOD_END (0x05)

| Offset | Size | Type | Field | Description |
|--------|------|------|-------|-------------|
| 0 | 2 bytes | `uint16` | sid_len | Byte length of session_id (0 if absent) |
| 2 | I bytes | UTF-8 | session_id | Logical session ID |
| 2+I | 2 bytes | `uint16` | thread_len | Byte length of thread_name |
| 4+I | T bytes | UTF-8 | thread_name | Executing thread name |
| 4+I+T | 8 bytes | `int64` | timestamp | Nanoseconds (`System.nanoTime()`) |
| 12+I+T | 8 bytes | `int64` | request_id | Request ID (same as entry) |

### ARGUMENTS (0x02) / ARGUMENTS_EXIT (0x08)

| Offset | Size | Type | Description |
|--------|------|------|-------------|
| 0 | variable | bytes | CBOR-encoded argument array |

### RETURN (0x03)

| Offset | Size | Type | Description |
|--------|------|------|-------------|
| 0 | variable | bytes | CBOR-encoded return value (empty for void) |

### EXCEPTION (0x04)

| Offset | Size | Type | Description |
|--------|------|------|-------------|
| 0 | variable | bytes | CBOR-encoded `Map` with `message` and `stacktrace` |

### THIS_INSTANCE (0x06)

| Offset | Size | Type | Description |
|--------|------|------|-------------|
| 0 | variable | bytes | Full CBOR-encoded `this` object |

### THIS_INSTANCE_REF (0x07)

| Offset | Size | Type | Description |
|--------|------|------|-------------|
| 0 | 8 bytes | `int64` | Object ID from `ObjectIdRegistry` |

### VERSION (0x09)

| Offset | Size | Type | Description |
|--------|------|------|-------------|
| 0 | 2 bytes | `uint16` | Format major version |
| 2 | 2 bytes | `uint16` | Format minor version |

Current version: 1.1.

## Text rendering

`RecordRenderer` converts binary records to `TAG;value` text lines:

| Binary record | Text line(s) |
|---------------|-------------|
| VERSION | `VR;<major>.<minor>` |
| METHOD_START | `TS;<timestamp>`, `SI;<session_id>` (if present), `MS;<signature>`, `TN;<thread>`, `RI;<request_id>`, `CL;<caller_line>` |
| THIS_INSTANCE | `TI;<decoded JSON>` |
| THIS_INSTANCE_REF | `TI;<object_id>` |
| ARGUMENTS | `AR;<decoded JSON>` |
| ARGUMENTS_EXIT | `AX;<decoded JSON>` |
| RETURN (void) | `RT;VOID` |
| RETURN (value) | `RT;VALUE`, `RE;<decoded JSON>` |
| EXCEPTION | `RT;EXCEPTION`, `RE;<decoded JSON>` |
| METHOD_END | `TE;<timestamp>`, `TN;<thread>`, `RI;<request_id>` |
