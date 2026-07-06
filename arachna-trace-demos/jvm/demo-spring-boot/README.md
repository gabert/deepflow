# Spring Boot Demo — Arachna Trace Agent Integration

This demo shows how to attach the Arachna Trace agent to a Spring Boot application
with session tracking and JPA proxy resolution. Use it as a reference for
integrating Arachna Trace into your own Spring Boot project.

## Prerequisites

- JDK 17+
- Maven
- The shared libs, agent JAR, and JVM extensions built from the
  project root:
  ```bash
  cd arachna-trace-shared        && mvn clean install   # codec / renderer / SPI APIs
  cd ../arachna-trace-agents/jvm && mvn clean install   # the JVM agent
  cd ../../arachna-trace-jvm-extensions && mvn clean install  # the SPI impls
  ```
  This produces
  `arachna-trace-agents/jvm/core/agent/target/arachna-trace-agent.jar`
  plus the SPI impl JARs in `arachna-trace-jvm-extensions/*/target/`.

## Running

**With the automated test script:**
```bash
cd arachna-trace-demos/jvm/demo-spring-boot
bash test-run.sh
```

The script starts the app, exercises the API with two users in separate HTTP
sessions, prints the trace output, and shuts down.

**Manually (for interactive testing):**
```bash
cd arachna-trace-demos/jvm/demo-spring-boot
mvn spring-boot:run \
    -Dspring-boot.run.jvmArguments="-javaagent:../../../arachna-trace-agents/jvm/core/agent/target/arachna-trace-agent.jar=config=./arachna-agent.cfg"
```

Then use curl or a browser against `http://localhost:8080/api/`.

## How to Integrate Arachna Trace into Your Spring Boot App

### 1. Add the SPI extension JARs to your `pom.xml`

The agent JAR is **not** a Maven dependency — it is attached via `-javaagent`.
The SPI implementation JARs *are* dependencies, dropped on the application
classpath so the agent's ServiceLoader can find them. The reference impls
ship from
[`arachna-trace-jvm-extensions/`](../../../arachna-trace-jvm-extensions/) —
each is a self-contained single-class plugin JAR:

```xml
<!-- HTTP session ID resolver for any Spring web / Jakarta Servlet app -->
<dependency>
    <groupId>com.github.gabert</groupId>
    <artifactId>SessionResolverSpring</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>

<!-- Optional: Hibernate proxy / collection unwrapping -->
<dependency>
    <groupId>com.github.gabert</groupId>
    <artifactId>JpaProxyResolverHibernate</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

Each impl jar transitively pulls only the API jar it needs — your app's
classpath stays slim.

### 2. Register `SessionIdFilter` as a `@Bean`

`SessionIdFilter` (from the `SessionResolverSpring` JAR) populates the
ThreadLocal that the resolver reads. It is intentionally **not** annotated
with `@Component` — register it explicitly so the wiring is visible in
your app:

```java
import com.github.gabert.arachna.trace.spi.session.spring.SessionIdFilter;

@SpringBootApplication
public class MyApp {

    public static void main(String[] args) {
        SpringApplication.run(MyApp.class, args);
    }

    @Bean
    public SessionIdFilter sessionIdFilter() {
        return new SessionIdFilter();
    }
}
```

Spring Boot auto-detects `Filter` beans and adds them to the servlet chain.

> **Want to write your own resolver instead?** Each extension module under
> `arachna-trace-jvm-extensions/` is a 1-class worked example — open
> `session-resolver-spring/` and you'll see the entire recipe (one resolver
> class + one `META-INF/services` file + the pom). Copy that shape for any
> custom session source (MDC, OpenTelemetry trace ID, gRPC metadata, etc.).

### 3. Create a `arachna-agent.cfg`

```properties
session_dump_location=D:\temp
matchers_include=com\.example\.yourapp\..*
destination=file
session_resolver=spring-session
jpa_proxy_resolver=hibernate
```

- `matchers_include` — regex matching classes to instrument (comma-separated, OR logic)
- `session_resolver` — must match the `name()` returned by your resolver
- `jpa_proxy_resolver=hibernate` — enables Hibernate proxy unwrapping (omit if not using JPA)

### 4. Attach the agent at startup

**Maven plugin:**
```bash
mvn spring-boot:run \
    -Dspring-boot.run.jvmArguments="-javaagent:/path/to/arachna-trace-agent.jar=config=./arachna-agent.cfg"
```

**JAR execution:**
```bash
java -javaagent:/path/to/arachna-trace-agent.jar="config=./arachna-agent.cfg" \
     -jar your-app.jar
```

**Docker / deployment script:**
```bash
JAVA_OPTS="-javaagent:/opt/arachna-trace/arachna-trace-agent.jar=config=/opt/arachna-trace/arachna-agent.cfg"
java $JAVA_OPTS -jar your-app.jar
```

### 5. Inspect the output

Traces are written to `<session_dump_location>/SESSION-<yyyyMMdd-HHmmss>/`
with one `.dft` file per thread. Files are flushed whenever the drain
queue runs empty, so you
can tail them while the application is running:

```bash
ls D:/temp/SESSION-*/
tail -f D:/temp/SESSION-20260324-*/http-nio-8080-exec-1.dft
```

Each request's traces are tagged with the HTTP session ID (`SI;` lines), so
you can correlate traces to specific users.

## Demo App Structure

```
src/main/java/.../library/
  LibraryApplication.java              Spring Boot main class
  controller/
    LibraryController.java             REST endpoints (/api/authors, /api/books)
  service/
    LibraryService.java                Business logic, DTO → SO mapping
    AuthorSO.java, BookSO.java         Service objects (returned to controller)
  repository/
    LibraryDAO.java                    Data access (JPA queries, entity → DTO)
    AuthorRepository.java              Spring Data JPA repository
    BookRepository.java                Spring Data JPA repository
    AuthorDTO.java, BookDTO.java       Data transfer objects
  model/
    AuthorEntity.java, BookEntity.java JPA entities (H2 in-memory)
```

The session-handling classes (`SessionIdHolder`, `SessionIdFilter`,
`SpringSessionIdResolver`) used to live inside this demo. They have
since been promoted into the shipped `SessionResolverSpring` module
(under `arachna-trace-jvm-extensions/`) — the demo now consumes them
as a dependency. `LibraryApplication.java` registers `SessionIdFilter`
as a `@Bean`; that's the entire glue.


## AI-code-audit demo (`audit-demo.sh`)

Records the same scenario under **two code versions** of the restock
appraisal — `classic` and an "AI-refactored" variant
(`library.restock.policy` property; see `RestockAppraiser` for what the
refactor changes and why it looks harmless in a code diff) — ships both
sessions through the centralised pipeline, and prints links to the
comparative screens.

The refactored version carries four artifacts typical of agent-written
edits, each visible on a different screen:

| Artifact | Where it shows |
|---|---|
| Legacy-identifier exception silently swallowed (vintage premium lost) | Flow narrative: `⚠ exception` on `checkDigitOf` while the parent returns normally; Behavior diff: `appraise` output changed (200.00 → 80.00) |
| Rounding switched HALF_UP → HALF_EVEN | Behavior diff: `round(29.325)` → 29.33 vs 29.32 |
| Quote lines sorted in place | Flow narrative: `± mutates args` on `summarize` |
| Dropped / added helpers | Behavior diff: `isIsbn13`/`vintagePremium` only in A, `fallbackRarity` only in B |

Prerequisites: the pipeline from `arachna-trace-infra/` (docker compose
with Kafka + ClickHouse, collector, processor, query server) and
optionally the UI dev server. Then:

```bash
bash audit-demo.sh
```

See [AI code audit](../../../arachna-trace-agents/docs/ai-code-audit.md)
for the workflows these screens implement.
