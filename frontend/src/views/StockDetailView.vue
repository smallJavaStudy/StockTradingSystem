<template>
  <div class="stock-detail">
    <router-link to="/" class="back">← 返回列表</router-link>
    <h1 v-if="data.basic">{{ data.basic.name }} ({{ data.basic.code }})</h1>
    <h1 v-else>{{ code }}</h1>

    <!-- 页签导航（Task #23 个股深度增强） -->
    <div class="wf-tabs">
      <button v-for="t in TABS" :key="t.key" class="wf-tab"
              :class="{ active: activeTab === t.key }" @click="activeTab = t.key">{{ t.label }}</button>
    </div>

    <!-- 概览 -->
    <div v-show="activeTab === 'overview'">
      <!-- 实时行情 -->
      <section v-if="data.quote">
        <h2>实时行情</h2>
        <div class="quote-grid">
          <div class="card"><span>现价</span><strong>{{ data.quote.price?.toFixed(2) }}</strong></div>
          <div class="card" :class="data.quote.changePct >= 0 ? 'up' : 'down'">
            <span>涨跌幅</span><strong>{{ data.quote.changePct?.toFixed(2) }}%</strong>
          </div>
          <div class="card"><span>开盘</span><strong>{{ data.quote.open?.toFixed(2) }}</strong></div>
          <div class="card"><span>最高</span><strong>{{ data.quote.high?.toFixed(2) }}</strong></div>
          <div class="card"><span>最低</span><strong>{{ data.quote.low?.toFixed(2) }}</strong></div>
          <div class="card"><span>昨收</span><strong>{{ data.quote.preClose?.toFixed(2) }}</strong></div>
          <div class="card"><span>成交量</span><strong>{{ fmtVol(data.quote.volume) }}</strong></div>
          <div class="card"><span>成交额</span><strong>{{ fmtAmt(data.quote.amount) }}</strong></div>
        </div>
      </section>

      <!-- 基本面 -->
      <section v-if="data.finances?.length">
        <h2>基本面</h2>
        <table>
          <thead><tr><th>报告期</th><th>EPS</th><th>ROE</th><th>营收(亿)</th><th>净利润(亿)</th></tr></thead>
          <tbody>
            <tr v-for="f in data.finances" :key="f.reportDate">
              <td>{{ f.reportDate }}</td>
              <td>{{ f.basicEps }}</td>
              <td>{{ f.weightedRoe }}%</td>
              <td>{{ (f.totalRevenue / 1e8).toFixed(2) }}</td>
              <td>{{ (f.netProfit / 1e8).toFixed(2) }}</td>
            </tr>
          </tbody>
        </table>
      </section>

      <!-- K线 -->
      <section v-if="data.klines?.length">
        <h2>日K线 (最近 {{ data.klines.length }} 日)</h2>
        <div class="kline-table" style="max-height:400px;overflow-y:auto">
          <table>
            <thead><tr><th>日期</th><th>开</th><th>高</th><th>低</th><th>收</th><th>成交量</th></tr></thead>
            <tbody>
              <tr v-for="k in data.klines" :key="k.tradeDate">
                <td>{{ k.tradeDate }}</td>
                <td>{{ k.open?.toFixed(2) }}</td>
                <td>{{ k.high?.toFixed(2) }}</td>
                <td>{{ k.low?.toFixed(2) }}</td>
                <td :class="k.close >= k.open ? 'up' : 'down'">{{ k.close?.toFixed(2) }}</td>
                <td>{{ fmtVol(k.volume) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <div v-if="!data.quote && !data.finances?.length && !data.klines?.length" class="empty">
        暂未拉取数据，请先通过 data-fetcher 采集数据
      </div>
    </div>

    <!-- 增强页签：切换后才挂载，避免一次性拉全部数据 -->
    <HoldersPanel v-if="visited.holders" v-show="activeTab === 'holders'" :code="code" />
    <ComparisonPanel v-if="visited.comparison" v-show="activeTab === 'comparison'" :code="code" />
    <NotesPanel v-if="visited.notes" v-show="activeTab === 'notes'" :code="code" />
    <AlertsPanel v-if="visited.alerts" v-show="activeTab === 'alerts'" :code="code" />
    <LhbPanel v-if="visited.lhb" v-show="activeTab === 'lhb'" :code="code" />
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, watch, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { getStockFull } from '../api/stock'
import HoldersPanel from '../components/stock/HoldersPanel.vue'
import ComparisonPanel from '../components/stock/ComparisonPanel.vue'
import NotesPanel from '../components/stock/NotesPanel.vue'
import AlertsPanel from '../components/stock/AlertsPanel.vue'
import LhbPanel from '../components/stock/LhbPanel.vue'

type TabKey = 'overview' | 'holders' | 'comparison' | 'notes' | 'alerts' | 'lhb'

const TABS: { key: TabKey; label: string }[] = [
  { key: 'overview', label: '概览' },
  { key: 'holders', label: '股东' },
  { key: 'comparison', label: '对标' },
  { key: 'notes', label: '笔记' },
  { key: 'alerts', label: '预警' },
  { key: 'lhb', label: '龙虎榜' }
]

const route = useRoute()
const code = route.params.code as string
const data = ref<any>({})
const activeTab = ref<TabKey>('overview')

// 记录访问过的页签：首次切换才挂载组件（懒加载），之后 v-show 保留状态
const visited = reactive<Record<string, boolean>>({})
watch(activeTab, (t) => { visited[t] = true }, { immediate: true })

function fmtVol(v: number) { return v ? (v / 10000).toFixed(1) + '万手' : '-' }
function fmtAmt(v: number) { return v ? (v / 1e8).toFixed(2) + '亿' : '-' }

onMounted(async () => {
  try {
    const res = await getStockFull(code)
    data.value = res.data
  } catch (e) {
    console.error('加载失败', e)
  }
})
</script>
