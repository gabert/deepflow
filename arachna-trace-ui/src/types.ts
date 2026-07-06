// Shared domain types for the UI. Mirrors the on-the-wire envelope
// shape produced by the agent's EnvelopeSerializer/Hasher and the
// JSON shapes returned by record-query-server's QueryHandler.

// ---------------------------------------------------------------------
// Envelope (what the agent's hasher emits inside payload JSON)
// ---------------------------------------------------------------------

export interface EnvelopeMeta {
  id: number;
  class?: string;
  hash?: string;
  own_hash?: string;
}

export interface Envelope {
  __meta__: EnvelopeMeta;
  [field: string]: unknown;
}

export interface CycleRef {
  cycle_ref: true;
  ref_id: number;
}

// A node inside a hashed payload tree. Conceptually one of: envelope,
// cycle ref, array, plain object, or primitive — but the recursive
// shape causes TS deep-instantiation errors when threaded through
// generic ref/computed inferences, so we keep it as `unknown` and let
// callers narrow with isEnvelope / isCycleRef / Array.isArray.
export type PayloadNode = unknown;

// JSON path segment. Object keys are strings; array indices are numbers.
export type PathSegment = string | number;
export type Path = PathSegment[];

// ---------------------------------------------------------------------
// Diff entries (envelopeDiff#diffOwnState output)
// ---------------------------------------------------------------------

export type DiffKind = 'scalar' | 'idSwap' | 'added' | 'removed';

export interface DiffEntryScalar {
  path: Path;
  kind: 'scalar';
  before: unknown;
  after: unknown;
}
export interface DiffEntryIdSwap {
  path: Path;
  kind: 'idSwap';
  before: unknown;
  after: unknown;
}
export interface DiffEntryAdded {
  path: Path;
  kind: 'added';
  after: unknown;
}
export interface DiffEntryRemoved {
  path: Path;
  kind: 'removed';
  before: unknown;
}
export type DiffEntry =
  | DiffEntryScalar
  | DiffEntryIdSwap
  | DiffEntryAdded
  | DiffEntryRemoved;

// ---------------------------------------------------------------------
// Watches (UI-local model)
// ---------------------------------------------------------------------

export type WatchKind = 'instance' | 'field';

export interface InstanceWatch {
  kind: 'instance';
  objectId: number;
  className: string;
  hash?: string;
}

export interface FieldWatch {
  kind: 'field';
  objectId: number;
  className: string;
  fieldPath: Path;
}

export type Watch = InstanceWatch | FieldWatch;

// ---------------------------------------------------------------------
// Navigator highlight + payload kinds
// ---------------------------------------------------------------------

export type PayloadKind = 'TI' | 'AR' | 'AX' | 'RE';

export interface Highlight {
  callId: string;
  kind: PayloadKind;
  pathKey: string;
}

export interface JumpAddress {
  callId: string;
  kind: PayloadKind;
  objectId?: number;
  path: Path;
  // Optional carry-through so the navigator can load the target's
  // call tree before highlighting (calls are loaded lazily per
  // request). Emitters that have it (search hits, watch rows,
  // origin appearances — anything sourced from a server response
  // that includes request_id) should include it; navigator falls
  // back to a callsById lookup when absent.
  requestId?: number;
}

// ---------------------------------------------------------------------
// Origin / provenance (Phase 1 — single-request, scalar-only)
// ---------------------------------------------------------------------

// What the user clicked on. The composable indexes parsed payloads by
// scalar value and looks up matches with this target as the reference
// point.
export interface OriginTarget {
  callId: string;
  kind: PayloadKind;
  path: Path;
  value: unknown;
}

// Instance currently being "traced" on the call tree. Selecting one
// from a JsonTree envelope row paints bubble marks on every FrameCard
// whose subtree contains this object_id. Mutated calls (own_hash
// transitions on this id) get a stronger mark than plain appearances.
export interface TraceTarget {
  objectId: number;
  className: string;
}

// Per-call (or per-subtree) classification used by FrameCard's bubble
// mark. 'mutated' wins over 'appears' when both are present somewhere
// in the rolled-up subtree.
export type AppearanceKind = 'appears' | 'mutated';

// Confidence the algorithm assigns to a match. Heuristic, not
// authoritative — see useProvenance for the rules. UI shows it as a
// pill so the reader can judge weak chains.
export type Confidence = 'HIGH' | 'MED' | 'LOW';

// One row in the origin chain. The list is sorted by event time
// ascending; the row with isCurrent=true is where the user clicked
// (highlighted in the panel).
export interface OriginAppearance {
  callId: string;
  kind: PayloadKind;
  path: Path;
  ts: string;
  signature: string;
  confidence: Confidence;
  isCurrent: boolean;
  // Carry the request the appearance lives in so jumps can ensure
  // the target's call tree is loaded (calls load lazily per
  // request).
  requestId?: number;
}

// "Then mutated to V'": the next observation of the SAME field on
// the SAME envelope after the user-clicked row, where the field's
// value differs from the value being traced. id-based linkage so
// no scalar-collision concern — confidence is implicit-HIGH (we
// don't render a pill for it). Only emitted when the click target
// has an enclosing envelope on its path; values floating in plain
// containers without envelope context can't be tracked back to a
// specific instance and thus skip this lookup silently.
export interface OriginMutation {
  callId: string;
  kind: PayloadKind;
  path: Path;
  ts: string;
  signature: string;
  newValue: unknown;
  envelopeId: number;
  fieldPath: Path;
  requestId?: number;
}

// Structured view of a single value's provenance within the request.
//
//   - source: the first event-time appearance of the value. Kept as
//     a specific event (any kind, including AX when the value was
//     produced by a mutation).
//   - sourceKind: 'produced' if source is AX/RE (no earlier AR/TI
//     of the same call carried it), 'entered' otherwise.
//   - propagation: the AR/RE events between source and current.
//     A method has two value-flow boundaries — AR (entry) and RE
//     (exit). AX/TI rows are dropped from propagation: AX adds no
//     boundary signal except when it IS the source (the mutation
//     case, surfaced via sourceKind='produced'); TI is the
//     receiver-instance side, not value flow.
//   - current: the user-clicked event (any kind).
//   - nextMutation: the first later observation of the same field
//     on the same envelope where the value differs. Closes the loop
//     when the traced value did not "die out" but was overwritten.
export interface OriginChain {
  source: OriginAppearance | null;
  sourceKind: 'produced' | 'entered' | null;
  propagation: OriginAppearance[];
  current: OriginAppearance | null;
  nextMutation: OriginMutation | null;
}

// ---------------------------------------------------------------------
// API response shapes (record-query-server / QueryHandler)
// ---------------------------------------------------------------------

export interface SessionRow {
  session_id: string;
  agent_run_id: string;
  first_seen: string;
  last_seen: string;
  retain: boolean | number;
}

// Per-session storage footprint surfaced in the UI status bar.
// `payload_bytes` is the sum of `payload_size` (uncompressed JSON
// bytes) — the developer-facing answer to "how big is this trace".
// On-disk after ClickHouse compression is roughly 10× smaller; we
// don't surface that because per-session compressed bytes aren't
// directly addressable in CH (compression is per-part, not per-row).
export interface SessionSize {
  session_id: string;
  payload_rows: number;
  payload_bytes: number;
  call_rows: number;
}

export interface RequestRow {
  request_id: number;
  thread_name: string;
  call_count: number;
  exception_count: number;
  first_call: string;
  last_call: string;
  span_ms: number;
}

export interface ThreadRow {
  thread_name: string;
  call_count: number;
  first_call: string;
  last_call: string;
}

export type ReturnType = 'VOID' | 'VALUE' | 'EXCEPTION';

export interface CallRow {
  call_id: string;
  parent_call_id: string | null;
  request_id: number;
  thread_name: string;
  ts_in: string;
  ts_out: string;
  duration_ms: number;
  signature: string;
  return_type: ReturnType;
  is_exception: boolean | number;
  this_id: number | null;
  seq: number;
}

// Per-call timing/order info. The `pre` / `post` pair is a DFS
// pre/post index over the call tree — together they form a monotonic
// total-order chronological clock that disambiguates ties regardless
// of nesting:
//   - parent.pre  < child.pre  (parent enters before child)
//   - child.post  < parent.post (child exits before parent)
//   - earlier-sibling.post < later-sibling.pre (siblings are sequential)
// AR/TI events use pre, AX/RE events use post. See util/chrono.ts.
export interface CallMeta {
  ts_in: string;
  ts_out: string;
  pre: number;
  post: number;
}

// `payload_json` is the raw string from the API; `parsed` is filled in
// client-side by tryParse() and shared via PAYLOADS_BY_CALL_ID.
export interface PayloadRow {
  call_id: string;
  kind: PayloadKind;
  payload_json: string;
  payload_size?: number;
  root_hash: string;
  object_ids?: number[];
  ts_in: string;
  signature: string;
  seq?: number;
  // Optional — populated by endpoints that return rows from multiple
  // calls (requestPayloads, objectPayloads). Not echoed by
  // /api/calls/{id}/payloads, which is single-call by URL.
  request_id?: number | string;
  parsed?: PayloadNode;
}

export interface ObjectHistoryRow {
  call_id: string;
  session_id: string;
  request_id: number;
  ts_in: string;
  signature: string;
  kind: PayloadKind;
  root_hash: string;
  payload_json: string;
}

export interface MutationFieldPath {
  path: string;
  kind: DiffKind;
}

export interface MutationSample {
  object_id: number;
  ar_snapshot: Envelope;
  ax_snapshot: Envelope;
}

export interface MutationGroup {
  call_id: string;
  // Server-side mutations are session-wide; carry request_id per
  // group so jumps can lazy-load the target's call tree.
  request_id: number | string;
  signature: string;
  ts_in: string;
  class: string;
  field_paths: MutationFieldPath[];
  object_ids: number[];
  occurrences: number;
  sample: MutationSample;
}

export interface MutationsSummary {
  total_mutations: number;
  total_groups: number;
  session_id?: string;
  request_id?: number;
}

// Per-call summary of object-level changes between AR and AX, computed
// server-side in the same SQL pass that builds `groups`. Powers the
// JsonTree in-tree marks (mutation / added envelope highlights) — the
// UI consumes these maps directly instead of walking parsed payloads.
export interface MutationPerCall {
  call_id: string;
  mutated: number[];
  added: number[];
}

export interface MutationsResponse {
  summary: MutationsSummary;
  groups: MutationGroup[];
  perCall: MutationPerCall[];
}

// One (signature, input-hash) group in the behavioral diff between two
// sessions, returned by /api/analysis/behavior-diff. `status` semantics:
//   - output_changed: both sessions saw this exact input (same AR Merkle
//     hash) but produced different return-value hash sets — same input,
//     different output. The sharpest review signal.
//   - added / removed: the (signature, input) combination executed in
//     only one session — flow changed.
//   - unchanged: identical behaviour (repeat-count differences are not
//     flagged; nondeterministic-but-equal output sets are not flagged).
export type BehaviorDiffStatus = 'output_changed' | 'added' | 'removed' | 'unchanged';

export interface BehaviorDiffGroup {
  signature: string;
  ar_hash: string;
  status: BehaviorDiffStatus;
  count_a: number;
  count_b: number;
  re_hashes_a: string[];
  re_hashes_b: string[];
  exception_a: boolean;
  exception_b: boolean;
  example_call_a: string | null;
  example_call_b: string | null;
}

export interface BehaviorDiffSummary {
  calls_a: number;
  calls_b: number;
  groups: number;
  output_changed: number;
  added: number;
  removed: number;
  unchanged: number;
}

export interface BehaviorDiffResponse {
  summary: BehaviorDiffSummary;
  groups: BehaviorDiffGroup[];
}

// One row of /api/analysis/observed-signatures — the liveness sweep's
// "what actually ran" half.
export interface ObservedSignatureRow {
  signature: string;
  call_count: number;
}

// Single occurrence of a scalar value within a payload, returned by the
// /api/analysis/value-search endpoint. The server's bloom-filter probe
// over `payloads.payload_tokens` finds candidate rows; for each row the
// server walks the JSON to resolve the path to the matching leaf.
// `path` is a JSON array of segments from the payload root (string keys
// for object fields, numbers for array indices) — same shape the UI's
// JsonTree uses for highlight pathKey.
export interface ValueSearchHit {
  call_id: string;
  kind: PayloadKind;
  signature: string;
  ts_in: string;
  request_id: number;
  path: Path;
}
