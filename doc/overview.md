# DeepFlow Overview

## The problem

Software breaks in two ways. Crashes and exceptions are loud -- you get a
stack trace, you find the bug, you fix it. Data errors are silent. The
application runs fine, returns HTTP 200, commits to the database, and the
result is wrong. A price is off. A permission check passes when it shouldn't.
A transaction settles with the wrong amount.

These bugs are hard because the code did what it was told. The problem is
in the values, not the structure. To find it you need to see what actually
went into each method and what came out. And today there's no good way to
do that.

Log statements only capture what someone thought to log. APM tools
(OpenTelemetry, Datadog) work at service boundaries -- they tell you a
request took 200ms, not that the discount was applied to the wrong line
item. Debuggers require reproducing the exact scenario interactively, which
is often impossible in production-like environments.

So data errors get debugged the way they always have: add print statements,
redeploy, reproduce, read the output, repeat. For a simple bug that's just
annoying. For a production issue in a bank or a defence system, it's a
serious problem.

## What DeepFlow does

DeepFlow is a Java agent that records what your code actually does with
data at runtime. Attach it via `-javaagent`, point it at your packages, run
the application. For every instrumented method it captures:

- Method signature, arguments, return value (or exception)
- Object identity -- the same `Order` instance gets the same ID everywhere
- Arguments at exit (optional) -- did the method mutate its inputs?
- Nanosecond timestamps, request ID, session ID, caller line number

No code changes. No annotations. No SDK. Just attach and run.

The result is a complete recording of what happened during execution --
every method call with its actual data, ordered in time. Think of it as
a debugging session that you can move forward and backward through without
restarting the application or reproducing the scenario. The data is
already there: every argument, every return value, every mutation,
timestamped to the nanosecond. You navigate the recording, not the live
process.

Because traces are deterministic records, a verified trace can serve as
a baseline. Run the same scenario after a code change and compare the
two traces. If the data flow changed, you see exactly where it diverged --
which method received different arguments, which return value shifted.
This turns traces into regression tests over actual runtime data, not
just over expected outputs.

The agent supports two destinations. **File** writes structured text files
locally (one `.dft` file per thread) -- suitable for local debugging and
development. **HTTP** sends binary records to a collector server that
stores them via Kafka into ClickHouse -- suitable for shared environments,
production tracing, and team-wide analysis through a query interface.

Both destinations capture the same data. The difference is where it lands.

## Two modes of use

DeepFlow is designed to work in two modes. Both use the same agent and
produce the same trace data -- the difference is who reads them.

**Human mode.** A developer attaches the agent, reproduces the scenario,
and reads the trace directly. In file mode, the `.dft` files are
structured text -- method signatures, argument values, return values,
timestamps -- readable in any editor. In HTTP mode, the same data is
queryable from ClickHouse. Either way, a human reads the trace and finds
the bug. No AI involved.

This mode matters for two reasons. First, data sensitivity: financial
transactions, classified data, patient records, cryptographic material --
when the trace contains values that must not leave a controlled
environment, only verified humans access the data. No external system,
including LLMs, ever sees it. Second, cost: LLM-based analysis of
detailed traces consumes significant tokens and adds up quickly. A
developer reading a structured trace costs nothing beyond their time and
is often faster for focused debugging.

**AI-assisted mode.** The same trace data can be fed to an LLM for
automated analysis. The Python formatter (`deepflow-formater`) can output
traces in a compact semicolon-delimited format designed for token-efficient
LLM consumption. This is useful for verifying AI-generated code (run the
feature, feed the trace to a reviewer) or for large traces where manual
reading is impractical.

Both modes can work with either destination. File mode keeps everything
local. HTTP mode centralizes traces but access is still controlled --
nothing reaches an LLM unless you choose to send it.

## Why this matters

**Data bugs are expensive.** A null pointer is found in minutes. A wrong
calculation that produces plausible results can go undetected for months.
When it's finally discovered, nobody knows what the data looked like when
it flowed through the system. With DeepFlow attached, the answer is in
the trace -- locally in `.dft` files or centrally in ClickHouse.

**AI agents write a lot of code.** Tools like Claude Code, Cursor, and
Copilot are generating significant portions of application code. It
compiles, tests pass, CI is green. But does the data actually flow
correctly? Unit tests verify the cases someone anticipated. They can't
cover every path through a complex system. DeepFlow lets you run the
scenario and see exactly what happened -- what values arrived at each
method, what transformations occurred, what came back.

**Regulated industries can't just trust the tests.** In financial services,
defence, and healthcare, "it passes the tests" isn't enough. Auditors and
compliance teams need to verify that data flows correctly -- that a price
was calculated from the right inputs, that classified data stayed in the
right code path, that patient records were accessed only by authorized
services. DeepFlow captures every method call with its actual data. It's
not sampling, not probabilistic -- it's a complete record that can serve
as evidence.

## How it compares

Most tracing tools capture structure (which methods were called, how long
they took). DeepFlow captures data (what values went in, what came out,
what changed).

| | OpenTelemetry / APM | Profilers | DeepFlow |
|---|---|---|---|
| Granularity | Service boundaries | Sampled methods | Every instrumented method |
| Argument capture | Manual | No | Automatic |
| Return value capture | Manual | No | Automatic |
| Mutation detection | No | No | Yes (AR vs AX) |
| Object identity | No | No | Yes (stable object_id) |
| Session grouping | Trace ID | No | Session resolver SPI |
| Code changes needed | Yes | No | No |

No other tool combines automatic value capture, mutation detection, and
object identity tracking with zero code changes.

## Use cases

**Debugging data errors.** Reproduce the scenario, read the trace. See
where the correct value goes in and the wrong one comes out. Move forward
and backward through the recording without restarting the application --
the full execution is already captured.

**Regression detection.** Record a verified flow as a baseline. After a
code change, run the same scenario and compare traces. If the data flow
diverged -- different arguments, different return values, a mutation that
wasn't there before -- you see exactly which method changed.

**Understanding unfamiliar code.** Instrument the packages, trigger a user
flow, read the trace. Real execution with real data.

**Detecting mutations.** Enable AX, compare entry arguments with exit
arguments. Find which method changed the list, the map, the entity.

**Finding dead code.** Set `serialize_values=false`, run the test suite.
Methods not in the trace were never called.

**Verifying AI-generated code.** Run the feature, read the trace yourself
or feed it to an AI reviewer. Confirm the data flows correctly without
relying solely on tests.

**Auditing data flows.** Capture traces during test runs. Hand them to
compliance or security reviewers as evidence.

## Components

- **deepflow-agent/** -- Java Maven project. The agent, codec, binary
  format, serializer, SPI interfaces, and demos.
- **deepflow-formater/** -- Python post-processor. Parses `.dft` files,
  computes content hashes for mutation detection, outputs human-readable
  or ML-ready formats.

## Further reading

- [Getting Started](getting-started.md) -- build, attach, configure, read output
- [Configuration Reference](configuration.md) -- all config options
- [Trace Format](trace-format.md) -- the `.dft` file format specification
- [Architecture](architecture.md) -- data flow, modules, design decisions
