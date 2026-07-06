# Getting Started

## Prerequisites

- JDK 17 (Java 11 may work but is not tested)
- Maven 3.x

## Build

```bash
cd arachna-trace-shared        && mvn clean install   # codec / renderer / SPI APIs
cd ../arachna-trace-agents/jvm && mvn clean install   # the JVM agent
```

This compiles all modules and produces the agent JAR at:

```
arachna-trace-agents/jvm/core/agent/target/arachna-trace-agent.jar
```

The JAR is self-contained (ByteBuddy, Jackson, codec, serializer are shaded
in). SPI resolver JARs are not bundled -- they go on the application classpath.

## Configure

Create `arachna-agent.cfg` (or copy the
[reference config](../arachna-agent.cfg)):

```properties
session_dump_location=D:\temp
matchers_include=com\.example\.myapp\..*
```

`matchers_include` is a comma-separated list of regexes matched against
fully-qualified class names. Only matched classes are instrumented.

See [Agent Configuration](reference/agent-config.md) for all options.

## Attach and run

```bash
java -javaagent:path/to/arachna-trace-agent.jar="config=path/to/arachna-agent.cfg" \
     -jar your-app.jar
```

For Spring Boot with Maven:

```bash
mvn spring-boot:run \
    -Dspring-boot.run.jvmArguments="-javaagent:path/to/arachna-trace-agent.jar=config=./arachna-agent.cfg"
```

Config values can be overridden inline (inline takes precedence over file):

```bash
java -javaagent:agent.jar="config=arachna-agent.cfg&serialize_values=false" -jar app.jar
```

### SPI resolver JARs

SPI resolver JARs (session resolver, JPA proxy resolver) go on the
**application classpath**, not inside the agent JAR. They need access to
framework classes (Hibernate, Spring session).

**Spring Boot** -- add as Maven dependencies.

**Non-Spring-Boot:**

```bash
java -javaagent:arachna-trace-agent.jar="config=arachna-agent.cfg" \
     -cp "your-app.jar;session-resolver-config.jar" com.example.MainClass
```

On Linux/Mac use `:` as classpath separator instead of `;`.

## Read the traces

Output is written to `<session_dump_location>/SESSION-<yyyyMMdd-HHmmss>/`
with one `.dft` file per thread. Files are flushed whenever the drain
queue runs empty, so
traces are readable while the application is still running.

```bash
ls D:/temp/SESSION-*/
head -30 D:/temp/SESSION-20260323-101215/20260323-101215-main.dft
```

A typical trace looks like this:

```
VR;1.3
TS;1730412345678
SI;alice-session-01
MS;com.example::BookService.findByAuthor(long) -> java.util::List [public]
TN;http-nio-8080-exec-3
RI;5
CL;42
CI;1a2b3c4d-1111-2222-3333-aaaaaaaaaaaa
PI;0fedcba9-9999-8888-7777-bbbbbbbbbbbb
TI;17
AR;[3]
TE;1730412345712
TN;http-nio-8080-exec-3
RI;5
CI;1a2b3c4d-1111-2222-3333-aaaaaaaaaaaa
RT;VALUE
RE;[{"object_id":101,"class":"java.util.ArrayList","value":[...]}]
```

Timestamps are milliseconds since Unix epoch. `CI` and `PI` are the
call's UUID and its enclosing call's UUID — calls are paired by `CI`,
not by stack ordering, so a stream that interleaves threads still parses
correctly.

See [Trace Format](../../../spec/TAGS.md) for the complete format specification.

## Run the Spring Boot demo

A working example with session tracking, JPA proxy resolution, and an
automated two-user test script:

```bash
cd arachna-trace-agents/jvm
mvn clean install
cd ../../arachna-trace-demos/jvm/demo-spring-boot
bash test-run.sh
```

The script starts the app with the agent, exercises the API with two users
(Alice and Bob) in separate HTTP sessions, shuts down, and prints the
collected traces.

To start the app manually for interactive testing:

```bash
cd arachna-trace-demos/jvm/demo-spring-boot
mvn spring-boot:run \
    -Dspring-boot.run.jvmArguments="-javaagent:../../../arachna-trace-agents/jvm/core/agent/target/arachna-trace-agent.jar=config=./arachna-agent.cfg"
```

## Run the tests

```bash
cd arachna-trace-agents/jvm
mvn clean install    # runs all module tests
```
