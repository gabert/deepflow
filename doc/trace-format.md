# Trace Format Specification

DeepFlow writes method-level execution traces as line-oriented text files
(`.dft`). Each line is a `TAG;value` pair. Tags are short uppercase codes
that carry one piece of information about a method call.

The format is language-neutral. Any language agent can emit these tags.
The method signature (MS) is an opaque string formatted in the source
language's native style. Everything else (call tree, timing, values) is
universal.

## Output structure

Each agent run creates a directory `SESSION-<yyyyMMdd-HHmmss>/` under
`session_dump_location`. Inside, there is one `.dft` file per thread, named
`<timestamp>-<thread-name>.dft`. Lines are flushed after each record, so
traces are readable while the application is still running.

## Version header

The first line of every `.dft` file is a version record:

```
VR;1.1
```

Parsers should check this before processing the rest of the file.

## Record structure

A method call produces an **entry block** (starting with TS) and an **exit
block** (starting with TE). These nest like parentheses within the thread
file.

### Entry tags (TS starts the block)

| Tag | Name | Value | Example |
|-----|------|-------|---------|
| `TS` | Timestamp (entry) | Nanoseconds (`System.nanoTime()`) -- always first | `TS;82741936205100` |
| `SI` | Session ID | Logical session/request identifier | `SI;user-alice-01` |
| `MS` | Method signature | Full method signature (always emitted) | `MS;com.example::Foo.bar(String) -> void [public]` |
| `TN` | Thread name | Name of the executing thread | `TN;http-handler-3` |
| `RI` | Request ID | Groups all calls in one request (shared across nesting) | `RI;5` |
| `CL` | Caller line | Source line number of the call site | `CL;42` |
| `TI` | This instance | Object ref ID, or full serialized object if `expand_this=true` | `TI;3` |
| `AR` | Arguments | Serialized method arguments as JSON | `AR;["hello", 42]` |

### Exit tags (TE starts the block)

| Tag | Name | Value | Example |
|-----|------|-------|---------|
| `TE` | Timestamp (exit) | Nanoseconds (`System.nanoTime()`) -- always first | `TE;82741936270000` |
| `TN` | Thread name | Repeated for the exit block | `TN;http-handler-3` |
| `RI` | Request ID | Same value as the entry block | `RI;5` |
| `RT` | Return type | `VOID`, `VALUE`, or `EXCEPTION` | `RT;VALUE` |
| `RE` | Return value | Serialized return value or exception as JSON | `RE;"result"` |
| `AX` | Arguments at exit | Arguments serialized again at exit (mutation detection) | `AX;["modified", 42]` |

## Tag configuration

All tags except `MS`, `TS`, and `TE` can be toggled via `emit_tags`. When a
tag is disabled, the agent skips both serialization and output.

`TS` and `TE` are always emitted because they serve as block delimiters.

Default: `SI,TN,RI,TS,CL,TI,AR,RT,RE,TE`

```properties
# Full trace with mutation detection
emit_tags=SI,TN,RI,TS,CL,TI,AR,RT,RE,TE,AX

# Structural trace only (no serialized values)
emit_tags=TN,RI,TS,CL,TE
```

## Call tree reconstruction

The call tree is implicit in the nesting of entry/exit blocks. TS/TE pairs
nest like matched parentheses. Post-processors reconstruct depth and
parent-child relationships by tracking the entry/exit stack within each
thread file.

All calls within a single request share the same `RI` (request ID). The
request ID is assigned when the first method in a request is entered (depth 0)
and carried through all nested calls.

```
TS;1000  MS;main()              <- root (depth 0, RI=1)
TS;1100  MS;greet("World")      <- nested inside main (depth 1, RI=1)
TS;1200  MS;decorate()          <- nested inside greet (depth 2, RI=1)
TE;1300                         <- decorate returns
TE;1400                         <- greet returns
TS;1500  MS;sneakyMutate()      <- back at depth 1 inside main (RI=1)
TE;1600                         <- sneakyMutate returns
TE;1700                         <- main returns
```

See [Request ID](features/request-id.md) for details on request ID
generation, nesting, and cross-thread propagation.

## Mutation detection

When `AX` is enabled, the agent serializes method arguments both at entry
(`AR`) and at exit (`AX`). Comparing the two reveals which arguments were
mutated during the call.

Objects carry an `object_id` field in their serialized form. Same `object_id`
with different content means the object was mutated.

```
AR;[{"object_id":9,"class":"java.util.ArrayList","items":["original"]}]
AX;[{"object_id":9,"class":"java.util.ArrayList","items":["CHANGED","sneaky"]}]
```

See [Mutation Detection](features/mutation-detection.md).

## Value truncation

When `max_value_size` is configured, serialized values that exceed the limit
are replaced with a truncation marker:

```
AR;{"__truncated":true,"original_size":12345}
```

This prevents oversized payloads from dominating trace files. See
[Truncation](features/truncation.md).

## Object identity envelopes

Arguments and return values are wrapped with identity metadata:

```json
{
  "object_id": 42,
  "class": "com.example.Person",
  "value": { "name": "John", "age": 30 }
}
```

The same Java object always gets the same `object_id` within a session.
Primitives, Strings, and other immutable types are serialized directly
without an envelope.

See [CBOR Codec](internals/codec.md) for the full envelope specification.

## Signature format

Method signatures follow the pattern:

```
package::ClassName.methodName(param::Types) -> return::Type [modifiers]
```

Class names use `::` as separator between the last package segment and the
class name (e.g. `com.example::Foo`). Array types use `[]` suffix.
Primitives have no package prefix.

Example: `com.example::Service.handle(java.lang::String, int) -> void [public]`

## Nanosecond timestamps

Timestamps use `System.nanoTime()` for sub-microsecond precision on duration
calculations. These values are relative to an arbitrary JVM-specific origin
and are only meaningful as deltas within the same session.

Duration of a method call = `TE` timestamp minus `TS` timestamp.
