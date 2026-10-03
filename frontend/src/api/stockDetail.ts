import axios from 'axios'

// 个股详情页增强 API（Task #23）：股东/对标/笔记/预警/信号/龙虎榜
const api = axios.create({ baseURL: 'http://localhost:8080/api' })

// ────────── 类型 ──────────

export interface HolderTop {
  id: number
  code: string
  reportDate: string
  holderType: 'TOP10' | 'TOP10_FLOAT'
  holderRank: number
  holderName: string
  holderNature: string | null
  shares: number | null
  holdRatio: number | null
  changeDesc: string | null
  changeRatio: number | null
}

export interface HoldersResp {
  code: string
  top10: HolderTop[]
  top10Float: HolderTop[]
}

export interface HolderCount {
  id: number
  code: string
  statDate: string
  holderCount: number | null
  prevCount: number | null
  changeRatio: number | null
  avgHoldShares: number | null
  avgHoldValue: number | null
}

export interface ComparisonRow {
  name: string
  code: string | null
  reportDate: string | null
  eps: number | null
  roe: number | null
  revenue: number | null
  netProfit: number | null
  price: number | null
  marketCap: number | null
  pe: number | null
  industry?: string | null
  mainProduct?: string | null
  isListed?: boolean | null
}

export interface ComparisonResp {
  base: ComparisonRow
  competitors: ComparisonRow[]
}

export interface ComparisonComment {
  code: string
  comment: string
  fromCache: boolean
  generatedAt: string
}

export type NoteCategory = 'INVEST_LOGIC' | 'RISK_POINT' | 'BUY_CONDITION' | 'SELL_CONDITION' | 'FREE_NOTE'

export interface InvestNote {
  id: number
  code: string
  category: NoteCategory
  content: string
  createdAt: string
  updatedAt: string
}

export type AlertType = 'PRICE_ABOVE' | 'PRICE_BELOW'

export interface PriceAlert {
  id: number
  code: string
  type: AlertType
  threshold: number
  enabled: boolean
  triggered: boolean
  triggeredAt: string | null
  createdAt: string
}

export interface TechnicalSignals {
  code: string
  available: boolean
  message?: string
  tradeDate?: string
  close?: number
  ma5?: number
  ma20?: number
  prevMa5?: number
  prevMa20?: number
  goldenCross?: boolean
  deathCross?: boolean
  volume?: number
  avgVolume5?: number
  volumeSurge?: boolean
  maTrend?: 'BULLISH' | 'BEARISH' | 'FLAT'
}

export interface LhbRecord {
  id: number
  code: string
  name: string | null
  tradeDate: string
  rankReason: string | null
  buyAmount: number | null
  sellAmount: number | null
  netAmount: number | null
  totalAmount: number | null
  changePct: number | null
  closePrice: number | null
  interpretation: string | null
}

// ────────── 股东 ──────────

export function getHolders(code: string, periods = 4) {
  return api.get<HoldersResp>(`/stock/${code}/holders`, { params: { periods } })
}

export function getHolderCount(code: string) {
  return api.get<HolderCount[]>(`/stock/${code}/holder-count`)
}

// ────────── 对标 ──────────

export function getComparison(code: string) {
  return api.get<ComparisonResp>(`/stock/${code}/comparison`)
}

export function getComparisonComment(code: string) {
  return api.get<ComparisonComment>(`/stock/${code}/comparison/comment`)
}

// ────────── 笔记 ──────────

export function getNotes(code: string, category?: NoteCategory) {
  return api.get<InvestNote[]>(`/stock/${code}/notes`, { params: category ? { category } : {} })
}

export function createNote(code: string, category: NoteCategory, content: string) {
  return api.post<InvestNote>(`/stock/${code}/notes`, { category, content })
}

export function updateNote(code: string, id: number, patch: { category?: NoteCategory; content?: string }) {
  return api.put<InvestNote>(`/stock/${code}/notes/${id}`, patch)
}

export function deleteNote(code: string, id: number) {
  return api.delete(`/stock/${code}/notes/${id}`)
}

// ────────── 预警 & 信号 ──────────

export function getAlerts(code: string) {
  return api.get<PriceAlert[]>(`/stock/${code}/alerts`)
}

export function createAlert(code: string, type: AlertType, threshold: number) {
  return api.post<PriceAlert>(`/stock/${code}/alerts`, { type, threshold })
}

export function updateAlert(code: string, id: number, patch: Partial<Pick<PriceAlert, 'type' | 'threshold' | 'enabled' | 'triggered'>>) {
  return api.put<PriceAlert>(`/stock/${code}/alerts/${id}`, patch)
}

export function deleteAlert(code: string, id: number) {
  return api.delete(`/stock/${code}/alerts/${id}`)
}

export function getSignals(code: string) {
  return api.get<TechnicalSignals>(`/stock/${code}/signals`)
}

// ────────── 龙虎榜 ──────────

export function getStockLhb(code: string) {
  return api.get<LhbRecord[]>(`/stock/${code}/lhb`)
}
