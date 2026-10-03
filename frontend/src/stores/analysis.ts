import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { resolveStockName, startAnalysis } from '../api/stock'
import { connectAnalysisStream } from '../api/sse'
import type { StockCandidate, ThinkingEvent, DirectionState, AnalysisStatus } from '../types/analysis'

export const useAnalysisStore = defineStore('analysis', () => {
  // Step state: 1=search, 2=select, 3=dashboard
  const step = ref(1)
  const loading = ref(false)
  const sessionId = ref('')
  const sessionStatus = ref('')

  // Stock resolution
  const searchQuery = ref('')
  const candidates = ref<StockCandidate[]>([])
  const selectedStock = ref<StockCandidate | null>(null)

  // Analysis config
  const selectedDirections = ref<string[]>([
    'TECHNICAL_ANALYSIS', 'TREND_ANALYSIS', 'SECTOR_ANALYSIS',
    'CAPITAL_FLOW', 'SENTIMENT_ANALYSIS', 'FUNDAMENTAL_ANALYSIS',
    'VALUATION_ANALYSIS', 'CHIP_STRUCTURE', 'RISK_WARNING',
    'COMPREHENSIVE_ADVICE'
  ])
  const modelChoice = ref('deepseek-v4')

  // SSE connection
  let eventSource: EventSource | null = null

  // Direction states — bump stateVersion instead of recreating the Map
  const directionStates = ref<Map<string, DirectionState>>(new Map())
  const stateVersion = ref(0)

  const directionNames: Record<string, string> = {
    'TECHNICAL_ANALYSIS': '日K线技术分析',
    'TREND_ANALYSIS': '近期走势研判',
    'SECTOR_ANALYSIS': '所属板块分析',
    'CAPITAL_FLOW': '资金面分析',
    'SENTIMENT_ANALYSIS': '市场舆情分析',
    'FUNDAMENTAL_ANALYSIS': '基本面分析',
    'VALUATION_ANALYSIS': '估值分析',
    'CHIP_STRUCTURE': '筹码结构分析',
    'RISK_WARNING': '风险预警',
    'COMPREHENSIVE_ADVICE': '综合投资建议'
  }

  // Derived: sorted list for the dashboard — depends on stateVersion for reactivity
  const directionList = computed(() => {
    // eslint-disable-next-line @typescript-eslint/no-unused-expressions
    stateVersion.value // track for reactivity
    const list: { key: string; state: DirectionState }[] = []
    directionStates.value.forEach((state, key) => list.push({ key, state }))
    return list
  })

  const completedCount = computed(() => {
    let count = 0
    directionStates.value.forEach((s) => {
      if (s.status === 'completed') count++
    })
    return count
  })

  const totalDirections = computed(() => selectedDirections.value.length)

  // ────────── rAF batcher for SSE events ──────────
  let batchedEvents: Array<{ direction: string; eventType: string; content: string; meta: Record<string, unknown> }> = []
  let rafId = 0

  function flushBatch() {
    rafId = 0
    const batch = batchedEvents
    batchedEvents = []

    for (const e of batch) {
      const state = directionStates.value.get(e.direction)
      if (!state) continue

      switch (e.eventType) {
        case 'THINKING_DELTA':
          state.thinkingText += e.content
          break
        case 'TEXT_DELTA':
          state.streamingText += e.content
          break
        case 'AGENT_COMPLETE':
          state.finalResult = e.content
          state.status = 'completed'
          state.thinkingEvents.push({ direction: e.direction, eventType: e.eventType, content: e.content, timestamp: Date.now(), meta: e.meta })
          stateVersion.value++
          break
        case 'AGENT_ERROR':
          state.errorMessage = e.content
          state.status = 'error'
          state.thinkingEvents.push({ direction: e.direction, eventType: e.eventType, content: e.content, timestamp: Date.now(), meta: e.meta })
          stateVersion.value++
          break
        default:
          // TOOL_CALL_*, AGENT_START, MODEL_CALL_END etc.
          state.thinkingEvents.push({ direction: e.direction, eventType: e.eventType, content: e.content, timestamp: Date.now(), meta: e.meta })
          break
      }
    }
    // Trigger one re-render per frame for running cards
    stateVersion.value++
  }

  // Actions
  async function resolveStock(query: string) {
    searchQuery.value = query
    loading.value = true
    try {
      const res = await resolveStockName(query)
      candidates.value = res.data || []
    } catch (e) {
      console.error('Failed to resolve stock name:', e)
      candidates.value = []
    } finally {
      loading.value = false
    }
  }

  function selectStock(candidate: StockCandidate) {
    selectedStock.value = candidate
    step.value = 2
  }

  function toggleDirection(key: string) {
    const idx = selectedDirections.value.indexOf(key)
    if (idx >= 0) {
      selectedDirections.value.splice(idx, 1)
    } else {
      selectedDirections.value.push(key)
    }
  }

  function setModelChoice(choice: string) {
    modelChoice.value = choice
  }

  async function beginAnalysis() {
    if (!selectedStock.value) return
    loading.value = true

    try {
      const res = await startAnalysis(
        selectedStock.value.code,
        selectedDirections.value,
        modelChoice.value
      )
      sessionId.value = res.data.sessionId

      // Initialize direction states
      const states = new Map<string, DirectionState>()
      for (const key of selectedDirections.value) {
        states.set(key, {
          key,
          displayName: directionNames[key] || key,
          status: 'running' as AnalysisStatus,
          thinkingEvents: [],
          thinkingText: '',
          streamingText: '',
          finalResult: '',
          errorMessage: ''
        })
      }
      directionStates.value = states
      stateVersion.value++

      // Connect SSE
      step.value = 3
      loading.value = false
      connectSSE()
    } catch (e) {
      console.error('Failed to start analysis:', e)
      loading.value = false
    }
  }

  function connectSSE() {
    if (eventSource) {
      eventSource.close()
    }
    eventSource = connectAnalysisStream(
      sessionId.value,
      (event: ThinkingEvent) => {
        // Batch all events via rAF — one DOM update per frame
        batchedEvents.push({
          direction: event.direction,
          eventType: event.eventType,
          content: event.content,
          meta: event.meta
        })
        if (!rafId) {
          rafId = requestAnimationFrame(flushBatch)
        }
      },
      (error: string) => {
        console.error('SSE error:', error)
      },
      (_sessionId: string, status: string) => {
        sessionStatus.value = status
      }
    )
  }

  function reset() {
    if (eventSource) {
      eventSource.close()
      eventSource = null
    }
    if (rafId) {
      cancelAnimationFrame(rafId)
      rafId = 0
    }
    batchedEvents = []
    step.value = 1
    loading.value = false
    sessionId.value = ''
    sessionStatus.value = ''
    searchQuery.value = ''
    candidates.value = []
    selectedStock.value = null
    directionStates.value = new Map()
    stateVersion.value = 0
  }

  return {
    step, loading, sessionId, sessionStatus,
    searchQuery, candidates, selectedStock,
    selectedDirections, modelChoice,
    directionStates, directionList, directionNames,
    completedCount, totalDirections,
    resolveStock, selectStock, toggleDirection,
    setModelChoice, beginAnalysis, reset
  }
})
