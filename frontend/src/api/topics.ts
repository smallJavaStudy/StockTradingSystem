import axios from 'axios'

const api = axios.create({ baseURL: 'http://localhost:8080/api/topics' })

// ────────── 类型定义（与后端 TopicService 契约一致） ──────────

/** 涨停池行业聚类桶 */
export interface IndustryBucket {
  industry: string
  count: number
  maxLimitUpDays: number
  topStocks: string[]
}

/** 题材库响应 */
export interface TopicsResponse {
  tradeDate: string | null
  industries: IndustryBucket[]
  llmAnalysis: string
  /** llm=LLM 级联分析成功 / fallback=LLM 不可用仅行业聚类 / none=无数据 */
  source: 'llm' | 'fallback' | 'none'
}

// ────────── API ──────────

export function getTopics() {
  return api.get<TopicsResponse>('')
}
