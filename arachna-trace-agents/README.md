# Arachna Trace Agents

Parent directory for **language-specific agent implementations**.

An agent hooks into a running program and emits trace records in the
[language-neutral wire format](../spec/SPEC.md). Each runtime needs
its own implementation (bytecode for the JVM, CLR profiler API for
.NET, `sys.monitoring` for Python, eBPF uprobes for native, …) — but
they all speak the same wire format on the way out, so any conformant
agent plugs into the shared [infra](../arachna-trace-infra/) pipeline
without infrastructure changes.

## Implementations

| Language | Directory | Status |
|---|---|---|
| JVM (Java, Kotlin, Scala, Groovy) | [`jvm/`](jvm/) | reference implementation |
| .NET, Python, Node, Go, Ruby, native | — | open contribution surface; see [../CONTRIBUTING.md](../CONTRIBUTING.md) §A1 |

## Generic agent docs (any language)

The [`docs/`](docs/) directory holds concepts every agent must
implement regardless of source language:

- [Concepts and vocabulary](docs/concepts.md) — `object_id`, `own_hash`, `call_id`, `agent_run_id`, …
- [Mutation detection](docs/mutation-detection.md) — AR/AX argument-at-exit pattern
- [Request ID propagation](jvm/docs/request-id.md) — cross-thread correlation (JVM writeup; other agents will need their own)
- [Truncation contract](docs/truncation.md) — size cap behaviour
- [Serialize modes](docs/serialize-modes.md) — full vs structural-only
- [Argument names](jvm/docs/reference/argument-names.md) — keyed argument capture (JVM writeup; other agents will need their own)
- [Reading a trace](docs/reading-a-trace.md) — interpreting the rendered output any agent produces
- [Bug-finding workflow](../arachna-trace-ui/docs/internals/bug-finding.md) — forensic workflow against any conformant trace
- [AI code audit](docs/ai-code-audit.md) — reviewing agent-generated code by recorded behavior: flow narrative, behavioral diff, liveness sweep
- [Process docs](docs/process/) — `KNOWN_BUGS.md`, `ROADMAP.md` (project-wide)

## Implementation-specific docs

Each language subdirectory has its own `docs/` for build, attach, and
runtime details that are specific to that runtime (e.g. ByteBuddy
quirks, SPI extension points, Spring Boot integration). For the JVM
agent: [`jvm/docs/`](jvm/docs/).

## Contract

The full normative contract any conformant agent must satisfy lives
in [`../spec/`](../spec/), starting with [`SPEC.md`](../spec/SPEC.md).
[`PORTING-GUIDE.md`](../spec/PORTING-GUIDE.md) is the practical
checklist for writing an agent in a new language.
