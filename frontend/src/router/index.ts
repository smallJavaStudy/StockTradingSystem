import { createRouter, createWebHistory } from 'vue-router'

// ReportsView.vue 由另一位同事并行创建中：用 import.meta.glob 接线，
// 文件落地后自动生效；缺失期间不阻断整个路由模块的加载（Vite 静态 import 会 500）
const reportsModules = import.meta.glob('../views/ReportsView.vue')
const ReportsView = reportsModules['../views/ReportsView.vue']
  ?? (() => Promise.reject(new Error('ReportsView.vue 尚未创建')))

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'Analysis', component: () => import('../views/AnalysisView.vue') },
    { path: '/stock/:code', name: 'StockDetail', component: () => import('../views/StockDetailView.vue') },
    // Market center & watchlist & reports
    { path: '/market', name: 'Market', component: () => import('../views/MarketView.vue') },
    { path: '/watchlist', name: 'Watchlist', component: () => import('../views/WatchlistView.vue') },
    { path: '/reports', name: 'Reports', component: ReportsView },
    // Topics & daily review center
    { path: '/topics', name: 'Topics', component: () => import('../views/TopicsView.vue') },
    { path: '/review', name: 'Review', component: () => import('../views/ReviewView.vue') },
    // Smart chat (Agent 项目 ChatService)
    { path: '/chat', name: 'Chat', component: () => import('../views/ChatView.vue') },
    // Redirect old routes
    { path: '/analysis', redirect: '/' },
    // Workflow management
    { path: '/workflow', name: 'WorkflowList', component: () => import('../views/WorkflowListView.vue') },
    { path: '/workflow/:id/edit', name: 'WorkflowEditor', component: () => import('../views/WorkflowEditorView.vue') },
    { path: '/workflow/execution/:processInstanceId', name: 'WorkflowExecution', component: () => import('../views/WorkflowExecutionView.vue') },
  ],
})

export default router
