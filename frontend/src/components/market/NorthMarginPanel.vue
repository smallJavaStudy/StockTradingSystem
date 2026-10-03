<template>
  <div class="north-margin-panel">
    <!-- 北向资金 -->
    <section class="chart-section">
      <h2>北向资金净流入（近 {{ days }} 日）</h2>
      <div v-if="northLoading" class="panel-empty"><span class="spinner"></span> 加载北向资金数据中...</div>
      <div v-else-if="!northData.length" class="panel-empty">暂无北向资金数据（后端接口可能未就绪）</div>
      <div v-show="northData.length && !northLoading" ref="northRef" class="chart-box"></div>
    </section>

    <!-- 两融余额 -->
    <section class="chart-section">
      <h2>两融余额趋势（近 {{ days }} 日）</h2>
      <div v-if="marginLoading" class="panel-empty"><span class="spinner"></span> 加载两融数据中...</div>
      <div v-else-if="!marginData.length" class="panel-empty">暂无两融数据（后端接口可能未就绪）</div>
      <div v-show="marginData.length && !marginLoading" ref="marginRef" class="chart-box"></div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted, nextTick } from 'vue'
import * as echarts from 'echarts'
import { getNorthFlow, getMargin, type NorthFlowItem, type MarginItem } from '../../api/market'

const days = 30
const northRef = ref<HTMLElement | null>(null)
const marginRef = ref<HTMLElement | null>(null)
const northData = ref<NorthFlowItem[]>([])
const marginData = ref<MarginItem[]>([])
const northLoading = ref(true)
const marginLoading = ref(true)

let northChart: echarts.ECharts | null = null
let marginChart: echarts.ECharts | null = null

function renderNorth() {
  if (!northRef.value || !northData.value.length) return
  if (northChart) northChart.dispose()
  northChart = echarts.init(northRef.value)

  const sorted = [...northData.value].sort((a, b) => a.tradeDate.localeCompare(b.tradeDate))
  const dates = sorted.map(d => d.tradeDate)
  const netFlows = sorted.map(d => +(d.netFlow / 1e8).toFixed(2))
  const accumFlows = sorted.map(d => +(d.accumFlow / 1e8).toFixed(2))

  northChart.setOption({
    animation: false,
    tooltip: { trigger: 'axis', valueFormatter: (v: number) => `${v} 亿` },
    legend: { data: ['当日净流入', '累计净流入'], top: 0 },
    grid: { left: '3%', right: '3%', top: 40, bottom: 24, containLabel: true },
    xAxis: { type: 'category', data: dates, axisLabel: { rotate: 30, fontSize: 10 } },
    yAxis: [
      { type: 'value', name: '净流入(亿)' },
      { type: 'value', name: '累计(亿)' }
    ],
    series: [
      {
        name: '当日净流入', type: 'bar', data: netFlows,
        itemStyle: { color: (p: { value: number }) => p.value >= 0 ? '#e02020' : '#20a020' }
      },
      { name: '累计净流入', type: 'line', yAxisIndex: 1, data: accumFlows, smooth: true, itemStyle: { color: '#1890ff' } }
    ]
  })
}

function renderMargin() {
  if (!marginRef.value || !marginData.value.length) return
  if (marginChart) marginChart.dispose()
  marginChart = echarts.init(marginRef.value)

  // 按日期聚合（沪深分市场数据求和）
  const byDate = new Map<string, { financing: number; securities: number; total: number }>()
  for (const m of marginData.value) {
    const agg = byDate.get(m.tradeDate) || { financing: 0, securities: 0, total: 0 }
    agg.financing += m.financingBalance || 0
    agg.securities += m.securitiesBalance || 0
    agg.total += m.totalBalance || 0
    byDate.set(m.tradeDate, agg)
  }
  const dates = [...byDate.keys()].sort()
  const toYi = (v: number) => +(v / 1e8).toFixed(2)
  const financing = dates.map(d => toYi(byDate.get(d)!.financing))
  const total = dates.map(d => toYi(byDate.get(d)!.total))

  marginChart.setOption({
    animation: false,
    tooltip: { trigger: 'axis', valueFormatter: (v: number) => `${v} 亿` },
    legend: { data: ['融资余额', '两融余额合计'], top: 0 },
    grid: { left: '3%', right: '3%', top: 40, bottom: 24, containLabel: true },
    xAxis: { type: 'category', data: dates, axisLabel: { rotate: 30, fontSize: 10 } },
    yAxis: { type: 'value', name: '余额(亿)', scale: true },
    series: [
      { name: '融资余额', type: 'line', data: financing, smooth: true, itemStyle: { color: '#fa8c16' } },
      { name: '两融余额合计', type: 'line', data: total, smooth: true, itemStyle: { color: '#722ed1' }, areaStyle: { opacity: 0.08 } }
    ]
  })
}

function onResize() {
  northChart?.resize()
  marginChart?.resize()
}

onMounted(async () => {
  try {
    const res = await getNorthFlow(days)
    northData.value = res.data || []
  } catch {
    northData.value = []
  } finally {
    northLoading.value = false
  }
  try {
    const res = await getMargin(days)
    marginData.value = res.data || []
  } catch {
    marginData.value = []
  } finally {
    marginLoading.value = false
  }
  await nextTick()
  renderNorth()
  renderMargin()
  window.addEventListener('resize', onResize)
})

onUnmounted(() => {
  window.removeEventListener('resize', onResize)
  northChart?.dispose()
  marginChart?.dispose()
})
</script>

<style scoped>
.chart-section { background: #fff; border-radius: 8px; padding: 16px; margin-bottom: 16px; box-shadow: 0 1px 4px rgba(0,0,0,0.06); }
.chart-section h2 { border: none; margin: 0 0 8px; padding: 0; }
.chart-box { width: 100%; height: 340px; }
</style>
