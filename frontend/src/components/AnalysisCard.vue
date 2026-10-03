<template>
  <div class="analysis-card" :class="'status-' + status">
    <div class="card-header">
      <span class="card-title">{{ title }}</span>
      <span class="card-status">
        <span v-if="status === 'loading'" class="spinner"></span>
        <span v-else-if="status === 'running'" class="spinner"></span>
        <span v-else-if="status === 'completed'" class="icon-success">&#10003;</span>
        <span v-else-if="status === 'error'" class="icon-error">&#10007;</span>
        <span v-else class="icon-idle">-</span>
      </span>
    </div>
    <div class="card-body">
      <!-- Loading skeleton -->
      <div v-if="status === 'idle' || status === 'loading'" class="card-placeholder">
        等待开始...
      </div>
      <!-- Running: show thinking process -->
      <div v-else-if="status === 'running'">
        <ThinkingProcess :events="thinkingEvents" :thinkingText="thinkingText" :loading="true" />
      </div>
      <!-- Completed: show final result -->
      <div v-else-if="status === 'completed'" class="card-result">
        <div class="result-text">{{ parsedContent }}</div>
        <details class="result-thinking-review">
          <summary>查看思考过程 ({{ thinkingEvents.length }} 个步骤)</summary>
          <ThinkingProcess :events="thinkingEvents" :thinkingText="thinkingText" :loading="false" />
        </details>
      </div>
      <!-- Error: still show thinking process if available -->
      <div v-else-if="status === 'error'" class="card-error">
        <p class="error-message">{{ errorMessage || '分析失败' }}</p>
        <details v-if="thinkingText || thinkingEvents.length > 0" class="result-thinking-review">
          <summary>查看思考过程 ({{ thinkingEvents.length }} 个步骤)</summary>
          <ThinkingProcess :events="thinkingEvents" :thinkingText="thinkingText" :loading="false" />
        </details>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import ThinkingProcess from './ThinkingProcess.vue'
import type { ThinkingEvent, AnalysisStatus } from '../types/analysis'

const props = defineProps<{
  title: string
  status: AnalysisStatus
  thinkingEvents: ThinkingEvent[]
  thinkingText: string
  streamingText: string
  finalResult: string
  errorMessage: string
}>()

const parsedContent = computed(() => {
  if (!props.finalResult) return props.streamingText || '等待分析结果...'
  try {
    const obj = JSON.parse(props.finalResult)
    // Extract summary fields for display
    const summaryKey = Object.keys(obj).find(k => k.endsWith('_summary') || k === 'conclusion' || k === 'summary')
    if (summaryKey && obj[summaryKey]) return String(obj[summaryKey])
    // If only one meaningful text field, show it
    const textFields = Object.entries(obj).filter(([, v]) => typeof v === 'string' && (v as string).length > 20)
    if (textFields.length === 1) return textFields[0][1] as string
    // Otherwise show compact JSON
    const compact: Record<string, unknown> = {}
    for (const [k, v] of Object.entries(obj)) {
      if (typeof v === 'string' && (v as string).length > 200) {
        compact[k] = (v as string).substring(0, 200) + '...'
      } else if (v !== null && v !== undefined && v !== '') {
        compact[k] = v
      }
    }
    return JSON.stringify(compact, null, 1)
  } catch {
    // Not valid JSON, show truncated raw text
    return props.finalResult.length > 500
      ? props.finalResult.substring(0, 500) + '...'
      : props.finalResult
  }
})
</script>
