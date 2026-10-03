export interface ThinkingEvent {
  direction: string
  eventType: string
  content: string
  timestamp: number
  meta: Record<string, unknown>
}

export interface StockCandidate {
  code: string
  name: string
  market: string
  industry: string
  marketDisplay: string
}

export interface AnalysisSession {
  id: number
  sessionId: string
  stockCode: string
  stockName: string
  modelChoice: string
  status: string
  totalElapsedMs: number
  createdAt: string
}

export interface AgentAnalysisResult {
  id: number
  sessionId: string
  direction: string
  agentName: string
  modelName: string
  contentJson: string
  thinkingTrace: string
  inputTokens: number
  outputTokens: number
  elapsedMs: number
  status: string
  errorMessage: string
  createdAt: string
}

export type AnalysisStatus = 'idle' | 'loading' | 'running' | 'completed' | 'error'

export interface DirectionState {
  key: string
  displayName: string
  status: AnalysisStatus
  thinkingEvents: ThinkingEvent[]
  thinkingText: string
  streamingText: string
  finalResult: string
  errorMessage: string
}
