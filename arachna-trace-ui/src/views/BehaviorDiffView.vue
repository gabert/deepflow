<script setup lang="ts">
// Behavioral diff — compare two recorded sessions of the same scenario
// (e.g. main vs an AI-authored branch) by evidence rather than by
// reading the code diff. Server groups each side's calls by
// (signature, argument Merkle hash) and compares the sets of
// return-value hashes: "same input, different output" surfaces as
// `output_changed`, flow changes as `added`/`removed`. Hashes only —
// no payload leaves ClickHouse until the user expands a group, which
// then loads the example calls from both sides for a side-by-side read.
import { computed, onMounted, ref } from 'vue';
import Button from 'primevue/button';
import Message from 'primevue/message';
import ProgressSpinner from 'primevue/progressspinner';
import { api } from '../api/client';
import type { BehaviorDiffGroup, BehaviorDiffResponse, PayloadRow, SessionRow } from '../types';

const sessions = ref<SessionRow[]>([]);
const sessionA = ref<string>('');
const sessionB = ref<string>('');
const loadingSessions = ref(true);
const running = ref(false);
const error = ref<string | null>(null);
const result = ref<BehaviorDiffResponse | null>(null);
const showUnchanged = ref(false);

const expandedKey = ref<string | null>(null);
const examplePayloads = ref<{ a: PayloadRow[]; b: PayloadRow[] } | null>(null);
const loadingExamples = ref(false);

onMounted(async () => {
  try {
    sessions.value = await api.listSessions();
    // Sensible default: the two most recent sessions, older first.
    if (sessions.value.length >= 2) {
      sessionA.value = sessions.value[1].session_id;
      sessionB.value = sessions.value[0].session_id;
    }
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    loadingSessions.value = false;
  }
});

async function run(): Promise<void> {
  if (!sessionA.value || !sessionB.value) return;
  running.value = true;
  error.value = null;
  result.value = null;
  expandedKey.value = null;
  try {
    result.value = await api.behaviorDiff(sessionA.value, sessionB.value);
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    running.value = false;
  }
}

const visibleGroups = computed<BehaviorDiffGroup[]>(() => {
  const groups = result.value?.groups ?? [];
  return showUnchanged.value ? groups : groups.filter((g) => g.status !== 'unchanged');
});

function keyOf(g: BehaviorDiffGroup): string {
  return `${g.signature}|${g.ar_hash}`;
}

async function toggleExpand(g: BehaviorDiffGroup): Promise<void> {
  const key = keyOf(g);
  if (expandedKey.value === key) {
    expandedKey.value = null;
    examplePayloads.value = null;
    return;
  }
  expandedKey.value = key;
  examplePayloads.value = null;
  loadingExamples.value = true;
  try {
    const [a, b] = await Promise.all([
      g.example_call_a ? api.callPayloads(g.example_call_a) : Promise.resolve([]),
      g.example_call_b ? api.callPayloads(g.example_call_b) : Promise.resolve([])
    ]);
    examplePayloads.value = { a, b };
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    loadingExamples.value = false;
  }
}

// --- side-by-side line diff -----------------------------------------------
//
// The expanded group renders both examples as ONE aligned grid inside one
// scroll container: the sides cannot scroll apart, and each aligned row is
// classified same / changed / only-A / only-B so the difference is marked
// in place. This is the "adjacent transformations made comparable when the
// user asks" case from the product philosophy — the user explicitly picked
// two sessions and expanded the group.
//
// __meta__ (object id, own_hash) is run-local identity: it differs between
// any two recordings, so with it visible almost every line would be marked.
// Hidden by default; the toggle brings it back (never silently unavailable).

const showMeta = ref(false);

interface DiffLineRow {
  left: string;
  right: string;
  cls: 'same' | 'changed' | 'left' | 'right';
}

function stripMeta(node: unknown): unknown {
  if (Array.isArray(node)) return node.map(stripMeta);
  if (node && typeof node === 'object') {
    const out: Record<string, unknown> = {};
    for (const [key, value] of Object.entries(node as Record<string, unknown>)) {
      if (key === '__meta__') continue;
      out[key] = stripMeta(value);
    }
    return out;
  }
  return node;
}

function payloadLines(rows: PayloadRow[], kind: string, includeMeta: boolean): string[] {
  const row = rows.find((r) => r.kind === kind);
  if (!row) return [];
  try {
    let parsed: unknown = JSON.parse(row.payload_json);
    if (!includeMeta) parsed = stripMeta(parsed);
    return JSON.stringify(parsed, null, 2).split('\n');
  } catch {
    return row.payload_json ? [row.payload_json] : [];
  }
}

/** Line-level LCS alignment; unmatched runs pair up as 'changed' rows. */
function diffRows(a: string[], b: string[]): DiffLineRow[] {
  const n = a.length;
  const m = b.length;
  const rows: DiffLineRow[] = [];
  if (n * m > 4_000_000) {
    // Degenerate size — pair line by line rather than blow up the DP table.
    for (let i = 0; i < Math.max(n, m); i++) {
      const left = a[i] ?? '';
      const right = b[i] ?? '';
      rows.push({ left, right, cls: left === right ? 'same' : 'changed' });
    }
    return rows;
  }
  const dp: Uint32Array[] = Array.from({ length: n + 1 }, () => new Uint32Array(m + 1));
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      dp[i][j] = a[i] === b[j] ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
    }
  }
  const pendingLeft: string[] = [];
  const pendingRight: string[] = [];
  const flush = () => {
    const k = Math.max(pendingLeft.length, pendingRight.length);
    for (let x = 0; x < k; x++) {
      const left = pendingLeft[x];
      const right = pendingRight[x];
      if (left !== undefined && right !== undefined) rows.push({ left, right, cls: 'changed' });
      else if (left !== undefined) rows.push({ left, right: '', cls: 'left' });
      else rows.push({ left: '', right, cls: 'right' });
    }
    pendingLeft.length = 0;
    pendingRight.length = 0;
  };
  let i = 0;
  let j = 0;
  while (i < n && j < m) {
    if (a[i] === b[j]) {
      flush();
      rows.push({ left: a[i], right: b[j], cls: 'same' });
      i++; j++;
    } else if (dp[i + 1][j] >= dp[i][j + 1]) {
      pendingLeft.push(a[i++]);
    } else {
      pendingRight.push(b[j++]);
    }
  }
  while (i < n) pendingLeft.push(a[i++]);
  while (j < m) pendingRight.push(b[j++]);
  flush();
  return rows;
}

const diffByKind = computed(() => {
  const payloads = examplePayloads.value;
  if (!payloads) return [];
  return (['AR', 'RE'] as const)
    .map((kind) => ({
      kind,
      rows: diffRows(
        payloadLines(payloads.a, kind, showMeta.value),
        payloadLines(payloads.b, kind, showMeta.value)
      )
    }))
    .filter((d) => d.rows.length > 0);
});

function shortSignature(signature: string): string {
  return signature.replace(/\s*\[[^\]]*\]\s*$/, '').replace(/^[^(]*::/, '');
}
// (raw single-side pretty-printing was replaced by the aligned diff grid)

const statusLabel: Record<string, string> = {
  output_changed: 'same input, different output',
  added: 'only in B',
  removed: 'only in A',
  unchanged: 'unchanged'
};
</script>

<template>
  <section class="diff-view">
    <header class="dv-head">
      <h1 class="page-title">Behavior diff</h1>
      <div class="dv-pickers">
        <label class="dv-pick">
          <span class="label">A (baseline)</span>
          <select v-model="sessionA" :disabled="loadingSessions">
            <option v-for="s in sessions" :key="s.session_id" :value="s.session_id">
              {{ s.session_id }} ({{ s.last_seen }})
            </option>
          </select>
        </label>
        <label class="dv-pick">
          <span class="label">B (candidate)</span>
          <select v-model="sessionB" :disabled="loadingSessions">
            <option v-for="s in sessions" :key="s.session_id" :value="s.session_id">
              {{ s.session_id }} ({{ s.last_seen }})
            </option>
          </select>
        </label>
        <Button label="Compare" icon="pi pi-arrows-h" :loading="running"
                :disabled="!sessionA || !sessionB || sessionA === sessionB"
                @click="run" />
      </div>
    </header>

    <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>

    <div v-if="result" class="dv-body">
      <div class="dv-summary">
        <span class="chip c-changed">{{ result.summary.output_changed }} output changed</span>
        <span class="chip c-added">{{ result.summary.added }} added</span>
        <span class="chip c-removed">{{ result.summary.removed }} removed</span>
        <span class="chip c-same">{{ result.summary.unchanged }} unchanged</span>
        <label class="dv-toggle">
          <input type="checkbox" v-model="showUnchanged" /> show unchanged
        </label>
      </div>

      <p v-if="!visibleGroups.length" class="muted centered">
        No behavioral differences between the two sessions
        {{ showUnchanged ? '' : '(unchanged groups hidden)' }}.
      </p>

      <div v-for="g in visibleGroups" :key="keyOf(g)" class="dv-group-wrap">
        <div class="dv-group" :class="'s-' + g.status" @click="toggleExpand(g)">
          <span class="dv-status">{{ statusLabel[g.status] }}</span>
          <span class="dv-sig-block">
            <span class="dv-sig mono">{{ shortSignature(g.signature) }}</span>
            <span v-if="g.input_preview" class="dv-input mono" :title="g.input_preview">{{ g.input_preview }}</span>
          </span>
          <span class="dv-counts mono muted">A×{{ g.count_a }} · B×{{ g.count_b }}</span>
          <span v-if="g.exception_a || g.exception_b" class="dv-exc" title="At least one side exited with an exception">⚠</span>
        </div>

        <div v-if="expandedKey === keyOf(g)" class="dv-detail">
          <div v-if="loadingExamples" class="centered">
            <ProgressSpinner style="width:1.5rem;height:1.5rem" />
          </div>
          <div v-else-if="examplePayloads">
            <div class="dv-diff-head">
              <h3>A <code class="mono muted">{{ g.example_call_a ?? '—' }}</code></h3>
              <h3>B <code class="mono muted">{{ g.example_call_b ?? '—' }}</code></h3>
              <label class="dv-toggle">
                <input type="checkbox" v-model="showMeta" /> show identity metadata (__meta__)
              </label>
            </div>
            <div v-for="d in diffByKind" :key="d.kind" class="dv-payload">
              <span class="dv-kind">{{ d.kind }}</span>
              <div class="dv-diff-grid mono">
                <template v-for="(row, idx) in d.rows" :key="idx">
                  <div class="dcell dl" :class="'d-' + row.cls">{{ row.left }}</div>
                  <div class="dcell dr" :class="'d-' + row.cls">{{ row.right }}</div>
                </template>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <div v-else-if="!error" class="dv-empty">
      <p class="muted">
        Record the same scenario twice — for example once on <em>main</em> and once on an
        AI-authored branch — then compare the two sessions here. Calls are matched by
        <em>signature + argument content hash</em>; a match with different return-value
        hashes means the code change altered behaviour for that exact input.
      </p>
    </div>
  </section>
</template>

<style scoped>
.diff-view {
  display: flex; flex-direction: column;
  height: 100%; min-height: 0;
  background: var(--bg-base);
}
.dv-head {
  display: flex; align-items: center; justify-content: space-between; gap: 1rem; flex-wrap: wrap;
  padding: 0.5rem 1rem;
  border-bottom: 1px solid var(--border);
  background: var(--bg-surface);
  flex-shrink: 0;
}
.page-title { margin: 0; font-size: 1rem; color: var(--text-primary); }
.dv-pickers { display: flex; align-items: flex-end; gap: 0.75rem; }
.dv-pick { display: flex; flex-direction: column; gap: 0.15rem; }
.label { color: var(--text-muted); text-transform: uppercase; font-size: 0.7rem; letter-spacing: 0.04em; }
.dv-pick select {
  background: var(--bg-elevated); color: var(--text-primary);
  border: 1px solid var(--border-strong); border-radius: 4px;
  padding: 0.3rem 0.5rem; font-family: ui-monospace, monospace; font-size: var(--mono-size);
  max-width: 26rem;
}

.dv-body { overflow-y: auto; padding: 0.75rem 1rem 2rem; }
.dv-summary { display: flex; align-items: center; gap: 0.5rem; margin-bottom: 0.75rem; flex-wrap: wrap; }
.chip {
  font-size: 0.75rem; padding: 0.1rem 0.6rem; border-radius: 999px;
  border: 1px solid var(--border-strong); color: var(--text-secondary);
  font-family: system-ui, sans-serif;
}
.c-changed { color: #fbbf24; border-color: rgba(251, 191, 36, 0.5); }
.c-added   { color: #34d399; border-color: rgba(52, 211, 153, 0.5); }
.c-removed { color: #f87171; border-color: rgba(248, 113, 113, 0.5); }
.dv-toggle { margin-left: auto; color: var(--text-muted); font-size: 0.8rem; display: flex; gap: 0.35rem; align-items: center; }

.dv-group {
  display: flex; align-items: baseline; gap: 0.75rem;
  padding: 0.35rem 0.75rem; border-radius: 4px; cursor: pointer;
  border-left: 3px solid transparent;
}
.dv-group:hover { background: var(--bg-hover); }
.dv-group.s-output_changed { border-left-color: #fbbf24; }
.dv-group.s-added          { border-left-color: #34d399; }
.dv-group.s-removed        { border-left-color: #f87171; }
.dv-group.s-unchanged      { border-left-color: var(--border); }
.dv-status {
  flex-shrink: 0; width: 15rem;
  font-size: 0.75rem; color: var(--text-muted); font-family: system-ui, sans-serif;
}
.mono { font-family: ui-monospace, "Cascadia Code", Consolas, monospace; font-size: var(--mono-size); }
.dv-sig-block { display: flex; flex-direction: column; min-width: 0; gap: 0.05rem; }
.dv-sig { color: var(--text-primary); min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.dv-input {
  color: var(--text-muted); font-size: 0.72rem;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.dv-counts { margin-left: auto; flex-shrink: 0; font-size: 0.75rem; }
.dv-exc { color: #f87171; }

.dv-detail { padding: 0.5rem 0.75rem 1rem; }
.dv-diff-head {
  display: grid; grid-template-columns: 1fr 1fr; gap: 1rem;
  align-items: baseline; margin: 0.25rem 0 0.5rem 2.8rem; position: relative;
}
.dv-diff-head h3 { margin: 0; font-size: 0.8rem; color: var(--text-secondary); }
.dv-diff-head .dv-toggle { position: absolute; right: 0; top: 0; margin-left: 0; }
.dv-payload { display: flex; gap: 0.6rem; margin: 0.25rem 0; align-items: flex-start; }
.dv-kind {
  flex-shrink: 0; width: 2.2rem; text-align: center;
  font-size: 0.7rem; font-weight: 700; color: var(--text-muted);
  border: 1px solid var(--border); border-radius: 4px; padding: 0.1rem 0;
}
/* One grid, one scroll container: the two sides are aligned row by row and
   scroll together by construction. */
.dv-diff-grid {
  flex: 1; min-width: 0;
  display: grid; grid-template-columns: 1fr 1fr;
  background: var(--bg-elevated); border: 1px solid var(--border);
  border-radius: 6px; overflow: auto; max-height: 28rem;
}
.dcell {
  padding: 0 0.75rem; white-space: pre; min-height: 1.25rem; line-height: 1.25rem;
  color: var(--text-secondary);
}
.dcell.dl { border-right: 1px solid var(--border); }
.dcell.d-changed { background: rgba(251, 191, 36, 0.13); color: var(--text-primary); }
.dcell.dl.d-left  { background: rgba(248, 113, 113, 0.13); color: var(--text-primary); }
.dcell.dr.d-right { background: rgba(52, 211, 153, 0.13); color: var(--text-primary); }
.dcell.dr.d-left, .dcell.dl.d-right { background: rgba(255, 255, 255, 0.02); }
.dv-empty { padding: 2rem 1.5rem; max-width: 46rem; }
.muted { color: var(--text-muted); }
.centered { display: flex; justify-content: center; padding: 1.5rem; }
</style>
