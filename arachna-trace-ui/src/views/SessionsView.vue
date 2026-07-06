<script setup lang="ts">
import { onMounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import Button from 'primevue/button';
import Message from 'primevue/message';
import ProgressSpinner from 'primevue/progressspinner';
import Splitter from 'primevue/splitter';
import SplitterPanel from 'primevue/splitterpanel';
import { api } from '../api/client';
import type { RequestRow, SessionRow } from '../types';

const router = useRouter();

// --- list state ---------------------------------------------------------

const sessions = ref<SessionRow[]>([]);
const loading = ref(true);
const error = ref<string | null>(null);

// --- preview (right pane) state -----------------------------------------

const selected = ref<SessionRow | null>(null);
const previewRequests = ref<RequestRow[]>([]);
const loadingPreview = ref(false);
const previewError = ref<string | null>(null);

async function loadList(): Promise<void> {
  loading.value = true;
  error.value = null;
  try {
    sessions.value = await api.listSessions();
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    loading.value = false;
  }
}
onMounted(loadList);

async function loadPreview(sessionId: string): Promise<void> {
  loadingPreview.value = true;
  previewError.value = null;
  previewRequests.value = [];
  try {
    previewRequests.value = await api.listRequests(sessionId);
  } catch (e) {
    previewError.value = (e as Error).message;
  } finally {
    loadingPreview.value = false;
  }
}

watch(selected, (row) => {
  if (row) loadPreview(row.session_id);
  else { previewRequests.value = []; previewError.value = null; }
});

function selectSession(row: SessionRow): void {
  selected.value = row;
}

function isSelected(row: SessionRow): boolean {
  return selected.value?.session_id === row.session_id;
}

function open(row: SessionRow | null): void {
  if (!row) return;
  router.push({ name: 'session-detail', params: { sessionId: row.session_id } });
}

function openNarrative(request: RequestRow): void {
  if (!selected.value) return;
  router.push({
    name: 'flow-narrative',
    params: { sessionId: selected.value.session_id, requestId: String(request.request_id) }
  });
}
</script>

<template>
  <section class="sessions-view">
    <header class="sv-head">
      <h1 class="page-title">Sessions</h1>
      <div class="sv-head-actions">
        <Button label="Behavior diff" icon="pi pi-arrows-h" text
                @click="router.push({ name: 'behavior-diff' })" />
        <Button icon="pi pi-refresh" text @click="loadList" :loading="loading" aria-label="Refresh" />
      </div>
    </header>

    <Message v-if="error" severity="error" :closable="false">{{ error }}</Message>

    <Splitter class="workspace" stateKey="arachna-trace-sessions-splitter" stateStorage="local">
      <SplitterPanel :size="45" :minSize="25" class="left-pane">
        <div v-if="loading" class="centered">
          <ProgressSpinner style="width:1.75rem;height:1.75rem" />
        </div>

        <div v-else-if="!sessions.length" class="empty">
          <p class="muted">No sessions yet — run an instrumented app to populate.</p>
        </div>

        <table v-else class="data-table sessions-table">
          <colgroup>
            <col class="c-session" />
            <col class="c-run" />
            <col class="c-ts" />
            <col class="c-retain" />
          </colgroup>
          <thead>
            <tr>
              <th>Session</th>
              <th>Agent run</th>
              <th>Last seen</th>
              <th>Retain</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="s in sessions"
                :key="s.session_id"
                :class="{ selected: isSelected(s) }"
                @click="selectSession(s)"
                @dblclick="open(s)">
              <td class="mono"><code>{{ s.session_id }}</code></td>
              <td class="mono"><code>{{ s.agent_run_id }}</code></td>
              <td class="mono muted">{{ s.last_seen }}</td>
              <td>
                <span v-if="s.retain" class="badge">retain</span>
                <span v-else class="muted">—</span>
              </td>
            </tr>
          </tbody>
        </table>
      </SplitterPanel>

      <SplitterPanel :size="55" :minSize="25" class="right-pane">
        <div v-if="!selected" class="empty">
          <p class="muted">Select a session to preview its requests.</p>
          <p class="muted small">Double-click a row or use the button below to open the full session view.</p>
        </div>

        <div v-else class="preview">
          <header class="preview-head">
            <div class="meta">
              <div><span class="label">session</span> <code>{{ selected.session_id }}</code></div>
              <div><span class="label">agent run</span> <code>{{ selected.agent_run_id }}</code></div>
              <div class="row-times">
                <span><span class="label">first</span> <code>{{ selected.first_seen }}</code></span>
                <span><span class="label">last</span> <code>{{ selected.last_seen }}</code></span>
                <span v-if="selected.retain" class="badge">retain</span>
              </div>
            </div>
            <Button label="Open session" icon="pi pi-arrow-right" @click="open(selected)" />
          </header>

          <Message v-if="previewError" severity="error" :closable="false">{{ previewError }}</Message>

          <div v-if="loadingPreview" class="centered">
            <ProgressSpinner style="width:1.75rem;height:1.75rem" />
          </div>

          <table v-else-if="previewRequests.length" class="data-table requests-table">
            <colgroup>
              <col class="c-req" />
              <col class="c-thread" />
              <col class="c-calls" />
              <col class="c-ms" />
              <col class="c-narrative" />
            </colgroup>
            <thead>
              <tr>
                <th>#</th>
                <th>Thread</th>
                <th>Calls</th>
                <th>ms</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="r in previewRequests"
                  :key="r.request_id"
                  :class="{ 'has-exception': Number(r.exception_count) > 0 }"
                  :title="Number(r.exception_count) > 0 ? `${r.exception_count} exception${Number(r.exception_count) === 1 ? '' : 's'} in this request` : undefined"
                  @dblclick="open(selected)">
                <td class="mono"><strong>#{{ r.request_id }}</strong></td>
                <td class="mono">{{ r.thread_name }}</td>
                <td class="mono num">{{ r.call_count }}</td>
                <td class="mono num">{{ r.span_ms }}</td>
                <td class="action-cell">
                  <Button icon="pi pi-book" text size="small"
                          title="Open as flow narrative (audit view)"
                          aria-label="Open flow narrative"
                          @click.stop="openNarrative(r)" />
                </td>
              </tr>
            </tbody>
          </table>

          <p v-else class="muted centered">no requests in this session</p>
        </div>
      </SplitterPanel>
    </Splitter>
  </section>
</template>

<style scoped>
.sessions-view {
  display: flex; flex-direction: column;
  height: 100%;                /* fills .app-main; outer shell already viewport-locked */
  min-height: 0;
  background: var(--bg-base);
}

.sv-head {
  display: flex; align-items: center; justify-content: space-between;
  padding: 0.5rem 1rem;
  border-bottom: 1px solid var(--border);
  background: var(--bg-surface);
  flex-shrink: 0;
}
.page-title { margin: 0; font-size: 1rem; color: var(--text-primary); }

/* Shared table style — same look as WatchItem's results, JsonTree
   monospace and dark theme tokens. Used for both the session list
   on the left pane and the request preview on the right pane. */
.data-table {
  width: 100%;
  border-collapse: collapse;
  table-layout: fixed;
  font-family: ui-monospace, "Cascadia Code", Consolas, monospace;
  font-size: var(--mono-size);
  color: var(--text-primary);
}
.data-table thead th {
  text-align: left;
  font-weight: 600;
  font-size: 0.72rem;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  color: var(--text-muted);
  padding: 0.5rem 0.6rem;
  background: var(--bg-surface);
  border-bottom: 1px solid var(--border);
  position: sticky;
  top: 0;
  z-index: 1;
  font-family: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
}
.data-table tbody tr {
  cursor: pointer;
  border-top: 1px solid rgba(255, 255, 255, 0.04);
}
.data-table tbody tr:first-child { border-top: 0; }
.data-table tbody tr:nth-child(even) { background: rgba(255, 255, 255, 0.015); }
.data-table tbody tr:hover  { background: var(--bg-hover); }
.data-table tbody tr.selected {
  background: rgba(96, 165, 250, 0.18) !important;
  outline: 1px solid var(--accent-blue);
  outline-offset: -1px;
}
/* Requests that contain at least one exception frame — same red tint
   as FrameCard's .rec-row.exception, so the signal reads consistently
   between the request list and the call tree. */
.data-table tbody tr.has-exception { background: rgba(248, 113, 113, 0.10); }
.data-table tbody tr.has-exception:hover { background: rgba(248, 113, 113, 0.18); }
.data-table tbody td {
  padding: 0.4rem 0.6rem;
  vertical-align: baseline;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.data-table td.mono code { color: inherit; font-family: inherit; }
.data-table td.num { text-align: right; color: var(--text-secondary); }

.sessions-table col.c-session { width: 35%; }
.sessions-table col.c-run     { width: 30%; }
.sessions-table col.c-ts      { width: 23%; }
.sessions-table col.c-retain  { width: 7ch; }

.requests-table col.c-req       { width: 6ch; }
.requests-table col.c-thread    { width: auto; }
.requests-table col.c-calls     { width: 7ch; }
.requests-table col.c-ms        { width: 7ch; }
.requests-table col.c-narrative { width: 4.5ch; }
.data-table td.action-cell { padding: 0; text-align: center; overflow: visible; }
.sv-head-actions { display: flex; align-items: center; gap: 0.25rem; }

/* Preview pane */
.preview { display: flex; flex-direction: column; height: 100%; min-height: 0; }
.preview-head {
  display: flex; align-items: flex-start; justify-content: space-between; gap: 1rem;
  padding: 0.75rem 1rem;
  border-bottom: 1px solid var(--border);
  background: var(--bg-surface);
  flex-shrink: 0;
}
.meta { display: flex; flex-direction: column; gap: 0.35rem; font-size: 0.85rem; }
.label { color: var(--text-muted); margin-right: 0.4rem; text-transform: uppercase; font-size: 0.7rem; letter-spacing: 0.04em; }
.meta code { color: var(--text-primary); font-family: ui-monospace, monospace; font-size: var(--mono-size); }
.row-times { display: flex; gap: 1rem; flex-wrap: wrap; }
.badge {
  background: var(--bg-elevated); color: var(--text-secondary);
  border: 1px solid var(--border-strong); padding: 0.05rem 0.5rem;
  border-radius: 4px; font-size: 0.7rem; text-transform: uppercase;
  font-family: system-ui, sans-serif;
}

.empty { padding: 2rem 1.25rem; }
.small { font-size: 0.8rem; }
.muted { color: var(--text-muted); }
.centered { display: flex; justify-content: center; padding: 1.5rem; }
</style>
