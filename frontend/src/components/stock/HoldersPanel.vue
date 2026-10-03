<template>
  <div class="holders-panel">
    <div v-if="loading" class="panel-empty">加载股东数据中...</div>
    <template v-else>
      <!-- 十大股东 / 十大流通股东 -->
      <section>
        <div class="holder-type-switch">
          <h2 style="border:none;margin:0">{{ activeType === 'TOP10' ? '十大股东' : '十大流通股东' }}</h2>
          <div class="wf-tabs" style="margin:0">
            <button class="wf-tab" :class="{ active: activeType === 'TOP10' }" @click="activeType = 'TOP10'">十大股东</button>
            <button class="wf-tab" :class="{ active: activeType === 'TOP10_FLOAT' }" @click="activeType = 'TOP10_FLOAT'">十大流通股东</button>
          </div>
        </div>
        <div v-if="reportDates.length === 0" class="panel-empty">
          暂无十大股东数据，请先运行 data-fetcher/fetch_holders.py 采集
        </div>
        <template v-else>
          <div class="period-tabs">
            <button v-for="d in reportDates" :key="d" class="wf-tab" :class="{ active: d === activeDate }"
                    @click="activeDate = d">{{ d }}</button>
          </div>
          <table>
            <thead>
              <tr><th>名次</th><th>股东名称</th><th v-if="activeType === 'TOP10'">股东性质</th>
                  <th>持股数(万股)</th><th>持股比例</th><th>较上期增减</th></tr>
            </thead>
            <tbody>
              <tr v-for="h in activeRows" :key="h.id">
                <td>{{ h.holderRank }}</td>
                <td>{{ h.holderName }}</td>
                <td v-if="activeType === 'TOP10'">{{ h.holderNature || '-' }}</td>
                <td>{{ h.shares != null ? (h.shares / 10000).toFixed(2) : '-' }}</td>
                <td>{{ h.holdRatio != null ? h.holdRatio.toFixed(2) + '%' : '-' }}</td>
                <td :class="changeClass(h.changeDesc)">{{ changeText(h) }}</td>
              </tr>
            </tbody>
          </table>
        </template>
      </section>

      <!-- 股东户数趋势 -->
      <section>
        <h2>股东户数趋势</h2>
        <div v-if="counts.length === 0" class="panel-empty">暂无股东户数数据</div>
        <template v-else>
          <div ref="chartRef" style="width:100%;height:300px"></div>
          <table>
            <thead><tr><th>统计日期</th><th>股东户数</th><th>上期户数</th><th>增减比例</th><th>户均持股市值(元)</th></tr></thead>
            <tbody>
              <tr v-for="c in recentCounts" :key="c.id">
                <td>{{ c.statDate }}</td>
                <td>{{ fmtInt(c.holderCount) }}</td>
                <td>{{ fmtInt(c.prevCount) }}</td>
                <td :class="ratioClass(c.changeRatio)">{{ c.changeRatio != null ? c.changeRatio.toFixed(2) + '%' : '-' }}</td>
                <td>{{ fmtInt(c.avgHoldValue) }}</td>
              </tr>
            </tbody>
          </table>
        </template>
      </section>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch, nextTick } from 'vue'
import * as echarts from 'echarts'
import { getHolders, getHolderCount, type HolderTop, type HolderCount } from '../../api/stockDetail'

const props = defineProps<{ code: string }>()

const loading = ref(true)
const top10 = ref<HolderTop[]>([])
const top10Float = ref<HolderTop[]>([])
const counts = ref<HolderCount[]>([])
const activeType = ref<'TOP10' | 'TOP10_FLOAT'>('TOP10')
const activeDate = ref('')

const chartRef = ref<HTMLElement | null>(null)
let chart: echarts.ECharts | null = null

const typeRows = computed(() => (activeType.value === 'TOP10' ? top10.value : top10Float.value))

const reportDates = computed(() => {
  const dates = [...new Set(typeRows.value.map(h => h.reportDate))]
  return dates.sort((a, b) => b.localeCompare(a))
})

const activeRows = computed(() => typeRows.value.filter(h => h.reportDate === activeDate.value))

// 最近 12 期户数明细（表格倒序）
const recentCounts = computed(() => [...counts.value].reverse().slice(0, 12))

// 切换股东类型后，若当前报告期在新类型下不存在则回落到最新一期
watch([activeType, reportDates], () => {
  if (!reportDates.value.includes(activeDate.value)) {
    activeDate.value = reportDates.value[0] ?? ''
  }
})

function changeClass(desc: string | null): string {
  if (!desc) return ''
  if (desc === '不变') return ''
  if (desc === '新进') return 'up'
  const n = Number(desc)
  if (!Number.isNaN(n)) return n > 0 ? 'up' : n < 0 ? 'down' : ''
  return ''
}

function changeText(h: HolderTop): string {
  if (!h.changeDesc) return '-'
  if (h.changeDesc === '不变' || h.changeDesc === '新进') return h.changeDesc
  const n = Number(h.changeDesc)
  if (!Number.isNaN(n)) {
    const base = (n > 0 ? '+' : '') + (n / 10000).toFixed(2) + '万股'
    return h.changeRatio != null ? `${base} (${h.changeRatio > 0 ? '+' : ''}${h.changeRatio.toFixed(2)}%)` : base
  }
  return h.changeDesc
}

function ratioClass(v: number | null): string {
  if (v == null) return ''
  return v > 0 ? 'up' : v < 0 ? 'down' : ''
}

function fmtInt(v: number | null): string {
  return v != null ? Math.round(v).toLocaleString() : '-'
}

function renderChart() {
  if (!chartRef.value || counts.value.length === 0) return
  if (!chart) chart = echarts.init(chartRef.value)
  chart.setOption({
    animation: false,
    tooltip: { trigger: 'axis' },
    grid: { left: '10%', right: '4%', top: 30, bottom: 50 },
    xAxis: { type: 'category', data: counts.value.map(c => c.statDate), axisLabel: { rotate: 30, fontSize: 10 } },
    yAxis: { type: 'value', scale: true, name: '股东户数' },
    series: [{
      type: 'line',
      data: counts.value.map(c => c.holderCount),
      smooth: true,
      symbolSize: 5,
      lineStyle: { color: '#1890ff' },
      itemStyle: { color: '#1890ff' },
      areaStyle: { color: 'rgba(24,144,255,0.08)' }
    }],
    dataZoom: [{ type: 'inside' }]
  })
}

onMounted(async () => {
  try {
    const [holdersRes, countRes] = await Promise.all([
      getHolders(props.code, 4),
      getHolderCount(props.code)
    ])
    top10.value = holdersRes.data.top10
    top10Float.value = holdersRes.data.top10Float
    counts.value = countRes.data
    activeDate.value = reportDates.value[0] ?? ''
  } catch (e) {
    console.error('股东数据加载失败', e)
  } finally {
    loading.value = false
    await nextTick()
    renderChart()
  }
})

onUnmounted(() => { chart?.dispose(); chart = null })
</script>

<style scoped>
.holders-panel section { margin-bottom: 24px; }
.holder-type-switch { display: flex; justify-content: space-between; align-items: center; margin: 20px 0 12px; border-bottom: 1px solid #ddd; padding-bottom: 8px; }
.period-tabs { display: flex; gap: 6px; margin-bottom: 10px; flex-wrap: wrap; }
</style>
