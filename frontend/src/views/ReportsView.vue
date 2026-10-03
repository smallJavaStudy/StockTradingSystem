<template>
  <div class="reports-view">
    <h1>报告库</h1>

    <!-- ══════════ 筛选栏 ══════════ -->
    <div class="reports-filter">
      <div class="filter-item">
        <label>股票代码</label>
        <input v-model.trim="filters.stockCode" placeholder="如 600519" @keyup.enter="onSearch" />
      </div>
      <div class="filter-item">
        <label>工作流名</label>
        <input v-model.trim="filters.workflowName" placeholder="名称模糊匹配" @keyup.enter="onSearch" />
      </div>
      <div class="filter-item">
        <label>开始日期</label>
        <input v-model="filters.startDate" type="date" />
      </div>
      <div class="filter-item">
        <label>结束日期</label>
        <input v-model="filters.endDate" type="date" />
      </div>
      <div class="filter-actions">
        <button class="btn-primary" :disabled="loading" @click="onSearch">查询</button>
        <button class="btn-plain" :disabled="loading" @click="onReset">重置</button>
      </div>
    </div>

    <!-- ══════════ 列表 ══════════ -->
    <div v-if="loading" class="empty">加载中...</div>
    <div v-else-if="error" class="empty reports-error">{{ error }}</div>
    <div v-else-if="!rows.length" class="empty">暂无运行报告</div>
    <table v-else class="reports-table">
      <thead>
        <tr>
          <th>工作流</th><th>股票</th><th>开始时间</th><th>结束时间</th>
          <th>状态</th><th>节点</th><th>报告摘要</th><th>操作</th>
        </tr>
      </thead>
      <tbody>
        <template v-for="row in rows" :key="row.runId">
          <tr class="report-row" :class="{ expanded: expandedRunId === row.runId }">
            <td>
              <strong>{{ row.workflowName || '（未知工作流）' }}</strong>
              <div class="run-id">{{ row.runId }}</div>
            </td>
            <td>{{ row.stockCode || '-' }}</td>
            <td class="time-cell">{{ formatTime(row.startTime) }}</td>
            <td class="time-cell">{{ formatTime(row.endTime) }}</td>
            <td>
              <span class="badge" :class="'badge-' + row.status.toLowerCase()">
                {{ statusLabel(row.status) }}
              </span>
            </td>
            <td>{{ row.nodeCount }}</td>
            <td class="preview-cell" :title="row.finalOutputPreview || ''">
              {{ previewText(row.finalOutputPreview) }}
            </td>
            <td class="row-actions">
              <a @click="toggleDetail(row.runId)">{{ expandedRunId === row.runId ? '收起' : '详情' }}</a>
              <a @click="onExport(row.runId, 'md')">导出MD</a>
              <a @click="onExport(row.runId, 'html')">导出HTML</a>
            </td>
          </tr>

          <!-- ══════════ 展开的运行详情 ══════════ -->
          <tr v-if="expandedRunId === row.runId" class="detail-row">
            <td colspan="8">
              <div v-if="detailLoading" class="empty">节点明细加载中...</div>
              <div v-else-if="detailError" class="empty reports-error">{{ detailError }}</div>
              <div v-else-if="detail" class="run-detail">
                <div class="run-detail-meta">
                  <span v-if="detail.stockName">{{ detail.stockName }}<template v-if="detail.stockCode">({{ detail.stockCode }})</template></span>
                  <span>共 {{ detail.nodes.length }} 个节点</span>
                  <span v-if="detail.startTime">开始 {{ formatTime(detail.startTime) }}</span>
                </div>
                <div v-for="(node, i) in detail.nodes" :key="node.nodeId" class="node-card"
                     :class="'node-' + node.status.toLowerCase()">
                  <div class="node-header" @click="toggleNode(node.nodeId)">
                    <span class="node-index">{{ i + 1 }}</span>
                    <span class="node-name">{{ node.nodeName || node.nodeId }}</span>
                    <span class="badge" :class="'badge-' + node.status.toLowerCase()">
                      {{ statusLabel(node.status) }}
                    </span>
                    <span class="node-duration">{{ formatDuration(node.durationMs) }}</span>
                    <span class="node-toggle">{{ collapsedNodes.has(node.nodeId) ? '▶ 展开' : '▼ 折叠' }}</span>
                  </div>
                  <div v-if="!collapsedNodes.has(node.nodeId)" class="node-body">
                    <p v-if="node.errorMessage" class="node-error">{{ node.errorMessage }}</p>
                    <div v-if="node.output" class="md-body" v-html="renderMarkdown(node.output)"></div>
                    <p v-else-if="!node.errorMessage" class="node-empty">（无输出）</p>
                  </div>
                </div>
              </div>
            </td>
          </tr>
        </template>
      </tbody>
    </table>

    <!-- ══════════ 分页 ══════════ -->
    <div v-if="totalPages > 1" class="reports-pager">
      <button class="btn-plain" :disabled="page <= 0 || loading" @click="goPage(page - 1)">上一页</button>
      <span class="pager-info">第 {{ page + 1 }} / {{ totalPages }} 页 · 共 {{ totalElements }} 条</span>
      <button class="btn-plain" :disabled="page >= totalPages - 1 || loading" @click="goPage(page + 1)">下一页</button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import {
  searchReports,
  getReportDetail,
  reportExportUrl,
  type ReportRunSummary,
  type ReportRunDetail
} from '../api/reports'

const PAGE_SIZE = 20

// ────────── 列表状态 ──────────

const filters = reactive({ stockCode: '', workflowName: '', startDate: '', endDate: '' })
const rows = ref<ReportRunSummary[]>([])
const page = ref(0)
const totalPages = ref(0)
const totalElements = ref(0)
const loading = ref(false)
const error = ref('')

// ────────── 详情状态 ──────────

const expandedRunId = ref<string | null>(null)
const detail = ref<ReportRunDetail | null>(null)
const detailLoading = ref(false)
const detailError = ref('')
const collapsedNodes = ref<Set<string>>(new Set())

async function load() {
  loading.value = true
  error.value = ''
  expandedRunId.value = null
  try {
    const { data } = await searchReports({
      stockCode: filters.stockCode || undefined,
      workflowName: filters.workflowName || undefined,
      startDate: filters.startDate || undefined,
      endDate: filters.endDate || undefined,
      page: page.value,
      size: PAGE_SIZE
    })
    rows.value = data.content
    totalPages.value = data.totalPages
    totalElements.value = data.totalElements
  } catch (e) {
    error.value = '报告列表加载失败，请确认后端服务已启动'
    console.error('Failed to load reports:', e)
  } finally {
    loading.value = false
  }
}

function onSearch() {
  page.value = 0
  load()
}

function onReset() {
  filters.stockCode = ''
  filters.workflowName = ''
  filters.startDate = ''
  filters.endDate = ''
  onSearch()
}

function goPage(p: number) {
  page.value = p
  load()
}

async function toggleDetail(runId: string) {
  if (expandedRunId.value === runId) {
    expandedRunId.value = null
    return
  }
  expandedRunId.value = runId
  detail.value = null
  detailError.value = ''
  detailLoading.value = true
  collapsedNodes.value = new Set()
  try {
    const { data } = await getReportDetail(runId)
    detail.value = data
  } catch (e) {
    detailError.value = '节点明细加载失败'
    console.error('Failed to load report detail:', e)
  } finally {
    detailLoading.value = false
  }
}

function toggleNode(nodeId: string) {
  const set = new Set(collapsedNodes.value)
  if (set.has(nodeId)) set.delete(nodeId)
  else set.add(nodeId)
  collapsedNodes.value = set
}

function onExport(runId: string, format: 'md' | 'html') {
  // 后端 Content-Disposition: attachment，直接以隐藏锚点触发浏览器下载
  const a = document.createElement('a')
  a.href = reportExportUrl(runId, format)
  a.rel = 'noopener'
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
}

// ────────── 展示辅助 ──────────

const statusLabels: Record<string, string> = {
  RUNNING: '运行中', COMPLETED: '已完成', FAILED: '失败', CANCELLED: '已取消'
}
function statusLabel(s: string) { return statusLabels[s] || s }

function formatTime(t: string | null): string {
  if (!t) return '-'
  const d = new Date(t)
  if (Number.isNaN(d.getTime())) return t
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function formatDuration(ms: number | null): string {
  if (ms === null || ms < 0) return ''
  const sec = Math.round(ms / 1000)
  return sec >= 60 ? `${Math.floor(sec / 60)}分${sec % 60}秒` : `${sec}秒`
}

function previewText(preview: string | null): string {
  if (!preview) return '-'
  const flat = preview.replace(/\s+/g, ' ').trim()
  return flat.length > 80 ? flat.substring(0, 80) + '...' : flat
}

// ────────── 轻量 Markdown 渲染（先转义再拼 HTML，防注入；无第三方依赖） ──────────

function escapeHtml(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
}

function inlineMd(s: string): string {
  return escapeHtml(s)
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
    .replace(/(^|[^*])\*([^*\s][^*]*)\*/g, '$1<em>$2</em>')
}

function renderMarkdown(md: string): string {
  const lines = md.split(/\r?\n/)
  const html: string[] = []
  let inCode = false
  let listType: 'ul' | 'ol' | null = null
  let inTable = false

  const closeList = () => { if (listType) { html.push(`</${listType}>`); listType = null } }
  const closeTable = () => { if (inTable) { html.push('</table>'); inTable = false } }

  for (const raw of lines) {
    if (raw.trimStart().startsWith('```')) {
      closeList(); closeTable()
      html.push(inCode ? '</code></pre>' : '<pre><code>')
      inCode = !inCode
      continue
    }
    if (inCode) { html.push(escapeHtml(raw)); continue }

    const t = raw.trim()
    if (!t) { closeList(); closeTable(); continue }

    const heading = /^(#{1,6})\s+(.*)$/.exec(t)
    if (heading) {
      closeList(); closeTable()
      const level = Math.min(heading[1].length + 2, 6) // 节点内标题降级，避免与页面标题冲突
      html.push(`<h${level}>${inlineMd(heading[2])}</h${level}>`)
      continue
    }
    if (/^([-*_]){3,}$/.test(t.replace(/\s/g, ''))) { closeList(); closeTable(); html.push('<hr>'); continue }

    if (t.startsWith('|')) {
      closeList()
      if (/^\|[\s:|-]+\|?$/.test(t)) continue // 表头分隔行
      const cells = t.replace(/^\|/, '').replace(/\|$/, '').split('|').map(c => inlineMd(c.trim()))
      if (!inTable) {
        html.push('<table><tr>' + cells.map(c => `<th>${c}</th>`).join('') + '</tr>')
        inTable = true
      } else {
        html.push('<tr>' + cells.map(c => `<td>${c}</td>`).join('') + '</tr>')
      }
      continue
    }
    closeTable()

    const ul = /^[-*+]\s+(.*)$/.exec(t)
    if (ul) {
      if (listType !== 'ul') { closeList(); html.push('<ul>'); listType = 'ul' }
      html.push(`<li>${inlineMd(ul[1])}</li>`)
      continue
    }
    const ol = /^\d+[.)]\s+(.*)$/.exec(t)
    if (ol) {
      if (listType !== 'ol') { closeList(); html.push('<ol>'); listType = 'ol' }
      html.push(`<li>${inlineMd(ol[1])}</li>`)
      continue
    }
    if (t.startsWith('>')) {
      closeList()
      html.push(`<blockquote>${inlineMd(t.replace(/^>\s?/, ''))}</blockquote>`)
      continue
    }
    closeList()
    html.push(`<p>${inlineMd(t)}</p>`)
  }
  closeList(); closeTable()
  if (inCode) html.push('</code></pre>')
  return html.join('\n')
}

onMounted(load)
</script>

<style scoped>
.reports-view { padding: 24px; }

/* ── 筛选栏 ── */
.reports-filter {
  display: flex; flex-wrap: wrap; align-items: flex-end; gap: 16px;
  background: #fff; border: 1px solid #eee; border-radius: 8px;
  padding: 16px; margin-bottom: 16px;
}
.filter-item { display: flex; flex-direction: column; gap: 4px; }
.filter-item label { font-size: 12px; color: #666; }
.filter-item input {
  padding: 6px 10px; border: 1px solid #ddd; border-radius: 6px;
  font-size: 14px; min-width: 150px;
}
.filter-item input:focus { border-color: #1890ff; outline: none; }
.filter-actions { display: flex; gap: 8px; }

/* ── 表格 ── */
.reports-table { width: 100%; border-collapse: collapse; background: #fff; }
.reports-table th, .reports-table td {
  padding: 10px 12px; border-bottom: 1px solid #f0f0f0; text-align: left;
  font-size: 14px; vertical-align: top;
}
.reports-table th { background: #fafafa; color: #666; font-weight: 600; }
.report-row:hover { background: #fafcff; }
.report-row.expanded { background: #f0f7ff; }
.run-id { font-size: 11px; color: #bbb; margin-top: 2px; word-break: break-all; }
.time-cell { white-space: nowrap; color: #666; }
.preview-cell { color: #888; max-width: 320px; }
.row-actions a { color: #1890ff; cursor: pointer; margin-right: 10px; white-space: nowrap; }
.row-actions a:hover { text-decoration: underline; }
.reports-error { color: #ff4d4f; }

/* ── 运行详情 ── */
.detail-row > td { background: #f9fbfd; padding: 16px 20px; }
.run-detail-meta { display: flex; gap: 16px; color: #666; font-size: 13px; margin-bottom: 12px; }
.node-card { background: #fff; border: 1px solid #eee; border-radius: 8px; margin-bottom: 10px; overflow: hidden; }
.node-card.node-failed { border-color: #ffccc7; }
.node-header {
  display: flex; align-items: center; gap: 10px;
  padding: 10px 14px; cursor: pointer; background: #fafafa;
}
.node-index {
  width: 22px; height: 22px; border-radius: 50%; background: #1890ff; color: #fff;
  font-size: 12px; display: inline-flex; align-items: center; justify-content: center;
  flex-shrink: 0;
}
.node-name { font-weight: 600; }
.node-duration { color: #999; font-size: 12px; }
.node-toggle { margin-left: auto; color: #1890ff; font-size: 12px; }
.node-body { padding: 12px 16px; }
.node-error { color: #ff4d4f; background: #fff1f0; padding: 8px 12px; border-radius: 6px; }
.node-empty { color: #bbb; }

/* ── Markdown 渲染 ── */
.md-body { line-height: 1.7; color: #333; font-size: 14px; overflow-x: auto; }
.md-body :deep(h3), .md-body :deep(h4), .md-body :deep(h5), .md-body :deep(h6) {
  margin: 14px 0 6px; color: #222;
}
.md-body :deep(p) { margin: 6px 0; }
.md-body :deep(ul), .md-body :deep(ol) { margin: 6px 0; padding-left: 22px; }
.md-body :deep(blockquote) {
  margin: 8px 0; padding: 4px 12px; background: #f6f8fa;
  border-left: 4px solid #1890ff; color: #555;
}
.md-body :deep(pre) {
  background: #f6f8fa; padding: 10px 12px; border-radius: 6px; overflow-x: auto;
}
.md-body :deep(code) { background: #f0f0f0; padding: 1px 4px; border-radius: 3px; font-size: 90%; }
.md-body :deep(pre code) { background: none; padding: 0; }
.md-body :deep(table) { border-collapse: collapse; margin: 8px 0; }
.md-body :deep(th), .md-body :deep(td) { border: 1px solid #ddd; padding: 4px 10px; }
.md-body :deep(th) { background: #fafafa; }
.md-body :deep(hr) { border: none; border-top: 1px solid #eee; margin: 12px 0; }

/* ── 分页 ── */
.reports-pager {
  display: flex; align-items: center; justify-content: center; gap: 16px;
  margin-top: 16px;
}
.pager-info { color: #666; font-size: 13px; }
</style>
