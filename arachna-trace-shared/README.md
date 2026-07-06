# arachna-trace-shared

Language-neutral umbrella reactor. Everything that's portable across
agent and infra — and across future non-JVM agents — lives here.

This is the **only** reactor that both the agent reactor
([`arachna-trace-agents/`](../arachna-trace-agents/)) and the infra
reactor ([`arachna-trace-infra/`](../arachna-trace-infra/)) depend on.
It is the load-bearing center of the system.

## Modules

| Module | Artifact | Holds |
|---|---|---|
| [`codec/`](codec/) | `ArachnaTraceCodec` | CBOR encode/decode, the envelope (object identity / cycle handling), `Hasher` (Merkle + own_hash content addressing), `AgentRun` (cross-platform per-process identity record + the seven canonical transport header names), and the binary wire-format record types (MS / ME / AR / AX / RE / etc.) plus their reader/writer. |
| [`renderer/`](renderer/) | `ArachnaTraceRenderer` | `RecordRenderer` (typed records → tag-line text), `PayloadDecoder` (CBOR payload → readable JSON) and `RecordHashEnricher` (injects `__meta__` blocks via Hasher, per line or per payload JSON). Package `com.github.gabert.arachna.trace.renderer`. Used by both the file destination on the agent side and the processor on the server side — same code, two deployment paths. |
| [`spi/session-resolver-api/`](spi/session-resolver-api/) | `SessionResolverApi` | Single interface: `SessionIdResolver`. Pure abstraction; zero deps. |
| [`spi/jpa-proxy-resolver-api/`](spi/jpa-proxy-resolver-api/) | `JpaProxyResolverApi` | Single interface: `JpaProxyResolver`. Pure abstraction; zero deps. |

The two SPI api modules are intentionally separate so SPI **impls**
can depend on just the one API they implement, without dragging in
the entire codec module. Reference impls live in
[`../arachna-trace-jvm-extensions/`](../arachna-trace-jvm-extensions/).

## Build

```bash
cd arachna-trace-shared
mvn clean install
```

Single linear reactor. No external `arachna-trace-*` deps. The codec
module pulls in `JpaProxyResolverApi` for the proxy-unwrap hook (a
sibling sub-module in the same reactor); everything else is Jackson +
JUnit at most.

## Why a shared umbrella

The original layout had codec, renderer, SPI APIs, record-format, and
AgentRun scattered across the agent reactor (`arachna-trace-agents/jvm/`).
That obscured a real architectural truth: most of that code is **not
language-specific**. The CBOR codec, the wire-format types, the hasher,
the rendering pipeline, the SPI interfaces — none of them have any reason
to live inside the JVM agent's bytecode-instrumentation guts.

Pulling them out as a single shared umbrella makes the JVM agent reactor
shrink to "code that uses ByteBuddy or otherwise touches the JVM API"
and makes the cross-agent contract physically obvious: a future Python
or Go agent reads CBOR records, fills in transport headers per
`AgentRun.Headers`, and ships them. That's it.

## Educative reading order

If you're new to the repo, read the modules in this order to build a
mental model:

1. `codec/Codec.java` — the public facade
2. `codec/envelope/EnvelopeSerializer.java` — how Java objects become CBOR with identity
3. `codec/recorder/record/RecordType.java` + `RecordWriter.java` — the binary wire format
4. `codec/Hasher.java` — Merkle + own_hash content addressing
5. `codec/AgentRun.java` — the cross-platform identity record
6. `renderer/RecordRenderer.java` + `renderer/PayloadDecoder.java` — binary back to readable text
7. `renderer/RecordHashEnricher.java` — adding `__meta__` to payload JSON
8. `spi/*-api/` — single interfaces; ~30 lines each

Then jump to the spec at [`../spec/`](../spec/) to see the
language-neutral contract that all of this is the reference
implementation of.
