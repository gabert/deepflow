# DeepFlow Trace Format — Feedback & Recommendations

Observations from using the trace format to diagnose a real bug (mutating "read-only" helpers inside a JPA-style service). Framed as what worked, what slowed analysis down, and what would make the next investigation even faster.

---

## What worked exceptionally well

### 1. `ID:N` + `CH:<hash>` identity/content split

The single best design choice in the format. Stable object identity plus a content hash turns *"did this object mutate?"* into a one-line diff — no need to reconstruct state from deltas or diff whole JSON blobs.

This is what cracked the case. Without `CH` changing while `ID` stayed at `9`, the side-effect bug in `normalizeIsbns` and `buildDisplayName` would have been invisible.

**Keep this. Lead with it in the docs.**

### 2. Parallel `AR`/`AI` and `RE`/`RI` tracks

Having a human-readable view (`AR`, `RE`) side-by-side with an identity-carrying view (`AI`, `RI`) is great. Reasoning happens on the readable form; identity confirmation happens on the other. Two tools, clean separation — and both cheap to grep.

### 3. Explicit `PM` (parent method) field

Reconstructing the call tree from `PM` was unambiguous even though the second column in each row has its own (different) meaning. An explicit parent pointer beats inferring structure from depth every time.

### 4. `CL` (call-site line number)

Quietly one of the strongest tags in the format. Easy to overlook because it's just an integer, but it punches way above its weight.

Most trace formats tell you *what* was called but not *where from*. `CL` collapses that step — you jump straight to the line in the caller's source. And because it's attached to every call (not just the top frame), you get free control-flow reconstruction:

```
prepareCatalogExport (line 88)  → normalizeIsbns
prepareCatalogExport (line 106) → buildDisplayName
prepareCatalogExport (line 127) → findBooksByAuthorEntity
```

Three siblings, three different call-site lines — the order of statements in `prepareCatalogExport` is visible without opening the file. If a conditional had been involved (`if (x) doA() else doB()`), `CL` would reveal which branch was taken. That's free control-flow reconstruction.

It also makes trace diffs much more useful: if two runs produce different `CL` sequences in their children, you know the branching differed — before looking at any data.

### 5. Cycle-reference handling (`ref_id` / `cycle_ref`)

JPA entities with bidirectional relationships (Author ↔ Books) would normally cause a serializer to loop forever or blow the stack. The format handles this cleanly:

```
'author': {'ref_id': 9, 'cycle_ref': True}
```

The back-reference is explicit, typed, and still tells you *which* previously-seen object it points to. That meant the `AuthorEntity` ↔ `BookEntity` cycle just worked, with zero noise in the trace. Not a small thing — many tracing formats would either crash here or produce unreadable output.

### 6. `TN` / `TI` on every record

Putting the thread name and thread id on *every* record feels redundant in a single-threaded trace, but it's the right call. If a file ever gets concatenated, interleaved, or filtered, every line is still self-identifying. The cost is a few bytes per line; the benefit is robustness under any downstream processing.

### 7. Flat, self-describing, human-readable lines

The `.llm` extension is a nice signal of intent, and the format lives up to it. Every line is independently meaningful — no binary framing, no state machine, no "you must read this header first to decode the rest." That's exactly what makes it tractable for an LLM (or a human with `grep`) to reason about without docs. I reconstructed most of the semantics from the file itself, which is the strongest possible test of the design.

One consequence worth being aware of: the format is verbose. That's a conscious tradeoff and the right one for post-hoc analysis — but it means at high call volumes you'll want clear guidance on sampling strategies or per-package filters.

---

## Things that slowed analysis down

### ~~1. Meaning of the second column is not obvious~~ DONE

Replaced depth-based tracking with explicit CI (call ID) and PI (parent call ID). Call tree is now unambiguous. Format documented in `doc/trace-format.md`.

### ~~2. The "double return block" per method is undocumented~~ DONE

Was a Python formatter bug — wrong `method_id` after TE popped the stack. Fixed in `agent_dump_processor.py`. Each method now produces exactly one return block.

### ~~3. JDK wrapper types serialize oddly~~ SKIPPED

Not relevant — traced code uses domain objects, not JDK wrapper types like Optional or AtomicReference.

### ~~4. Line endings~~ DONE

Pinned to LF. `FileDestination` now uses `writer.write('\n')` instead of `writer.newLine()`.

---

## High-leverage additions

### 1. A summary record at the end of each trace

A single trailing record with:

- total method calls
- max call depth
- whether any exception escaped the root
- total wall time
- thread name / session id (already present, but summarized)

…would turn *"is there anything to look at here?"* into a zero-cost question. Right now users have to scan.

### ~~2. An explicit `EX` record for propagated exceptions~~ SKIPPED

`RT;EXCEPTION` already covers this. Tracking `caught_by` across frames adds complexity for a niche use case.

### ~~3. An optional `MU` (mutation) record~~ DONE

Implemented as `AX` (arguments at exit) tag instead of a computed `MU` record. The agent re-serializes arguments at method exit when `AX` is enabled via `emit_tags`. Comparing AR and AX with the same `object_id` reveals mutations. Opt-in to avoid doubling serialization cost.

### ~~4. (Nice-to-have) Timing aggregates~~ SKIPPED

Duration can be computed from TS/TE by any consumer — no need to emit it.

### 5. Polish for `CL`

`CL` is already excellent; these are small extensions that would make it even stronger:

- **Pair it with the source file.** Right now `CL;88` is a line in *some* file — inferable from the enclosing `MS`, but a parser has to do that work. An optional `SF` (source file) record, or folding the filename into `MS`, removes the inference step. Especially useful when bytecode weaving, lambdas, or inner classes make the "obvious" file ambiguous.
- **`CL` on the method entry itself, not just call sites inside it.** A method with no children (a leaf) currently tells you nothing about where inside itself it lives. Emitting `CL` for the method's declaration line would at least anchor the leaf.
- **Bytecode-offset fallback.** When source isn't available (stripped jars, JIT'd stdlib), `CL` may be `-1` or missing. A `BC` (bytecode offset) tag as a fallback keeps the "where" question answerable even without source.

### 6. Format version header

Standard hygiene, currently absent. A single leading record like:

```
#;DEEPFLOW;VERSION;1.0
```

…lets parsers handle future format changes gracefully instead of silently misinterpreting records. Cheap to add now; expensive to retrofit later when v2 ships and old parsers start reading it as v1.

### 7. Explicit truncation indicator

Values in the trace (especially deep object graphs) may eventually need a size cap. When that happens, a value that got shortened to fit a limit should say so — otherwise users will chase ghosts trying to figure out why a field "disappeared." Something like:

```
<id>;<depth>;AR;[{...partial...}];TRUNC;original_size=48213
```

Or a separate `TR` record sibling to `AR`/`RE`. Either way, the principle is: if the serializer ever lies about completeness, the trace should admit it in-band.

---

## Bottom line

The format has real teeth. It caught a non-obvious side-effect bug from a log file alone, which is not something most APM or tracing tools can do — they capture spans but not the *content* of objects flowing through them. The identity/content-hash mechanism is the differentiator; lean into it.

The additions above — especially `EX` and `MU` records, plus a documented column spec — would move the format from "extremely useful once you understand it" to "immediately legible to a new user."
