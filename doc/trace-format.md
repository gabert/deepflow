# Trace Record Format

DeepFlow captures method-level execution traces and writes them as line-oriented text files (`.dft`). Each line is a **tag;value** pair. Tags are short uppercase codes that carry one piece of information about a method call — its identity, timing, arguments, or result.

The format is language-neutral. Any language agent can emit these tags as long as it follows the `TAG;value` structure. The method signature (MS) is an opaque string — each language formats it in its native style. Everything else (call tree, timing, values) is universal.

## Output Structure

Each agent run creates a directory `SESSION-<yyyyMMdd-HHmmss>/` under the configured output location. Inside, there is one `.dft` file per thread, named `<timestamp>-<thread-name>.dft`. Lines are flushed after each record, so traces are readable while the application is still running.

## Version Header

The first line of every `.dft` file is a version record:

```
VR;1.1
```

This identifies the format version. Parsers should check this before processing the rest of the file.

## Record Tags

A method call produces an **entry block** when the method is entered and an **exit block** when it returns. Tags appear in the order listed below.

### Entry Tags (TS starts the block)

| Tag | Name | Value | Example |
|-----|------|-------|---------|
| `TS` | Timestamp (entry) | Nanoseconds (from `System.nanoTime()`) at method entry — always first | `TS;82741936205100` |
| `SI` | Session ID | Identifier linking calls to a user session or request | `SI;user-alice-01` |
| `MS` | Method signature | Full method signature (always emitted) | `MS;com.example::Foo.bar(String) -> void [public]` |
| `TN` | Thread name | Name of the executing thread | `TN;http-handler-3` |
| `CI` | Call ID | Unique ID for this method invocation (per thread) | `CI;7` |
| `CL` | Caller line | Source line number of the call site | `CL;42` |
| `TI` | This instance | Object reference ID, or full serialized object if `expand_this=true` | `TI;3` |
| `AR` | Arguments | Serialized method arguments as JSON | `AR;["hello", 42]` |

### Exit Tags (TE starts the block)

| Tag | Name | Value | Example |
|-----|------|-------|---------|
| `TE` | Timestamp (exit) | Nanoseconds (from `System.nanoTime()`) at method return — always first | `TE;82741936270000` |
| `TN` | Thread name | Repeated for the exit block | `TN;http-handler-3` |
| `RT` | Return type | `VOID`, `VALUE`, or `EXCEPTION` | `RT;VALUE` |
| `RE` | Return value | Serialized return value or exception as JSON | `RE;"result"` |
| `AX` | Arguments at exit | Serialized arguments captured again at method exit (for mutation detection) | `AX;["modified", 42]` |

## Tag Configuration

All tags except `MS`, `TS`, and `TE` can be toggled via the `emit_tags` configuration property. `TS` and `TE` are always emitted as they serve as block delimiters. When a tag is disabled, the agent skips both the serialization and the output — there is no runtime cost for disabled tags.

Default: `SI,TN,CI,TS,CL,TI,AR,RT,RE,TE`

Examples:

```properties
# Full trace with mutation detection
emit_tags=SI,TN,CI,TS,CL,TI,AR,RT,RE,TE,AX

# Structural trace only (no serialized values — for dead code detection)
emit_tags=TN,CI,TS,CL,TE

```

## Call Tree Reconstruction

Each method invocation gets a unique `CI` (call ID) — a simple per-thread counter. The call tree structure is implicit in the nesting of entry/exit record pairs (MS/TE), just like matched parentheses. Post-processors reconstruct depth and parent-child relationships by tracking the entry/exit stack.

```
CI;0  MS;main()              ← root (depth 0)
CI;1  MS;greet("World")      ← nested inside main (depth 1)
CI;2  MS;decorate()          ← nested inside greet (depth 2)
      TE;...                 ← decorate returns
      TE;...                 ← greet returns
CI;3  MS;sneakyMutate()      ← back at depth 1 inside main
      TE;...                 ← sneakyMutate returns
      TE;...                 ← main returns
```

## Mutation Detection

When `AX` is enabled, the agent serializes method arguments both at entry (`AR`) and at exit (`AX`). Comparing the two reveals which arguments were mutated during the call.

Objects carry an `object_id` field in their serialized form. Same `object_id` with different content means the object was mutated.

```
AR;[{"object_id":9,"class":"java.util.ArrayList","items":["original"]}]
AX;[{"object_id":9,"class":"java.util.ArrayList","items":["CHANGED","sneaky"]}]
```
