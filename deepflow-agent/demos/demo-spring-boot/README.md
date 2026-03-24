# Spring Boot Demo — Deepflow Agent Integration

This demo shows how to attach the Deepflow agent to a Spring Boot application
with session tracking and JPA proxy resolution. Use it as a reference for
integrating Deepflow into your own Spring Boot project.

## Prerequisites

- JDK 17+
- Maven
- The agent JAR built from the project root:
  ```bash
  cd deepflow-agent
  mvn clean install
  ```
  This produces `core/agent/target/deepflow-agent.jar`.

## Running

**With the automated test script:**
```bash
cd demos/demo-spring-boot
bash test-run.sh
```

The script starts the app, exercises the API with two users in separate HTTP
sessions, prints the trace output, and shuts down.

**Manually (for interactive testing):**
```bash
cd demos/demo-spring-boot
mvn spring-boot:run \
    -Dspring-boot.run.jvmArguments="-javaagent:../../core/agent/target/deepflow-agent.jar=config=./deepagent.cfg"
```

Then use curl or a browser against `http://localhost:8080/api/`.

## How to Integrate Deepflow into Your Spring Boot App

### 1. Add SPI dependencies to your `pom.xml`

The agent JAR is **not** a Maven dependency — it is attached via `-javaagent`.
However, the SPI interfaces and implementations must be on the application
classpath so the agent's ServiceLoader can find them at runtime:

```xml
<!-- Required: SPI interface for session ID resolution -->
<dependency>
    <groupId>com.github.gabert</groupId>
    <artifactId>SessionResolverApi</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>

<!-- Optional: SPI interface for JPA proxy unwrapping -->
<dependency>
    <groupId>com.github.gabert</groupId>
    <artifactId>JpaProxyResolverApi</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>

<!-- Optional: Hibernate proxy resolver implementation -->
<dependency>
    <groupId>com.github.gabert</groupId>
    <artifactId>JpaProxyResolverHibernate</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

### 2. Implement a `SessionIdResolver`

Create a resolver that reads the HTTP session ID from a ThreadLocal:

```java
// SessionIdHolder.java — ThreadLocal storage
public final class SessionIdHolder {
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();
    public static void set(String id) { CURRENT.set(id); }
    public static String get()        { return CURRENT.get(); }
    public static void clear()        { CURRENT.remove(); }
}

// SessionIdFilter.java — Servlet filter that populates the ThreadLocal
@Component
public class SessionIdFilter implements Filter {
    @Override
    public void doFilter(ServletRequest request, ServletResponse response,
                         FilterChain chain) throws IOException, ServletException {
        try {
            String sessionId = ((HttpServletRequest) request).getSession().getId();
            SessionIdHolder.set(sessionId);
            chain.doFilter(request, response);
        } finally {
            SessionIdHolder.clear();
        }
    }
}

// SpringSessionIdResolver.java — SPI implementation
public final class SpringSessionIdResolver implements SessionIdResolver {
    @Override public String name()    { return "spring-session"; }
    @Override public String resolve() { return SessionIdHolder.get(); }
}
```

### 3. Register the SPI via ServiceLoader

Create the file:
```
src/main/resources/META-INF/services/com.github.gabert.deepflow.agent.session.SessionIdResolver
```

With the fully qualified class name of your resolver:
```
com.example.yourapp.SpringSessionIdResolver
```

### 4. Create a `deepagent.cfg`

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

### 5. Attach the agent at startup

**Maven plugin:**
```bash
mvn spring-boot:run \
    -Dspring-boot.run.jvmArguments="-javaagent:/path/to/deepflow-agent.jar=config=./deepagent.cfg"
```

**JAR execution:**
```bash
java -javaagent:/path/to/deepflow-agent.jar="config=./deepagent.cfg" \
     -jar your-app.jar
```

**Docker / deployment script:**
```bash
JAVA_OPTS="-javaagent:/opt/deepflow/deepflow-agent.jar=config=/opt/deepflow/deepagent.cfg"
java $JAVA_OPTS -jar your-app.jar
```

### 6. Inspect the output

Traces are written to `<session_dump_location>/SESSION-<yyyyMMdd-HHmmss>/`
with one `.dft` file per thread. Files are flushed after each record, so you
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
  session/
    SessionIdHolder.java               ThreadLocal for HTTP session ID
    SessionIdFilter.java               Servlet filter (populates ThreadLocal)
    SpringSessionIdResolver.java       SPI implementation (reads ThreadLocal)
```
