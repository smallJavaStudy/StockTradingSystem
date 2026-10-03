import axios from 'axios'

const api = axios.create({ baseURL: 'http://localhost:8080/api' })

export function getStockList() {
  return api.get('/stock/list')
}

export function getStockFull(code: string) {
  return api.get(`/stock/${code}/full`)
}

export function getQuote(code: string) {
  return api.get(`/stock/${code}/quote`)
}

export function getKline(code: string) {
  return api.get(`/stock/${code}/kline`)
}

export function getFinance(code: string) {
  return api.get(`/stock/${code}/finance`)
}

export function saveStockBasic(code: string, name: string, market: string) {
  return api.post('/stock/basic', { code, name, market })
}

// ────────── Analysis Agent API ──────────

const analysisApi = axios.create({ baseURL: 'http://localhost:8080/api/v1/analysis-agent' })

export function resolveStockName(query: string) {
  return analysisApi.post('/resolve', { query })
}

export function startAnalysis(stockCode: string, directions: string[], modelChoice: string) {
  return analysisApi.post('/analyze', { stockCode, directions, modelChoice })
}

export function getAnalysisSession(sessionId: string) {
  return analysisApi.get(`/session/${sessionId}`)
}

export function getStockAnalysisHistory(stockCode: string) {
  return analysisApi.get(`/stock/${stockCode}/history`)
}
