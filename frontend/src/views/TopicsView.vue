<template>
  <div class="topics-view">
    <h1>🔥 题材库</h1>
    <p class="topics-sub">
      基于当日涨停池行业分布 + LLM 级联分析（DeepSeek→Kimi）的热点题材全景
      <button class="btn-plain" :disabled="loading" @click="load">刷新</button>
    </p>

    <div v-if="loading" class="panel-empty"><span class="spinner"></span> 加载题材数据中...</div>
    <div v-else-if="loadError" class="panel-empty">加载失败：{{ loadError }}</div>

    <template v-else-if="data">
      <div class="topics-meta">
        <span>交易日：{{ data.tradeDate || '-' }}</span>
        <span class="topics-source" :class="'src-' + data.source">
          {{ sourceLabel(data.source) }}
        </span>
      </div>

      <!-- LLM 热点题材分析 -->
      <section class="topics-panel">
        <h2>🧠 今日热点题材分析</h2>
        <pre class="topics-analysis">{{ data.llmAnalysis || '暂无分析' }}</pre>
      </section>

      <!-- 涨停池行业分布 -->
      <section class="topics-panel">
        <h2>📊 涨停池行业分布（Top {{ data.industries.length }}）</h2>
        <div v-if="!data.industries.length" class="panel-empty">当日暂无涨停池数据</div>
        <table v-else>
          <thead>
            <tr><th>行业/板块</th><th>涨停家数</th><th>最高连板</th><th>代表个股（板数/涨停统计）</th></tr>
          </thead>
          <tbody>
            <tr v-for="b in data.industries" :key="b.industry">
              <td class="topics-industry">{{ b.industry }}</td>
              <td>
                <span class="topics-bar" :style="{ width: barWidth(b.count) }"></span>
                {{ b.count }}
              </td>
              <td>{{ b.maxLimitUpDays }}板</td>
              <td class="topics-stocks">{{ b.topStocks.join('、') }}</td>
            </tr>
          </tbody>
        </table>
      </section>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { getTopics, type TopicsResponse } from '../api/topics'

const data = ref<TopicsResponse | null>(null)
const loading = ref(false)
const loadError = ref('')

const maxCount = computed(() =>
  Math.max(1, ...(data.value?.industries.map(b => b.count) ?? [1]))
)

function barWidth(count: number) {
  return Math.round((count / maxCount.value) * 120) + 'px'
}

function sourceLabel(source: TopicsResponse['source']) {
  if (source === 'llm') return 'LLM 分析'
  if (source === 'fallback') return 'LLM 降级（仅行业分布）'
  return '无数据'
}

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const res = await getTopics()
    data.value = res.data
  } catch (e: any) {
    loadError.value = e?.message || String(e)
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.topics-view {
  max-width: 1100px;
  margin: 0 auto;
  padding: 24px 16px;
}
.topics-sub {
  color: #8a8f98;
  margin: 4px 0 16px;
  display: flex;
  align-items: center;
  gap: 12px;
}
.topics-meta {
  display: flex;
  gap: 16px;
  align-items: center;
  margin-bottom: 12px;
  color: #b9bec7;
}
.topics-source {
  font-size: 12px;
  padding: 2px 8px;
  border-radius: 10px;
}
.src-llm { background: rgba(64, 158, 255, 0.15); color: #409eff; }
.src-fallback { background: rgba(230, 162, 60, 0.15); color: #e6a23c; }
.src-none { background: rgba(144, 147, 153, 0.15); color: #909399; }
.topics-panel {
  margin-bottom: 24px;
}
.topics-panel h2 {
  font-size: 16px;
  margin-bottom: 10px;
}
.topics-analysis {
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.7;
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 8px;
  padding: 14px 16px;
  font-family: inherit;
  margin: 0;
}
.topics-industry { font-weight: 600; }
.topics-bar {
  display: inline-block;
  height: 8px;
  background: linear-gradient(90deg, #f56c6c, #e6a23c);
  border-radius: 4px;
  margin-right: 8px;
  vertical-align: middle;
}
.topics-stocks { color: #b9bec7; }
</style>
