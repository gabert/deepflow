<script setup lang="ts">
// Flow narrative — the audit-oriented, document-like rendering of one
// request. Where SessionDetailView is a debugger (hunt a bug by
// drilling), this view is a *readable artifact*: the request as a
// chronological story — every traced call in execution order with its
// named arguments, return value, and factual badges (exception,
// argument mutation, thread hop). Built for reviewing what code —
// typically AI-generated code — actually did with real data; the
// "copy as Markdown" button exists so the narrative can be pasted
// into a PR review.
//
// The badges are facts from the trace (hash inequality, exception
// records, thread ids), not judgments — consistent with the product
// philosophy in docs/internals/ui.md.
import { computed, onMounted, ref } from 'vue';
import Button from 'primevue/button';
import Message from 'primevue/message';
import ProgressSpinner from 'primevue/progressspinner';
import { api } from '../api/client';
import type { CallRow, PayloadKind, PayloadRow } from '../types';

const props = defineProps<{ sessionId: string; requestId: string }>();

const loading = ref(true);
const error = ref<string | null>(null);
const calls = ref<CallRow[]>([]);
const payloadsByCall = ref<Map<string, Partial<Record<PayloadKind, PayloadRow>>>>(new Map());
const expanded = ref<Set<string>>(new Set());
const copied = ref(false);

interface NarrativeRow {
  call: CallRow;
  depth: number;
  threadHop: boolean;
  mutated: boolean;
  argsPreview: string;
  returnPreview: string;
}

onMounted(load);

async function load(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    const [callRows, payloadRows] = await Promise.all([
      api.callTree(props.sessionId, { requestId: props.requestId }),
      api.requestPayloads(props.sessionId, props.requestId)
    ]);
    calls.value = [...callRows].sort((a, b) => Number(a.seq) - Number(b.seq));
    const map = new Map<string, Partial<Record<PayloadKind, PayloadRow>>>();
    for (const p of payloadRows) {
      const entry = map.get(p.call_id) ?? {};
      entry[p.kind] = p;
      map.set(p.call_id, entry);
    }
    payloadsByCall.value = map;
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    loading.value = false;
  }
}

const rows = computed<NarrativeRow[]>(() => {
  const byId = new Map(calls.value.map((c) => [c.call_id, c]));
  return calls.value.map((call) => {
    const parent = call.parent_call_id ? byId.get(call.parent_call_id) : undefined;
    const p = payloadsByCall.value.get(call.call_id) ?? {};
    const mutated = !!(p.AR && p.AX && p.AR.root_hash !== p.AX.root_hash);
    return {
      call,
      depth: depthOf(call, byId),
      threadHop: parent != null && parent.thread_name !== call.thread_name,
      mutated,
      argsPreview: argsPreview(p.AR),
      returnPreview: returnPreview(call, p.RE)
    };
  });
});

function depthOf(call: CallRow, byId: Map<string, CallRow>): number {
  let depth = 0;
  let cursor: CallRow | undefined = call;
  while (cursor?.parent_call_id && byId.has(cursor.parent_call_id) && depth < 64) {
    cursor = byId.get(cursor.parent_call_id);
    depth++;
  }
  return depth;
}

// --- previews (one-line summaries; full JSON on expand) -----------------

function tryParse(row?: PayloadRow): unknown {
  if (!row) return undefined;
  try {
    return JSON.parse(row.payload_json);
  } catch {
    return undefined;
  }
}

function previewValue(node: unknown): string {
  if (node === null || node === undefined) return 'null';
  if (typeof node !== 'object') {
    const s = JSON.stringify(node);
    return s.length > 40 ? s.slice(0, 37) + '…' : s;
  }
  if (Array.isArray(node)) return `[${node.length}]`;
  const obj = node as Record<string, unknown>;
  const meta = obj.__meta__ as Record<string, unknown> | undefined;
  if (obj.cycle_ref) return `↺#${obj.ref_id}`;
  if (meta) {
    const cls = String(meta.class ?? 'object');
    const short = cls.substring(cls.lastIndexOf('.') + 1);
    const items = obj.items;
    return Array.isArray(items) ? `${short}[${items.length}]#${meta.id}` : `${short}#${meta.id}`;
  }
  return '{…}';
}

function argsPreview(ar?: PayloadRow): string {
  const parsed = tryParse(ar) as Record<string, unknown> | undefined;
  if (!parsed || typeof parsed !== 'object') return '';
  const parts: string[] = [];
  for (const [key, value] of Object.entries(parsed)) {
    if (key === '__meta__') continue;
    parts.push(`${key}=${previewValue(value)}`);
  }
  return parts.join(', ');
}

function returnPreview(call: CallRow, re?: PayloadRow): string {
  if (call.return_type === 'VOID') return 'void';
  if (call.return_type === 'EXCEPTION') {
    // The exception payload is {message, stacktrace} — the message is
    // the readable part; the full stacktrace is in the expanded view.
    const parsed = tryParse(re);
    const message = parsed && typeof parsed === 'object' && 'message' in (parsed as object)
        ? String((parsed as Record<string, unknown>).message)
        : previewValue(parsed);
    return `threw ${message.length > 80 ? message.slice(0, 77) + '…' : message}`;
  }
  return re ? previewValue(tryParse(re)) : '';
}

function shortSignature(signature: string): string {
  // "pkg::Class.method(args) -> ret [mods]" → "Class.method(args) -> ret"
  const noMods = signature.replace(/\s*\[[^\]]*\]\s*$/, '');
  return noMods.replace(/^[^(]*::/, '');
}

function prettyJson(row?: PayloadRow): string {
  const parsed = tryParse(row);
  return parsed === undefined ? '' : JSON.stringify(parsed, null, 2);
}

function toggle(callId: string): void {
  const next = new Set(expanded.value);
  if (next.has(callId)) next.delete(callId);
  else next.add(callId);
  expanded.value = next;
}

// --- export ---------------------------------------------------------------

async function copyMarkdown(): Promise<void> {
  const lines: string[] = [
    `# Flow narrative — session \`${props.sessionId}\`, request #${props.requestId}`,
    ''
  ];
  for (const row of rows.value) {
    const indent = '  '.repeat(row.depth);
    const badges = [
      row.call.is_exception ? '⚠ exception' : '',
      row.mutated ? '± mutates args' : '',
      row.threadHop ? `⇄ thread ${row.call.thread_name}` : ''
    ].filter(Boolean).join(' ');
    lines.push(`${indent}- \`${shortSignature(row.call.signature)}\`` +
        (row.argsPreview ? ` — ${row.argsPreview}` : '') +
        ` → ${row.returnPreview}` +
        (badges ? `  **${badges}**` : ''));
  }
  await navigator.clipboard.writeText(lines.join('\n'));
  copied.value = true;
  setTimeout(() => (copied.value = false), 1500);
}
</script>

<template>
  <section class="narrative-view">
    <header class="nv-head">
      <div class="nv-title">
        <h1 class="page-title">Flow narrative</h1>
        <span class="nv-scope mono">
          session <code>{{ sessionId }}</code> · request <strong>#{{ requestId }}</strong>
        </span>
      </div>
      <div class="nv-actions">
        <Button
          :label="copied ? 'Copied' : 'Copy as Markdown'"
          :icon="copied ? 'pi pi-check' : 'pi pi-copy'"
          text
          @click="copyMarkdown" />
        <Button
          label="Open in debugger"
          icon="pi pi-arrow-up-right"
          text
          @click="$router.push({ name: 'session-detail', params: { sessionId } })" />
      </div>
    </header>

    <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>

    <div v-if="loading" class="centered">
      <ProgressSpinner style="width:1.75rem;height:1.75rem" />
    </div>

    <div v-else class="nv-body">
      <p v-if="!rows.length" class="muted centered">no calls in this request</p>

      <div v-for="row in rows" :key="row.call.call_id" class="nv-row-wrap">
        <div
          class="nv-row"
          :class="{ exception: row.call.is_exception, expanded: expanded.has(row.call.call_id) }"
          :style="{ paddingLeft: `${0.75 + row.depth * 1.25}rem` }"
          @click="toggle(row.call.call_id)">
          <div class="nv-line">
            <span class="nv-sig mono">{{ shortSignature(row.call.signature) }}</span>
            <span v-if="row.argsPreview" class="nv-args mono">({{ row.argsPreview }})</span>
            <span class="nv-ret mono">→ {{ row.returnPreview }}</span>
          </div>
          <div class="nv-meta">
            <span v-if="row.call.is_exception" class="nv-badge b-exception" title="This call exited with an exception">⚠ exception</span>
            <span v-if="row.mutated" class="nv-badge b-mutation" title="Argument content hash differs between entry (AR) and exit (AX) — the call mutated its arguments">± mutates args</span>
            <span v-if="row.threadHop" class="nv-badge b-thread" :title="`Continues on thread ${row.call.thread_name}`">⇄ {{ row.call.thread_name }}</span>
            <span class="nv-ms mono muted">{{ row.call.duration_ms }} ms</span>
          </div>
        </div>

        <div v-if="expanded.has(row.call.call_id)" class="nv-detail" :style="{ marginLeft: `${0.75 + row.depth * 1.25}rem` }">
          <template v-for="kind in (['TI', 'AR', 'AX', 'RE'] as const)" :key="kind">
            <div v-if="payloadsByCall.get(row.call.call_id)?.[kind]" class="nv-payload">
              <span class="nv-kind">{{ kind }}</span>
              <pre class="mono">{{ prettyJson(payloadsByCall.get(row.call.call_id)?.[kind]) }}</pre>
            </div>
          </template>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.narrative-view {
  display: flex; flex-direction: column;
  height: 100%; min-height: 0;
  background: var(--bg-base);
}
.nv-head {
  display: flex; align-items: center; justify-content: space-between;
  padding: 0.5rem 1rem;
  border-bottom: 1px solid var(--border);
  background: var(--bg-surface);
  flex-shrink: 0;
}
.nv-title { display: flex; align-items: baseline; gap: 1rem; }
.page-title { margin: 0; font-size: 1rem; color: var(--text-primary); }
.nv-scope { color: var(--text-muted); font-size: 0.8rem; }
.nv-scope code { color: var(--text-secondary); }
.nv-actions { display: flex; gap: 0.25rem; }

.nv-body { overflow-y: auto; padding: 0.75rem 1rem 2rem; }

.nv-row {
  display: flex; align-items: baseline; justify-content: space-between; gap: 1rem;
  padding: 0.3rem 0.75rem;
  border-left: 2px solid transparent;
  cursor: pointer;
  border-radius: 4px;
}
.nv-row:hover { background: var(--bg-hover); }
.nv-row.exception { background: rgba(248, 113, 113, 0.10); border-left-color: rgba(248, 113, 113, 0.6); }
.nv-row.expanded { background: var(--bg-elevated); }

.nv-line { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.mono { font-family: ui-monospace, "Cascadia Code", Consolas, monospace; font-size: var(--mono-size); }
.nv-sig { color: var(--text-primary); font-weight: 600; }
.nv-args { color: var(--text-secondary); margin-left: 0.35rem; }
.nv-ret { color: var(--accent-blue, #60a5fa); margin-left: 0.5rem; }

.nv-meta { display: flex; align-items: baseline; gap: 0.5rem; flex-shrink: 0; }
.nv-badge {
  font-size: 0.7rem; padding: 0.05rem 0.45rem; border-radius: 4px;
  border: 1px solid var(--border-strong);
  font-family: system-ui, sans-serif;
}
.b-exception { color: #f87171; border-color: rgba(248, 113, 113, 0.5); }
.b-mutation  { color: #fbbf24; border-color: rgba(251, 191, 36, 0.5); }
.b-thread    { color: #60a5fa; border-color: rgba(96, 165, 250, 0.5); }
.nv-ms { font-size: 0.75rem; }

.nv-detail { padding: 0.25rem 0.75rem 0.5rem; }
.nv-payload { display: flex; gap: 0.6rem; margin: 0.25rem 0; align-items: flex-start; }
.nv-kind {
  flex-shrink: 0; width: 2.2rem; text-align: center;
  font-size: 0.7rem; font-weight: 700; color: var(--text-muted);
  border: 1px solid var(--border); border-radius: 4px; padding: 0.1rem 0;
}
.nv-payload pre {
  margin: 0; padding: 0.5rem 0.75rem;
  background: var(--bg-elevated); border: 1px solid var(--border);
  border-radius: 6px; overflow-x: auto; max-height: 20rem;
  color: var(--text-secondary); flex: 1; min-width: 0;
}
.muted { color: var(--text-muted); }
.centered { display: flex; justify-content: center; padding: 1.5rem; }
</style>
