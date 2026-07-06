# Auditing AI-generated code with Arachna Trace

Three workflows for the reviewer of agent-written code. All three exist
for the same reason: a reviewer of AI-generated changes today reads the
diff and the agent's own description of it — both describe *intent*.
Arachna Trace records *behavior*: what the code actually did, with real
data, in what order, touching which objects. These workflows turn one
recorded exercise of the code into evidence a reviewer can read.

They are language-neutral — anything the wire format captures supports
them — with the JVM agent as the reference implementation. Screens live
in the trace browser ([UI internals](../../arachna-trace-ui/docs/internals/ui.md));
config keys referenced below are documented in the
[agent configuration reference](../jvm/docs/reference/agent-config.md).

---

## 1. Flow narrative — read what the generated code did

**Question answered:** *"What does this new flow actually do with real
data?"*

**Rationale.** A call tree with drillable payloads (the debugger view)
is built for hunting a known bug. Reviewing is a different activity:
reading, start to finish, and judging. The flow narrative renders one
request as a chronological document — every traced call in execution
order, with its named arguments, return value, and duration — so a
reviewer reads the behavior the way they would read prose, and can
export it as Markdown to attach to the pull request. The review artifact
then contains three things: the diff (what changed), the agent's claim
(what it says it did), and the narrative (what it demonstrably did).

**Workflow:**

1. The AI agent produces a feature on a branch.
2. Run one scripted exercise of the flow with the trace agent attached
   (staging-shaped data; `matchers_include` scoped to the affected
   packages).
3. Open the session in the trace browser → pick the request → the book
   icon opens the flow narrative
   (`/sessions/{id}/requests/{rid}/narrative`).
4. Read; expand any call to the full captured payloads; *Copy as
   Markdown* → paste into the PR.

**The badges** on narrative rows are trace facts, not judgments (see
the philosophy note in the UI internals doc):

| Badge | Fact it states | Why a reviewer of AI code cares |
|---|---|---|
| `⚠ exception` | The call exited with an exception record | Silently caught-and-continued failures are a hallmark of "make the test pass" agent edits — a swallowed exception shows as an ⚠ call whose parent returns normally |
| `± mutates args` | AR and AX Merkle hashes differ — the call changed its arguments' content in place | Unexpected in-place mutation is one of the most common behavioral surprises in generated code |
| `⇄ thread` | The call runs on a different thread than its parent | Concurrency the diff didn't make obvious |

## 2. Behavioral diff — review the change, not the run

**Question answered:** *"Did behavior change where the agent claimed —
and only there?"*

**Rationale.** Every call's arguments and return value carry Merkle
content hashes computed at capture time. Two recordings of the same
scenario — one on the baseline, one on the candidate (distinguished by
`code_version` on their agent runs) — are therefore comparable without
reading a single payload: group each side's calls by *(signature,
argument hash)* and compare the sets of return-value hashes.

- **Same input, different output** (`output_changed`) is the sharp
  signal: proof, by content hash, that the change altered behavior for
  that exact input.
- **Added / removed** groups show flow changes — code paths the change
  introduced or abandoned.
- Repeat counts and nondeterministic-but-equal output *sets* are
  deliberately not flagged — flagging them would blame the run, not
  the change.

**Workflow:**

1. Script one representative exercise of the scenario.
2. Run it on the baseline (session A) and on the candidate (session B),
   agent attached, same inputs.
3. Trace browser → *Behavior diff* → pick A and B → *Compare*.
4. `output_changed` groups sort first; expanding one loads an example
   call from each side and shows the captured arguments and returns
   side by side.

Endpoint: `GET /api/analysis/behavior-diff?session_a=...&session_b=...`
— a hash-level comparison in ClickHouse; payloads load only on drill-in.

**Honest limits:** inputs must actually repeat between the runs for
groups to align (timestamps, random ids, or sequence-dependent inputs
in arguments make every group `added`+`removed`; keep the scenario
deterministic or truncate/scope such fields). The comparison is
call-level, not tree-level — it says *what* behaved differently, and
the narrative or debugger view says *why*.

## 3. Liveness sweep — does everything the AI kept actually run?

**Question answered:** *"Which code survived the change without ever
executing?"*

**Rationale.** Agentic refactors leave orphans — duplicated helpers,
branches nothing calls anymore. Static tools reason about reachability;
a recording states execution as fact. Structural mode
(`serialize_values=false`) is cheap enough (see the
[performance page](../jvm/docs/reference/performance.md)) to leave on
for a full regression suite or a day of staging traffic.

**Workflow:**

1. Attach the agent with `serialize_values=false` and
   `instrumentation_inventory=<path>` — the agent writes one line per
   method it instrumented, in the exact signature format traces carry.
2. Exercise the application: regression suite, staging traffic.
3. Fetch what ran:
   `GET /api/analysis/observed-signatures?session_id=...`
4. Diff the two lists (format parity is guaranteed by a test):

   ```bash
   sort instrumented-methods.txt > inventory.sorted
   curl -s '.../api/analysis/observed-signatures?session_id=SID' \
     | jq -r '.[].signature' | sort > observed.sorted
   comm -23 inventory.sorted observed.sorted   # instrumented, never called
   ```

The result is a list of methods that were woven but never executed
under everything that was thrown at the application — pruning
candidates, each with the evidence of the traffic that failed to reach
it. The inverse (`comm -13`) lists observed-but-not-inventoried
signatures, useful when comparing traffic across differently-scoped
runs.

**Scope note:** the inventory contains methods of classes the JVM
actually *loaded* (instrumentation happens at class load). A class the
exercised traffic never even loads produces no inventory lines — such
code is not missed by the sweep, it is one step deader: not only never
called but never linked. Class-level liveness for the never-loaded tail
comes from the classloader, not the tracer.

---

## Where each workflow lives

| Workflow | Agent side | Server side | UI |
|---|---|---|---|
| Flow narrative | any recording with values | existing calltree/payload endpoints | `FlowNarrativeView` (`/sessions/{id}/requests/{rid}/narrative`) |
| Behavioral diff | two recordings of one scenario | `/api/analysis/behavior-diff` | `BehaviorDiffView` (`/diff`) |
| Liveness sweep | `instrumentation_inventory=<path>`, structural mode | `/api/analysis/observed-signatures` | none — two sorted files and `comm` |
