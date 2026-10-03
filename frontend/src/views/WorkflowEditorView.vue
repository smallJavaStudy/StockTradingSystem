<template>
  <div class="workflow-editor">
    <!-- ══════════ 顶部工具栏 ══════════ -->
    <div class="editor-toolbar">
      <span class="back-btn" @click="router.push('/workflow')">&larr; 返回列表</span>
      <div class="editor-meta">
        <input class="editor-name" v-model="name" placeholder="工作流名称 *" />
        <input class="editor-desc" v-model="description" placeholder="描述（可选）" />
        <select v-model="category">
          <option value="DEV_PROCESS">研发流程</option>
          <option value="STOCK_ANALYSIS">股票分析</option>
          <option value="CUSTOM">自定义</option>
        </select>
      </div>
      <div class="editor-actions">
        <button class="btn-primary" :disabled="saving || !name.trim()" @click="onSave">
          {{ saving ? '保存中...' : '保存' }}
        </button>
        <button class="btn-plain" :disabled="isNew || publishing" @click="onPublish">发布</button>
        <button class="btn-plain" :disabled="isNew || status !== 'PUBLISHED'" @click="openExecuteModal">执行</button>
      </div>
    </div>

    <!-- AI 修改 -->
    <div class="editor-ai-bar">
      <input v-model="aiInstruction" :disabled="isNew"
             :placeholder="isNew ? '保存后可使用 AI 修改' : '用自然语言描述修改，例如：在技术分析节点后增加一个风险评估节点'"
             @keyup.enter="onAiEdit" />
      <button class="btn-primary" :disabled="isNew || !aiInstruction.trim() || aiEditing" @click="onAiEdit">
        {{ aiEditing ? 'AI 修改中...' : '✨ AI 修改' }}
      </button>
    </div>

    <div v-if="loadError" class="empty">{{ loadError }}</div>

    <!-- ══════════ 主体左右分栏 ══════════ -->
    <div v-else class="editor-body">
      <!-- 左侧：节点列表编辑器 -->
      <div class="editor-nodes">
        <h2>节点编排（{{ nodes.length }} 个节点）</h2>
        <div v-for="(node, idx) in nodes" :key="node.id" class="node-card">
          <div class="node-card-header">
            <span class="node-id-badge">{{ node.id }}</span>
            <input class="node-name-input" v-model="node.name" placeholder="节点名称" />
            <span class="node-delete" @click="removeNode(idx)" title="删除节点">✕</span>
          </div>
          <div class="node-card-body">
            <div class="form-row">
              <label>智能体</label>
              <select v-model="node.agentId">
                <option :value="null">— 请选择 —</option>
                <option v-for="ag in store.agents" :key="ag.id" :value="ag.id">
                  {{ ag.name }}（{{ ag.type }} / {{ ag.modelChoice }}）
                </option>
              </select>
            </div>
            <div class="form-row">
              <label>提示词模板（支持 ${goal} 输入占位符与 ${nodeId.output} 上游输出）</label>
              <textarea v-model="node.promptTemplate" rows="3" placeholder="请分析 ${goal} ..."></textarea>
            </div>
            <div class="form-row-group">
              <div class="form-row">
                <label>超时（秒）</label>
                <input type="number" v-model.number="node.timeoutSeconds" min="1" />
              </div>
              <div class="form-row">
                <label>依赖节点 dependsOn</label>
                <div class="checkbox-group inline">
                  <label v-for="other in nodes" :key="other.id" v-show="other.id !== node.id">
                    <input type="checkbox" :value="other.id" v-model="node.dependsOn" />
                    {{ other.id }}
                  </label>
                  <span v-if="nodes.length <= 1" class="tool-desc">暂无其他节点</span>
                </div>
              </div>
            </div>
          </div>
        </div>
        <button class="btn-plain btn-add-node" @click="addNode">＋ 添加节点</button>
      </div>

      <!-- 右侧：definitionJson 预览/编辑 -->
      <div class="editor-json">
        <div class="editor-json-header">
          <h2>definitionJson</h2>
          <div class="editor-json-actions">
            <template v-if="jsonEditMode">
              <button class="btn-plain" @click="cancelJsonEdit">取消</button>
              <button class="btn-primary" @click="applyJsonEdit">应用</button>
            </template>
            <button v-else class="btn-plain" @click="enterJsonEdit">手动编辑 JSON</button>
          </div>
        </div>
        <p v-if="jsonError" class="json-error">{{ jsonError }}</p>
        <textarea v-if="jsonEditMode" class="json-textarea" v-model="jsonDraft" spellcheck="false"></textarea>
        <pre v-else class="json-preview">{{ definitionJsonPreview }}</pre>
      </div>
    </div>

    <!-- ══════════ 执行弹窗 ══════════ -->
    <div v-if="showExecuteModal" class="modal-mask" @click.self="showExecuteModal = false">
      <div class="modal">
        <h3>执行工作流：{{ name }}</h3>
        <template v-if="executeInputKeys.length">
          <p class="modal-hint">请填写工作流输入参数</p>
          <div v-for="key in executeInputKeys" :key="key" class="form-row">
            <label>{{ key }}</label>
            <input v-model="executeInputs[key]" :placeholder="'请输入 ' + key" />
          </div>
        </template>
        <p v-else class="modal-hint">该工作流无需输入参数，点击执行直接启动</p>
        <div class="modal-footer">
          <button class="btn-plain" @click="showExecuteModal = false">取消</button>
          <button class="btn-primary" :disabled="executing" @click="onExecute">
            {{ executing ? '启动中...' : '执行' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useWorkflowStore } from '../stores/workflow'
import {
  getWorkflow, createWorkflow, updateWorkflow, publishWorkflow,
  aiEditWorkflow, executeWorkflow
} from '../api/workflow'
import type {
  WorkflowDef, WorkflowNodeDef, WorkflowDefinition, WorkflowCategory, WorkflowStatus
} from '../types/workflow'

const route = useRoute()
const router = useRouter()
const store = useWorkflowStore()

const rawId = computed(() => String(route.params.id))
const isNew = computed(() => rawId.value === 'new' || workflowId.value === null)
const workflowId = ref<number | null>(null)

const name = ref('')
const description = ref('')
const category = ref<WorkflowCategory>('CUSTOM')
const status = ref<WorkflowStatus>('DRAFT')
const nodes = ref<WorkflowNodeDef[]>([])

const loadError = ref('')
const saving = ref(false)
const publishing = ref(false)
const aiInstruction = ref('')
const aiEditing = ref(false)

// ────────── 加载 ──────────

onMounted(async () => {
  if (!store.agents.length) store.fetchAgents()
  if (rawId.value !== 'new') {
    const idNum = Number(rawId.value)
    if (Number.isNaN(idNum)) {
      loadError.value = '无效的工作流 ID'
      return
    }
    try {
      const res = await getWorkflow(idNum)
      applyWorkflow(res.data)
    } catch (e) {
      console.error('Failed to load workflow:', e)
      loadError.value = '加载工作流失败'
    }
  }
})

function applyWorkflow(wf: WorkflowDef) {
  workflowId.value = wf.id
  name.value = wf.name
  description.value = wf.description || ''
  category.value = wf.category || 'CUSTOM'
  status.value = wf.status
  store.currentWorkflow = wf
  try {
    const def: WorkflowDefinition = JSON.parse(wf.definitionJson || '{}')
    nodes.value = (def.nodes || []).map((n) => ({
      id: n.id,
      name: n.name || '',
      agentId: n.agentId ?? null,
      promptTemplate: n.promptTemplate || '',
      timeoutSeconds: n.timeoutSeconds || 300,
      dependsOn: Array.isArray(n.dependsOn) ? [...n.dependsOn] : []
    }))
  } catch (e) {
    console.error('Failed to parse definitionJson:', e)
    nodes.value = []
  }
}

// ────────── 节点编辑 ──────────

function nextNodeId(): string {
  let i = 1
  const ids = new Set(nodes.value.map((n) => n.id))
  while (ids.has(`n${i}`)) i++
  return `n${i}`
}

function addNode() {
  nodes.value.push({
    id: nextNodeId(),
    name: `节点 ${nodes.value.length + 1}`,
    agentId: null,
    promptTemplate: '',
    timeoutSeconds: 300,
    dependsOn: []
  })
}

function removeNode(idx: number) {
  const removed = nodes.value[idx]
  nodes.value.splice(idx, 1)
  // 清理其他节点对已删节点的依赖
  for (const n of nodes.value) {
    n.dependsOn = n.dependsOn.filter((d) => d !== removed.id)
  }
}

// ────────── JSON 预览/手动编辑 ──────────

const jsonEditMode = ref(false)
const jsonDraft = ref('')
const jsonError = ref('')

function buildDefinition(): WorkflowDefinition {
  return {
    name: name.value,
    nodes: nodes.value.map((n) => ({
      id: n.id,
      name: n.name,
      agentId: n.agentId,
      promptTemplate: n.promptTemplate,
      timeoutSeconds: n.timeoutSeconds,
      dependsOn: [...n.dependsOn]
    }))
  }
}

const definitionJsonPreview = computed(() => JSON.stringify(buildDefinition(), null, 2))

function enterJsonEdit() {
  jsonDraft.value = definitionJsonPreview.value
  jsonError.value = ''
  jsonEditMode.value = true
}

function cancelJsonEdit() {
  jsonEditMode.value = false
  jsonError.value = ''
}

function applyJsonEdit() {
  jsonError.value = ''
  let def: WorkflowDefinition
  try {
    def = JSON.parse(jsonDraft.value)
  } catch {
    jsonError.value = 'JSON 格式不合法，请检查'
    return
  }
  if (!def || !Array.isArray(def.nodes)) {
    jsonError.value = 'JSON 必须包含 nodes 数组'
    return
  }
  const err = validateNodes(def.nodes)
  if (err) {
    jsonError.value = err
    return
  }
  if (def.name) name.value = def.name
  nodes.value = def.nodes.map((n) => ({
    id: String(n.id),
    name: n.name || '',
    agentId: n.agentId ?? null,
    promptTemplate: n.promptTemplate || '',
    timeoutSeconds: n.timeoutSeconds || 300,
    dependsOn: Array.isArray(n.dependsOn) ? n.dependsOn.map(String) : []
  }))
  jsonEditMode.value = false
}

// ────────── 校验 ──────────

function validateNodes(list: WorkflowNodeDef[]): string {
  if (!list.length) return '至少需要一个节点'
  const ids = new Set<string>()
  for (const n of list) {
    if (!n.id) return '存在节点缺少 id'
    if (ids.has(n.id)) return `节点 id 重复：${n.id}`
    ids.add(n.id)
  }
  for (const n of list) {
    for (const dep of n.dependsOn || []) {
      if (dep === n.id) return `节点 ${n.id} 不能依赖自己`
      if (!ids.has(dep)) return `节点 ${n.id} 依赖的 ${dep} 不存在`
    }
  }
  return ''
}

// ────────── 保存 / 发布 ──────────

async function onSave() {
  const err = validateNodes(nodes.value)
  if (err) {
    alert('校验失败：' + err)
    return
  }
  saving.value = true
  try {
    const payload = {
      name: name.value.trim(),
      description: description.value.trim(),
      category: category.value,
      definitionJson: JSON.stringify(buildDefinition())
    }
    if (workflowId.value) {
      const res = await updateWorkflow(workflowId.value, payload)
      if (res.data) applyWorkflow(res.data)
    } else {
      const res = await createWorkflow(payload)
      if (res.data?.id) {
        applyWorkflow(res.data)
        router.replace(`/workflow/${res.data.id}/edit`)
      }
    }
    alert('保存成功')
  } catch (e) {
    console.error('Failed to save workflow:', e)
    alert('保存失败，请稍后重试')
  } finally {
    saving.value = false
  }
}

async function onPublish() {
  if (!workflowId.value) return
  if (!confirm(`确认发布工作流「${name.value}」？请先保存最新修改。`)) return
  publishing.value = true
  try {
    await publishWorkflow(workflowId.value)
    status.value = 'PUBLISHED'
    alert('发布成功')
  } catch (e) {
    console.error('Failed to publish workflow:', e)
    alert('发布失败，请稍后重试')
  } finally {
    publishing.value = false
  }
}

// ────────── AI 修改 ──────────

async function onAiEdit() {
  if (!workflowId.value || !aiInstruction.value.trim()) return
  aiEditing.value = true
  try {
    const res = await aiEditWorkflow(workflowId.value, aiInstruction.value.trim())
    if (res.data) {
      applyWorkflow(res.data)
      aiInstruction.value = ''
    }
  } catch (e) {
    console.error('Failed to AI-edit workflow:', e)
    alert('AI 修改失败，请稍后重试')
  } finally {
    aiEditing.value = false
  }
}

// ────────── 执行 ──────────

const showExecuteModal = ref(false)
const executeInputKeys = ref<string[]>([])
const executeInputs = reactive<Record<string, string>>({})
const executing = ref(false)

function openExecuteModal() {
  const nodeIds = new Set(nodes.value.map((n) => n.id))
  const keys = new Set<string>()
  for (const node of nodes.value) {
    // 与后端 AgentTaskDelegate 一致：仅识别 ${var} 与 ${nodeId.output}
    const matches = (node.promptTemplate || '').matchAll(/\$\{\s*([A-Za-z0-9_-]+)(\.output)?\s*\}/g)
    for (const m of matches) {
      if (m[2] && nodeIds.has(m[1])) continue // 上游输出引用
      if (m[1].startsWith('_')) continue // 下划线开头：后端预注入变量（如 _kline_context），无需用户填写
      keys.add(m[1])
    }
  }
  executeInputKeys.value = Array.from(keys)
  Object.keys(executeInputs).forEach((k) => delete executeInputs[k])
  executeInputKeys.value.forEach((k) => { executeInputs[k] = '' })
  showExecuteModal.value = true
}

async function onExecute() {
  if (!workflowId.value) return
  executing.value = true
  try {
    const res = await executeWorkflow(workflowId.value, { ...executeInputs })
    const pid = res.data?.processInstanceId
    showExecuteModal.value = false
    if (pid) router.push(`/workflow/execution/${pid}`)
  } catch (e) {
    console.error('Failed to execute workflow:', e)
    alert('执行失败，请稍后重试')
  } finally {
    executing.value = false
  }
}
</script>
