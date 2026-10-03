<template>
  <div class="lhb-panel">
    <h2>龙虎榜上榜记录</h2>
    <div v-if="loading" class="panel-empty">加载龙虎榜记录中...</div>
    <div v-else-if="records.length === 0" class="panel-empty">该股暂无龙虎榜上榜记录</div>
    <template v-else>
      <p class="lhb-summary">共 {{ records.length }} 条上榜记录（同日多榜单会有多条）</p>
      <table>
        <thead>
          <tr><th>日期</th><th>收盘价</th><th>涨跌幅</th><th>上榜原因</th>
              <th>买入额(万)</th><th>卖出额(万)</th><th>净买额(万)</th><th>解读</th></tr>
        </thead>
        <tbody>
          <tr v-for="r in records" :key="r.id">
            <td>{{ r.tradeDate }}</td>
            <td>{{ r.closePrice != null ? Number(r.closePrice).toFixed(2) : '-' }}</td>
            <td :class="pctClass(r.changePct)">{{ r.changePct != null ? Number(r.changePct).toFixed(2) + '%' : '-' }}</td>
            <td class="reason">{{ r.rankReason || '-' }}</td>
            <td>{{ fmtWan(r.buyAmount) }}</td>
            <td>{{ fmtWan(r.sellAmount) }}</td>
            <td :class="pctClass(r.netAmount)">{{ fmtWan(r.netAmount) }}</td>
            <td class="reason">{{ r.interpretation || '-' }}</td>
          </tr>
        </tbody>
      </table>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { getStockLhb, type LhbRecord } from '../../api/stockDetail'

const props = defineProps<{ code: string }>()

const loading = ref(true)
const records = ref<LhbRecord[]>([])

function fmtWan(v: number | null): string {
  return v != null ? (Number(v) / 10000).toFixed(1) : '-'
}

function pctClass(v: number | null): string {
  if (v == null) return ''
  return Number(v) > 0 ? 'up' : Number(v) < 0 ? 'down' : ''
}

onMounted(async () => {
  try {
    const res = await getStockLhb(props.code)
    records.value = res.data
  } catch (e) {
    console.error('龙虎榜记录加载失败', e)
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.lhb-summary { color: #999; font-size: 13px; margin-bottom: 10px; }
.reason { max-width: 260px; font-size: 13px; }
</style>
