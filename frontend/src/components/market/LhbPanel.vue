<template>
  <div class="lhb-panel">
    <div class="panel-toolbar">
      <MarketDatePicker type="lhb" @change="onDateChange" />
      <span v-if="items.length" class="panel-summary">共 {{ items.length }} 条上榜记录</span>
    </div>

    <div v-if="loading" class="panel-empty"><span class="spinner"></span> 加载龙虎榜数据中...</div>
    <div v-else-if="error" class="panel-empty">加载失败：{{ error }}</div>
    <div v-else-if="!items.length" class="panel-empty">当日暂无龙虎榜数据（后端接口可能未就绪）</div>

    <table v-else>
      <thead>
        <tr>
          <th>代码</th>
          <th>名称</th>
          <th>涨跌幅</th>
          <th>上榜原因</th>
          <th>买入金额</th>
          <th>卖出金额</th>
          <th class="sortable" @click="toggleSort">
            净买入 <span class="sort-icon">{{ sortDesc ? '▼' : '▲' }}</span>
          </th>
          <th>成交总额</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="(row, i) in sortedItems" :key="`${row.code}-${row.rankReason}-${i}`" class="clickable" @click="goDetail(row.code)">
          <td>{{ row.code }}</td>
          <td>{{ row.name }}</td>
          <td :class="row.changePct >= 0 ? 'up' : 'down'">{{ row.changePct >= 0 ? '+' : '' }}{{ row.changePct?.toFixed(2) }}%</td>
          <td class="lhb-reason" :title="row.rankReason">{{ row.rankReason }}</td>
          <td>{{ fmtAmt(row.buyAmount) }}</td>
          <td>{{ fmtAmt(row.sellAmount) }}</td>
          <td :class="row.netAmount >= 0 ? 'up' : 'down'"><strong>{{ fmtAmt(row.netAmount) }}</strong></td>
          <td>{{ fmtAmt(row.totalAmount) }}</td>
        </tr>
      </tbody>
    </table>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { getLhb, type LhbItem } from '../../api/market'
import MarketDatePicker from './MarketDatePicker.vue'

const router = useRouter()
const items = ref<LhbItem[]>([])
const loading = ref(false)
const error = ref('')
const sortDesc = ref(true)

const sortedItems = computed(() =>
  [...items.value].sort((a, b) => sortDesc.value ? b.netAmount - a.netAmount : a.netAmount - b.netAmount)
)

function toggleSort() { sortDesc.value = !sortDesc.value }

function fmtAmt(v: number) {
  if (v == null) return '-'
  const abs = Math.abs(v)
  if (abs >= 1e8) return (v / 1e8).toFixed(2) + '亿'
  if (abs >= 1e4) return (v / 1e4).toFixed(0) + '万'
  return v.toFixed(0)
}

function goDetail(code: string) {
  router.push(`/stock/${code}`)
}

async function onDateChange(date: string) {
  loading.value = true
  error.value = ''
  try {
    const res = await getLhb(date)
    items.value = res.data || []
  } catch (e: any) {
    items.value = []
    // 404/接口未部署（No static resource）按空态处理，仅真实错误提示
    const msg: string = e?.response?.data?.message || ''
    if (e?.response?.status && e.response.status !== 404 && !msg.includes('No static resource')) {
      error.value = msg || e.message
    }
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.sortable { cursor: pointer; user-select: none; white-space: nowrap; }
.sortable:hover { color: #1890ff; }
.sort-icon { font-size: 10px; color: #1890ff; }
.lhb-reason { max-width: 260px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
</style>
