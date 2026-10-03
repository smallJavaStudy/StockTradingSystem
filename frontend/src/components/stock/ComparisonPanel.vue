<template>
  <div class="comparison-panel">
    <div v-if="loading" class="panel-empty">加载对比数据中...</div>
    <div v-else-if="!data" class="panel-empty">对比数据加载失败</div>
    <template v-else>
      <!-- 指标并排表 -->
      <section>
        <h2>财务对标（最新报告期）</h2>
        <div v-if="rows.length <= 1" class="panel-empty">暂无竞品数据（StockCompetitor 未采集）</div>
        <table v-else>
          <thead>
            <tr><th>指标</th><th v-for="r in rows" :key="r.name" :class="{ 'base-col': r === data.base }">
              {{ r.name }}<span v-if="r === data.base" class="badge" style="margin-left:6px">本股</span>
            </th></tr>
          </thead>
          <tbody>
            <tr><td>代码</td><td v-for="r in rows" :key="r.name">{{ r.code || '未上市' }}</td></tr>
            <tr><td>报告期</td><td v-for="r in rows" :key="r.name">{{ r.reportDate || '-' }}</td></tr>
            <tr><td>EPS(元)</td><td v-for="r in rows" :key="r.name">{{ fmt(r.eps) }}</td></tr>
            <tr><td>ROE(%)</td><td v-for="r in rows" :key="r.name">{{ fmt(r.roe) }}</td></tr>
            <tr><td>营收(亿)</td><td v-for="r in rows" :key="r.name">{{ fmtYi(r.revenue) }}</td></tr>
            <tr><td>净利润(亿)</td><td v-for="r in rows" :key="r.name">{{ fmtYi(r.netProfit) }}</td></tr>
            <tr><td>现价(元)</td><td v-for="r in rows" :key="r.name">{{ fmt(r.price) }}</td></tr>
            <tr><td>市值(亿)</td><td v-for="r in rows" :key="r.name">{{ fmtYi(r.marketCap) }}</td></tr>
            <tr><td>PE(年化)</td><td v-for="r in rows" :key="r.name">{{ fmt(r.pe) }}</td></tr>
            <tr><td>主营</td><td v-for="r in rows" :key="r.name">{{ r === data.base ? (r.industry || '-') : (r.mainProduct || '-') }}</td></tr>
          </tbody>
        </table>
      </section>

      <!-- 竞争格局点评 -->
      <section>
        <h2>竞争格局点评（AI）</h2>
        <div class="comment-card">
          <div v-if="commentLoading" class="panel-empty"><span class="spinner"></span> 点评生成中（约需 30-60 秒）...</div>
          <div v-else-if="commentError" class="comment-error">
            <p>{{ commentError }}</p>
            <button class="btn-plain" @click="loadComment">重试</button>
          </div>
          <template v-else-if="comment">
            <p class="comment-text">{{ comment.comment }}</p>
            <p class="comment-meta">
              {{ comment.fromCache ? '当日缓存' : '实时生成' }} · {{ fmtTime(comment.generatedAt) }}
            </p>
          </template>
          <div v-else class="panel-empty">
            <button class="btn-primary" @click="loadComment">生成竞争格局点评</button>
          </div>
        </div>
      </section>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { getComparison, getComparisonComment, type ComparisonResp, type ComparisonComment } from '../../api/stockDetail'

const props = defineProps<{ code: string }>()

const loading = ref(true)
const data = ref<ComparisonResp | null>(null)
const comment = ref<ComparisonComment | null>(null)
const commentLoading = ref(false)
const commentError = ref('')

const rows = computed(() => (data.value ? [data.value.base, ...data.value.competitors] : []))

function fmt(v: number | null | undefined): string {
  return v != null ? Number(v).toFixed(2) : '-'
}

function fmtYi(v: number | null | undefined): string {
  return v != null ? (Number(v) / 1e8).toFixed(2) : '-'
}

function fmtTime(t: string): string {
  return t ? t.replace('T', ' ').slice(0, 19) : ''
}

async function loadComment() {
  commentLoading.value = true
  commentError.value = ''
  try {
    const res = await getComparisonComment(props.code)
    comment.value = res.data
  } catch (e: any) {
    commentError.value = e?.response?.data?.message || '点评生成失败，请稍后重试'
  } finally {
    commentLoading.value = false
  }
}

onMounted(async () => {
  try {
    const res = await getComparison(props.code)
    data.value = res.data
  } catch (e) {
    console.error('对比数据加载失败', e)
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.comparison-panel section { margin-bottom: 24px; }
.base-col { color: #1890ff; }
.badge { display: inline-block; padding: 1px 8px; background: #e6f7ff; color: #1890ff; border-radius: 10px; font-size: 11px; }
.comment-card { background: #fff; border-radius: 8px; padding: 16px 20px; box-shadow: 0 1px 4px rgba(0,0,0,0.06); }
.comment-text { line-height: 1.8; font-size: 14px; white-space: pre-wrap; }
.comment-meta { margin-top: 10px; color: #999; font-size: 12px; }
.comment-error { color: #ff4d4f; font-size: 14px; }
.comment-error p { margin-bottom: 10px; }
</style>
