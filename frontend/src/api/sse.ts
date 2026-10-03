import type { ThinkingEvent } from '../types/analysis'

type EventHandler = (event: ThinkingEvent) => void
type ErrorHandler = (error: string) => void
type CompleteHandler = (sessionId: string, status: string) => void

export function connectAnalysisStream(
  sessionId: string,
  onEvent: EventHandler,
  onError: ErrorHandler,
  onComplete: CompleteHandler
): EventSource {
  const url = `http://localhost:8080/api/v1/analysis-agent/stream/${sessionId}`
  const source = new EventSource(url)

  source.addEventListener('analysis-event', (e: MessageEvent) => {
    try {
      const event: ThinkingEvent = JSON.parse(e.data)
      onEvent(event)
    } catch (err) {
      console.error('Failed to parse SSE event:', err)
    }
  })

  source.addEventListener('session-complete', (e: MessageEvent) => {
    try {
      const data = JSON.parse(e.data)
      onComplete(data.sessionId, data.status)
    } catch (err) {
      console.error('Failed to parse session-complete:', err)
      onComplete(sessionId, 'COMPLETED')
    }
    source.close()
  })

  source.addEventListener('error', () => {
    if (source.readyState === EventSource.CLOSED) {
      onError('SSE connection closed')
    }
  })

  source.onerror = () => {
    if (source.readyState === EventSource.CLOSED) {
      onError('SSE connection lost')
    }
  }

  return source
}
