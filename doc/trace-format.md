# Trace Record Format

DeepFlow captures method-level execution traces and writes them as line-oriented text files (`.dft`). Each line is a **tag;value** pair. Tags are short uppercase codes that carry one piece of information about a method call — its identity, timing, arguments, or result.

The format is language-neutral. Any language agent can emit these tags as long as it follows the `TAG;value` structure. The method signature (MS) is an opaque string — each language formats it in its native style. Everything else (call tree, timing, values) is universal.

## Output Structure

Each agent run creates a directory `SESSION-<yyyyMMdd-HHmmss>/` under the configured output location. Inside, there is one `.dft` file per thread, named `<timestamp>-<thread-name>.dft`. Lines are flushed after each record, so traces are readable while the application is still running.

## Record Tags

A method call produces an **entry block** when the method is entered and an **exit block** when it returns. Tags appear in the order listed below.

### Entry Tags

| Tag | Name | Value | Example |
|-----|------|-------|---------|
| `SI` | Session ID | Identifier linking calls to a user session or request | `SI;user-alice-01` |
| `MS` | Method signature | Full method signature (always emitted) | `MS;com.example::Foo.bar(String) -> void [public]` |
| `TN` | Thread name | Name of the executing thread | `TN;http-handler-3` |
| `CI` | Call ID | Unique ID for this method invocation (per thread) | `CI;7` |
| `PI` | Parent call ID | Call ID of the caller (-1 for root calls) | `PI;3` |
| `TS` | Timestamp (entry) | Epoch milliseconds at method entry | `TS;1777061479135` |
| `CL` | Caller line | Source line number of the call site | `CL;42` |
| `TI` | This instance | Object reference ID, or full serialized object if `expand_this=true` | `TI;3` |
| `AR` | Arguments | Serialized method arguments as JSON | `AR;["hello", 42]` |

### Exit Tags

| Tag | Name | Value | Example |
|-----|------|-------|---------|
| `RT` | Return type | `VOID`, `VALUE`, or `EXCEPTION` | `RT;VALUE` |
| `RE` | Return value | Serialized return value or exception as JSON | `RE;"result"` |
| `AX` | Arguments at exit | Serialized arguments captured again at method exit (for mutation detection) | `AX;["modified", 42]` |
| `TN` | Thread name | Repeated for the exit block | `TN;http-handler-3` |
| `TE` | Timestamp (exit) | Epoch milliseconds at method return | `TE;1777061479200` |

## Tag Configuration

All tags except `MS` can be toggled via the `emit_tags` configuration property. When a tag is disabled, the agent skips both the serialization and the output — there is no runtime cost for disabled tags.

Default: `SI,TN,CI,PI,TS,CL,TI,AR,RT,RE,TE`

Examples:

```properties
# Full trace with mutation detection
emit_tags=SI,TN,CI,PI,TS,CL,TI,AR,RT,RE,TE,AX

# Structural trace only (no serialized values — for dead code detection)
emit_tags=TN,CI,PI,TS,CL,TE

```

## Call Tree Reconstruction

The call tree is encoded via `CI` (call ID) and `PI` (parent call ID). Each method invocation gets a unique call ID. The parent call ID points to the caller's ID, or `-1` for root calls.

This design supports reactive and event-loop frameworks where a single thread handles multiple sessions concurrently — the call tree is reconstructed from CI/PI references, not from thread-local depth counting.

```
CI;0  PI;-1   main()              ← root
CI;1  PI;0      greet("World")    ← called by main
CI;2  PI;1        decorate()      ← called by greet
CI;3  PI;0      sneakyMutate()    ← called by main (greet already returned)
```

## Mutation Detection

When `AX` is enabled, the agent serializes method arguments both at entry (`AR`) and at exit (`AX`). Comparing the two reveals which arguments were mutated during the call.

Objects carry an `object_id` field in their serialized form. Same `object_id` with different content means the object was mutated.

```
AR;[{"object_id":9,"class":"java.util.ArrayList","items":["original"]}]
AX;[{"object_id":9,"class":"java.util.ArrayList","items":["CHANGED","sneaky"]}]
```
