<template>
  <div class="analysis-dashboard">
    <!-- K-line chart -->
    <div class="dashboard-kline">
      <KlineChart
        :klineData="klineData"
        :stockName="stockName"
        :stockCode="stockCode"
        :loading="klineLoading"
      />
    </div>

    <!-- Progress bar -->
    <div class="dashboard-progress">
      <div class="progress-bar">
        <div class="progress-fill" :style="{ width: progressPercent + '%' }"></div>
      </div>
      <span class="progress-text">{{ completedCount }} / {{ totalCount }} 完成</span>
      <span v-if="sessionStatus" class="progress-status">{{ sessionStatusText }}</span>
    </div>

    <!-- Analysis cards grid — v-memo skips re-render for completed cards -->
    <div class="dashboard-grid">
      <AnalysisCard
        v-for="item in directionList"
        :key="item.key"
        v-memo="[item.state.status, item.state.thinkingText.length, item.state.thinkingEvents.length, item.state.finalResult.length]"
        :title="item.state.displayName"
        :status="item.state.status"
        :thinkingEvents="item.state.thinkingEvents"
        :thinkingText="item.state.thinkingText"
        :streamingText="item.state.streamingText"
        :finalResult="item.state.finalResult"
        :errorMessage="item.state.errorMessage"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import KlineChart from './KlineChart.vue'
import AnalysisCard from './AnalysisCard.vue'
import { getKline } from '../api/stock'
import type { DirectionState } from '../types/analysis'

const props = defineProps<{
  stockCode: string
  stockName: string
  directionList: { key: string; state: DirectionState }[]
  completedCount: number
  totalCount: number
  sessionStatus: string
}>()

interface KlineItem {
  tradeDate: string
  open: number
  high: number
  low: number
  close: number
  volume: number
}

const klineData = ref<KlineItem[]>([])
const klineLoading = ref(false)

const progressPercent = computed(() =>
  props.totalCount > 0 ? Math.round((props.completedCount / props.totalCount) * 100) : 0
)

const sessionStatusText = computed(() => {
  switch (props.sessionStatus) {
    case 'COMPLETED': return '分析全部完成'
    case 'PARTIAL_FAILED': return '部分分析失败'
    case 'FAILED': return '分析失败'
    default: return ''
  }
})

onMounted(async () => {
  klineLoading.value = true
  try {
    const res = await getKline(props.stockCode)
    klineData.value = (res.data || []).map((k: Record<string, unknown>) => ({
      tradeDate: k.tradeDate as string,
      open: Number(k.open) || 0,
      high: Number(k.high) || 0,
      low: Number(k.low) || 0,
      close: Number(k.close) || 0,
      volume: Number(k.volume) || 0
    })).reverse()
  } catch (e) {
    console.error('Failed to load kline data:', e)
  } finally {
    klineLoading.value = false
  }
})
</script>
