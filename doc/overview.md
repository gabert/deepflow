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

The output is structured text files (one per thread) that you can read
directly, diff between runs, or feed into analysis tools.

## Why this matters now

**AI agents write a lot of code.** Tools like Claude Code, Cursor, and
Copilot are generating significant portions of application code. It
compiles, tests pass, CI is green. But does the data actually flow
correctly? Unit tests verify the cases someone anticipated. They can't
cover every path through a complex system. DeepFlow lets you run the
scenario and see exactly what happened -- what values arrived at each
method, what transformations occurred, what came back. You can verify
AI-generated code by reading the trace instead of guessing from the source.

**Regulated industries can't just trust the tests.** In financial services,
defence, and healthcare, "it passes the tests" isn't enough. Auditors and
compliance teams need to verify that data flows correctly -- that a price
was calculated from the right inputs, that classified data stayed in the
right code path, that patient records were accessed only by authorized
services. DeepFlow captures every method call with its actual data. It's
not sampling, not probabilistic -- it's a complete record that can serve
as evidence.

**Data bugs are expensive.** A null pointer is found in minutes. A wrong
calculation that produces plausible results can go undetected for months.
When it's finally discovered, nobody knows what the data looked like when
it flowed through the system. With DeepFlow attached, the answer is in the
trace file.

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
where the correct value goes in and the wrong one comes out.

**Verifying AI-generated code.** Run the feature, read the trace, confirm
the data flows correctly. Optionally feed the trace to an AI reviewer.

**Auditing data flows.** Capture traces during test runs. Hand them to
compliance or security reviewers as evidence.

**Understanding unfamiliar code.** Instrument the packages, trigger a user
flow, read the trace. Real execution with real data.

**Detecting mutations.** Enable AX, compare entry arguments with exit
arguments. Find which method changed the list, the map, the entity.

**Finding dead code.** Set `serialize_values=false`, run the test suite.
Methods not in the trace were never called.

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
