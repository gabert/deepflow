# Serialization Modes

DeepFlow has two recording modes controlled by `serialize_values`:

## Full serialization (default)

```properties
serialize_values=true
```

The agent encodes arguments, return values, exceptions, and `this` instances
as CBOR with identity envelopes. The full pipeline runs:

**Entry:** `METHOD_START` + optional `THIS_INSTANCE` / `THIS_INSTANCE_REF` + `ARGUMENTS`

**Exit:** `METHOD_END` + `RETURN` / `EXCEPTION` + optional `ARGUMENTS_EXIT`

Trace output includes all configured tags (AR, RE, TI, AX, etc.).

This mode answers: "What data flowed through this method?"

## Structural-only mode

```properties
serialize_values=false
```

The agent records only the call graph and timestamps. No CBOR serialization
occurs.

**Entry:** `METHOD_START` (only)

**Exit:** `METHOD_END` (only)

Trace output is limited to structural tags: MS, TN, RI, TS, CL, TE.
Value tags (AR, RE, TI, AX, RT) are not emitted even if listed in
`emit_tags`.

This mode answers: "Which methods were called, in what order, by which
thread?"

## When to use each mode

| Mode | Use case | Overhead |
|------|----------|----------|
| `serialize_values=true` | Debugging data errors, mutation detection, understanding data flow | Higher (CBOR encoding per method call) |
| `serialize_values=false` | Dead code detection, call graph analysis, performance profiling | Minimal (no serialization) |

## How branching works internally

The branching happens in `DeepFlowAdvice.recordEntry` / `recordExit`:

- `serialize_values=true` calls `buildSerializedEntry()` which invokes
  `RecordWriter.logEntrySimple()` + `RecordWriter.thisInstance()` /
  `thisInstanceRef()` + `RecordWriter.arguments()`.

- `serialize_values=false` calls only `RecordWriter.logEntrySimple()`
  (entry) and `RecordWriter.logExitSimple()` (exit).

Everything downstream (buffer, drainer, destination) is unaware of the
distinction. Both modes produce `byte[]` records in the same binary frame
format.

## Combining with emit_tags

Even in full serialization mode, individual tags can be disabled via
`emit_tags`. When a tag is disabled:

- The agent skips the corresponding serialization (no CPU cost)
- The tag is not emitted in the output (no I/O cost)

Example -- full mode but without argument capture:

```properties
serialize_values=true
emit_tags=SI,TN,RI,TS,CL,RT,RE,TE
```

This captures return values and exceptions but not arguments. Useful when
you care about what methods return but arguments are too large or
uninteresting.
