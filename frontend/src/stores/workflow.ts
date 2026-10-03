import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import {
  listWorkflows, listAgents, getExecution, cancelExecution,
  connectExecutionStream
} from '../api/workflow'
import type {
  WorkflowDef, AgentDef, WorkflowExecution, WorkflowEvent,
  NodeRuntimeState, ExecutionStatus
} from '../types/workflow'

const TERMINAL_STATUSES: ExecutionStatus[] = ['COMPLETED', 'FAILED', 'CANCELLED']

export const useWorkflowStore = defineStore('workflow', () => {
  // ────────── List state ──────────
  const workflows = ref<WorkflowDef[]>([])
  const agents = ref<AgentDef[]>([])
  const loading = ref(false)

  // ────────── Editor state ──────────
  const currentWorkflow = ref<WorkflowDef | null>(null)

  // ────────── Execution state ──────────
  const currentExecution = ref<WorkflowExecution | null>(null)
  // nodeStates — bump stateVersion instead of recreating the Map（沿用 analysis store 模式）
  const nodeStates = ref<Map<string, NodeRuntimeState>>(new Map())
  const stateVersion = ref(0)
  const executionStatus = ref<ExecutionStatus | ''>('')
  const connectionMode = ref<'sse' | 'polling' | 'closed'>('closed')

  let eventSource: EventSource | null = null
  let pollTimer: ReturnType<typeof setInterval> | null = null
  let reconnectAttempted = false
  let activeInstanceId = ''

  const nodeList = computed(() => {
    // eslint-disable-next-line @typescript-eslint/no-unused-expressions
    stateVersion.value // track for reactivity
    const list: NodeRuntimeState[] = []
    nodeStates.value.forEach((s) => list.push(s))
    return list
  })

  const isTerminal = computed(() =>
    TERMINAL_STATUSES.includes(executionStatus.value as ExecutionStatus)
  )

  // ────────── List actions ──────────

  async function fetchWorkflows() {
    loading.value = true
    try {
      const res = await listWorkflows()
      workflows.value = res.data || []
    } catch (e) {
      console.error('Failed to fetch workflows:', e)
      workflows.value = []
    } finally {
      loading.value = false
    }
  }

  async function fetchAgents() {
    try {
      const res = await listAgents()
      agents.value = res.data || []
    } catch (e) {
      console.error('Failed to fetch agents:', e)
      agents.value = []
    }
  }

  // ────────── Execution helpers ──────────

  function ensureNodeState(nodeId: string, nodeName?: string): NodeRuntimeState {
    let state = nodeStates.value.get(nodeId)
    if (!state) {
      state = {
        nodeId,
        nodeName: nodeName || nodeId,
        status: 'PENDING',
        output: '',
        delta: '',
        errorMessage: '',
        startedAt: '',
        completedAt: ''
      }
      nodeStates.value.set(nodeId, state)
    }
    if (nodeName) state.nodeName = nodeName
    return state
  }

  function applySnapshot(exec: WorkflowExecution) {
    currentExecution.value = exec
    executionStatus.value = exec.status
    for (const node of exec.nodes || []) {
      const state = ensureNodeState(node.nodeId, node.nodeName)
      state.status = node.status || state.status
      if (node.output) state.output = node.output
      if (node.errorMessage) state.errorMessage = node.errorMessage
      if (node.startedAt) state.startedAt = node.startedAt
      if (node.completedAt) state.completedAt = node.completedAt
    }
    stateVersion.value++
  }

  function handleEvent(event: WorkflowEvent) {
    switch (event.type) {
      case 'NODE_STARTED': {
        const state = ensureNodeState(event.nodeId || '', event.nodeName)
        state.status = 'RUNNING'
        if (!state.startedAt) state.startedAt = tsToIso(event.timestamp)
        break
      }
      case 'NODE_DELTA': {
        const state = ensureNodeState(event.nodeId || '', event.nodeName)
        state.status = 'RUNNING'
        state.delta += event.delta || ''
        break
      }
      case 'NODE_COMPLETED': {
        const state = ensureNodeState(event.nodeId || '', event.nodeName)
        state.status = 'COMPLETED'
        state.output = event.output || state.delta
        state.completedAt = tsToIso(event.timestamp)
        break
      }
      case 'NODE_FAILED': {
        const state = ensureNodeState(event.nodeId || '', event.nodeName)
        state.status = 'FAILED'
        state.errorMessage = event.error || '节点执行失败'
        state.completedAt = tsToIso(event.timestamp)
        break
      }
      case 'PROCESS_COMPLETED':
        executionStatus.value = 'COMPLETED'
        disconnect()
        break
      case 'PROCESS_CANCELLED':
        executionStatus.value = 'CANCELLED'
        disconnect()
        break
      case 'PROCESS_FAILED':
        executionStatus.value = 'FAILED'
        disconnect()
        break
    }
    stateVersion.value++
  }

  function tsToIso(ts?: number): string {
    return ts ? new Date(ts).toISOString() : new Date().toISOString()
  }

  // ────────── Execution actions ──────────

  /** 挂载执行页：先 GET 快照，再连 SSE 回放 */
  async function loadExecution(processInstanceId: string) {
    activeInstanceId = processInstanceId
    reconnectAttempted = false
    nodeStates.value = new Map()
    stateVersion.value = 0
    currentExecution.value = null
    executionStatus.value = ''

    try {
      const res = await getExecution(processInstanceId)
      if (res.data) applySnapshot(res.data)
    } catch (e) {
      console.error('Failed to fetch execution snapshot:', e)
    }

    if (!isTerminal.value) {
      connect(processInstanceId)
    }
  }

  function connect(processInstanceId: string) {
    closeSse()
    connectionMode.value = 'sse'
    eventSource = connectExecutionStream(
      processInstanceId,
      (event: WorkflowEvent) => handleEvent(event),
      () => {
        // 断线：自动重连一次，失败降级为轮询
        closeSse()
        if (isTerminal.value || activeInstanceId !== processInstanceId) return
        if (!reconnectAttempted) {
          reconnectAttempted = true
          console.warn('Workflow SSE lost, reconnecting once...')
          connect(processInstanceId)
        } else {
          console.warn('Workflow SSE reconnect failed, fall back to 5s polling')
          startPolling(processInstanceId)
        }
      }
    )
  }

  function startPolling(processInstanceId: string) {
    stopPolling()
    connectionMode.value = 'polling'
    pollTimer = setInterval(async () => {
      try {
        const res = await getExecution(processInstanceId)
        if (res.data) applySnapshot(res.data)
        if (isTerminal.value) disconnect()
      } catch (e) {
        console.error('Polling execution failed:', e)
      }
    }, 5000)
  }

  function stopPolling() {
    if (pollTimer) {
      clearInterval(pollTimer)
      pollTimer = null
    }
  }

  function closeSse() {
    if (eventSource) {
      eventSource.close()
      eventSource = null
    }
  }

  function disconnect() {
    closeSse()
    stopPolling()
    connectionMode.value = 'closed'
  }

  async function cancelCurrentExecution() {
    if (!activeInstanceId) return
    try {
      await cancelExecution(activeInstanceId)
    } catch (e) {
      console.error('Failed to cancel execution:', e)
    }
  }

  function resetExecution() {
    disconnect()
    activeInstanceId = ''
    reconnectAttempted = false
    currentExecution.value = null
    executionStatus.value = ''
    nodeStates.value = new Map()
    stateVersion.value = 0
  }

  return {
    workflows, agents, loading,
    currentWorkflow,
    currentExecution, nodeStates, nodeList, stateVersion,
    executionStatus, isTerminal, connectionMode,
    fetchWorkflows, fetchAgents,
    loadExecution, disconnect, cancelCurrentExecution, resetExecution
  }
})
