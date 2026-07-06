import type { RouteRecordRaw } from 'vue-router';
import { createRouter, createWebHistory } from 'vue-router';

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    redirect: '/sessions'
  },
  {
    path: '/sessions',
    name: 'sessions',
    component: () => import('../views/SessionsView.vue')
  },
  {
    path: '/sessions/:sessionId',
    name: 'session-detail',
    component: () => import('../views/SessionDetailView.vue'),
    props: true
  },
  {
    // Audit-oriented, read-only rendering of one request as a
    // chronological document — see docs/internals/ui.md § Flow narrative.
    path: '/sessions/:sessionId/requests/:requestId/narrative',
    name: 'flow-narrative',
    component: () => import('../views/FlowNarrativeView.vue'),
    props: true
  },
  {
    // Two-session behavioral comparison (hash-based) — see
    // docs/internals/ui.md § Behavior diff.
    path: '/diff',
    name: 'behavior-diff',
    component: () => import('../views/BehaviorDiffView.vue')
  },
  {
    path: '/objects/:objectId',
    name: 'object-history',
    component: () => import('../views/ObjectHistoryView.vue'),
    props: true
  }
];

export default createRouter({
  history: createWebHistory(),
  routes
});
