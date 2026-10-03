<template>
  <div class="workflow-execution">
    <!-- ══════════ 头部 ══════════ -->
    <div class="execution-header">
      <span class="back-btn" @click="router.push('/workflow')">&larr; 返回列表</span>
      <div class="execution-title">
        <h1>工作流执行</h1>
        <span class="wf-instance-id">实例: {{ processInstanceId }}</span>
      </div>
      <div class="execution-status">
        <span v-if="store.connectionMode === 'polling'" class="tag tag-purple">轮询模式</span>
        <span class="badge badge-lg" :class="'badge-' + overallStatus.toLowerCase()">
          <span v-if="overallStatus === 'RUNNING'" class="spinner"></span>
          {{ statusLabel(overallStatus) }}
        </span>
        <button v-if="overallStatus === 'RUNNING'" class="btn-danger" :disabled="cancelling" @click="onCancel">
          {{ cancelling ? '取消中...' : '取消执行' }}
        </button>
      </div>
    </div>

    <!-- ══════════ 节点时间线 ══════════ -->
    <div v-if="!store.nodeList.length" class="empty">
      <span class="spinner"></span> 等待节点执行数据...
    </div>
    <div v-else class="execution-timeline">
      <div v-for="node in store.nodeList" :key="node.nodeId"
           class="exec-node-card" :class="'node-' + nodeStatusClass(node.status)">
        <div class="exec-node-header" @click="toggleCollapse(node.nodeId)">
          <span class="exec-node-status">
            <span v-if="nodeStatusClass(node.status) === 'running'" class="spinner"></span>
            <span v-else-if="nodeStatusClass(node.status) === 'completed'" class="icon-success">✔</span>
            <span v-else-if="nodeStatusClass(node.status) === 'failed'" class="icon-error">✖</span>
            <span v-else class="icon-idle">○</span>
          </span>
          <span class="node-id-badge">{{ node.nodeId }}</span>
          <span class="exec-node-name">{{ node.nodeName }}</span>
          <span class="exec-node-elapsed">{{ elapsed(node) }}</span>
          <span class="thinking-toggle">{{ collapsed.has(node.nodeId) ? '▶ 展开' : '▼ 折叠' }}</span>
        </div>
        <div v-if="!collapsed.has(node.nodeId)" class="exec-node-body">
          <!-- 失败信息 -->
          <p v-if="node.errorMessage" class="card-error">{{ node.errorMessage }}</p>
          <!-- 完成后完整输出 -->
          <pre v-if="node.output" class="exec-node-output">{{ node.output }}</pre>
          <!-- 运行中实时增量流 -->
          <pre v-else-if="node.delta" class="exec-node-output streaming">{{ node.delta }}</pre>
          <p v-else-if="nodeStatusClass(node.status) === 'pending'" class="card-placeholder">等待执行...</p>
          <p v-else-if="nodeStatusClass(node.status) === 'running'" class="card-placeholder">运行中，等待输出...</p>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useWorkflowStore } from '../stores/workflow'
import type { NodeRuntimeState, ExecutionStatus } from '../types/workflow'

const route = useRoute()
const router = useRouter()
const store = useWorkflowStore()

const processInstanceId = computed(() => String(route.params.processInstanceId))
const cancelling = ref(false)
const collapsed = ref<Set<string>>(new Set())

const overallStatus = computed<ExecutionStatus | 'RUNNING'>(
  () => (store.executionStatus || 'RUNNING') as ExecutionStatus
)

const statusLabels: Record<string, string> = {
  RUNNING: '运行中', COMPLETED: '已完成', FAILED: '失败', CANCELLED: '已取消'
}
function statusLabel(s: string) { return statusLabels[s] || s }

function nodeStatusClass(status: string): 'pending' | 'running' | 'completed' | 'failed' {
  const s = (status || '').toUpperCase()
  if (s === 'RUNNING' || s === 'ACTIVE' || s === 'STARTED') return 'running'
  if (s === 'COMPLETED' || s === 'SUCCESS') return 'completed'
  if (s === 'FAILED' || s === 'ERROR' || s === 'CANCELLED') return 'failed'
  return 'pending'
}

function elapsed(node: NodeRuntimeState): string {
  if (!node.startedAt) return ''
  const start = new Date(node.startedAt).getTime()
  const end = node.completedAt ? new Date(node.completedAt).getTime() : Date.now()
  if (Number.isNaN(start) || Number.isNaN(end) || end < start) return ''
  const sec = Math.round((end - start) / 1000)
  return sec >= 60 ? `${Math.floor(sec / 60)}分${sec % 60}秒` : `${sec}秒`
}

function toggleCollapse(nodeId: string) {
  const set = new Set(collapsed.value)
  if (set.has(nodeId)) set.delete(nodeId)
  else set.add(nodeId)
  collapsed.value = set
}

async function onCancel() {
  if (!confirm('确认取消当前工作流执行？')) return
  cancelling.value = true
  try {
    await store.cancelCurrentExecution()
  } finally {
    cancelling.value = false
  }
}

onMounted(() => {
  // 先 GET 一次状态快照，再连 SSE 回放（store 内部处理）
  store.loadExecution(processInstanceId.value)
})

onUnmounted(() => {
  store.resetExecution()
})
</script>
