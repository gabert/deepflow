# Known Bugs and Leaks

The bug catalog. Each entry has a stable ID (referenced from tests,
code comments, the roadmap, and other docs). Look here to answer
"is X still a bug?" — every entry carries a current **Status** field.

**Legend** — first letter of the ID:

- **B** Bug (correctness / wrong data)
- **L** Leak (memory / state)
- **D** Data quality (degradation / inaccuracy)
- **U** UI (frontend issue in `arachna-trace-ui/`)
- **A** Architectural (instrumentation / runtime limits, pre-existing)

**Status values:**

- **OPEN** — known, not fixed.
- **FIXED** — resolved; entry kept for the stable ID and reference
  from tests / commit messages.
- **ACCEPTED** — known degradation we've chosen not to fix.

For *open* items and what to do about them, see `ROADMAP.md`.

---

## Index

| ID   | Severity | Status   | Category    | Title |
|------|----------|----------|-------------|-------|
| B-01 | High     | FIXED    | Correctness | `recordExit` null-pop emits wrong-id ME |
| B-02 | High     | FIXED    | Correctness | Double CI in entry block silently leaks builder |
| B-03 | High     | FIXED    | Correctness | Async-after-root undercounts `requests.call_count` |
| B-04 | High     | OPEN     | Architectural | No version-aware parsing → rolling deploys mis-parse |
| B-05 | Medium   | OPEN     | Correctness | JVM crash mid-method leaves orphan UUID on `CALL_STACK` |
| B-06 | Medium   | OPEN     | Correctness | Failed entry on request's originating root → spurious root |
| L-01 | Medium   | FIXED    | Leak        | `RecordParser.openCalls` map — never evicts |
| L-02 | Medium   | FIXED    | Leak        | `ClickHouseSink.openRequests` map — never evicts |
| L-03 | Medium   | FIXED    | Leak        | `ClickHouseSink.seenSessions` set — grows monotonically |
| L-04 | Low      | OPEN     | Leak        | Long-lived thread `CALL_STACK` accumulates after B-05 |
| D-01 | Low      | ACCEPTED | Data quality | `agent_runs` row missing if a single insert fails |
| D-02 | Medium   | OPEN     | Data quality | `sessions.first_seen` is wrong after processor restart |
| D-03 | Low      | OPEN     | Data quality | `ReplacingMergeTree` version uses 1-second resolution |
| D-04 | Low      | OPEN     | Data quality | `ALTER TABLE UPDATE retain` is async — race with TTL pass |
| D-05 | Low      | OPEN     | Data quality | `payload_size` is JSON UTF-8 byte count, not CBOR/object size |
| D-06 | Low      | ACCEPTED | Coupling    | `is_exception` materialized expression hardcodes `'EXCEPTION'` |
| D-07 | Low      | ACCEPTED | Cosmic-ray  | UUID collision overwrites `openCalls` entry |
| D-08 | Low      | ACCEPTED | Subtle      | `runScoped` body sees swapped stack — caller's outer call invisible |
| D-09 | Low      | ACCEPTED | Data quality | Cyclic graphs hash differently depending on traversal entry |
| D-10 | High     | OPEN     | Data quality | Truncation marker drops envelope — breaks identity, mutation detection, object-tree walk |
| U-01 | Medium   | FIXED    | UI          | Tree pane stops responding after several watch-row navigations |
| U-02 | Low      | OPEN     | UI          | Watch-row click highlights the JsonTree node but the left pane does not scroll it into view |
| A-01 | —        | ACCEPTED | Pre-existing | `new Thread(r).start()` without instrumentation: no propagation |
| A-02 | —        | ACCEPTED | Pre-existing | Custom executors via `CompletableFuture.runAsync` not covered |
| A-03 | —        | ACCEPTED | Pre-existing | Virtual threads — should work via thread-locals, not tested |
| A-04 | —        | ACCEPTED | Pre-existing | No FK enforcement; cross-table joins must tolerate missing rows |

---

## Bugs

### B-01 — `recordExit` null-pop emits wrong-id ME

**Status:** FIXED.
**Where:** `RequestRecorder.recordExit` (core/agent).

**Was:** `recordExit` running without a matching `recordEntry` would
get null from `popCallId()` and emit ME with the all-zero sentinel.
Worse, `endRequest()` ran first and double-decremented depth.

**Fix:** Pop reordered to come first; bail before any state mutation
if pop returns null. Regression test:
`ArachnaTraceAdviceRecordingTest.recordExitWithoutMatchingEntryIsSilentlyIgnored`.

---

### B-02 — Double CI in entry block silently leaks builder

**Status:** FIXED.
**Where:** `RecordParser.parse` — `case "CI"` in entry context.

**Was:** Two `CI` tags in one MS block would put the same builder
under two keys; only one would be matched on TE, the other leaked
in `openCalls` forever.

**Fix:** Guard `if (currentEntry.callId != null) break;` ignores
subsequent CI within the same entry context. Regression test:
`RecordParserTest.duplicateCiInEntryBlockIsIgnoredNotLeaked`.

---

### B-03 — Async-after-root undercounts `requests.call_count`

**Status:** FIXED.
**Where (was):** `ClickHouseSink.aggregateRequest`.

**Was:** A worker thread emitting a `ParsedCall` belonging to a
`request_id` whose root call had already closed would re-create a
fresh `RequestAggregator` via `computeIfAbsent`. The new aggregator
incremented but no root would ever arrive to close it — so it stayed
in `openRequests` forever (also feeding **L-02**) and the original
`requests` row's `call_count` was permanently under-reported.

The root cause was an upstream model error: the in-process
aggregator required the processor to know "when is the request
done," which is unanswerable in the presence of fire-and-forget
async work.

**Fix:** Aggregation moved to ClickHouse via a materialized view
(`requests_mv` in `clickhouse-init/01-schema.sql`) that reads every
insert into `calls` and folds it into a `requests`
`AggregatingMergeTree` table via `SimpleAggregateFunction` columns.
Late async calls are folded into the rollup automatically when
their `calls` row is inserted. The processor no longer holds any
per-request state — `aggregateRequest`, `RequestAggregator`,
`RequestKey`, `requestRow`, `openRequests`, `requestBuffer`,
`insertRequestsUri` are all deleted.

Semantic shift: `requests.ended_at` is now `max(ts_out)` across all
calls in the request rather than the synchronous root's `ts_out`.
For async-using requests this is a more honest answer (the request
really isn't done until its async work finishes); for purely
synchronous requests it's identical. Read via `requests_view` for
clean column types.

---

### B-04 — No version-aware parsing → rolling deploys mis-parse

**Status:** OPEN. Dismissed for now: agent + processor ship as one
product, so a wire-format change can land in lockstep. Revisit only
if external consumers of the wire appear.
**Where:** `TraceRecord.parse` and `RecordParser`.

**Trigger:** A v1.2-format wire stream (pre-Phase-0 agent) is read
by a v1.3 processor.

**Symptom:** Old MS records lack the trailing 48 bytes of UUID
fields. The new parser reads garbage from the next record's bytes
thinking they're UUIDs, gets a wrong frame length, and corrupts the
entire batch.

**Mitigation today:** Always deploy agent + processor together.
Agent versions are not pinned anywhere, so a rolling deploy of just
one side breaks parsing.

**Possible fixes:** Dispatch on `VersionRecord` at the start of each
stream. Either keep two parser implementations (cost: maintenance),
or refuse to parse if version doesn't match (cost: silent skip
during deploy windows). The cleaner long-term path is making the
wire format self-describing per record (TLV-style), but that's a
much bigger change.

---

### B-05 — JVM crash mid-method leaves orphan UUID on `CALL_STACK`

**Status:** OPEN.
**Where:** `RequestRecorder.recordEntry` push + `recordExit` pop
balance.

**Trigger:** `OutOfMemoryError`, `StackOverflowError`, JVM-internal
crash, or any path that lets `onEnter` complete but prevents
`onExit` from firing. The bulletproof contract handles failures
*during* `recordEntry`/`recordExit`, but not failures *between* them
inside the user code.

**Symptom:** Push happened; pop didn't. Subsequent traced methods
on the same thread see the orphan UUID as their parent. Parent
linkage for the rest of the request is corrupted. Self-heals when
the JVM stack drains to empty (top-of-request).

**Possible fixes:**

1. Periodic `CALL_STACK` size sanity check with an alarm if depth
   exceeds N. Doesn't fix, just observes.
2. Try/finally pop in the advice itself — but ByteBuddy `@Advice`
   doesn't easily wrap user code in try/finally without bytecode
   manipulation that may break inlining optimizations.
3. Accept it as a known degradation. The compromised data is
   observable (parent_call_id pointing to a non-existent call_id
   in `calls`) so it can be filtered out at query time.

---

### B-06 — Failed entry on request's originating root → spurious root

**Status:** OPEN.
**Where:** `RequestRecorder.recordEntry` (failed-entry contract) +
`ClickHouseSink.aggregateRequest` (root detection).

**Trigger:** The very first traced method of a request fails entry
(per the bulletproof contract, suppressed). The second call's
`peekParentCallId()` returns null (nothing was pushed), so it
appears to be the root.

**Symptom:** The `requests` row records the second call as the
root. `started_at` is later than reality. `entry_signature` is
wrong. `call_count` undercounts by 1+.

**Possible fixes:** Hard. The agent doesn't know "first call of a
request" explicitly. Paths:

1. Track `request_id → first_call_seen` set in the parser/sink and
   only treat parent_call_id == null as root if it's the first call
   for that request_id.
2. Always push a sentinel in `recordEntry` even on failure (but then
   `recordExit` would pop it as if it were a real call → wrong ME,
   breaks the contract).
3. Accept it as a known degradation.

---

## Leaks

### L-01 — `RecordParser.openCalls` map — never evicts

**Status:** FIXED.
**Where:** `RecordParser.openCalls`.

**Was:** A traced method whose `MS` arrived but `ME` never did
(agent crashed mid-call, network drop, etc.) left a `Builder` in
the map forever. Memory grew monotonically per leaked call,
bounded only by JVM heap.

**Fix:** Each `Builder` records `openedAtMillis` (processor
wall-clock at admission). At the end of every `parse()` an
eviction sweep drops entries whose admission age exceeds
`OPEN_CALL_TTL_MS` (10 minutes). The sweep is throttled to
`SWEEP_INTERVAL_MS` (60 s) so the O(n) cost is amortised. The
clock is injectable for tests via a package-private constructor.
A late `ME` for an evicted call is treated as a normal orphan and
dropped silently — same behaviour as a stray `ME` from a
failed-entry contract.

Regression test:
`RecordParserTest.staleOpenCallIsEvictedAfterTtl` — drives a fake
clock past TTL and asserts `openCallCount()` drains.

---

### L-02 — `ClickHouseSink.openRequests` map — never evicts

**Status:** FIXED.
**Where (was):** `ClickHouseSink.openRequests`.

**Was:** A request whose root never closes (or whose root closed
but later async calls re-created the entry — see B-03) would leave
a `RequestAggregator` in the map forever. Memory grew
monotonically, bounded only by the JVM heap.

**Fix:** The map and its surrounding aggregator code were deleted
entirely as part of the B-03 fix. There is no per-request
in-memory state in the processor anymore — `requests` is an
`AggregatingMergeTree` maintained server-side by `requests_mv`.

---

### L-03 — `ClickHouseSink.seenSessions` set — grows monotonically

**Status:** FIXED.
**Where:** `ClickHouseSink.seenSessions`.

**Was:** Many distinct `(agent_run_id, session_id)` pairs over a
long-lived processor instance. With short-lived sessions (e.g. one
per HTTP user session), the set grew continuously at ~64 bytes per
entry. Matters at millions of distinct sessions per processor
instance.

**Fix:** Replaced `Set<SessionKey>` with `Map<SessionKey, Long>`
where the value is the processor wall-clock time at admission.
`periodicFlush` (which already runs every second) calls
`evictStaleSessions` first, dropping entries older than
`SESSION_TTL_MS` (1 hour). The sweep is throttled to
`SESSION_SWEEP_INTERVAL_MS` (5 minutes). A re-emit of an evicted
session produces one duplicate `sessions` row, which the
`ReplacingMergeTree` engine collapses on the server.

---

### L-04 — Long-lived thread `CALL_STACK` accumulates after B-05

**Status:** OPEN. Tied to B-05.
**Where:** `RequestContext.CALL_STACK` ThreadLocal `ArrayDeque`.

**Trigger:** Repeated occurrences of B-05 on a long-lived worker
thread (Tomcat, Netty event loop). Each occurrence may leave one
entry stuck.

**Symptom:** Slow growth bounded by thread liveness. Each leaked
entry also pollutes parent_call_id assignment for future calls on
that thread.

**Possible fix:** Tied to B-05 fix. Periodic stack-size check could
trigger an emergency clear with logging.

---

## Data quality

### D-01 — `agent_runs` row missing if a single insert fails

**Status:** ACCEPTED. Severity dropped to Low after the
transport-layer rework (every Kafka batch upserts `agent_runs`).
**Where:** `ClickHouseSink.flush` for `agentRunBuffer`.

**Symptom:** `agent_runs` is buffered once per distinct run id per
flush window and upserted from the headers (idempotent via
`ReplacingMergeTree`). A single CH-insert failure for one flush
leaves the row not yet present, but the next flush window re-issues
it. The window of vulnerability shrinks from "rest of JVM lifetime"
to "until the next flush lands."

**Residual:** If the JVM emits one batch and dies before any
subsequent batch, that one failed insert is the only chance and the
row is lost. Mitigation if needed: retry agent_runs inserts with
bounded backoff (small per-row cost, idempotent so safe).

---

### D-02 — `sessions.first_seen` is wrong after processor restart

**Status:** OPEN.
**Where:** `ClickHouseSink.noteSessionIfNew`.

**Trigger:** Processor restarts. `seenSessions` is empty in memory.
The first `ParsedCall` for any session triggers a re-emit. But that
call may be the second-or-later for that session in real time
(Kafka backlog replay).

**Symptom:** `first_seen` set to "first call seen by this processor
instance," not the actual first call. `ReplacingMergeTree` picks
the latest insert (highest `inserted_at`), so `first_seen` ends up
= first-call-seen-by-the-most-recent-processor. Not actual first
call.

**Possible fix:** Use `MIN(first_seen)` aggregation server-side —
but `ReplacingMergeTree` doesn't aggregate. Alternative: switch to
`AggregatingMergeTree` with `minState(first_seen)` /
`maxState(last_seen)` — different query model. Or: accept the
degradation.

---

### D-03 — `ReplacingMergeTree` version uses 1-second resolution

**Status:** OPEN. Single-processor (today) is unaffected.
**Where:** `agent_runs` and `sessions` use
`ReplacingMergeTree(inserted_at)` where `inserted_at` is `DateTime`
(1-second resolution).

**Trigger:** Two processors emit the same key within 1 second.
Multi-processor deployments.

**Symptom:** Arbitrary winner at merge time.

**Possible fix:** Change `inserted_at` to `DateTime64(3)` or
`DateTime64(6)`, or use a UInt64 sequence number column for
versioning.

---

### D-04 — `ALTER TABLE UPDATE retain` is async — race with TTL pass

**Status:** OPEN.
**Where:** Retention escape mechanism.

**Trigger:** User runs `ALTER TABLE ... UPDATE retain = true` to
promote a session/call to long retention. ClickHouse mutations are
async — there's a window between SQL submission and on-disk effect
during which the next TTL pass can still delete the row.

**Symptom:** Retain promotion may not stick if a TTL pass races it.
Probabilistic failure.

**Possible fix:** Run `OPTIMIZE TABLE ... FINAL` after the UPDATE
to force mutation completion. Or: handle this via the deferred
"retain at insert time" path (set `retain=true` from the start so
no UPDATE is needed).

---

### D-05 — `payload_size` is JSON UTF-8 byte count, not CBOR/object size

**Status:** OPEN.
**Where:** `ClickHouseSink.payloadRow` —
`json.getBytes(UTF_8).length`.

**Symptom:** Naming is misleading. A user querying "find calls with
huge payloads" gets the JSON-bloat-inflated proxy, not the original
object size in memory or on the wire.

**Possible fix:** Either rename to `payload_json_size_bytes` for
clarity, or also capture the original CBOR size at the agent and
ship it through.

---

### D-06 — `is_exception` materialized expression hardcodes `'EXCEPTION'`

**Status:** ACCEPTED. Coupling is documented.
**Where:** `calls` table DDL —
`is_exception MATERIALIZED return_type = 'EXCEPTION'`.

**Trigger:** If `return_type` enum is ever extended (e.g., adding
`SUSPENDED` for coroutines, `CANCELLED` for futures),
`is_exception` silently returns false even for cases that morally
should be exceptional.

**Symptom:** Future extension footgun. Coupled to one literal.

**Possible fix:** Rebuild the expression as a `multiIf` with a
comment listing all the values.

---

### D-07 — UUID collision overwrites `openCalls` entry

**Status:** ACCEPTED. Not worth fixing.
**Where:** `RecordParser.openCalls.put(callId, builder)`.

**Trigger:** `UUID.randomUUID()` produces the same UUID twice.
Probability 2^-128. In practice never.

**Symptom:** Second `put` overwrites first. First call is lost from
the map; its eventual ME is treated as orphan.

---

### D-08 — `runScoped` body sees swapped stack

**Status:** ACCEPTED. This is the intended behaviour for async
parent linkage; documented for clarity.
**Where:** `RequestContext.runScoped` — `CALL_STACK.set(workerStack)`.

**Trigger:** A worker thread is currently inside a traced method
(its `CALL_STACK` non-empty), AND the body of that method calls a
nested `runScoped` (e.g., further async submission). During the
body, the worker's "outer" call is invisible.

**Symptom:** Calls inside the body have parent = seeded UUID, not
the outer call's UUID. Almost certainly the right semantic for
async (the seeded UUID *is* the logical parent across the
boundary), but it's a subtle behaviour that could surprise.

---

### D-09 — Cyclic graphs: same envelope hashes differently depending on traversal entry

**Status:** ACCEPTED. The principled fix would downgrade the
project's core Merkle property; cost not worth it. Mitigated in
the UI by the own-state hash (D-09 follow-up below).
**Where:** `arachna-trace-shared/codec/.../Hasher.java` (Merkle walk) +
`arachna-trace-shared/codec/.../envelope/EnvelopeSerializer.java`
(cycle marker emission).

**Trigger:** Any object graph with a cycle (e.g. JPA bidirectional
`Author` ⇌ `Book`). Two traces of the same logical state can
produce different envelope hashes.

**Cause:** When a cycle is closed, `EnvelopeSerializer` emits
`{ref_id: <id-of-the-already-seen-object>, cycle_ref: true}`. The
id inside that marker — and the *position* where the marker lands —
depends on which side of the cycle was the traversal root. The
Merkle hash incorporates that marker, so the asymmetry propagates
upward.

Concretely, with `Author#1 ⇌ Book#5`:
- Root = Author → cycle marker lands inside Book as `{ref_id: 1}`.
- Root = Book → cycle marker lands inside Author as `{ref_id: 5}`.

Author's hash (or Book's) ends up direction-dependent.

**Symptom in the UI:** The watch panel showed "hash transition"
when two methods observed the same object from opposite sides of a
cycle, even though no field actually changed. False positive in the
change detection.

**Mitigation in clients:** When inspecting a transition flagged on
a cyclic object, verify the field-level diff manually before
trusting it as a real change. The `own_hash` column in the watch
panel (and `__meta__.own_hash` in the wire format) is invariant
under cycle-entry direction and surfaces the real signal — see the
"own_hash — per-envelope own-state hash" section in
`BUG_FINDING_DESIGN.md`.

**Reference case:** `BookEntity #193` in the demo: four distinct
deep hashes (`dd3deb9c`, `7e0950ec`, `fe0d7f3c`, `9279c45c`)
decomposed as the cross product of **two cycle entry directions ×
two real ISBN states** (`978-0-618-39111-3` → `9780618391113`,
stripped by `LibraryDAO.normalizeIsbns`). Two of the four
transitions were honest signal; two were cycle-direction noise.

---

### D-10 — Truncation marker drops envelope — breaks identity, mutation detection, object-tree walk

**Status:** OPEN. Severity High — collides with core product features.
**Where:** `ValueEncoder.truncationMarker(int)` (`core/agent`) +
the marker shape defined in
[../spec/CBOR-ENVELOPE.md §5b](../../../spec/CBOR-ENVELOPE.md).

**Trigger:** Any captured value (`TI` / `AR` / `AX` / `RE` /
`EXCEPTION`) whose CBOR-encoded size exceeds `max_value_size`
(when set > 0).

**Symptom:** The truncation marker
`{"__truncated": true, "original_size": N}` is emitted as a
**bare CBOR map** with no envelope wrapper. Consequences:

- **Identity lost.** No `OBJECT_ID` on the marker → the consumer
  cannot link this value to other appearances of the same
  instance. The same `Order #42` seen full in one call and
  truncated in another can't be paired across calls.
- **Class lost.** No `CLASS_NAME` → the UI can render "something
  was truncated" but not "this `Order` was truncated". Type
  context disappears from the trace.
- **Mutation detection broken.** Two truncation markers with
  equal `original_size` hash identically, even if the underlying
  object changed across `AR`↔`AX`. False negative.
- **Conversely**: two markers with different `original_size`
  hash differently even if the underlying object didn't change
  (e.g. an unrelated transient field's size shifted). False
  positive.
- **Object-tree walk dies at this node.** The consumer can't
  recurse into a truncated value — the subtree is opaque.
  Provenance, value-search, and field-level diffs all stop here.

These consequences directly conflict with Arachna Trace's core
claim: *"every object has stable identity, mutations are
detectable, the same instance is tracked across calls."*
Truncation as currently shaped opts out of all three for the
clipped value.

**Mitigation today:** Set `max_value_size=0` (the agent
default). Pay storage / network for accurate identity tracking.
Documented in [CBOR-ENVELOPE.md §5b](../../../spec/CBOR-ENVELOPE.md)
as "known false-positive" — that framing was wrong; this is a
bug, not an accepted trade-off.

**Possible fixes** (ordered by effort × parity-with-current):

1. **Envelope-wrap the marker.** Keep the truncation marker but
   put it inside the standard envelope:

   ```
   {1: <object_id>,
    2: "<class.name>",
    3: {"__truncated": true, "original_size": N}}
   ```

   Identity preserved. Class preserved. Cross-call tracking
   restored. Mutation detection still false-negatives within
   one `(AR, AX)` pair if both sides truncate to the same size,
   but identity-level use cases (watch this instance across the
   request, find every appearance of `Order #42`) work again.
   Smallest change; backward-compatible if we accept either
   shape in the consumer.

2. **Field-level truncation.** Walk the CBOR tree before
   encoding; replace only the over-sized children/fields with
   per-field markers, preserving the parent envelope and the
   non-oversized siblings. The consumer sees *which specific
   field* was too large — "`Order.notes` truncated, everything
   else intact". Most expressive; significantly larger change
   to `ValueEncoder` / `Codec`.

3. **Content hash on the marker.** Add a CBOR-level hash of the
   *would-have-been* encoded bytes inside the marker so two
   markers compare on hash, not just size. Restores mutation
   detection at the cost of hashing on the agent's hot path
   (we currently hash on the processor; agent-side hashing was
   the original design and was deliberately moved off the hot
   path).

**Lean:** ship (1) as a minor wire-format bump (consumers
accept either the legacy flat marker or the new envelope-
wrapped form during the transition window). Defer (2) and (3)
as later refinements; (2) is the eventual right answer but the
implementation cost is non-trivial.

**Docs that need updating when this lands:**

- `spec/CBOR-ENVELOPE.md §5b` — rewrite the marker shape;
  drop the "known false-positive" framing.
- `spec/WIRE-FORMAT.md` — note the new wire-version
  (likely 1.5 if backward-compat, 2.0 if hard-cutover combined
  with the JCS migration in the spec-evolution roadmap entry).
- `arachna-trace-agents/docs/truncation.md` — describe the new shape and
  what it preserves.

---

## UI

### U-01 — Tree pane stops responding after several watch-row navigations

**Status:** FIXED. Architectural reframe rather than a point fix.
**Where (was):** `arachna-trace-ui/src/components/JsonTree.vue`
auto-expanding watchers + global `highlight` ref.

**Was:** The watch-row click set a global `highlight` ref that
every mounted `JsonTree` listened to via deep prop chains, with
reactive watchers that auto-expanded matching nodes. With several
open calls and deep payloads, the per-click recompute fanout grew
faster than Vue could flush, eventually starving the toggle
hit-tests.

**Fix (architectural reframe):**

- The page now treats a request as an addressable timeline of
  frames keyed by the `seq` ordering primitive. Navigation is
  imperative: `SessionDetailView` keeps a
  `frameRefs[callId] → FrameCard` map and a single
  `goto({callId, kind, path})` entry point that walks
  `frame.revealAt(kind, path)` →
  `payloadViewer.revealPath(path)`. Only the targeted component
  is touched per click; nothing else on the page reacts.
- `PayloadViewer` is the sole owner of expansion state for its
  tree (a `Set` of JSON-stringified path keys). User toggle and
  imperative reveal both mutate the same set — no two sources of
  truth fighting.
- `JsonTree` is now a dumb recursive renderer: it receives an
  `isExpanded(key)` callback and emits `toggle(key)` / `pin` /
  `follow-cycle`. No `highlight` prop, no `containsMatch` /
  `isMatch` computeds, no auto-expand watchers, no
  provide/inject of reactive refs.
- Highlight is pure DOM: `revealPath` queries `[data-path="…"]`
  after Vue commits, adds a `.flashed` class. No reactive
  cascade.
- Cycle-ref chip resolution lives inside the `PayloadViewer` —
  the cycle target is by definition an ancestor in the same
  payload, so a local `findPathToObjectId(data, targetId)` walk
  suffices; no id-based highlight fallback at the global level.

**Why this also helps future products on the schema side:** the
same addressing model — `(seq, kind, path)` — composes cleanly into
URLs, which means "share me a link to that exact field at that
exact moment" becomes trivial when we add router state for it.

---

### U-02 — Left pane sometimes doesn't scroll to the highlighted node

**Status:** OPEN. Visual-only; the address is correct, only the
auto-scroll convenience is missing.
**Where:** `arachna-trace-ui/src/components/JsonTree.vue`
(`scrollSelfIfMatching` — the `watch(isMatch, …)` and
`watch(navTick, …)` post-flush handlers) and the surrounding
navigation flow in `SessionDetailView.vue` / `PayloadViewer.vue`.

**Symptom:** Clicking a watch row whose target is deep in the tree
(e.g. one of the later appearances of `BookEntity #193`) correctly
applies the `.flashed` highlight class to the matching JsonTree
node, **but** the left pane does not scroll the node into view —
the user has to scroll manually to find the amber-outlined row.
The watch panel's "current row" highlight is correct in lockstep,
so the navigation address is right; only the `scrollIntoView` side
is failing.

**Reproduction:**

1. Pick a demo session, pick a request that mutates a tracked
   object (e.g. session containing `BookEntity #193`).
2. Pin `BookEntity #193` as a watch.
3. Click the watch's last appearance row (deep in the tree).
4. Observe: row on the right turns amber, target node has
   `.flashed` on the left, but the visible scroll position of the
   left pane is unchanged.

**What was tried:**

- Two cooperating watchers (`watch(isMatch, …, immediate, post)`
  plus `watch(navTick, …, post)`) so re-clicks and freshly-mounted
  matches both run a scroll attempt.
- Switched the scroll target from `.jt-node` (whose bounding box
  is the entire expanded subtree, often thousands of pixels tall)
  to `.jt-row` (the visible row inside the node). The issue
  persists.

**Suspected causes to investigate:**

- `scrollIntoView` may resolve to the wrong scrollable ancestor.
  PrimeVue's Splitter inserts wrapping divs around the
  SplitterPanel; the actual scroll container may differ from what
  `:deep(.left-pane)` CSS sets `overflow-y: auto` on. Walk up
  `parentElement` from the target with
  `getComputedStyle().overflowY` to confirm which element is doing
  the scrolling.
- Post-flush may fire before the deepest recursive levels mount,
  so `nodeRef.value` is the right element but its position in
  layout is premature; subsequent renders push it. Adding a
  `requestAnimationFrame` (or two) before the `scrollIntoView`
  call may help — wait for layout to settle.
- The matched element may be technically "in viewport" of the
  scroll container at the time of the call (so `scrollIntoView`
  becomes a no-op) yet visually obscured by another stacking
  context. Replace `scrollIntoView` with explicit
  `container.scrollTop = …` arithmetic, computing the target
  offset relative to the scroll container.

**Workaround for now:** The user can scroll manually; the
highlight class is correct so the target is findable visually once
on screen.

---

## Architectural / pre-existing

### A-01 — `new Thread(r).start()` without instrumentation

**Status:** ACCEPTED.
**Where:** Whatever code uses raw `Thread` instead of an executor.

**Symptom:** Worker thread has empty `CALL_STACK`, fresh
`request_id`. Call tree breaks at the boundary.

**Possible fix:** Instrument `Thread.start` — possible but
invasive.

---

### A-02 — Custom executors via `CompletableFuture.runAsync(r, customExecutor)`

**Status:** ACCEPTED.
**Where:** Only `ThreadPoolExecutor` and `ForkJoinPool` are
instrumented. A user-written executor is invisible.

**Possible fix:** Instrument the `Executor` interface itself — but
that hits many unintended classes. Or document the supported set.

---

### A-03 — Virtual threads

**Status:** ACCEPTED. Should work via normal thread-locals on the
carrier, but not explicitly tested. Confidence: medium.

**Possible fix:** Add a smoke test using `Thread.ofVirtual()`.

---

### A-04 — No FK enforcement; cross-table joins must tolerate missing rows

**Status:** ACCEPTED. ClickHouse doesn't enforce FKs at all.
**Where:** All table relationships in the ClickHouse schema.

**Symptom:** Pre-existing; ClickHouse doesn't enforce FKs at all.

**Possible fix:** Document the join semantics for each query path.
LEFT JOIN by default to tolerate missing rows.
