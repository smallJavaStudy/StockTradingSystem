import axios from 'axios'
import type {
  WorkflowSavePayload,
  AgentSavePayload,
  WorkflowEvent
} from '../types/workflow'

// ────────── Workflow Definition API ──────────

const workflowApi = axios.create({ baseURL: 'http://localhost:8080/api/v1/workflow' })

export function listWorkflows() {
  return workflowApi.get('/list')
}

export function getWorkflow(id: number) {
  return workflowApi.get(`/${id}`)
}

export function createWorkflow(payload: WorkflowSavePayload) {
  return workflowApi.post('/create', payload)
}

export function updateWorkflow(id: number, payload: WorkflowSavePayload) {
  return workflowApi.put(`/${id}`, payload)
}

export function deleteWorkflow(id: number) {
  return workflowApi.delete(`/${id}`)
}

export function publishWorkflow(id: number) {
  return workflowApi.post(`/${id}/publish`)
}

export function generateWorkflow(description: string) {
  return workflowApi.post('/generate', { description })
}

export function aiEditWorkflow(id: number, instruction: string) {
  return workflowApi.post(`/${id}/ai-edit`, { instruction })
}

export function executeWorkflow(id: number, input: Record<string, string>) {
  return workflowApi.post(`/${id}/execute`, { input })
}

// ────────── Workflow Execution API ──────────

const executionApi = axios.create({ baseURL: 'http://localhost:8080/api/v1/workflow-execution' })

export function getExecution(processInstanceId: string) {
  return executionApi.get(`/${processInstanceId}`)
}

export function cancelExecution(processInstanceId: string) {
  return executionApi.post(`/${processInstanceId}/cancel`)
}

export function getExecutionHistory(workflowDefId: number) {
  return executionApi.get(`/history/${workflowDefId}`)
}

// ────────── Workflow Execution SSE ──────────

type WorkflowEventHandler = (event: WorkflowEvent) => void
type WorkflowErrorHandler = () => void

export function connectExecutionStream(
  processInstanceId: string,
  onEvent: WorkflowEventHandler,
  onError: WorkflowErrorHandler
): EventSource {
  const url = `http://localhost:8080/api/v1/workflow-execution/${processInstanceId}/stream`
  const source = new EventSource(url)

  source.addEventListener('workflow-event', (e: MessageEvent) => {
    try {
      const event: WorkflowEvent = JSON.parse(e.data)
      onEvent(event)
    } catch (err) {
      console.error('Failed to parse workflow SSE event:', err)
    }
  })

  source.onerror = () => {
    if (source.readyState === EventSource.CLOSED) {
      onError()
    }
  }

  return source
}

// ────────── Workflow Agent API ──────────

const agentApi = axios.create({ baseURL: 'http://localhost:8080/api/v1/workflow-agent' })

export function listAgents() {
  return agentApi.get('/list')
}

export function getAgent(id: number) {
  return agentApi.get(`/${id}`)
}

export function createAgent(payload: AgentSavePayload) {
  return agentApi.post('', payload)
}

export function updateAgent(id: number, payload: AgentSavePayload) {
  return agentApi.put(`/${id}`, payload)
}

export function deleteAgent(id: number) {
  return agentApi.delete(`/${id}`)
}

export function getAvailableTools() {
  return agentApi.get('/available-tools')
}
