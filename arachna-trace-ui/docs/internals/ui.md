# UI/UX Design — Arachna Trace Trace Browser

Living design doc for `arachna-trace-ui/` and the read-only `record-query-server/`
that backs it. Update as decisions firm up.

## Tech stack

- **Vite + Vue 3 + plain JavaScript** (no TypeScript). Keep it simple and reviewable.
- **PrimeVue** for data components. Chosen over Naive UI specifically for `TreeTable`,
  which is what the call-tree view needs out of the box.
- **Pinia** for shared state (current session, selected call, expanded nodes,
  open object-history pane).
- **Vue Router** so trace views are shareable URLs (`/sessions/:id/calls/:callId`).
- **Read API** via `record-query-server` (Java/Netty, port 8082). The browser
  never touches ClickHouse directly; SQL stays on the server.

## Core user question

> "What happened to my data?"

The UI is not a log viewer. It exists to support a *narrative* — a request came
in, data flowed through methods, something went wrong. Users need to follow
that story, not browse raw trace files.

## Product philosophy: surface signals, leave judgment to the user

A request is a data flow: data comes in, code transforms it, data
goes out. Somewhere along the way, in one of the transformations, a
mapping is wrong, a calculation slips, a side effect drops a field.
Whether any specific diff is "the bug" is a judgment the tool cannot
reliably make from the trace alone — heuristics for "the suspicious
diff" or "the auto-detected anomaly" produce false positives, which
shift the user's work from inspecting data to inspecting the heuristic.

The recording captures every value at every step within the configured
scope. The UI's job is to let a human point the lens at the part of the
recording they care about and read what is there. The interpretation
lives with the user; the tool exposes every transformation, every value,
and every nested field at arbitrary depth.

Concretely, this rules out:

- Auto-highlighting "what changed" between two payloads.
- Auto-extracting "the suspicious field."
- Inferring "the bug location" from heuristics.

And rules in:

- Faithful, fully drillable rendering of every captured envelope.
- Navigation that lets the user *construct* the focus they want — pin
  any path, any class, any object id into a watch.
- Adjacent transformations made comparable **when the user asks**, not
  pre-emptively.

This is also less code than the smart-detection paths. Fewer heuristics
to be wrong about.

## Mental model: "session as a recording"

The primary metaphor is a **recording the user walks through**. They sit
down, pick *their* session from a list, and scrub through what their app
did. Not a query tool — a playback tool with annotations.

This implies:

- The session list is the only landing page. (No global search bar, no
  "recent activity" feed at the top level.)
- Inside a session, the user moves linearly through method calls,
  forward and back, like stepping through a recording. Tree-shaped
  drill-down comes second to time-shaped scrubbing.

## Authorised sessions (later)

The session list will eventually be filtered: a user only sees sessions
they are authorised to see. For now the list is unfiltered and shows
everything. Auth model is deferred — but the API and UI should not
assume the list is global, so adding a filter later is a one-place change.

## Markers (user-set chapters)

The user, while working with the running app, will be able to **drop
markers** at moments they consider significant — typically just before
doing the operation they want to debug ("I'm about to enter the
problematic section"). Markers appear on the recording timeline as
chapter boundaries and let the user scope the view to a window around
the marker, instead of scrolling thousands of unrelated calls.

Implications (not yet implemented):

- Markers need a **wire path**, not a UI-only annotation. The right
  answer is an agent-side emit (new record kind, e.g. `MK` carrying
  label + timestamp + optional tags), so markers travel with the trace,
  persist in ClickHouse, and survive replay.
- A small REST/CLI endpoint on the running app for the user to drop a
  marker, which calls into the agent's recorder.
- UI affordance: chapter ticks on the left-pane timeline, with "scope
  to window around marker" as a one-click filter.

## Two views, side by side: the session screen

Inside a chosen session, the screen is a two-pane layout — the core
working surface of the product.

### Left pane — method-call recording

Linear walkthrough of the calls in the session (filtered by thread, by
marker window, etc.). User scrubs forward/back through method
invocations, each row showing signature, duration, return type. Selecting
a row reveals its arguments, return, and `this`. Mutations are visually
highlighted (hash change between consecutive observations of the same
object).

This is *time-shaped* navigation: "what happened next?"

### Right pane — the lens (watch list)

The user-steered focus pane. **Not** a tool-curated index of "classes
seen" or "fields that look interesting" — those are detective features
and we don't do those. Instead, this pane is a list the user **builds
by clicking** in the left pane.

While walking the recording on the left, when the user sees a value
worth tracking — `customer.address.country`, `invoice.tax`, an
`object_id`, a class — they click it, and that *path* is pinned to the
right pane. The right pane then shows every appearance of that path
across the request, in time order, with its value each time:

```
Watching: invoice.tax
─────────────────────────────────────────────────────
12:34:01.018  AR  TaxService.calc          19.99
12:34:01.019  RE  TaxService.calc          39.98
12:34:01.021  AR  CartMapper.toEntity      39.98
12:34:01.025  TI  CartRepository.save      39.98
…
```

A watch generalises across handles:

| Watch on…        | Right pane shows                                                  |
|------------------|-------------------------------------------------------------------|
| A JSON path      | Every payload in the request that has that path, with its value   |
| An `object_id`   | Every payload that mentions that id (existing object-history view)|
| A class          | Every envelope in the request whose `__meta__.class` matches      |
| A field name     | Every payload that has a field with that name, regardless of path |

Selecting a watch row on the right scrolls the left pane to that call.
Selecting a call on the left does **not** auto-pin anything — the user
chooses what to watch. The tool stays passive.

## Entry points (the three ways a user shows up)

The user usually arrives because *something is wrong*. They have a vague
signal — "Bob's invoice doubled," "the tax field is null." They don't
yet know which method is at fault. The landing page must support
**guess-by-narrowing**, not "click the right method first try."

| # | Entry point         | Mental model                                     | UI affordance                            |
|---|---------------------|--------------------------------------------------|------------------------------------------|
| 1 | I know the session  | "I just ran the test, where's my trace?"         | Sessions list (the landing page)         |
| 2 | I know the type     | "I think `Invoice` is being filled wrong."       | Right-pane class list inside the session |
| 3 | I know the instance | "Show me everywhere customer 42 was touched."    | Click instance in the right pane         |

(2) and (3) live *inside* the session, not as a global search. That's
the user's stated mental model: pick your session, then narrow.

## Interaction flow

```
Sessions list
  └── Pick session ──► Session view (two panes, side by side)
                          │
                          ├── LEFT: method-call recording (linear scrub)
                          │     ├── Filter: by thread, by marker window
                          │     ├── Click call ──► reveal AR / AX / RE / this
                          │     └── Markers shown as chapter ticks
                          │
                          └── RIGHT: object navigator
                                ├── Grouped by class
                                ├── Expand class ──► instances
                                ├── Click instance ──► highlight calls on left
                                └── Mutation count / hash timeline per instance
```

## Open UX questions

- **How much data by default?** Traces can have thousands of calls. Options:
  start collapsed, top-N levels, filter by package/class, search by signature.
- **Visualising mutations** — colour coding, inline diff, side-by-side?
- **Multi-thread view** — separate tabs per thread, or unified timeline with
  thread lanes?
- **Diff between two requests** — Alice's that worked vs. Bob's that didn't.
  Possibly the most valuable view, not yet sketched.
- **Streaming / live updates** while the agent is running, or refresh-only?

## Flow narrative (audit view)

`FlowNarrativeView` (`/sessions/:id/requests/:rid/narrative`) renders
one request as a chronological *document*: every traced call in `seq`
order, indented by nesting depth, one line each — signature, named-arg
preview, return preview, duration — expandable to the full captured
payloads. A *Copy as Markdown* button exports the whole narrative so it
can be attached to a pull request.

**Why a second screen instead of a mode of the session view:** the
debugger metaphor (structural-left / inspection-right) is built for
*hunting* — the user knows something is wrong and drills. Auditing —
the primary review activity for AI-generated code, see
[AI code audit](../../../arachna-trace-agents/docs/ai-code-audit.md) —
is *reading*: start to finish, judging as you go, then sharing what you
read. Reading wants a document, not panes; sharing wants an export, not
a deep link into tool state. The two screens intentionally answer
different questions: the session view answers "where is the bug," the
narrative answers "what happens."

**Badges and the product philosophy.** Narrative rows carry three
badges: `⚠ exception` (the call exited exceptionally), `± mutates args`
(AR and AX Merkle hashes differ), `⇄ thread` (runs on a different
thread than its parent). These do not violate the "surface signals,
leave judgment to the user" rule above — each states a *fact from the
trace*, exactly as `ExceptionChip` and `MutationsPanel` already do,
with the judgment (is this mutation intended? is this catch correct?)
left entirely to the reader. What the rule prohibits — and the
narrative still does not do — is ranking, anomaly-scoring, or
"suspiciousness" inference.

## Behavior diff (two-session comparison)

`BehaviorDiffView` (`/diff`) compares two recorded sessions of the same
scenario — the answer to the open question "diff between two requests"
above, generalised to whole sessions and grounded in hashes rather than
payload walks.

The server (`/api/analysis/behavior-diff`) groups each side's calls by
`(signature, AR root_hash)` — "this method invoked with exactly this
input" — and compares the *sets* of RE root hashes per group:

| Status | Meaning |
|---|---|
| `output_changed` | Both sessions saw this exact input; the return-value hash sets differ. Same input, different output — proof by content hash that behaviour changed for this input. |
| `added` / `removed` | The (signature, input) combination executed on one side only — flow changed. |
| `unchanged` | Identical behaviour. Repeat-count differences and nondeterministic-but-equal output sets are deliberately not flagged. |

The screen shows summary chips and the group list (`output_changed`
first, `unchanged` hidden by default). Non-unchanged rows carry a
compact `__meta__`-stripped preview of the example call's arguments,
attached server-side, so groups sharing a signature but differing in
input are distinguishable without expanding. Expanding loads one
example call from each side. The two payloads render as ONE aligned line-diff
grid (LCS at line level) inside a single scroll container — the sides
cannot scroll apart, and each aligned row is tinted same / changed /
only-A / only-B, so the difference is marked in place. Marking here is
consistent with the philosophy's "comparable when the user asks"
clause: the user explicitly chose two sessions and expanded the group.
`__meta__` identity fields (object id, own_hash) are hidden from the
diff by default — they are run-local and differ between any two
recordings, which would mark nearly every line — with a toggle to show
them. In line with the offload-to-server rule, the group-level diff is
hash algebra in ClickHouse/Java — no payload JSON reaches the browser
until the user drills into a group.

**Honest limits (also stated in the workflow doc):** inputs must repeat
between the two runs for groups to align — nondeterministic argument
content (timestamps, random ids) degrades the diff to added+removed
noise. Call-level, not tree-level: it says *what* behaved differently;
the narrative and session views say *why*.

## Read API surface (record-query-server)

Currently implemented:

| Method & path                                | Purpose                                          |
|----------------------------------------------|--------------------------------------------------|
| `GET /api/health`                            | Liveness                                         |
| `GET /api/sessions`                          | Recent sessions (most recent first)              |
| `GET /api/sessions/{id}/threads`             | Threads observed in a session, with call counts  |
| `GET /api/sessions/{id}/calltree?thread=…`   | Paired call rows for the call-tree view          |
| `GET /api/calls/{id}/payloads`               | TI/AR/AX/RE payloads for one call                |
| `GET /api/objects/{id}/history`              | Every payload row mentioning the given object id |
| `GET /api/analysis/behavior-diff?session_a=&session_b=` | Two-session behavioral diff, hash-based (Behavior diff view) |
| `GET /api/analysis/observed-signatures?session_id=` | Distinct observed signatures (liveness sweep) |

To add (driven by the two-pane session view + markers):

| Method & path                                          | Purpose                                                            |
|--------------------------------------------------------|--------------------------------------------------------------------|
| `GET /api/sessions/{id}/classes`                       | Right-pane top level: classes seen in the session, with counts     |
| `GET /api/sessions/{id}/classes/{class}/instances`     | Drill-down: instances of a class, with mutation count & timestamps |
| `GET /api/sessions/{id}/markers`                       | Markers/chapters set during recording                              |
| `GET /api/sessions/{id}/recording?from=&to=&marker=…`  | Linear call list scoped to a window or marker chapter              |

Open question for the class/instance endpoints: extracting `__meta__.class`
from `payload_json` works at small scale but won't at large. The clean
answer is a derived column or a small `objects_seen(session, class, object_id)`
materialised view populated by the processor. Defer until we have a query
that's actually slow.

All endpoints return JSON; the browser holds no SQL and no DB credentials.

## Project layout

```
flowspy/
  arachna-trace-infra/
    record-query-server/         Read API (Netty + ClickHouse HTTP)
  arachna-trace-ui/              Vite + Vue 3 + PrimeVue + Pinia
    docs/internals/ui.md         ← this file
```

## Build/run

See `arachna-trace-ui/README.md` for the dev loop. The Vite dev server proxies
`/api` → `localhost:8082`, so the UI can call the API as if same-origin.

---

## Current architecture

This section reflects the implementation. Update when the shape
changes.

### Navigation: one reactive address

A single ref drives every selection:

```
highlight = { callId, kind, pathKey } | null
```

Provided by `SessionDetailView` (which also bumps a `navTick` ref on every
nav so re-clicks of the same address still trigger scroll).

`PayloadViewer` injects `highlight`, computes a *local* highlighted path
(non-null only when the address's `(callId, kind)` matches its own props),
forwards it to its `JsonTree` subtree as `highlightedPathKey`, and expands
the path's prefixes when its local highlight transitions to non-null.

`JsonTree` compares its own `pathKey` to the prop. `isMatch` is one
equality, no recursive walk. When `isMatch` is true the node renders
the `.flashed` class; a post-flush watcher scrolls it into view.

`WatchPanel` injects the same `highlight` and marks the row whose address
matches it with the same `.current` styling — so both sides agree on
selection by construction. Click a watch row → `goto({callId, kind, path})`
→ chain expansion + highlight assignment + tick bump → Vue's reactivity
does the rest.

No imperative method chains. No ref maps. No querySelector. No polling.

### Rendering: nested call tree, top-to-bottom = time

`SessionDetailView` provides a `payloadsByCallId` map (built once from the
request's bulk-loaded payloads) and a `childrenByParent` map. It renders
only root frames; each `FrameCard` looks up its own children and renders
them recursively *inside* its body, between its TI/AR (entry) block and
its AX/RE (exit) block. Indentation comes from CSS nesting — a dashed
left border per level.

Expansion is shared state: an `expansionDefault` ref plus an
`expansionOverrides` `Map<callId, boolean>`. Per-frame toggles write
into overrides; `expand all` / `collapse all` flip the default and
clear overrides. New frames inherit the most recent global default.

Default state on request load: collapsed (only root frames visible).

### Watch panel: two honest signals, one table

For instance watches the right pane shows two columns side by side:
deep hash (agent's Merkle, half-MD5) and own-state hash (UI-computed
64-bit FNV-1a over the envelope's own scalar fields and child reference
IDs). Two transition markers per row (▲ deep, △ own). Cycle-direction
noise shows as ▲ alone; real mutations show ▲△ together. Renders as
a real `<table>` with `<colgroup>` + `<thead>` so column widths are
aligned by the browser.

Field watches stay one-column (resolved value) since the value-based
comparison naturally collapses cycle refs to ids.

### Open issue carried into next session

- **U-02** — Watch-row click correctly applies the `.flashed` class
  but the left pane sometimes doesn't scroll the target into view.
  See `arachna-trace-agents/docs/process/KNOWN_BUGS.md → U-02` for the suspected causes
  and what to try next.

### Map of the components

Three views (top-level routed pages) plus seventeen components.
Components are grouped here by role; the navigation primitive
described above flows through the ones marked **(nav)**.

**Views (`src/views/`)**

| File | Responsibility |
|---|---|
| `App.vue` | Router outlet. Mounts the active view; no domain logic. |
| `views/SessionsView.vue` | Sessions list. Two-pane preview: pick a session on the left, see its requests + rollups (exceptions, mutations, duration) on the right before opening. |
| `views/SessionDetailView.vue` | **(nav)** Top-level page for one session. Provides `highlight`, `navTick`, `payloadsByCallId`, `childrenByParent`, expansion state. Hosts `goto({callId, kind, path})`. |
| `views/ObjectHistoryView.vue` | Per-`object_id` timeline across the session. Vertical history keyed off own_hash transitions; reuses the diff renderer. |
| `views/FlowNarrativeView.vue` | Audit view: one request as a chronological document with fact badges and Markdown export. Self-contained (own data loading; no injections from SessionDetailView). |
| `views/BehaviorDiffView.vue` | Two-session behavioral comparison; summary chips, grouped statuses, lazy side-by-side example payloads. Self-contained. |

**Structural panels — left pane of the session view**

| File | Responsibility |
|---|---|
| `components/CallTreePanel.vue` | **(nav)** Owns the call-tree recursive layout, expand/collapse, and the canonical `highlightCall(callId)` primitive that every other panel routes through to land on a call (BOX + EXPAND ancestors + SCROLL into view). |
| `components/RequestNode.vue` | Per-request header inside the tree (entry signature, badges, exception tint). |
| `components/FrameCard.vue` | One call's row + body. Recursive — its body contains nested FrameCards between entry and exit payloads. |
| `components/FrameChildrenGroup.vue` | Groups consecutive child FrameCards under a parent for visual nesting. |
| `components/PayloadViewer.vue` | **(nav)** One payload (TI/AR/AX/RE). Owns its tree's expansion `Set<pathKey>`. Expands path prefixes when its local highlight is set. |
| `components/JsonTree.vue` | **(nav)** Recursive renderer for one JSON envelope. Stateless beyond what its props describe. Computes `isMatch`, applies `.flashed`, scrolls itself into view on match. |

**Inspection panels — right pane (the lens)**

| File | Responsibility |
|---|---|
| `components/CallInspectionCard.vue` | Pinned/transient inspection of one call. Sections for TI/AR/AX/RE with chips (`ExceptionChip`, mutation count). Has a ↙ "reveal in tree" button that calls back into `CallTreePanel.highlightCall`. |
| `components/WatchPanel.vue` | **(nav)** Pinned watches over time. Two-column hash display (deep / own_hash); marks the row whose address matches the global highlight. |
| `components/WatchItem.vue` | One pinned-watch row in `WatchPanel`. Renders the appearance list, hash transitions, and the row-click → `goto` wiring. |
| `components/MutationsPanel.vue` | Within-request mutations view. AR↔AX own_hash diff, grouped per `(call, class, changed-field-set)`. See [bug-finding.md](bug-finding.md). |
| `components/OriginPanel.vue` | Field provenance / origin chain. Source / propagation / next-mutation cards per appearance. |
| `components/SearchPanel.vue` | Value search across the loaded session/request. Calls `/api/analysis/value-search`; click a hit → `goto` jumps to the JsonTree node. |
| `components/DiffEntries.vue` | Field-level diff rows used by `MutationsPanel` and `OriginPanel`. Shared renderer. |

**Chrome**

| File | Responsibility |
|---|---|
| `components/CollapsiblePanel.vue` | Generic show/hide wrapper used by the right-pane tabs. |
| `components/NavOverlay.vue` | The ↑/↓ navigator overlay shown above the workspace header (e.g. exception nav across the session). |
| `components/ExceptionChip.vue` | Red ⚠ chip on rows / cards with `return_type=EXCEPTION`. |
| `components/StatusBar.vue` | Bottom status bar: session id, request id, loading indicators, exception counters. |
