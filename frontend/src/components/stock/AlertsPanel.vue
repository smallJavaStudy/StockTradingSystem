<template>
  <div class="alerts-panel">
    <!-- 技术信号 -->
    <section>
      <h2>当前技术信号</h2>
      <div v-if="signalLoading" class="panel-empty">计算技术信号中...</div>
      <div v-else-if="!signals || !signals.available" class="panel-empty">
        {{ signals?.message || '技术信号不可用（K线数据不足）' }}
      </div>
      <div v-else class="signal-grid">
        <div class="card">
          <span>MA5 / MA20（{{ signals.tradeDate }}）</span>
          <strong>{{ fmt(signals.ma5) }} / {{ fmt(signals.ma20) }}</strong>
        </div>
        <div class="card" :class="trendClass">
          <span>均线形态</span>
          <strong>{{ trendText }}</strong>
        </div>
        <div class="card" :class="{ up: signals.goldenCross, down: signals.deathCross }">
          <span>金叉/死叉</span>
          <strong>{{ signals.goldenCross ? '金叉 ✚' : signals.deathCross ? '死叉 ✖' : '无' }}</strong>
        </div>
        <div class="card" :class="{ up: signals.volumeSurge }">
          <span>放量（>5日均量2倍）</span>
          <strong>{{ signals.volumeSurge ? '放量' : '正常' }}</strong>
        </div>
      </div>
    </section>

    <!-- 预警设置 -->
    <section>
      <h2>价格预警</h2>
      <div class="alert-form">
        <select v-model="form.type">
          <option value="PRICE_ABOVE">价格突破（≥）</option>
          <option value="PRICE_BELOW">价格跌破（≤）</option>
        </select>
        <input v-model="form.threshold" type="number" min="0.01" step="0.01" placeholder="阈值价格（元）" />
        <button class="btn-primary" :disabled="!canCreate || saving" @click="create">添加预警</button>
        <span v-if="error" class="form-error">{{ error }}</span>
      </div>

      <div v-if="loading" class="panel-empty">加载预警列表中...</div>
      <div v-else-if="alerts.length === 0" class="panel-empty">暂无预警，添加一条价格预警吧（每5分钟自动扫描）</div>
      <table v-else>
        <thead>
          <tr><th>类型</th><th>阈值(元)</th><th>状态</th><th>触发时间</th><th>操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="a in alerts" :key="a.id">
            <td>{{ a.type === 'PRICE_ABOVE' ? '突破 ≥' : '跌破 ≤' }}</td>
            <td>{{ Number(a.threshold).toFixed(2) }}</td>
            <td>
              <span v-if="a.triggered" class="status-tag triggered">已触发</span>
              <span v-else-if="a.enabled" class="status-tag watching">监控中</span>
              <span v-else class="status-tag disabled">已停用</span>
            </td>
            <td>{{ a.triggeredAt ? fmtTime(a.triggeredAt) : '-' }}</td>
            <td class="ops">
              <button v-if="a.triggered" class="op-btn" @click="reset(a)">重置</button>
              <button v-else class="op-btn" @click="toggle(a)">{{ a.enabled ? '停用' : '启用' }}</button>
              <button class="op-btn danger" @click="remove(a)">删除</button>
            </td>
          </tr>
        </tbody>
      </table>
    </section>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { getAlerts, createAlert, updateAlert, deleteAlert, getSignals,
         type PriceAlert, type TechnicalSignals, type AlertType } from '../../api/stockDetail'

const props = defineProps<{ code: string }>()

const loading = ref(true)
const signalLoading = ref(true)
const saving = ref(false)
const error = ref('')
const alerts = ref<PriceAlert[]>([])
const signals = ref<TechnicalSignals | null>(null)
const form = ref<{ type: AlertType; threshold: string }>({ type: 'PRICE_ABOVE', threshold: '' })

const canCreate = computed(() => Number(form.value.threshold) > 0)

const trendText = computed(() => {
  switch (signals.value?.maTrend) {
    case 'BULLISH': return '多头排列'
    case 'BEARISH': return '空头排列'
    default: return '均线粘合'
  }
})

const trendClass = computed(() => ({
  up: signals.value?.maTrend === 'BULLISH',
  down: signals.value?.maTrend === 'BEARISH'
}))

function fmt(v: number | undefined): string {
  return v != null ? Number(v).toFixed(2) : '-'
}

function fmtTime(t: string): string {
  return t ? t.replace('T', ' ').slice(0, 19) : '-'
}

async function loadAlerts() {
  loading.value = true
  try {
    const res = await getAlerts(props.code)
    alerts.value = res.data
  } catch (e) {
    console.error('预警列表加载失败', e)
  } finally {
    loading.value = false
  }
}

async function create() {
  if (!canCreate.value) return
  saving.value = true
  error.value = ''
  try {
    await createAlert(props.code, form.value.type, Number(form.value.threshold))
    form.value.threshold = ''
    await loadAlerts()
  } catch (e: any) {
    error.value = e?.response?.data?.message || '添加失败'
  } finally {
    saving.value = false
  }
}

async function toggle(a: PriceAlert) {
  try {
    await updateAlert(props.code, a.id, { enabled: !a.enabled })
    await loadAlerts()
  } catch (e: any) {
    error.value = e?.response?.data?.message || '操作失败'
  }
}

/** 重置已触发的预警使其重新生效 */
async function reset(a: PriceAlert) {
  try {
    await updateAlert(props.code, a.id, { triggered: false, enabled: true })
    await loadAlerts()
  } catch (e: any) {
    error.value = e?.response?.data?.message || '重置失败'
  }
}

async function remove(a: PriceAlert) {
  if (!confirm(`确认删除该预警（${a.type === 'PRICE_ABOVE' ? '突破' : '跌破'} ${Number(a.threshold).toFixed(2)} 元）？`)) return
  try {
    await deleteAlert(props.code, a.id)
    await loadAlerts()
  } catch (e: any) {
    error.value = e?.response?.data?.message || '删除失败'
  }
}

onMounted(async () => {
  loadAlerts()
  try {
    const res = await getSignals(props.code)
    signals.value = res.data
  } catch (e) {
    console.error('技术信号加载失败', e)
  } finally {
    signalLoading.value = false
  }
})
</script>

<style scoped>
.alerts-panel section { margin-bottom: 24px; }
.signal-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; }
@media (max-width: 900px) { .signal-grid { grid-template-columns: repeat(2, 1fr); } }
.alert-form { display: flex; gap: 10px; align-items: center; margin-bottom: 14px; flex-wrap: wrap; }
.alert-form select, .alert-form input { padding: 8px 12px; border: 1px solid #ddd; border-radius: 4px; font-size: 14px; }
.alert-form input { width: 160px; }
.form-error { color: #ff4d4f; font-size: 13px; }
.status-tag { padding: 2px 10px; border-radius: 10px; font-size: 12px; }
.status-tag.watching { background: #e6f7ff; color: #1890ff; }
.status-tag.triggered { background: #fff1f0; color: #ff4d4f; }
.status-tag.disabled { background: #f5f5f5; color: #999; }
.ops { display: flex; gap: 6px; }
.op-btn { padding: 2px 10px; border: 1px solid #ddd; background: #fff; border-radius: 4px; cursor: pointer; font-size: 12px; }
.op-btn:hover { border-color: #1890ff; color: #1890ff; }
.op-btn.danger:hover { border-color: #ff4d4f; color: #ff4d4f; }
</style>
