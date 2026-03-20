# Agent Packaging and Dependency Shading

The agent module produces a single fat JAR (`deepflow-agent.jar`) via
`maven-shade-plugin`. This JAR is loaded into the target application's JVM
via the `-javaagent` flag.

## Why shading is required

A Java agent shares the same classloader namespace as the target application.
If the target application uses Jackson 2.15 and the agent bundles Jackson 2.17,
class conflicts and runtime errors would occur. The same applies to ByteBuddy —
a Spring Boot application may bundle its own version.

The shade plugin solves this by **relocating** all third-party packages into
the agent's own namespace at build time. All bytecode references are rewritten
automatically — no runtime classloader tricks are needed.

## Relocation table

| Original package         | Relocated to                                               |
|--------------------------|------------------------------------------------------------|
| `com.fasterxml.jackson`  | `com.github.gabert.deepflow.shaded.com.fasterxml.jackson`  |
| `net.bytebuddy`          | `com.github.gabert.deepflow.shaded.net.bytebuddy`          |

The agent's own modules (`codec`, `record-format`, `serializer`) are bundled
as-is. Their packages (`com.github.gabert.deepflow.*`) are unique and do not
conflict with target application classes.

## What gets bundled

The shaded JAR includes all transitive dependencies:

| Module / Dependency          | Purpose                              |
|------------------------------|--------------------------------------|
| `DeepFlowCodec`              | CBOR serialization of captured data  |
| `DeepFlowRecordFormat`       | Binary wire format for trace records |
| `DeepFlowSerializer`         | Buffer, drainer, and destinations    |
| `jackson-databind`           | JSON/CBOR object mapping (shaded)    |
| `jackson-dataformat-cbor`    | CBOR binary format support (shaded)  |
| `byte-buddy`                 | Bytecode instrumentation (shaded)    |

## Excluded resources

Signature files (`META-INF/*.SF`, `*.DSA`, `*.RSA`) are stripped from the
shaded JAR. Repackaged dependencies carry invalid signatures that would cause
`SecurityException` at class load time if left in.

JDK 24 multi-release class files (`META-INF/versions/24/**`) are also excluded
to avoid compatibility issues.

## Build

```bash
cd deepflow-agent/agent
mvn clean install
# Output: target/deepflow-agent.jar
```

## Usage

```bash
java -javaagent:deepflow-agent.jar=config=deepagent.cfg \
     -cp <your-app.jar> com.example.MainClass
```

The `config` parameter accepts a file path. Additional key-value pairs can be
appended with `&` to override file values:

```bash
java -javaagent:deepflow-agent.jar=config=deepagent.cfg&destination=zip \
     -cp <your-app.jar> com.example.MainClass
```
