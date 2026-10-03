<template>
  <div class="thinking-process">
    <div class="thinking-header" @click="expanded = !expanded">
      <span class="thinking-title">
        <span v-if="loading" class="spinner"></span>
        思考过程
      </span>
      <span class="thinking-toggle">{{ expanded ? '收起' : '展开' }}</span>
    </div>
    <div v-show="expanded" class="thinking-body" ref="bodyRef">
      <div v-if="!thinkingText && events.length === 0 && loading" class="thinking-empty">等待分析开始...</div>

      <!-- Flowing thinking text -->
      <div v-if="thinkingText" class="thinking-flow">{{ thinkingText }}</div>

      <!-- Discrete events: tool calls, agent lifecycle -->
      <div v-for="(event, i) in events" :key="'e'+i" class="thinking-event">
        <div v-if="event.eventType === 'TOOL_CALL_START'" class="event-tool-call">
          <span class="event-label tag">调用工具</span>
          <span class="event-tool-name">{{ event.content }}</span>
        </div>
        <div v-else-if="event.eventType === 'TOOL_CALL_ARGS'" class="event-tool-args">
          <span class="event-label">参数</span>
          <code>{{ event.content }}</code>
        </div>
        <div v-else-if="event.eventType === 'TOOL_RESULT_DELTA'" class="event-tool-result">
          <span class="event-label">结果</span>
          <code class="result-content">{{ truncate(event.content, 500) }}</code>
        </div>
        <div v-else-if="event.eventType === 'TOOL_RESULT_END'" class="event-tool-status">
          <span class="event-label tag" :class="getState(event) === 'SUCCESS' ? 'tag-success' : 'tag-error'">
            {{ getState(event) === 'SUCCESS' ? '完成' : '失败' }}
          </span>
        </div>
        <div v-else-if="event.eventType === 'MODEL_CALL_END'" class="event-model-stats">
          <span class="event-label">Token</span>
          <span class="token-stats">
            输入 {{ formatTokens(event.meta?.inputTokens) }} /
            输出 {{ formatTokens(event.meta?.outputTokens) }}
          </span>
        </div>
        <div v-else-if="event.eventType === 'AGENT_START'" class="event-status">
          <span class="event-label tag tag-info">启动</span>
          <span>{{ event.content }}</span>
        </div>
        <div v-else-if="event.eventType === 'AGENT_COMPLETE'" class="event-status">
          <span class="event-label tag tag-success">完成</span>
          <span>分析完成</span>
        </div>
        <div v-else-if="event.eventType === 'AGENT_ERROR'" class="event-status">
          <span class="event-label tag tag-error">错误</span>
          <span>{{ event.content }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch, nextTick } from 'vue'
import type { ThinkingEvent } from '../types/analysis'

const props = defineProps<{
  events: ThinkingEvent[]
  thinkingText: string
  loading: boolean
}>()

const expanded = ref(true)
const bodyRef = ref<HTMLElement | null>(null)

// Debounced auto-scroll (max once per 100ms)
let scrollTimer: ReturnType<typeof setTimeout> | null = null
watch(() => props.thinkingText.length + props.events.length, async () => {
  if (!expanded.value) return
  if (scrollTimer) return // already scheduled
  scrollTimer = setTimeout(async () => {
    scrollTimer = null
    await nextTick()
    if (bodyRef.value) {
      bodyRef.value.scrollTop = bodyRef.value.scrollHeight
    }
  }, 100)
})

function getState(event: ThinkingEvent): string {
  return (event.meta?.state as string) || ''
}

function formatTokens(tokens: unknown): string {
  const n = Number(tokens)
  if (!n || n <= 0) return '0'
  if (n >= 1000) return (n / 1000).toFixed(1) + 'k'
  return String(n)
}

function truncate(text: string, maxLen: number): string {
  if (!text) return ''
  if (text.length <= maxLen) return text
  return text.substring(0, maxLen) + '...'
}
</script>
