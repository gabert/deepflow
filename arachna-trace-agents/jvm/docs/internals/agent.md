# Agent Module

The agent module is the entry point of Arachna Trace. It is a Java agent that
attaches to a target application via `-javaagent` and instruments
selected classes at load time using ByteBuddy.

The agent produces binary records (see [WIRE-FORMAT.md](../../../../spec/WIRE-FORMAT.md))
and offers them to an in-memory buffer. A background drainer thread delivers
records to a configured destination (see [serializer.md](serializer.md)).

## Startup sequence

```
JVM loads -javaagent
  -> ArachnaTraceAgent.premain(agentArgs, instrumentation)
    1. AgentConfig.from(agentArgs)        Parse config file + CLI args
    2. IF propagate_request_id:
         injectBootstrapClasses()                Inject RequestContext + wrappers
                                                 into the bootstrap classloader
         redefineModule(java.base)               Grant java.base reads access
                                                 to the unnamed module
    3. RecorderManager.create(config)            Build destination, emit VR record,
                                                 start drainer, register shutdown hook
    4. ArachnaTraceAdvice.setup(new RequestRecorder( Wire the recorder into the
                manager.getBuffer(), config))    inlined-advice's static slot
    5. Build ByteBuddy type matchers              From matchers_include / matchers_exclude
    6. Install advice on instrumentation          Intercepts matched methods at class load
    7. IF propagate_request_id:
         installExecutorInstrumentation()        Retransform ThreadPoolExecutor, ForkJoinPool

  Lazy, on first instrumented method entry:
    8. SpiBootstrap.getSessionIdResolver()       Load + init() the SessionIdResolver
    9. SpiBootstrap.initJpaProxyResolverOnce()   Load JpaProxyResolver, register with Codec
```

Step 2 must happen before step 4. See
[Executor Instrumentation](executor-instrumentation.md) for why.

## ArachnaTraceAdvice — the inlined-advice slot

`ArachnaTraceAdvice` is a thin facade. It carries one writable static field
— `public static volatile RequestRecorder RECORDER` — and the two
ByteBuddy advice methods (`onEnter`, `onExit`) that read it. The
actual recording work lives on `RequestRecorder`, not `ArachnaTraceAdvice`.

Why split: ByteBuddy advice methods MUST be static, but real recording
state (config flags, buffer reference, SPI, value encoder) is naturally
per-instance. The advice reads `RECORDER` and delegates the call to
the live `RequestRecorder` instance — keeping the inlined bytecode
small and the configuration owned by a single object.

`onEnter` returns a boolean. ByteBuddy threads it to `onExit` as
`@Advice.Enter`, so a failed entry suppresses its matching exit and
the call's UUID never gets pushed onto the stack — preventing
mismatch cascades.

## RequestRecorder — the recording owner

`RequestRecorder` (in `agent/recording/`) holds the per-call state and
the flag fields, snapshotted from the config at construction so the hot
path does not pay a `HashMap` lookup per call:

| Field | Purpose |
|---|---|
| `recordBuffer` | The concurrent queue records are offered to |
| `valueEncoder` | CBOR encoder with truncation cap (`max_value_size`) |
| `spi` | Lazy `SpiBootstrap` for SessionIdResolver + JpaProxyResolver |
| `expandThis` | Full `this` vs ref-only |
| `serializeValues` | Full vs structural-only mode |
| `emitTi`, `emitAr`, `emitReturnRecord`, `emitAx` | Per-tag serialization gates |

Request-ID and call-stack state lives separately in `RequestContext`
(in `agent/bootstrap/`, injected into the bootstrap classloader so
JDK-class advice can see it):

| Field | Type | Purpose |
|---|---|---|
| `RequestContext.REQUEST_COUNTER` | `AtomicLong` | Monotonic request-id source |
| `RequestContext.CURRENT_REQUEST_ID` | `ThreadLocal<long[]>` | Active request id on this thread |
| `RequestContext.DEPTH` | `ThreadLocal<int[]>` | Per-thread call depth |
| `RequestContext.CALL_STACK` | `ThreadLocal<Deque<UUID>>` | Open-call UUID stack for parent-id resolution |

See [Executor Instrumentation](executor-instrumentation.md) for why
these fields must live on a separately-injected bootstrap class.

## Recording flow

### `recordEntry(method, self, allArguments) -> boolean`

1. Trigger lazy `JpaProxyResolver` initialization
   (`SpiBootstrap.initJpaProxyResolverOnce()`).
2. Format method signature; read thread name; read **epoch-millisecond**
   timestamp (`System.currentTimeMillis()`); walk caller-frame line
   number via `StackWalker`.
3. Resolve session ID via the active `SessionIdResolver`.
4. `RequestContext.beginRequest()` — increments depth and assigns a
   new request id when depth was 0.
5. Read `parentCallId` (top of `CALL_STACK`) and freshly generate this
   call's UUID.
6. If `serializeValues=true`: build METHOD_START + optional
   THIS_INSTANCE / THIS_INSTANCE_REF + ARGUMENTS records, encoding
   each value via `ValueEncoder.encode()` (which applies the
   truncation cap).
7. If `serialize_values=false`: build METHOD_START only.
8. **Push the new call's UUID onto `CALL_STACK`, then offer the byte[]
   to the buffer.** Returns true.

If anything throws before step 8, depth is rolled back, the byte[] is
not offered, and `false` is returned so `ArachnaTraceAdvice.onExit` skips
the matching exit.

### `recordExit(method, returned, throwable, allArguments)`

1. Pop the call's UUID off `CALL_STACK`. If empty (contract violation),
   bail without writing — degrade silently rather than corrupt the
   stream.
2. `RequestContext.endRequest()` — decrement depth, return the active
   request id.
3. Read thread name, epoch-millisecond timestamp, session ID.
4. If `serializeValues=true`: build METHOD_END + RETURN/EXCEPTION +
   optional ARGUMENTS_EXIT, encoding values via `ValueEncoder.encode()`.
5. If `serialize_values=false`: build METHOD_END only.
6. Offer byte[] to buffer.

## ValueEncoder

`ValueEncoder.encode(Object value)` wraps `Codec.encode()` with a
single behaviour: if `max_value_size > 0` and the encoded payload
exceeds it, replace the payload with a fixed-shape truncation marker
via a second `Codec.encode()` call. The agent always pays the cost of
the original encoding — truncation saves I/O and storage, not CPU. See
[Truncation](../../../docs/truncation.md).

## SpiBootstrap

Both SPI resolvers are loaded lazily on first instrumented method
entry, not at agent startup. `premain` runs before application
classloaders are fully initialized; in particular, Spring Boot's
context classloader is not ready until the application starts, so a
`ServiceLoader` call during `premain` would fail to find resolver
implementations.

- `SpiBootstrap.getSessionIdResolver()` — double-checked locking; on
  first call, picks the resolver matching `config.getSessionResolver()`,
  invokes `resolver.init(config.getConfigMap())`, caches it. Always
  returns non-null (falls back to noop).
- `SpiBootstrap.initJpaProxyResolverOnce()` — double-checked locking;
  on first call, loads the named `JpaProxyResolver` (if any) and
  registers it with `JpaProxyResolvers.setActive()`. Idempotent.

Loading uses the thread's context classloader, falling back to the
system classloader.

## Method signature formatting

`MethodSignatureFormatter.format(Method)` produces:

```
package::ClassName.methodName(param::Types) -> return::Type [modifiers]
```

The `::` separator between the last package segment and the class name
is applied by `formatClassName(Class)`. Array types get `[]` suffix.
Primitives have no package prefix.

## Error isolation

Both `recordEntry` and `recordExit` wrap their work in
`try/catch(Throwable)`. Failures print to `stderr` and do not
propagate into the target application. Additionally, `recordEntry`
returns `false` on failure so its matching exit is suppressed — a
partial-record cascade can never corrupt downstream pairing.

## Key source files

- `core/agent/.../agent/ArachnaTraceAgent.java` — `premain`, ByteBuddy setup, exclusion list
- `core/agent/.../agent/AgentConfig.java` — config parsing
- `core/agent/.../agent/RecorderManager.java` — recorder lifecycle (buffer, drainer, destination, shutdown hook)
- `core/agent/.../agent/advice/ArachnaTraceAdvice.java` — `@Advice` interceptor (thin facade over RequestRecorder)
- `core/agent/.../agent/recording/RequestRecorder.java` — per-call recording logic
- `core/agent/.../agent/recording/ValueEncoder.java` — CBOR encode + truncation
- `core/agent/.../agent/recording/MethodSignatureFormatter.java` — signature rendering
- `core/agent/.../agent/spi/SpiBootstrap.java` — lazy SPI loading
- `core/agent/.../agent/bootstrap/RequestContext.java` — bootstrap-injected ThreadLocal state
