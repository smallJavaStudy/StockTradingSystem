// ────────── Workflow Definition ──────────

export type WorkflowCategory = 'DEV_PROCESS' | 'STOCK_ANALYSIS' | 'MARKET_REVIEW' | 'CUSTOM'
export type WorkflowStatus = 'DRAFT' | 'PUBLISHED' | 'ARCHIVED'
export type WorkflowCreatedBy = 'USER' | 'AGENT'

export interface WorkflowDef {
  id: number
  name: string
  description: string
  category: WorkflowCategory
  definitionJson: string
  processDefinitionKey: string
  deploymentId: string
  status: WorkflowStatus
  version: number
  createdBy: WorkflowCreatedBy
  createdAt: string
  updatedAt: string
  /** POST /generate 降级标记（可能存在） */
  fallback?: boolean
}

export interface WorkflowSavePayload {
  name: string
  description: string
  category: WorkflowCategory
  definitionJson: string
}

/** definitionJson 解析后的结构 */
export interface WorkflowNodeDef {
  id: string
  name: string
  agentId: number | null
  promptTemplate: string
  timeoutSeconds: number
  dependsOn: string[]
}

export interface WorkflowDefinition {
  name: string
  nodes: WorkflowNodeDef[]
}

// ────────── Workflow Execution ──────────

export type ExecutionStatus = 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED'

export interface ExecutionNode {
  nodeId: string
  nodeName: string
  status: string
  output: string
  errorMessage: string
  startedAt: string
  completedAt: string
}

export interface WorkflowExecution {
  processInstanceId: string
  status: ExecutionStatus
  nodes: ExecutionNode[]
}

export type WorkflowEventType =
  | 'NODE_STARTED'
  | 'NODE_DELTA'
  | 'NODE_COMPLETED'
  | 'NODE_FAILED'
  | 'PROCESS_COMPLETED'
  | 'PROCESS_CANCELLED'
  | 'PROCESS_FAILED'

export interface WorkflowEvent {
  type: WorkflowEventType
  processInstanceId: string
  nodeId?: string
  nodeName?: string
  delta?: string
  output?: string
  error?: string
  timestamp?: number
}

export interface ExecutionHistoryItem {
  processInstanceId: string
  status: ExecutionStatus
  startedAt: string
  completedAt: string
}

/** 前端运行时节点状态（SSE 增量累积） */
export interface NodeRuntimeState {
  nodeId: string
  nodeName: string
  status: string
  output: string
  delta: string
  errorMessage: string
  startedAt: string
  completedAt: string
}

// ────────── Workflow Agent ──────────

export type AgentType = 'PROMPT' | 'AGENTSCOPE'

export interface AgentDef {
  id: number
  name: string
  type: AgentType
  systemPrompt: string
  modelChoice: string
  temperature: number
  maxTokens: number
  toolsJson: string
  maxIterations: number
  loopDepth: number
  eventsJson: string
  createdAt: string
  updatedAt: string
}

export interface AgentSavePayload {
  name: string
  type: AgentType
  systemPrompt: string
  modelChoice: string
  temperature: number
  maxTokens: number
  toolsJson: string
  maxIterations: number
  loopDepth: number
  eventsJson: string
}

/** /available-tools 可能返回字符串或含说明的对象 */
export interface AvailableTool {
  name: string
  description: string
}
