# Executor Instrumentation

When `propagate_request_id=true` (the default), the agent instruments JDK
executor classes so that request IDs propagate from a submitting thread to
the worker thread. Without this, every pool thread would start its own
independent request ID, breaking the correlation across async boundaries.

This document explains _how_ the agent modifies JDK classes and the three
obstacles that must be overcome to do so safely.

## What gets instrumented

Two JDK classes are targeted:

| Class | Method | Wrapper |
|-------|--------|---------|
| `ThreadPoolExecutor` | `execute(Runnable)` | `PropagatingRunnable` |
| `ForkJoinPool` | `execute(Runnable)` | `PropagatingRunnable` |
| `ForkJoinPool` | `submit(Callable)` | `PropagatingCallable` |

The advice replaces the incoming `Runnable` or `Callable` with a wrapper
that captures the submitting thread's request ID and restores it in the
worker thread:

```
Submitting thread (RI=10):
  executor.execute(task)
    -> ExecutorAdvice.onEnter():
         task = new PropagatingRunnable(task, currentRequestId=10)

Worker thread:
  PropagatingRunnable.run():
    set currentRequestId = 10       <- restore parent's RI
    set depth = 1                   <- force non-root so no new RI is assigned
    delegate.run()                  <- application code runs with RI=10
    restore previous RI and depth   <- clean up
```

This covers `ExecutorService.submit()`, `CompletableFuture.supplyAsync()`,
Spring `@Async`, and WebFlux reactive schedulers, because they all route
through `ThreadPoolExecutor.execute()` or `ForkJoinPool.execute()/submit()`.

## The three obstacles

ByteBuddy advice works by **inlining**: it copies the bytecode from our
advice method and pastes it directly into the target method. After
instrumentation, `ThreadPoolExecutor.execute()` literally contains our code.

This is important because the inlined code runs in the context of the target
class -- it uses the target class's classloader, module, and access rules.
JDK classes live in a restricted environment, which creates three problems.

### 1. Classloader visibility

**Problem.** Java uses a hierarchy of classloaders:

```
Bootstrap classloader     loads java.*, javax.* (the JDK itself)
  +-- System classloader  loads application classes from -classpath
```

`ThreadPoolExecutor` is loaded by the bootstrap classloader. Our agent
classes (`RequestContext`, `PropagatingRunnable`) are loaded by the system
classloader.

A class can only see classes loaded by its own classloader or an ancestor.
Bootstrap is the root -- it has no parent. When the inlined advice code
inside `ThreadPoolExecutor.execute()` references `RequestContext`, the JVM
resolves it using the bootstrap classloader, which cannot see system classes.
Result: `ClassNotFoundException`.

**Solution.** Before any instrumentation happens, `DeepFlowAgent` creates a
temporary JAR containing only the three classes that the inlined code needs
and appends it to the bootstrap classloader's search path:

```java
instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(tempJar));
```

The temp JAR contains exactly:
- `RequestContext.class` -- holds the ThreadLocal state
- `PropagatingRunnable.class` -- wraps Runnable
- `PropagatingCallable.class` -- wraps Callable

**Why not append the entire agent JAR?** The agent JAR contains shaded
ByteBuddy classes (relocated to `com.github.gabert.deepflow.shaded`).
ByteBuddy is also loaded by the system classloader to perform the
instrumentation. Putting the same classes on two classloaders causes
`LinkageError: loader constraint violation` when objects cross the boundary.

**Why read class bytes as resources?** Writing `RequestContext.class` in Java
source triggers the JVM to load the class immediately via the current
(system) classloader. If the system classloader loads `RequestContext` first,
and we then inject it into bootstrap, two copies exist. The ThreadLocal in
the bootstrap copy is a _different_ object from the one in the system copy,
so the submitting thread and the worker thread would read different
ThreadLocals. Instead, we read raw bytes via `getResourceAsStream()` without
triggering class loading:

```java
ClassLoader cl = DeepFlowAgent.class.getClassLoader();
InputStream is = cl.getResourceAsStream("com/github/.../RequestContext.class");
```

This ensures that when `RequestContext` is referenced for the first time by
anyone, it is loaded from bootstrap. All threads share the same instance.

### 2. Default ignore rules

**Problem.** `AgentBuilder.Default()` ships with a built-in ignore rule that
silently skips all `java.*` classes. This is a safety feature -- you usually
do not want to accidentally instrument the JDK.

Our type matcher `.type(ElementMatchers.is(ThreadPoolExecutor.class))` does
match, but the ignore rule discards the match before the transformation is
applied. There is no error, no warning. The instrumentation silently does
nothing.

**Solution.** Override the default ignore rule for the two AgentBuilder
instances that target JDK classes:

```java
new AgentBuilder.Default()
    .ignore(ElementMatchers.none())   // do not ignore anything
    .type(ElementMatchers.is(ThreadPoolExecutor.class))
    ...
```

The main application AgentBuilder (the one that instruments user classes)
keeps the default ignore rules. Only the executor-targeting builders need the
override.

### 3. Module system access (JPMS)

**Problem.** Java 9+ organizes the JDK into modules with strict access
rules. `ThreadPoolExecutor` lives in `java.base`. A module can only access
classes in modules it explicitly _reads_.

The three bootstrap-injected classes do not belong to any named module. They
land in the _unnamed module_ of the bootstrap classloader. Even though they
are on the bootstrap classpath (obstacle 1 solved), and the instrumentation
is applied (obstacle 2 solved), `java.base` does not have a reads edge to
the unnamed module. The inlined code fails at runtime:

```
IllegalAccessError: module java.base cannot access class
    com.github.gabert.deepflow.agent.PropagatingRunnable
    (in unnamed module @0x...)
```

**Solution.** After injecting the classes, add an explicit reads edge:

```java
Class<?> injected = Class.forName(
    "com.github.gabert.deepflow.agent.RequestContext", true, null);
Module javaBase      = Object.class.getModule();
Module unnamedModule = injected.getModule();

instrumentation.redefineModule(
    javaBase,                     // target module to modify
    Set.of(unnamedModule),        // add reads: java.base -> unnamed module
    Map.of(), Map.of(),           // no exports/opens changes
    Set.of(), Map.of()            // no uses/provides changes
);
```

The classes themselves must also be declared `public` -- the module reads
edge grants access to the module, but Java access control still requires
public visibility.

## Execution order

The three fixes must happen in a specific order during `premain()`:

```
premain(agentArgs, instrumentation)
  1. Parse config
  2. IF propagate_request_id:
       a. Build temp JAR with 3 classes        (solve classloader visibility)
       b. Append temp JAR to bootstrap CL
       c. Force-load RequestContext from bootstrap (via Class.forName with null CL)
       d. Add module reads edge                (solve JPMS access)
  3. DeepFlowAdvice.setup(config)              (safe: RequestContext is on bootstrap)
  4. Install main application advice
  5. IF propagate_request_id:
       e. Install ThreadPoolExecutor advice    (with ignore override)
       f. Install ForkJoinPool advice          (with ignore override)
```

Step 2 must come before step 3. `DeepFlowAdvice.setup()` may reference
`RequestContext`, and if the system classloader loads it first, the bootstrap
copy becomes a separate instance (see obstacle 1 above).

Steps e/f use `AgentBuilder.RedefinitionStrategy.RETRANSFORMATION` because
`ThreadPoolExecutor` and `ForkJoinPool` are already loaded by the time
`premain` runs.

## RequestContext

`RequestContext` is a minimal, dependency-free class that holds the three
pieces of ThreadLocal state needed for request ID tracking:

```java
public class RequestContext {
    public static final AtomicLong REQUEST_COUNTER = ...;
    public static final ThreadLocal<long[]> CURRENT_REQUEST_ID = ...;
    public static final ThreadLocal<int[]> DEPTH = ...;
}
```

This state was extracted from `DeepFlowAdvice` into its own class
specifically to minimize what must be injected into the bootstrap
classloader. `DeepFlowAdvice` has many dependencies (codec, record-format,
config); injecting it into bootstrap would require injecting the entire agent.

Both `DeepFlowAdvice` (loaded by system CL) and `PropagatingRunnable`
(loaded by bootstrap CL) reference `RequestContext`. Because `RequestContext`
is loaded from bootstrap, both see the same class instance and share the same
ThreadLocals.

## Key source files

- `DeepFlowAgent.java` -- `premain()`, `injectBootstrapClasses()`,
  `installExecutorInstrumentation()`
- `RequestContext.java` -- ThreadLocal state (bootstrap-injected)
- `ExecutorAdvice.java` -- advice for `ThreadPoolExecutor.execute()`
- `ForkJoinAdvice.java` -- advice for `ForkJoinPool.execute()/submit()`
- `PropagatingRunnable.java` -- Runnable wrapper (bootstrap-injected)
- `PropagatingCallable.java` -- Callable wrapper (bootstrap-injected)
