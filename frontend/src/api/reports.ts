import axios from 'axios'

// ────────── 报告库 API 类型 ──────────

export interface ReportRunSummary {
  runId: string
  workflowName: string | null
  stockCode: string | null
  startTime: string | null
  endTime: string | null
  status: string
  nodeCount: number
  finalOutputPreview: string | null
}

export interface ReportNodeDetail {
  nodeId: string
  nodeName: string | null
  status: string
  output: string | null
  errorMessage: string | null
  startedAt: string | null
  completedAt: string | null
  durationMs: number | null
}

export interface ReportRunDetail {
  runId: string
  workflowName: string | null
  stockCode: string | null
  stockName: string | null
  status: string
  startTime: string | null
  endTime: string | null
  nodes: ReportNodeDetail[]
}

export interface ReportPage {
  content: ReportRunSummary[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface ReportSearchParams {
  stockCode?: string
  workflowName?: string
  startDate?: string
  endDate?: string
  page?: number
  size?: number
}

// ────────── 报告库 API ──────────

const REPORTS_BASE = 'http://localhost:8080/api/reports'

const reportsApi = axios.create({ baseURL: REPORTS_BASE })

/** 分页检索运行级报告汇总（时间倒序） */
export function searchReports(params: ReportSearchParams) {
  return reportsApi.get<ReportPage>('', { params })
}

/** 单次运行全部节点明细 */
export function getReportDetail(runId: string) {
  return reportsApi.get<ReportRunDetail>(`/${encodeURIComponent(runId)}`)
}

/** 导出下载地址（浏览器直接触发 Content-Disposition 下载） */
export function reportExportUrl(runId: string, format: 'md' | 'html'): string {
  return `${REPORTS_BASE}/${encodeURIComponent(runId)}/export?format=${format}`
}
