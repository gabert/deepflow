# Bug-Finding Workflow — Design

How Arachna Trace's UI turns a trace into a bug-finder. Describes the
algorithms and architecture that power the Mutations panel,
`own_hash`-based change detection, the client-side diff walker,
field provenance, and value search.

For open backlog items in this area (B2–B6 etc.), see
`ROADMAP.md`. For the bug catalog, see `KNOWN_BUGS.md`.

---

## Two complementary hashes per envelope

Every serialized envelope carries two MD5 hashes in its `__meta__`
block:

- **`hash`** — Merkle hash of the envelope's own scalar state
  *plus* the hashes of its children. Drift detection: anything
  changed anywhere in the subtree shifts this hash.
- **`own_hash`** — hash of the envelope's own scalar state *plus
  the child ids* (not their content). Drift in this object only;
  invariant to mutations of child objects.

Both are computed server-side by `RecordHashEnricher` in a single
walk. Both flow through `payload_json` as `__meta__.hash` and
`__meta__.own_hash`; `own_hash` is also stored in
`payloads.own_hashes Array(String)` (length-aligned with
`object_ids`) and indexed with a bloom filter for SQL lookups.

### Why two?

The Merkle `hash` is the right shape for **tree drilling** — "an
Order changed somewhere; walk down comparing hashes until I find
the leaf that moved". It is not the right shape for **flat row
inspection** — "did this AuthorEntity's own state move at this
call?". Without `own_hash`, an `Author` whose `books[i].isbn` is
rewritten by another method registers as a hash drift on the
`Author` row, even though the author itself didn't change.

The own-state hash answers "did this object's own data change" and
is invariant under cycle-entry direction (see **D-09** in
`KNOWN_BUGS.md`) and sibling envelope mutations.

### Algorithm — `own_hash`

For each envelope `E` with class `C`, scalar fields `S = {k → v}`,
and child references `R = [child.__meta__.id for child ∈ E]` in
wire order:

```
own_hash(E) = MD5(canonical(C, S, R))
```

`canonical(...)` is the same canonicalization used for the Merkle
hash, except each child is replaced by its **id only**, not its
hash. Cycle markers (`{cycle_ref: true, ref_id: N}`) collapse to
the same ref shape as full envelopes — so own-hash is invariant
under cycle-entry direction.

Captures *scalars + child identity* but not *child content*.
Captures "this object's own fields changed" and "the set/order of
children changed", but not "a child's content changed".

### What this unlocks

- UI: WatchPanel reads `__meta__.own_hash` directly per
  appearance; bands flip only on own-state transitions. The
  deep-hash column was removed from the panel — own-state is the
  sole row signal there.
- SQL / LLM: `WHERE has(own_hashes, 'abc…')` filters are trivial
  via the bloom-filter index. "Find every call where Author #N's
  own state was modified" is a single-pass query against an
  indexed column.

---

## Mutations view — hash detects, client diffs

The Mutations panel (right pane, default tab) lists, for the
loaded request, every `(call, object_id)` where the object's
`own_hash` at exit (`AX`) differs from its `own_hash` at entry
(`AR`). Each row renders the field-level diff inline:

```
buildDisplayName ⏵ AuthorEntity #9
  name:  "J.R.R. Tolkien"  →  "Tolkien, J.R.R."

normalizeIsbns ⏵ BookEntity #11
  isbn:  "978-0-618-00221-3"  →  "9780618002213"
```

Click any row → jumps to that call in the left pane (FrameCard
expanded, AR + AX visible) for full context. The diff is the
discovery; the click is for context.

### Architecture: hash detects, client diffs

The server emits `own_hash` per envelope. The UI uses hash
transitions as the detection signal, then computes the
field-level diff on demand for the rows it renders. The diff is a
pure derivation from the two `payload_json` blobs already in
memory on the client; no server-side diff materialization, no extra
schema column, no batch-time cost for diffs the user does not
inspect.

The detection endpoint is `GET /api/analysis/mutations`. It
returns groups (`{call, class, changed-field-set}`) — bulk
transforms over N items collapse to one row + expand-on-demand.

### Diff walker — using nested own_hashes as a directory

To find the exact mutated field inside an envelope, the diff
walker uses `own_hash` at each envelope boundary as a "subtree
changed?" oracle:

```
diff(envelope_AR, envelope_AX):
  if envelope_AR.__meta__.own_hash == envelope_AX.__meta__.own_hash:
    return []                      # no own-state change here, skip
  for each field f shared by both:
    if both envelopes' f are scalars and they differ:
      record (f, AR.f, AX.f)
    else if f is a child envelope at both sides:
      if AR.f.__meta__.id != AX.f.__meta__.id:
        record (f, "→ id swap from #X to #Y")
      else:
        recurse diff(AR.f, AX.f)   # only if subtree own_hash differs
    else if f is a list:
      element-wise pair, apply same rules
  record any added / removed fields
```

For an Author with 10 books where one ISBN changed: ~3 hash
comparisons total (Author own_hash same → never recursed; books
list walked; one book's own_hash differs → drill into its
scalars). Not 10 deep field comparisons. The own_hash chain *is*
the directory structure of "where in this object did something
move."

### Predicate

`own_hash(AX) != own_hash(AR)` for the same `(call_id,
object_id)`. Detect on the client by scanning loaded payloads of
the current request; an SQL variant
(`SELECT WHERE arrayMap(...) != arrayMap(...)` over
`payloads.own_hashes`) lets the server pre-aggregate counts for
session-list badges.

### Prerequisite: agent must emit AX

The default `emit_tags` (`ConfigLoader.DEFAULT_EMIT_TAGS`) does
**not** include `AX`. The demo agent config
(`release/configs/demo-agent.cfg`) has `emit_tags=...,AX,...`
explicitly. Without AX, mutation detection falls back to
"compare adjacent appearances of the same id across calls" —
still useful but weaker than within-call mutation detection.

---

## Field provenance

Answers "where did this field's value come from?" for any field
of any object at any moment in the trace. Walks backward across
calls; pairs with mutation detection to close the debugging loop
*symptom → cause* without re-running.

### Why not just instrument getters/setters

The precise answer is "track every getter and setter and the
trace shows you exactly which method read which field when." We
deliberately don't do that. A typical Spring Boot request runs
thousands of getter/setter calls; instrumenting them would bury
the business-logic calls under property-access noise and inflate
trace size by an order of magnitude. The default matcher policy
excludes `get*` / `set*` by convention, and that's the right
default.

So the question becomes: can we give a *useful hint* of
provenance without that instrumentation, accepting that the
answer is heuristic, not authoritative?

### Inference from data already in the trace

Three sources of signal are present in current traces, no new
agent surface needed:

1. **Argument-flow.** When a call's AX shows
   `author.name = "Tolkien"`, scan that call's AR scalars for
   `"Tolkien"`. A match means an argument is the candidate
   source for that field at that call.
2. **Chained return values.** That argument was probably itself
   the return value of an earlier call (`fetchUser`, `parseDto`,
   …). Walk backwards: find the most recent call whose RE
   contained the value.
3. **Object-id linkage.** When the field is a child envelope, its
   `__meta__.id` first appeared as a return value (or argument)
   somewhere — that's a confident "constructed here" signal
   because ids are unambiguous, unlike string values.

For unique-ish values (UUIDs, names, ISBNs, generated IDs) this
draws a high-confidence "field came from here" arrow. Where it
breaks down: constants, booleans, common strings — there the
honest UX is to show candidate sources with a confidence flag
rather than a single arrow.

### Where the computation lives

- **Single-request scope: client-side.** The JSON is already
  hydrated; the diff walker is on the client. No new endpoint,
  no extra cost. Within a single request the source frame is
  usually visible in the timeline anyway.
- **Cross-request / cross-session scope: server-side.** The
  client can't answer "where did Author #9's `name` first come
  from across this whole session" without refetching everything.
  ClickHouse can. This is also where provenance gets
  *interesting* — within one request the construction is usually
  local; across a session, the chain runs through HTTP
  boundaries, repository layers, mappers.

### Indexing — `payload_tokens`

Object-id linkage is fast — `payloads.object_ids` has a
bloom-filter skip-index, so "envelope #N first appeared in call
X" is one indexed lookup.

Scalar-value matching used to be a full-scan (`payload_json LIKE
'%Tolkien%'` across every payload row in scope). The
`payload_tokens Array(String)` column was added for this:
distinct canonicalized scalar values present in the payload tree
(strings, numbers, booleans), populated at enrich time by
`ScalarTokenCollector`, bloom-filter indexed. Value-search and
provenance lookups run as `has(payload_tokens, ?)` probes
instead of `LIKE` full-table scans.

### Endpoint shape

```
POST /api/provenance
  { object_id, field_path, scope: "request" | "session" }
  → { candidates: [{call_id, source_kind, value, confidence}, ...] }
```

The endpoint shape is exactly what an LLM agent calling into the
trace store would want — "Trace provenance of `discountPercent`
on Order #4" is one tool call rather than several raw-payload
pulls plus reasoning. Lines up with "trace store as queryable
substrate for LLM debugging" — UI is one consumer, agents are
another.

---

## Value search

A developer reading a trace often spots a suspicious value
(`discount: 0.15`, `userId: "alice"`, an ISBN, a UUID) and wants
to find every other place in the same session or request where
that value appears.

```
GET /api/analysis/value-search?session_id=...&request_id=...&value=...
  → [{call_id, kind, path, signature}, ...]
```

UI: a search box (header bar or right-pane tab) — click any hit
→ jumps to that exact JsonTree node via the existing highlight
machinery.

The endpoint runs as a `has(payload_tokens, ?)` bloom-filter
probe, plus a small JSON walk in Java to refine each match into
`(path, kind)`. The same endpoint serves provenance's
scalar-source step internally — one tokenized-values index, two
consumers.

---

## What this is not

- Not a re-architecture of the agent. Agent stays unchanged;
  wire format gained one `own_hash` field per envelope.
- Not a "make the UI smart" play. Detection predicates are
  simple hash comparisons; smartness comes from surfacing the
  signal, not inferring intent.
- Not opinionated about *which* anomaly classes matter.
  Mutations and exceptions are the two with first-class agent
  signal today; if a third class earns first-class signal later
  (e.g. "method-time anomaly" via APM-ish data — which we have
  explicitly *not* taken on), it slots into the same shelf.
