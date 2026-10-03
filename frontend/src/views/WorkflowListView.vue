<template>
  <div class="workflow-view">
    <h1>工作流管理</h1>

    <!-- Tabs -->
    <div class="wf-tabs">
      <span class="wf-tab" :class="{ active: tab === 'workflow' }" @click="tab = 'workflow'">工作流</span>
      <span class="wf-tab" :class="{ active: tab === 'agent' }" @click="tab = 'agent'">智能体</span>
    </div>

    <!-- ══════════ 工作流 Tab ══════════ -->
    <section v-if="tab === 'workflow'">
      <div class="wf-toolbar">
        <button class="btn-primary" @click="showGenerateModal = true">✨ AI 生成工作流</button>
        <button class="btn-plain" @click="router.push('/workflow/new/edit')">＋ 新建空白工作流</button>
      </div>

      <div v-if="store.loading" class="empty">加载中...</div>
      <div v-else-if="!store.workflows.length" class="empty">暂无工作流，点击上方按钮创建</div>
      <table v-else>
        <thead>
          <tr>
            <th>名称</th><th>分类</th><th>状态</th><th>版本</th><th>来源</th><th>更新时间</th><th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="wf in store.workflows" :key="wf.id">
            <td>
              <strong>{{ wf.name }}</strong>
              <div class="wf-desc" v-if="wf.description">{{ wf.description }}</div>
            </td>
            <td><span class="tag" :class="categoryClass(wf.category)">{{ categoryLabel(wf.category) }}</span></td>
            <td><span class="badge" :class="'badge-' + wf.status.toLowerCase()">{{ statusLabel(wf.status) }}</span></td>
            <td>v{{ wf.version }}</td>
            <td>{{ wf.createdBy === 'AGENT' ? 'AI' : '用户' }}</td>
            <td class="wf-time">{{ formatTime(wf.updatedAt) }}</td>
            <td class="wf-actions">
              <a @click="router.push(`/workflow/${wf.id}/edit`)">编辑</a>
              <a v-if="wf.status === 'DRAFT'" @click="onPublish(wf)">发布</a>
              <a v-if="wf.status === 'PUBLISHED'" @click="openExecuteModal(wf)">执行</a>
              <a @click="openHistoryModal(wf)">历史</a>
              <a class="danger" @click="onDelete(wf)">删除</a>
            </td>
          </tr>
        </tbody>
      </table>
    </section>

    <!-- ══════════ 智能体 Tab ══════════ -->
    <section v-if="tab === 'agent'">
      <div class="wf-toolbar">
        <button class="btn-primary" @click="openAgentForm(null)">＋ 新建智能体</button>
      </div>

      <div v-if="!store.agents.length" class="empty">暂无智能体，点击上方按钮创建</div>
      <table v-else>
        <thead>
          <tr>
            <th>名称</th><th>类型</th><th>模型</th><th>温度</th><th>最大Token</th><th>更新时间</th><th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="ag in store.agents" :key="ag.id">
            <td><strong>{{ ag.name }}</strong></td>
            <td><span class="tag" :class="ag.type === 'AGENTSCOPE' ? 'tag-info' : 'tag-success'">{{ ag.type }}</span></td>
            <td>{{ ag.modelChoice }}</td>
            <td>{{ ag.temperature }}</td>
            <td>{{ ag.maxTokens }}</td>
            <td class="wf-time">{{ formatTime(ag.updatedAt) }}</td>
            <td class="wf-actions">
              <a @click="openAgentForm(ag)">编辑</a>
              <a class="danger" @click="onDeleteAgent(ag)">删除</a>
            </td>
          </tr>
        </tbody>
      </table>
    </section>

    <!-- ══════════ AI 生成弹窗 ══════════ -->
    <div v-if="showGenerateModal" class="modal-mask" @click.self="showGenerateModal = false">
      <div class="modal">
        <h3>AI 生成工作流</h3>
        <p class="modal-hint">用自然语言描述你想要的工作流，AI 将自动生成节点编排</p>
        <textarea v-model="generateDesc" rows="5" placeholder="例如：先分析股票的技术面，再分析基本面，最后综合两者给出投资建议"></textarea>
        <div class="modal-footer">
          <button class="btn-plain" @click="showGenerateModal = false">取消</button>
          <button class="btn-primary" :disabled="!generateDesc.trim() || generating" @click="onGenerate">
            {{ generating ? '生成中...' : '生成' }}
          </button>
        </div>
      </div>
    </div>

    <!-- ══════════ 执行弹窗 ══════════ -->
    <div v-if="executeTarget" class="modal-mask" @click.self="executeTarget = null">
      <div class="modal">
        <h3>执行工作流：{{ executeTarget.name }}</h3>
        <template v-if="executeInputKeys.length">
          <p class="modal-hint">请填写工作流输入参数</p>
          <div v-for="key in executeInputKeys" :key="key" class="form-row">
            <label>{{ key }}</label>
            <input v-model="executeInputs[key]" :placeholder="'请输入 ' + key" />
          </div>
        </template>
        <p v-else class="modal-hint">该工作流无需输入参数，点击执行直接启动</p>
        <div class="modal-footer">
          <button class="btn-plain" @click="executeTarget = null">取消</button>
          <button class="btn-primary" :disabled="executing" @click="onExecute">
            {{ executing ? '启动中...' : '执行' }}
          </button>
        </div>
      </div>
    </div>

    <!-- ══════════ 历史弹窗 ══════════ -->
    <div v-if="historyTarget" class="modal-mask" @click.self="historyTarget = null">
      <div class="modal">
        <h3>执行历史：{{ historyTarget.name }}</h3>
        <div v-if="historyLoading" class="empty">加载中...</div>
        <div v-else-if="!historyList.length" class="empty">暂无执行记录</div>
        <table v-else>
          <thead><tr><th>实例 ID</th><th>状态</th><th>开始时间</th><th>结束时间</th></tr></thead>
          <tbody>
            <tr v-for="h in historyList" :key="h.processInstanceId" class="clickable"
                @click="router.push(`/workflow/execution/${h.processInstanceId}`)">
              <td class="wf-instance-id">{{ h.processInstanceId }}</td>
              <td><span class="badge" :class="'badge-' + h.status.toLowerCase()">{{ h.status }}</span></td>
              <td class="wf-time">{{ formatTime(h.startedAt) }}</td>
              <td class="wf-time">{{ formatTime(h.completedAt) }}</td>
            </tr>
          </tbody>
        </table>
        <div class="modal-footer">
          <button class="btn-plain" @click="historyTarget = null">关闭</button>
        </div>
      </div>
    </div>

    <!-- ══════════ 智能体编辑弹窗 ══════════ -->
    <div v-if="showAgentForm" class="modal-mask" @click.self="closeAgentForm">
      <div class="modal modal-wide">
        <h3>{{ editingAgentId ? '编辑智能体' : '新建智能体' }}</h3>
        <div class="form-row">
          <label>名称 *</label>
          <input v-model="agentForm.name" placeholder="智能体名称" />
        </div>
        <div class="form-row">
          <label>类型</label>
          <div class="radio-group">
            <label><input type="radio" value="PROMPT" v-model="agentForm.type" /> PROMPT（单次调用）</label>
            <label><input type="radio" value="AGENTSCOPE" v-model="agentForm.type" /> AGENTSCOPE（工具循环）</label>
          </div>
        </div>
        <div class="form-row">
          <label>系统提示词</label>
          <textarea v-model="agentForm.systemPrompt" rows="5" placeholder="你是一个..."></textarea>
        </div>
        <div class="form-row-group">
          <div class="form-row">
            <label>模型</label>
            <select v-model="agentForm.modelChoice">
              <option value="deepseek-v4">deepseek-v4</option>
              <option value="deepseek-v5">deepseek-v5</option>
              <option value="kimi">kimi</option>
            </select>
          </div>
          <div class="form-row">
            <label>温度</label>
            <input type="number" v-model.number="agentForm.temperature" step="0.1" min="0" max="2" />
          </div>
          <div class="form-row">
            <label>最大 Token</label>
            <input type="number" v-model.number="agentForm.maxTokens" step="256" min="1" />
          </div>
        </div>

        <template v-if="agentForm.type === 'AGENTSCOPE'">
          <div class="form-row">
            <label>可用工具</label>
            <div class="checkbox-group">
              <label v-for="t in availableTools" :key="t.name">
                <input type="checkbox" :value="t.name" v-model="selectedTools" />
                {{ t.name }}<span v-if="t.description" class="tool-desc">（{{ t.description }}）</span>
              </label>
              <span v-if="!availableTools.length" class="tool-desc">工具列表加载中或为空</span>
            </div>
          </div>
          <div class="form-row-group">
            <div class="form-row">
              <label>循环次数 maxIterations</label>
              <input type="number" v-model.number="agentForm.maxIterations" min="1" />
            </div>
            <div class="form-row">
              <label>循环深度 loopDepth</label>
              <input type="number" v-model.number="agentForm.loopDepth" min="1" />
            </div>
          </div>
          <div class="form-row">
            <label>事件配置 eventsJson（可选）</label>
            <textarea v-model="agentForm.eventsJson" rows="3" placeholder='{"onNodeStart": "..."}'></textarea>
          </div>
        </template>

        <div class="modal-footer">
          <button class="btn-plain" @click="closeAgentForm">取消</button>
          <button class="btn-primary" :disabled="!agentForm.name.trim() || agentSaving" @click="onSaveAgent">
            {{ agentSaving ? '保存中...' : '保存' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useWorkflowStore } from '../stores/workflow'
import {
  deleteWorkflow, publishWorkflow, generateWorkflow, executeWorkflow,
  getExecutionHistory, createAgent, updateAgent, deleteAgent, getAvailableTools
} from '../api/workflow'
import type {
  WorkflowDef, AgentDef, AgentSavePayload, ExecutionHistoryItem,
  AvailableTool, WorkflowDefinition, WorkflowCategory, WorkflowStatus
} from '../types/workflow'

const router = useRouter()
const store = useWorkflowStore()

const tab = ref<'workflow' | 'agent'>('workflow')

onMounted(() => {
  store.fetchWorkflows()
  store.fetchAgents()
  loadTools()
})

// ────────── 展示辅助 ──────────

const categoryLabels: Record<WorkflowCategory, string> = {
  DEV_PROCESS: '研发流程', STOCK_ANALYSIS: '股票分析', MARKET_REVIEW: '市场复盘', CUSTOM: '自定义'
}
const statusLabels: Record<WorkflowStatus, string> = {
  DRAFT: '草稿', PUBLISHED: '已发布', ARCHIVED: '已归档'
}

function categoryLabel(c: WorkflowCategory) { return categoryLabels[c] || c }
function statusLabel(s: WorkflowStatus) { return statusLabels[s] || s }
function categoryClass(c: WorkflowCategory) {
  return c === 'STOCK_ANALYSIS' ? 'tag-info' : c === 'DEV_PROCESS' ? 'tag-success' : 'tag-purple'
}
function formatTime(t: string) {
  if (!t) return '-'
  return t.replace('T', ' ').substring(0, 19)
}

// ────────── 工作流操作 ──────────

async function onPublish(wf: WorkflowDef) {
  if (!confirm(`确认发布工作流「${wf.name}」？发布后可执行。`)) return
  try {
    await publishWorkflow(wf.id)
    await store.fetchWorkflows()
  } catch (e) {
    console.error('Failed to publish workflow:', e)
    alert('发布失败，请稍后重试')
  }
}

async function onDelete(wf: WorkflowDef) {
  if (!confirm(`确认删除工作流「${wf.name}」？此操作不可恢复。`)) return
  try {
    await deleteWorkflow(wf.id)
    await store.fetchWorkflows()
  } catch (e) {
    console.error('Failed to delete workflow:', e)
    alert('删除失败，请稍后重试')
  }
}

// ────────── AI 生成 ──────────

const showGenerateModal = ref(false)
const generateDesc = ref('')
const generating = ref(false)

async function onGenerate() {
  generating.value = true
  try {
    const res = await generateWorkflow(generateDesc.value.trim())
    // 后端响应契约：{ workflow, fallback, message }
    const wf: WorkflowDef | undefined = res.data?.workflow
    showGenerateModal.value = false
    generateDesc.value = ''
    if (res.data?.fallback) {
      alert('AI 生成降级为模板，请在编辑器中完善')
    }
    if (wf?.id) {
      router.push(`/workflow/${wf.id}/edit`)
    } else {
      await store.fetchWorkflows()
    }
  } catch (e) {
    console.error('Failed to generate workflow:', e)
    alert('AI 生成失败，请稍后重试')
  } finally {
    generating.value = false
  }
}

// ────────── 执行弹窗 ──────────

const executeTarget = ref<WorkflowDef | null>(null)
const executeInputKeys = ref<string[]>([])
const executeInputs = reactive<Record<string, string>>({})
const executing = ref(false)

/** 从 definitionJson 提取输入占位符（与后端 AgentTaskDelegate 一致：仅识别 ${var} 与 ${nodeId.output}） */
function parseInputPlaceholders(definitionJson: string): string[] {
  const keys = new Set<string>()
  try {
    const def: WorkflowDefinition = JSON.parse(definitionJson)
    const nodeIds = new Set((def.nodes || []).map((n) => n.id))
    for (const node of def.nodes || []) {
      const matches = (node.promptTemplate || '').matchAll(/\$\{\s*([A-Za-z0-9_-]+)(\.output)?\s*\}/g)
      for (const m of matches) {
        if (m[2] && nodeIds.has(m[1])) continue // 上游输出引用
        if (m[1].startsWith('_')) continue // 下划线开头：后端预注入变量（如 _kline_context），无需用户填写
        keys.add(m[1])
      }
    }
  } catch (e) {
    console.error('Failed to parse definitionJson:', e)
  }
  return Array.from(keys)
}

function openExecuteModal(wf: WorkflowDef) {
  executeTarget.value = wf
  executeInputKeys.value = parseInputPlaceholders(wf.definitionJson)
  Object.keys(executeInputs).forEach((k) => delete executeInputs[k])
  executeInputKeys.value.forEach((k) => { executeInputs[k] = '' })
}

async function onExecute() {
  if (!executeTarget.value) return
  executing.value = true
  try {
    const res = await executeWorkflow(executeTarget.value.id, { ...executeInputs })
    const pid = res.data?.processInstanceId
    executeTarget.value = null
    if (pid) {
      router.push(`/workflow/execution/${pid}`)
    } else {
      alert('执行已提交，但未返回实例 ID')
    }
  } catch (e) {
    console.error('Failed to execute workflow:', e)
    alert('执行失败，请稍后重试')
  } finally {
    executing.value = false
  }
}

// ────────── 历史弹窗 ──────────

const historyTarget = ref<WorkflowDef | null>(null)
const historyList = ref<ExecutionHistoryItem[]>([])
const historyLoading = ref(false)

async function openHistoryModal(wf: WorkflowDef) {
  historyTarget.value = wf
  historyLoading.value = true
  historyList.value = []
  try {
    const res = await getExecutionHistory(wf.id)
    historyList.value = res.data || []
  } catch (e) {
    console.error('Failed to fetch execution history:', e)
  } finally {
    historyLoading.value = false
  }
}

// ────────── 智能体表单 ──────────

const showAgentForm = ref(false)
const editingAgentId = ref<number | null>(null)
const agentSaving = ref(false)
const availableTools = ref<AvailableTool[]>([])
const selectedTools = ref<string[]>([])

const defaultAgentForm = (): AgentSavePayload => ({
  name: '',
  type: 'PROMPT',
  systemPrompt: '',
  modelChoice: 'deepseek-v4',
  temperature: 0.7,
  maxTokens: 4096,
  toolsJson: '[]',
  maxIterations: 10,
  loopDepth: 1,
  eventsJson: ''
})

const agentForm = reactive<AgentSavePayload>(defaultAgentForm())

async function loadTools() {
  try {
    const res = await getAvailableTools()
    // 后端契约：{ tools: string[], descriptions: { name: desc }, usage: string }
    const data = (res.data ?? {}) as { tools?: string[]; descriptions?: Record<string, string> }
    const names = Array.isArray(data.tools) ? data.tools : []
    availableTools.value = names.map((t) => ({
      name: t,
      description: data.descriptions?.[t] || ''
    }))
  } catch (e) {
    console.error('Failed to fetch available tools:', e)
    availableTools.value = []
  }
}

function openAgentForm(agent: AgentDef | null) {
  editingAgentId.value = agent?.id ?? null
  Object.assign(agentForm, defaultAgentForm(), agent ? {
    name: agent.name,
    type: agent.type,
    systemPrompt: agent.systemPrompt || '',
    modelChoice: agent.modelChoice || 'deepseek-v4',
    temperature: agent.temperature ?? 0.7,
    maxTokens: agent.maxTokens ?? 4096,
    toolsJson: agent.toolsJson || '[]',
    maxIterations: agent.maxIterations ?? 10,
    loopDepth: agent.loopDepth ?? 1,
    eventsJson: agent.eventsJson || ''
  } : {})
  try {
    selectedTools.value = JSON.parse(agentForm.toolsJson || '[]')
  } catch {
    selectedTools.value = []
  }
  showAgentForm.value = true
}

function closeAgentForm() {
  showAgentForm.value = false
  editingAgentId.value = null
}

async function onSaveAgent() {
  agentSaving.value = true
  try {
    const payload: AgentSavePayload = {
      ...agentForm,
      toolsJson: JSON.stringify(selectedTools.value)
    }
    if (editingAgentId.value) {
      await updateAgent(editingAgentId.value, payload)
    } else {
      await createAgent(payload)
    }
    closeAgentForm()
    await store.fetchAgents()
  } catch (e) {
    console.error('Failed to save agent:', e)
    alert('保存失败，请稍后重试')
  } finally {
    agentSaving.value = false
  }
}

async function onDeleteAgent(ag: AgentDef) {
  if (!confirm(`确认删除智能体「${ag.name}」？`)) return
  try {
    await deleteAgent(ag.id)
    await store.fetchAgents()
  } catch (e) {
    console.error('Failed to delete agent:', e)
    alert('删除失败，可能被工作流引用')
  }
}
</script>
