<template>
  <div class="kline-chart-wrapper">
    <div v-if="klineData.length === 0" class="kline-empty">
      <span v-if="loading">加载K线数据中...</span>
      <span v-else>暂无K线数据</span>
    </div>
    <div v-else ref="chartRef" style="width:100%;height:450px"></div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, watch, onUnmounted } from 'vue'
import * as echarts from 'echarts'

interface KlineData {
  tradeDate: string
  open: number
  high: number
  low: number
  close: number
  volume: number
}

const props = defineProps<{
  klineData: KlineData[]
  stockName: string
  stockCode: string
  loading?: boolean
}>()

const chartRef = ref<HTMLElement | null>(null)
let chart: echarts.ECharts | null = null

function initChart() {
  if (!chartRef.value || props.klineData.length === 0) return
  if (chart) chart.dispose()
  chart = echarts.init(chartRef.value)

  const dates = props.klineData.map(k => k.tradeDate).reverse()
  const ohlc = props.klineData.map(k => [k.open, k.close, k.low, k.high]).reverse()
  const volumes = props.klineData.map(k => k.volume).reverse()

  chart.setOption({
    animation: false,
    title: {
      text: `${props.stockName} (${props.stockCode})`,
      left: 'center',
      textStyle: { fontSize: 16 }
    },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'cross' }
    },
    grid: [
      { left: '8%', right: '2%', top: '15%', height: '55%' },
      { left: '8%', right: '2%', top: '75%', height: '15%' }
    ],
    xAxis: [
      { type: 'category', data: dates, gridIndex: 0, axisLabel: { show: false } },
      { type: 'category', data: dates, gridIndex: 1, axisLabel: { rotate: 30, fontSize: 10 } }
    ],
    yAxis: [
      { type: 'value', gridIndex: 0, scale: true, splitArea: { show: true } },
      { type: 'value', gridIndex: 1, scale: true }
    ],
    series: [
      {
        type: 'candlestick',
        data: ohlc,
        xAxisIndex: 0,
        yAxisIndex: 0,
        itemStyle: {
          color: '#e02020',
          color0: '#20a020',
          borderColor: '#e02020',
          borderColor0: '#20a020'
        }
      },
      {
        type: 'bar',
        data: volumes,
        xAxisIndex: 1,
        yAxisIndex: 1,
        itemStyle: {
          color: (params: { dataIndex: number }) => {
            const d = ohlc[params.dataIndex]
            if (!d) return '#999'
            return d[1] >= d[0] ? '#e02020' : '#20a020'
          }
        }
      }
    ],
    dataZoom: [
      { type: 'inside', xAxisIndex: [0, 1], start: 50, end: 100 },
      { type: 'slider', xAxisIndex: [0, 1], start: 50, end: 100, bottom: 5 }
    ]
  })
}

onMounted(() => {
  if (props.klineData.length > 0) initChart()
})

watch(() => props.klineData, (val) => {
  if (val.length > 0) initChart()
  else { chart?.dispose(); chart = null }
})

onUnmounted(() => {
  chart?.dispose()
})
</script>

<style scoped>
.kline-chart-wrapper {
  min-height: 48px;
}
.kline-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 48px;
  color: #999;
  font-size: 14px;
  border: 1px dashed #ddd;
  border-radius: 6px;
  margin-bottom: 8px;
}
</style>