<template>
  <div class="block-trade-panel">
    <div class="panel-toolbar">
      <MarketDatePicker type="block" @change="onDateChange" />
      <span v-if="items.length" class="panel-summary">共 {{ items.length }} 笔大宗交易</span>
    </div>

    <div v-if="loading" class="panel-empty"><span class="spinner"></span> 加载大宗交易数据中...</div>
    <div v-else-if="error" class="panel-empty">加载失败：{{ error }}</div>
    <div v-else-if="!items.length" class="panel-empty">当日暂无大宗交易数据（后端接口可能未就绪）</div>

    <table v-else>
      <thead>
        <tr>
          <th>代码</th>
          <th>名称</th>
          <th>成交价</th>
          <th>成交量</th>
          <th>成交金额</th>
          <th>折溢价率</th>
          <th>买方营业部</th>
          <th>卖方营业部</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="(row, i) in items" :key="`${row.code}-${i}`" class="clickable" @click="goDetail(row.code)">
          <td>{{ row.code }}</td>
          <td>{{ row.name }}</td>
          <td>{{ row.price?.toFixed(2) }}</td>
          <td>{{ fmtVol(row.volume) }}</td>
          <td>{{ fmtAmt(row.amount) }}</td>
          <td>
            <span class="premium-badge" :class="premiumClass(row.premiumRate)">
              {{ row.premiumRate > 0 ? '+' : '' }}{{ row.premiumRate?.toFixed(2) }}%
            </span>
          </td>
          <td class="branch" :title="row.buyerBranch">{{ row.buyerBranch || '-' }}</td>
          <td class="branch" :title="row.sellerBranch">{{ row.sellerBranch || '-' }}</td>
        </tr>
      </tbody>
    </table>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { getBlockTrade, type BlockTradeItem } from '../../api/market'
import MarketDatePicker from './MarketDatePicker.vue'

const router = useRouter()
const items = ref<BlockTradeItem[]>([])
const loading = ref(false)
const error = ref('')

// 溢价红 / 折价绿 / 平价灰
function premiumClass(rate: number) {
  if (rate > 0) return 'premium-up'
  if (rate < 0) return 'premium-down'
  return 'premium-flat'
}

function fmtVol(v: number) { return v ? (v / 10000).toFixed(2) + '万股' : '-' }
function fmtAmt(v: number) {
  if (v == null) return '-'
  return v >= 1e8 ? (v / 1e8).toFixed(2) + '亿' : (v / 1e4).toFixed(0) + '万'
}

function goDetail(code: string) {
  router.push(`/stock/${code}`)
}

async function onDateChange(date: string) {
  loading.value = true
  error.value = ''
  try {
    const res = await getBlockTrade(date)
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
.premium-badge { display: inline-block; padding: 2px 10px; border-radius: 10px; font-size: 12px; font-weight: 600; }
.premium-up { background: #fff1f0; color: #e02020; }
.premium-down { background: #e6ffed; color: #20a020; }
.premium-flat { background: #f0f0f0; color: #999; }
.branch { max-width: 220px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 12px; color: #666; }
</style>
