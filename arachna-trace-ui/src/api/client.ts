import type {
  BehaviorDiffResponse,
  CallRow,
  MutationsResponse,
  ObjectHistoryRow,
  ObservedSignatureRow,
  PayloadRow,
  RequestRow,
  SessionRow,
  SessionSize,
  ThreadRow,
  ValueSearchHit
} from '../types';

const BASE = '/api';

async function request<T>(path: string): Promise<T> {
  const res = await fetch(BASE + path, { headers: { Accept: 'application/json' } });
  if (!res.ok) {
    const text = await res.text();
    throw new Error(`HTTP ${res.status}: ${text || res.statusText}`);
  }
  return res.json() as Promise<T>;
}

export interface CallTreeQuery {
  thread?: string;
  requestId?: number | string | null;
}

export const api = {
  health: (): Promise<{ status: string }> =>
    request('/health'),
  listSessions: (): Promise<SessionRow[]> =>
    request('/sessions'),
  listThreads: (sessionId: string): Promise<ThreadRow[]> =>
    request(`/sessions/${encodeURIComponent(sessionId)}/threads`),
  listRequests: (sessionId: string): Promise<RequestRow[]> =>
    request(`/sessions/${encodeURIComponent(sessionId)}/requests`),
  sessionSize: (sessionId: string): Promise<SessionSize> =>
    request(`/sessions/${encodeURIComponent(sessionId)}/size`),
  callTree: (sessionId: string, q: CallTreeQuery = {}): Promise<CallRow[]> => {
    const qs = new URLSearchParams();
    if (q.thread) qs.set('thread', q.thread);
    if (q.requestId != null) qs.set('request_id', String(q.requestId));
    const suffix = qs.toString() ? `?${qs}` : '';
    return request(`/sessions/${encodeURIComponent(sessionId)}/calltree${suffix}`);
  },
  callPayloads: (callId: string): Promise<PayloadRow[]> =>
    request(`/calls/${encodeURIComponent(callId)}/payloads`),
  // Returns (call_id, request_id) pairs in this session whose payloads
  // contain the given object_id. Server-side bloom-filter probe; the
  // request_id is included so the lazy call-tree loader knows which
  // request to fetch when the user navigates to an appearance.
  objectTrace: (sessionId: string, objectId: number): Promise<{ call_id: string; request_id: number | string }[]> =>
    request(`/sessions/${encodeURIComponent(sessionId)}/object-trace?object_id=${encodeURIComponent(String(objectId))}`),
  // Ordered list of every call in the session that ended with an
  // exception. Replaces the client-side iteration over the full
  // session calls list — once calls are loaded lazily per request,
  // exception nav can't walk a global array.
  exceptionCalls: (sessionId: string): Promise<{
    call_id: string;
    request_id: number | string;
    signature: string;
    ts_in: string;
    thread_name: string;
  }[]> =>
    request(`/sessions/${encodeURIComponent(sessionId)}/exception-calls`),
  // Same bloom-filter scope but returns the actual payload data, so
  // session-wide tools (Watch appearance rows, Origin next-mutation)
  // can walk the envelope snapshots without forcing every session
  // payload into the client cache.
  objectPayloads: (sessionId: string, objectId: number): Promise<PayloadRow[]> =>
    request(`/sessions/${encodeURIComponent(sessionId)}/object-payloads?object_id=${encodeURIComponent(String(objectId))}`),
  requestPayloads: (sessionId: string, requestId: number | string): Promise<PayloadRow[]> =>
    request(`/sessions/${encodeURIComponent(sessionId)}/requests/${encodeURIComponent(String(requestId))}/payloads`),
  objectHistory: (objectId: string | number): Promise<ObjectHistoryRow[]> =>
    request(`/objects/${encodeURIComponent(String(objectId))}/history`),
  analysisMutations: (sessionId: string, requestId: number | string | null = null): Promise<MutationsResponse> => {
    const qs = new URLSearchParams();
    qs.set('session_id', sessionId);
    if (requestId != null) qs.set('request_id', String(requestId));
    return request(`/analysis/mutations?${qs}`);
  },
  // Behavioral diff between two sessions — group calls by
  // (signature, AR root_hash) on each side and compare output-hash
  // sets. Hash comparison only; payloads load lazily when the user
  // drills into a group's example calls.
  behaviorDiff: (sessionA: string, sessionB: string): Promise<BehaviorDiffResponse> => {
    const qs = new URLSearchParams();
    qs.set('session_a', sessionA);
    qs.set('session_b', sessionB);
    return request(`/analysis/behavior-diff?${qs}`);
  },
  // Distinct signatures observed in a session — the "what actually ran"
  // half of the liveness sweep (diffed against the agent's
  // instrumentation_inventory file).
  observedSignatures: (sessionId: string): Promise<ObservedSignatureRow[]> => {
    const qs = new URLSearchParams();
    qs.set('session_id', sessionId);
    return request(`/analysis/observed-signatures?${qs}`);
  },
  valueSearch: (
    sessionId: string,
    requestId: number | string | null,
    value: string,
    mode: 'exact' | 'substring' = 'exact'
  ): Promise<ValueSearchHit[]> => {
    const qs = new URLSearchParams();
    qs.set('session_id', sessionId);
    if (requestId != null) qs.set('request_id', String(requestId));
    qs.set('value', value);
    if (mode === 'substring') qs.set('mode', 'substring');
    return request(`/analysis/value-search?${qs}`);
  }
};
