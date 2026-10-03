<template>
  <div class="zt-pool-panel">
    <div class="panel-toolbar">
      <MarketDatePicker type="zt" @change="onDateChange" />
      <span v-if="items.length" class="panel-summary">共 {{ items.length }} 只涨停</span>
    </div>

    <div v-if="loading" class="panel-empty"><span class="spinner"></span> 加载涨停数据中...</div>
    <div v-else-if="error" class="panel-empty">加载失败：{{ error }}</div>
    <div v-else-if="!items.length" class="panel-empty">当日暂无涨停数据（后端接口可能未就绪）</div>

    <template v-else>
      <section v-for="tier in tiers" :key="tier.label">
        <template v-if="tier.items.length">
          <h2 class="tier-title">{{ tier.label }} <span class="tier-count">{{ tier.items.length }} 只</span></h2>
          <div class="zt-grid">
            <div v-for="s in tier.items" :key="s.code" class="zt-card" @click="goDetail(s.code)">
              <div class="zt-card-head">
                <div>
                  <strong class="zt-name">{{ s.name }}</strong>
                  <span class="zt-code">{{ s.code }}</span>
                </div>
                <span class="zt-days" :class="{ 'zt-days-high': s.limitUpDays >= 3 }">{{ s.limitUpDays }} 连板</span>
              </div>
              <div class="zt-reason" :title="s.reason">{{ s.reason || '—' }}</div>
              <div class="zt-meta">
                <span v-if="s.industry" class="zt-industry">{{ s.industry }}</span>
                <span class="up">+{{ s.changePct?.toFixed(2) }}%</span>
              </div>
              <div class="zt-stats">
                <span>首封 {{ s.firstTime || '-' }}</span>
                <span>终封 {{ s.lastTime || '-' }}</span>
                <span :class="{ 'zt-open-warn': s.openTimes > 0 }">炸板 {{ s.openTimes ?? 0 }} 次</span>
              </div>
              <div class="zt-stats">
                <span>成交 {{ fmtAmt(s.amount) }}</span>
                <span>换手 {{ s.turnoverRate?.toFixed(2) }}%</span>
              </div>
            </div>
          </div>
        </template>
      </section>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { getZtPool, type ZtPoolItem } from '../../api/market'
import MarketDatePicker from './MarketDatePicker.vue'

const router = useRouter()
const items = ref<ZtPoolItem[]>([])
const loading = ref(false)
const error = ref('')

const tiers = computed(() => [
  { label: '🚀 3板及以上梯队', items: items.value.filter(s => s.limitUpDays >= 3).sort((a, b) => b.limitUpDays - a.limitUpDays) },
  { label: '⚡ 2板梯队', items: items.value.filter(s => s.limitUpDays === 2) },
  { label: '🔥 首板梯队', items: items.value.filter(s => s.limitUpDays <= 1) },
])

function fmtAmt(v: number) { return v ? (v / 1e8).toFixed(2) + '亿' : '-' }

function goDetail(code: string) {
  router.push(`/stock/${code}`)
}

async function onDateChange(date: string) {
  loading.value = true
  error.value = ''
  try {
    const res = await getZtPool(date)
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
.tier-title { display: flex; align-items: center; gap: 8px; }
.tier-count { font-size: 13px; color: #999; font-weight: 400; }
.zt-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(250px, 1fr)); gap: 12px; margin-bottom: 8px; }
.zt-card { background: #fff; border: 1px solid #eee; border-radius: 8px; padding: 12px 14px; cursor: pointer; transition: all 0.2s; }
.zt-card:hover { border-color: #e02020; transform: translateY(-1px); box-shadow: 0 2px 8px rgba(224,32,32,0.12); }
.zt-card-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 6px; }
.zt-name { font-size: 15px; }
.zt-code { color: #999; font-size: 12px; margin-left: 6px; }
.zt-days { background: #fff1f0; color: #e02020; font-size: 12px; font-weight: 600; padding: 2px 8px; border-radius: 10px; white-space: nowrap; }
.zt-days-high { background: #e02020; color: #fff; }
.zt-reason { font-size: 13px; color: #555; margin-bottom: 6px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.zt-meta { display: flex; justify-content: space-between; align-items: center; font-size: 13px; margin-bottom: 6px; }
.zt-industry { color: #1890ff; background: #f0f7ff; padding: 1px 8px; border-radius: 4px; font-size: 12px; }
.zt-stats { display: flex; gap: 12px; font-size: 12px; color: #999; margin-top: 2px; }
.zt-open-warn { color: #fa8c16; }
</style>
