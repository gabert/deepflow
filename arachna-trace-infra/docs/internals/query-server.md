# Query Server

`server/record-query-server` is the read-only HTTP API the UI
talks to. It accepts JSON-only GET requests, runs parameterised
SQL against ClickHouse, and returns row arrays. No write paths;
no SQL ever crosses the network from the browser.

For the producer side that lands data into ClickHouse, see
[record-format.md](record-format.md) and
[processor.md](processor.md). For the UI that consumes this API,
see [ui.md](../../../arachna-trace-ui/docs/internals/ui.md).

## Why SQL stays on the server

Browser code never touches ClickHouse directly. The query
server owns:

- **The connection string and credentials** — only the server has
  them.
- **SQL strings** — concentrated server-side so a single audit
  point covers what's queryable.
- **Parameter binding** — every user-supplied value goes through
  ClickHouse's HTTP `param_X=` binding, never string-formatted.
  No `sqlString` / `sqlUuid` escape helpers; the binding is
  load-bearing for safety.

The browser sends `GET /api/...?param=value`; the server returns
JSON.

## Pipeline shape

```
RecordQueryServer
  ├─ ServerBootstrap (Netty)
  │    ├─ HttpServerCodec        # HTTP parsing
  │    ├─ HttpObjectAggregator   # whole-request buffering (1 MiB cap)
  │    └─ QueryHandler           # routes + response writing
  └─ ClickHouseClient            # ClickHouse HTTP interface
       └─ HttpClient (JDK)
```

`QueryHandler` is intentionally thin — URL routing and response
writing only. The actual SQL and JSON walking lives in three
sibling classes grouped by endpoint family:

| Class | Endpoints |
|---|---|
| `SessionsApi` | Sessions, threads, requests, call tree, object trace, exceptions |
| `ObjectsApi` | Per-`object_id` history |
| `AnalysisApi` | Mutations, value search |

A new endpoint is: add a dispatch case to `QueryHandler.dispatch`,
add the method to the matching `*Api` class, write the SQL there.

## Endpoints

All return `application/json` and accept only `GET` (plus
`OPTIONS` preflight for CORS).

### Sessions

| Path | Returns |
|---|---|
| `GET /api/sessions` | All sessions, most recent first |
| `GET /api/sessions/{id}/threads` | Distinct thread names in the session |
| `GET /api/sessions/{id}/requests` | Requests in a session (rollup row from `requests_view`) |
| `GET /api/sessions/{id}/calltree?thread=...&request_id=...` | Paired call rows for one (session, thread, request) |
| `GET /api/sessions/{id}/size` | Storage footprint summary |
| `GET /api/sessions/{id}/object-trace?object_id=...` | Every appearance of an `object_id` across the session |
| `GET /api/sessions/{id}/object-payloads?object_id=...` | Full payload rows for an object across a session |
| `GET /api/sessions/{id}/exception-calls` | Calls in the session with `return_type=EXCEPTION` |
| `GET /api/sessions/{id}/requests/{rid}/payloads` | Every payload row in one request |

### Calls and objects

| Path | Returns |
|---|---|
| `GET /api/calls/{call_id}/payloads` | TI/AR/AX/RE payload rows for one call |
| `GET /api/objects/{object_id}/history` | Every payload row that mentions the given `object_id` (bloom-filter probe on `payloads.object_ids`) |

### Analysis (powering the bug-finding workflow)

| Path | Returns |
|---|---|
| `GET /api/analysis/mutations?session_id=...&request_id=...` | Within-call argument mutations (AR vs AX own_hash diff), grouped per `(call, class, changed-field-set)` |
| `GET /api/analysis/value-search?session_id=...[&request_id=...]&value=...[&mode=substring]` | Every appearance of a scalar value in a session/request (bloom-filter probe on `payloads.payload_tokens`) |
| `GET /api/analysis/behavior-diff?session_a=...&session_b=...` | Behavioral diff between two sessions: calls grouped by `(signature, AR root_hash)` per side, output-hash sets compared. Statuses: `output_changed` (same input, different output), `added`, `removed`, `unchanged`. Hash-only — payloads stay in ClickHouse until the client drills into a group's example calls |
| `GET /api/analysis/observed-signatures?session_id=...` | Distinct signatures observed in a session with call counts — the "what actually ran" half of the liveness sweep (diffed against the agent's `instrumentation_inventory` file) |

See [bug-finding.md](../../../arachna-trace-ui/docs/internals/bug-finding.md)
for the algorithms behind mutations and value search.

### Operational

| Path | Returns |
|---|---|
| `GET /api/health` | `{"status": "ok"}` |

`OPTIONS` requests on any path return 204 with CORS headers (see
below).

## ClickHouseClient

A thin wrapper over the JDK `HttpClient` against ClickHouse's
HTTP interface. Two overloads:

```java
client.query(sql);                       // SQL with no user values
client.query(sql, Map.of("X", value));   // SQL with parameter binding
```

Parameter binding uses ClickHouse's `param_X=` query-string
mechanism, e.g. `SELECT ... WHERE session_id = {X:String}`. Rows
come back as `List<Map<String, Object>>` via Jackson parsing of
the `JSONEachRow` response — shape-preserving, no schema mapping.

Credentials are HTTP Basic, computed once at construction.

## CORS

`QueryHandler` adds CORS headers on every response so the UI
(usually on a different port in dev: `5173` vs `8082`) can call
the API:

```
Access-Control-Allow-Origin: <configured corsOrigin>
Access-Control-Allow-Methods: GET, OPTIONS
Access-Control-Allow-Headers: Content-Type
Access-Control-Max-Age: 600
```

The allowed origin is `QueryServerConfig.getCorsOrigin()` (set
via config), typically `*` for local dev, the UI's deployed
origin for production.

## Helpers

- **`Params.required(params, name)`** — extract a required
  query-string value or throw `IllegalArgumentException` (mapped
  to HTTP 400 by the handler). Single source of truth for
  "missing required parameter" responses.
- **`Params.singleParam(params, name)`** — same for optional
  values, returns `null` if absent.
- **`EnvelopeDiff`** — walks two payload JSON trees and produces
  a field-level diff. Used by `AnalysisApi.mutations` to compute
  the AR↔AX delta per `(call, object_id)` server-side. Same
  algorithm as the client-side diff walker described in
  [bug-finding.md](../../../arachna-trace-ui/docs/internals/bug-finding.md), so
  the user sees identical results whether the UI computes the
  diff or the server pre-computed it.

## Source files

`server/record-query-server/src/main/java/com/github/gabert/arachna/trace/query/`

- `RecordQueryServer.java` — Netty bootstrap + main
- `QueryHandler.java` — routes, response writing, CORS
- `QueryServerConfig.java` — port, ClickHouse URL + credentials,
  CORS origin
- `ClickHouseClient.java` — ClickHouse HTTP wrapper
- `SessionsApi.java` — sessions / threads / requests / call tree
  / object trace
- `ObjectsApi.java` — per-`object_id` history
- `AnalysisApi.java` — mutations + value search
- `EnvelopeDiff.java` — field-level diff walker
- `Params.java` — query-parameter extraction helpers
