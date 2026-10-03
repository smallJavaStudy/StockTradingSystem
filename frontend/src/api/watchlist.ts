import axios from 'axios'

const api = axios.create({ baseURL: 'http://localhost:8080/api' })

/** 自选股条目（含后端内联的行情速览字段，无行情数据时为 null） */
export interface WatchlistItem {
  id: number
  stockCode: string
  stockName: string
  groupName: string
  tags: string | null
  note: string | null
  createdAt: string
  latestPrice: number | null
  changePct: number | null
  quoteDate: string | null
}

export function getWatchlist() {
  return api.get<WatchlistItem[]>('/watchlist')
}

export function addWatchlist(item: { stockCode: string; stockName: string; groupName?: string; tags?: string; note?: string }) {
  return api.post<WatchlistItem>('/watchlist', item)
}

export function updateWatchlist(id: number, patch: Partial<Omit<WatchlistItem, 'id' | 'createdAt' | 'latestPrice' | 'changePct' | 'quoteDate'>>) {
  return api.put<WatchlistItem>(`/watchlist/${id}`, patch)
}

export function deleteWatchlist(id: number) {
  return api.delete(`/watchlist/${id}`)
}
