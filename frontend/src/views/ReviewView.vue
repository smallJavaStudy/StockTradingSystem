<template>
  <div class="review-view">
    <h1>📈 复盘中心</h1>
    <p class="review-sub">
      市场情绪周期仪 + 每日复盘工作流（数据总览 → 涨停梯队/龙虎榜资金/两融情绪三路并行 → 综合复盘）
    </p>

    <!-- 情绪周期仪 -->
    <section class="review-panel">
      <div class="panel-head">
        <h2>🌡️ 市场情绪周期仪</h2>
        <button class="btn-plain" :disabled="sentimentLoading" @click="loadSentiment">刷新</button>
      </div>

      <div v-if="sentimentLoading" class="panel-empty"><span class="spinner"></span> 计算情绪指标中...</div>
      <div v-else-if="sentimentError" class="panel-empty">加载失败：{{ sentimentError }}</div>

      <template v-else-if="sentiment">
        <div class="gauge-row">
          <!-- 情绪分仪表 -->
          <div class="gauge-card">
            <div class="gauge-score" :style="{ color: scoreColor(sentiment.latestScore) }">
              {{ sentiment.latestScore }}
            </div>
            <div class="gauge-bar">
              <div class="gauge-fill"
                   :style="{ width: sentiment.latestScore + '%', background: scoreColor(sentiment.latestScore) }"></div>
            </div>
            <div class="gauge-phase">
              周期阶段：<strong>{{ interpret?.phase || sentiment.phaseHint }}</strong>
              <span v-if="interpret" class="gauge-src">（{{ interpret.source === 'llm' ? 'LLM 定性' : '规则法' }}）</span>
            </div>
            <div class="gauge-date">交易日 {{ sentiment.latestDate || '-' }}</div>
          </div>

          <!-- 指标卡 -->
          <div class="indicator-cards" v-if="latest">
            <div class="ind-card">
              <div class="ind-value">{{ latest.ztCount }}</div>
              <div class="ind-label">涨停家数</div>
            </div>
            <div class="ind-card">
              <div class="ind-value">{{ latest.maxLimitUpDays }}板</div>
              <div class="ind-label">最高连板</div>
            </div>
            <div class="ind-card">
              <div class="ind-value">{{ latest.brokenRate }}%</div>
              <div class="ind-label">炸板率</div>
            </div>
            <div class="ind-card">
              <div class="ind-value">
                {{ latest.marginChangePct == null ? '-' : (latest.marginChangePct > 0 ? '+' : '') + latest.marginChangePct + '%' }}
              </div>
              <div class="ind-label">两融环比</div>
            </div>
          </div>
        </div>

        <p class="review-note">{{ sentiment.note }}</p>
        <pre v-if="interpret?.analysis" class="review-analysis">{{ interpret.analysis }}</pre>

        <!-- 近 N 日指标明细 -->
        <table v-if="sentiment.dailyIndicators.length">
          <thead>
            <tr>
              <th>日期</th><th>情绪分</th><th>涨停家数</th><th>最高连板</th>
              <th>炸板数</th><th>炸板率%</th><th>两融余额(亿)</th><th>两融环比%</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="d in sentiment.dailyIndicators" :key="d.tradeDate">
              <td>{{ d.tradeDate }}</td>
              <td :style="{ color: scoreColor(d.score), fontWeight: '600' }">{{ d.score }}</td>
              <td>{{ d.ztCount }}</td>
              <td>{{ d.maxLimitUpDays }}板</td>
              <td>{{ d.brokenCount }}</td>
              <td>{{ d.brokenRate }}</td>
              <td>{{ d.marginTotal == null ? '-' : (d.marginTotal / 1e8).toFixed(0) }}</td>
              <td>{{ d.marginChangePct == null ? '-' : d.marginChangePct }}</td>
            </tr>
          </tbody>
        </table>
      </template>
    </section>

    <!-- 一键生成复盘报告 -->
    <section class="review-panel">
      <div class="panel-head">
        <h2>📝 每日复盘报告</h2>
        <button class="btn-primary" :disabled="generating" @click="generateReview">
          {{ generating ? '启动中...' : '⚡ 一键生成复盘报告' }}
        </button>
      </div>
      <p v-if="generateError" class="review-error">{{ generateError }}</p>

      <div v-if="reportsLoading" class="panel-empty"><span class="spinner"></span> 加载最近复盘报告...</div>
      <div v-else-if="!recentReports.length" class="panel-empty">
        暂无复盘报告，点击「一键生成复盘报告」跑第一份吧
      </div>
      <table v-else>
        <thead>
          <tr><th>开始时间</th><th>状态</th><th>节点数</th><th>结论预览</th><th>操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="r in recentReports" :key="r.runId">
            <td class="wf-time">{{ fmtTime(r.startTime) }}</td>
            <td><span class="review-status" :class="'st-' + r.status">{{ r.status }}</span></td>
            <td>{{ r.nodeCount }}</td>
            <td class="review-preview" :title="r.finalOutputPreview || ''">
              {{ r.finalOutputPreview || '-' }}
            </td>
            <td class="wf-actions">
              <router-link :to="`/workflow/execution/${r.runId}`">查看</router-link>
            </td>
          </tr>
        </tbody>
      </table>
    </section>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { getSentiment, getSentimentInterpret, type SentimentSnapshot, type SentimentInterpret } from '../api/market'
import { listWorkflows, publishWorkflow, executeWorkflow } from '../api/workflow'
import { searchReports, type ReportRunSummary } from '../api/reports'
import type { WorkflowDef } from '../types/workflow'

const REVIEW_WORKFLOW_NAME = '每日复盘工作流'

const router = useRouter()

const sentiment = ref<SentimentSnapshot | null>(null)
const interpret = ref<SentimentInterpret | null>(null)
const sentimentLoading = ref(false)
const sentimentError = ref('')

const recentReports = ref<ReportRunSummary[]>([])
const reportsLoading = ref(false)

const generating = ref(false)
const generateError = ref('')

const latest = computed(() => sentiment.value?.dailyIndicators[0] ?? null)

function scoreColor(score: number) {
  if (score >= 70) return '#f56c6c'
  if (score >= 50) return '#e6a23c'
  if (score >= 30) return '#409eff'
  return '#909399'
}

function fmtTime(t: string | null) {
  return t ? t.replace('T', ' ').slice(0, 16) : '-'
}

async function loadSentiment() {
  sentimentLoading.value = true
  sentimentError.value = ''
  try {
    const res = await getSentiment(10)
    sentiment.value = res.data
    // 周期定性（当日缓存，失败不阻断仪表展示）
    try {
      interpret.value = (await getSentimentInterpret()).data
    } catch {
      interpret.value = null
    }
  } catch (e: any) {
    sentimentError.value = e?.message || String(e)
  } finally {
    sentimentLoading.value = false
  }
}

async function loadRecentReports() {
  reportsLoading.value = true
  try {
    const res = await searchReports({ workflowName: REVIEW_WORKFLOW_NAME, page: 0, size: 5 })
    recentReports.value = res.data.content
  } catch {
    recentReports.value = []
  } finally {
    reportsLoading.value = false
  }
}

/** 一键复盘：找到种子复盘工作流 → 未发布先发布 → 执行 → 跳转执行页 */
async function generateReview() {
  generating.value = true
  generateError.value = ''
  try {
    const res = await listWorkflows()
    const workflows: WorkflowDef[] = res.data ?? []
    const def = workflows.find(w => w.name === REVIEW_WORKFLOW_NAME)
    if (!def) {
      generateError.value = `未找到「${REVIEW_WORKFLOW_NAME}」，请重启后端以初始化种子工作流`
      return
    }
    if (def.status !== 'PUBLISHED') {
      await publishWorkflow(def.id)
    }
    const exec = await executeWorkflow(def.id, { goal: '盘后例行复盘（复盘中心手动触发）' })
    const pid = exec.data?.processInstanceId
    if (!pid) {
      generateError.value = '执行启动失败：未返回流程实例 ID'
      return
    }
    router.push(`/workflow/execution/${pid}`)
  } catch (e: any) {
    generateError.value = e?.response?.data?.message || e?.message || String(e)
  } finally {
    generating.value = false
  }
}

onMounted(() => {
  loadSentiment()
  loadRecentReports()
})
</script>

<style scoped>
.review-view {
  max-width: 1100px;
  margin: 0 auto;
  padding: 24px 16px;
}
.review-sub {
  color: #8a8f98;
  margin: 4px 0 16px;
}
.review-panel {
  margin-bottom: 28px;
}
.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.panel-head h2 {
  font-size: 16px;
  margin: 0;
}
.gauge-row {
  display: flex;
  gap: 20px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}
.gauge-card {
  flex: 0 0 260px;
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 10px;
  padding: 18px;
  text-align: center;
}
.gauge-score {
  font-size: 44px;
  font-weight: 700;
  line-height: 1.2;
}
.gauge-bar {
  height: 10px;
  border-radius: 5px;
  background: rgba(255, 255, 255, 0.08);
  overflow: hidden;
  margin: 10px 0;
}
.gauge-fill {
  height: 100%;
  border-radius: 5px;
  transition: width 0.4s;
}
.gauge-phase { margin-top: 6px; }
.gauge-src { color: #8a8f98; font-size: 12px; }
.gauge-date { color: #8a8f98; font-size: 12px; margin-top: 4px; }
.indicator-cards {
  flex: 1;
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(120px, 1fr));
  gap: 12px;
  align-content: start;
}
.ind-card {
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 10px;
  padding: 16px 12px;
  text-align: center;
}
.ind-value {
  font-size: 24px;
  font-weight: 700;
}
.ind-label {
  color: #8a8f98;
  font-size: 12px;
  margin-top: 4px;
}
.review-note {
  color: #8a8f98;
  font-size: 12px;
  margin: 8px 0;
}
.review-analysis {
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.7;
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 8px;
  padding: 12px 14px;
  font-family: inherit;
  margin: 0 0 14px;
}
.review-error { color: #f56c6c; margin: 6px 0; }
.review-preview {
  max-width: 420px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #b9bec7;
}
.review-status {
  font-size: 12px;
  padding: 2px 8px;
  border-radius: 10px;
  background: rgba(144, 147, 153, 0.15);
  color: #909399;
}
.st-COMPLETED { background: rgba(103, 194, 58, 0.15); color: #67c23a; }
.st-RUNNING { background: rgba(64, 158, 255, 0.15); color: #409eff; }
.st-FAILED { background: rgba(245, 108, 108, 0.15); color: #f56c6c; }
</style>
