# Agent Module

The agent module is the entry point of DeepFlow. It is a Java agent that
attaches to a target application via `-javaagent` and instruments selected
classes at load time using ByteBuddy.

The agent produces binary records (see [record-format.md](record-format.md))
and offers them to an in-memory buffer. A background drainer thread delivers
records to a configured destination (see [serializer.md](serializer.md)).

## Startup sequence

```
JVM loads -javaagent
  -> DeepFlowAgent.premain(agentArgs, instrumentation)
    1. AgentConfig.getInstance(agentArgs)        Parse config file + CLI args
    2. IF propagate_request_id:
         injectBootstrapClasses()                Inject RequestContext + wrappers
         redefineModule(java.base)               Grant module access
    3. DeepFlowAdvice.setup(config)              Initialize advice statics
       -> RecorderManager.create(config)         Create buffer, destination, drainer
          -> Register JVM shutdown hook           Ensures drain + close on exit
    4. Build ByteBuddy type matchers              From matchers_include / matchers_exclude
    5. Install advice on instrumentation          Intercepts matched methods at class load
    6. IF propagate_request_id:
         installExecutorInstrumentation()        Retransform ThreadPoolExecutor, ForkJoinPool

  On first instrumented method entry (deferred from startup):
    7. Load SessionIdResolver via SPI             Lazy, uses context classloader
    8. Load JpaProxyResolver via SPI              Lazy, registers with Codec
```

Step 2 must happen before step 3. See
[Executor Instrumentation](executor-instrumentation.md) for why.

## DeepFlowAdvice static fields

The advice class uses static fields because ByteBuddy advice methods must be
static. Fields are initialized in `setup()` and read on every method
entry/exit:

| Field | Type | Purpose |
|-------|------|---------|
| `CONFIG` | `AgentConfig` | Full configuration |
| `RECORD_BUFFER` | `RecordBuffer` | Concurrent queue for binary records |
| `SERIALIZE_VALUES` | `boolean` | Full serialization vs structural-only |
| `EXPAND_THIS` | `boolean` | Full `this` vs ref ID only |
| `EMIT_TI/AR/RT/AX` | `boolean` | Per-tag serialization gates |
| `MAX_VALUE_SIZE` | `int` | Truncation cap (0 = unlimited) |
| `SESSION_ID_RESOLVER` | `SessionIdResolver` | Lazily loaded, volatile |

Request ID state lives in `RequestContext` (a separate class injected into
the bootstrap classloader):

| Field | Type | Purpose |
|-------|------|---------|
| `RequestContext.CURRENT_REQUEST_ID` | `ThreadLocal<long[]>` | Per-thread request ID |
| `RequestContext.DEPTH` | `ThreadLocal<int[]>` | Per-thread call depth |
| `RequestContext.REQUEST_COUNTER` | `AtomicLong` | Global request ID generator |

See [Executor Instrumentation](executor-instrumentation.md) for why these
fields are in a separate class and how they are injected into bootstrap.

## Recording flow

### recordEntry(method, self, allArguments)

1. Format method signature from `java.lang.reflect.Method`
2. Get thread name, nanosecond timestamp, caller line number (via StackWalker)
3. Resolve session ID via SPI
4. If depth == 0: assign new request ID from global counter
5. Increment depth
6. If `serialize_values=true`: build METHOD_START + optional THIS_INSTANCE +
   ARGUMENTS records. Values encoded via `encodeWithLimit()` (truncation-aware).
7. If `serialize_values=false`: build METHOD_START only
8. Offer byte[] to buffer

### recordExit(method, returned, throwable, allArguments)

1. Read current request ID (before decrementing depth)
2. Decrement depth
3. Get thread name, nanosecond timestamp, session ID
4. If `serialize_values=true`: build METHOD_END + RETURN/EXCEPTION +
   optional ARGUMENTS_EXIT. Values encoded via `encodeWithLimit()`.
5. If `serialize_values=false`: build METHOD_END only
6. Offer byte[] to buffer

### encodeWithLimit(obj)

Encodes the value via `Codec.encode()`, then checks byte array length against
`MAX_VALUE_SIZE`. If exceeded, replaces with truncation marker. See
[Truncation](../features/truncation.md).

## SPI loading

Both SPI resolvers use double-checked locking and are loaded lazily on first
use:

- `getResolver()` -- loads `SessionIdResolver` matching
  `config.getSessionResolver()` name
- `initJpaProxyResolver()` -- loads `JpaProxyResolver` matching
  `config.getJpaProxyResolver()` name, registers with `Codec`

Loading uses the thread's context classloader (falling back to system
classloader). This ensures framework classes are available for SPI
implementations in container environments like Spring Boot.

## Method signature formatting

`formatMethodSignature(Method)` produces:

```
package::ClassName.methodName(param::Types) -> return::Type [modifiers]
```

The `::` separator between last package segment and class name is applied
by `formatClassName(Class)`. Array types get `[]` suffix. Primitives have
no package prefix.

## Error isolation

Both `recordEntry` and `recordExit` wrap all work in `try/catch(Throwable)`.
Failures print to `stderr` but never propagate to the target application.

## Key source files

- `DeepFlowAgent.java` -- `premain`, ByteBuddy setup, exclusion list
- `DeepFlowAdvice.java` -- `@Advice` interceptor, SPI loading, recording logic
- `AgentConfig.java` -- config parsing
- `RecorderManager.java` -- recorder lifecycle (buffer, drainer, destination,
  shutdown hook)
