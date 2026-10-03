import axios from 'axios'

const api = axios.create({ baseURL: 'http://localhost:8080/api/market' })

// ────────── 类型定义（与后端 API 契约一致，勿改动） ──────────

/** 涨停池个股 */
export interface ZtPoolItem {
  code: string
  name: string
  tradeDate: string
  closePrice: number
  changePct: number
  limitUpDays: number
  firstTime: string
  lastTime: string
  openTimes: number
  amount: number
  turnoverRate: number
  industry: string
  reason: string
}

/** 龙虎榜条目 */
export interface LhbItem {
  code: string
  name: string
  tradeDate: string
  rankReason: string
  buyAmount: number
  sellAmount: number
  netAmount: number
  totalAmount: number
  changePct: number
}

/** 北向资金流 */
export interface NorthFlowItem {
  tradeDate: string
  netFlow: number
  accumFlow: number
}

/** 两融余额 */
export interface MarginItem {
  tradeDate: string
  market: string
  financingBalance: number
  securitiesBalance: number
  totalBalance: number
}

/** 大宗交易 */
export interface BlockTradeItem {
  code: string
  name: string
  tradeDate: string
  price: number
  volume: number
  amount: number
  premiumRate: number
  buyerBranch: string
  sellerBranch: string
}

export type MarketDateType = 'zt' | 'lhb' | 'block'

// ────────── 情绪周期仪（/api/market/sentiment） ──────────

/** 单日情绪指标明细 */
export interface SentimentDaily {
  tradeDate: string
  ztCount: number
  maxLimitUpDays: number
  brokenCount: number
  brokenRate: number
  sealRate: number
  marginTotal: number | null
  marginChangePct: number | null
  score: number
}

/** 情绪快照 */
export interface SentimentSnapshot {
  latestDate: string | null
  latestScore: number
  phaseHint: string
  note: string
  dailyIndicators: SentimentDaily[]
}

/** LLM 周期定性结果 */
export interface SentimentInterpret {
  phase: string
  analysis: string
  source: 'llm' | 'rule'
  score?: number
  tradeDate?: string
}

// ────────── API ──────────

export function getZtPool(date?: string) {
  return api.get<ZtPoolItem[]>('/zt-pool', { params: date ? { date } : {} })
}

export function getLhb(date?: string) {
  return api.get<LhbItem[]>('/lhb', { params: date ? { date } : {} })
}

export function getNorthFlow(days = 30) {
  return api.get<NorthFlowItem[]>('/north-flow', { params: { days } })
}

export function getMargin(days = 30) {
  return api.get<MarginItem[]>('/margin', { params: { days } })
}

export function getBlockTrade(date?: string) {
  return api.get<BlockTradeItem[]>('/block-trade', { params: date ? { date } : {} })
}

export function getMarketDates(type: MarketDateType) {
  return api.get<string[]>('/dates', { params: { type } })
}

export function getSentiment(days = 10) {
  return api.get<SentimentSnapshot>('/sentiment', { params: { days } })
}

export function getSentimentInterpret() {
  return api.get<SentimentInterpret>('/sentiment/interpret')
}
