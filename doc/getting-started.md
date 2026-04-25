# Getting Started

## Prerequisites

- JDK 17 (Java 11 may work but is not tested)
- Maven 3.x
- Python 3.6+ (for the formatter, optional)

## Build

```bash
cd deepflow-agent
mvn clean install
```

This compiles all modules and produces the agent JAR at:

```
core/agent/target/deepflow-agent.jar
```

The JAR is self-contained (ByteBuddy, Jackson, codec, serializer are shaded
in). SPI resolver JARs are not bundled -- they go on the application classpath.

## Configure

Create `deepagent.cfg` (or copy the
[reference config](../deepflow-agent/deepagent.cfg)):

```properties
session_dump_location=D:\temp
matchers_include=com\.example\.myapp\..*
```

`matchers_include` is a comma-separated list of regexes matched against
fully-qualified class names. Only matched classes are instrumented.

See [Configuration Reference](configuration.md) for all options.

## Attach and run

```bash
java -javaagent:path/to/deepflow-agent.jar="config=path/to/deepagent.cfg" \
     -jar your-app.jar
```

For Spring Boot with Maven:

```bash
mvn spring-boot:run \
    -Dspring-boot.run.jvmArguments="-javaagent:path/to/deepflow-agent.jar=config=./deepagent.cfg"
```

Config values can be overridden inline (inline takes precedence over file):

```bash
java -javaagent:agent.jar="config=deepagent.cfg&serialize_values=false" -jar app.jar
```

### SPI resolver JARs

SPI resolver JARs (session resolver, JPA proxy resolver) go on the
**application classpath**, not inside the agent JAR. They need access to
framework classes (Hibernate, Spring session).

**Spring Boot** -- add as Maven dependencies.

**Non-Spring-Boot:**

```bash
java -javaagent:deepflow-agent.jar="config=deepagent.cfg" \
     -cp "your-app.jar;session-resolver-config.jar" com.example.MainClass
```

On Linux/Mac use `:` as classpath separator instead of `;`.

## Read the traces

Output is written to `<session_dump_location>/SESSION-<yyyyMMdd-HHmmss>/`
with one `.dft` file per thread. Files are flushed after each record, so
traces are readable while the application is still running.

```bash
ls D:/temp/SESSION-*/
head -30 D:/temp/SESSION-20260323-101215/20260323-101215-main.dft
```

A typical trace looks like this:

```
VR;1.1
TS;82741936205100
SI;alice-session-01
MS;com.example::BookService.findByAuthor(long) -> java.util::List [public]
TN;http-nio-8080-exec-3
RI;5
CL;42
TI;17
AR;[3]
TE;82741936270000
TN;http-nio-8080-exec-3
RI;5
RT;VALUE
RE;[{"object_id":101,"class":"java.util.ArrayList","value":[...]}]
```

See [Trace Format](trace-format.md) for the complete format specification.

## Run the Spring Boot demo

A working example with session tracking, JPA proxy resolution, and an
automated two-user test script:

```bash
cd deepflow-agent
mvn clean install
cd demos/demo-spring-boot
bash test-run.sh
```

The script starts the app with the agent, exercises the API with two users
(Alice and Bob) in separate HTTP sessions, shuts down, and prints the
collected traces.

To start the app manually for interactive testing:

```bash
cd deepflow-agent/demos/demo-spring-boot
mvn spring-boot:run \
    -Dspring-boot.run.jvmArguments="-javaagent:../../core/agent/target/deepflow-agent.jar=config=./deepagent.cfg"
```

## Run the tests

**Java tests:**

```bash
cd deepflow-agent
mvn clean install    # runs all module tests
```

**Python formatter tests:**

```bash
cd deepflow-formater
python -m pytest tests/preprocessor_test.py
```
