# DeepFlow

A Java application tracing tool that captures the complete runtime data flow
of your application -- method arguments, return values, exceptions, object
identity, and object mutations -- without any code changes.

Attach it via `-javaagent`, point it at your packages, reproduce the problem,
and read the trace.

## Components

- **deepflow-agent/** -- Java agent for bytecode instrumentation and trace
  capture. See [deepflow-agent/README.md](deepflow-agent/README.md) for
  quick start.
- **deepflow-formater/** -- Python post-processor for trace output (mutation
  detection, formatting).

## Documentation

All documentation is in [doc/](doc/):

- [Overview](doc/overview.md) -- what DeepFlow is and how it's different
- [Getting Started](doc/getting-started.md) -- build, attach, configure
- [Configuration Reference](doc/configuration.md) -- all config options
- [Trace Format](doc/trace-format.md) -- `.dft` file format specification

Features:
- [Request ID](doc/features/request-id.md) -- request correlation and cross-thread propagation
- [Truncation](doc/features/truncation.md) -- capping serialized value size
- [Mutation Detection](doc/features/mutation-detection.md) -- detecting argument changes
- [Serialization Modes](doc/features/serialize-modes.md) -- full vs structural-only

Internals:
- [Architecture](doc/architecture.md) -- data flow and module structure
- [Agent](doc/internals/agent.md) -- bytecode instrumentation
- [Binary Format](doc/internals/record-format.md) -- wire protocol
- [CBOR Codec](doc/internals/codec.md) -- object serialization
- [Serializer](doc/internals/serializer.md) -- recording pipeline

SPI:
- [Session Resolver](doc/spi/session-resolver.md) -- session ID injection
- [JPA Proxy Resolver](doc/spi/jpa-proxy-resolver.md) -- Hibernate proxy unwrapping

## Quick start

```bash
cd deepflow-agent
mvn clean install
java -javaagent:core/agent/target/deepflow-agent.jar="config=deepagent.cfg" -jar your-app.jar
```
