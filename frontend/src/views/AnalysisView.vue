<template>
  <div class="analysis-view">
    <h1>智能股票分析</h1>

    <!-- Step 1: Search stock -->
    <section v-if="store.step === 1" class="step-1">
      <StockResolver
        :candidates="store.candidates"
        :loading="store.loading"
        :searched="store.searchQuery.length > 0 && !store.loading && store.candidates.length === 0"
        :selectedCode="store.selectedStock?.code || ''"
        @search="(q: string) => store.resolveStock(q)"
        @select="(c: any) => store.selectStock(c)"
      />
    </section>

    <!-- Step 2: Select directions and model -->
    <section v-if="store.step === 2" class="step-2">
      <div class="selected-stock-info">
        <span class="back-btn" @click="store.reset()">&larr; 返回搜索</span>
        <div class="stock-card">
          <strong>{{ store.selectedStock?.name }}</strong>
          <span>{{ store.selectedStock?.code }}</span>
          <span>{{ store.selectedStock?.market === 'SH' ? '上海' : '深圳' }}</span>
          <span v-if="store.selectedStock?.industry">{{ store.selectedStock?.industry }}</span>
        </div>
      </div>

      <DirectionSelector
        :directions="store.directionNames"
        :selected="store.selectedDirections"
        @toggle="(k: string) => store.toggleDirection(k)"
        @selectAll="store.selectedDirections = Object.keys(store.directionNames)"
        @deselectAll="store.selectedDirections = []"
      />

      <ModelSelector
        :modelValue="store.modelChoice"
        @update:modelValue="(v: string) => store.setModelChoice(v)"
      />

      <div class="start-section">
        <button
          class="btn-start"
          :disabled="!store.selectedDirections.length || store.loading"
          @click="store.beginAnalysis()"
        >
          {{ store.loading ? '启动中...' : '开始分析' }}
        </button>
      </div>
    </section>

    <!-- Step 3: Dashboard -->
    <section v-if="store.step === 3" class="step-3">
      <div class="dashboard-header">
        <span class="back-btn" @click="store.reset()">&larr; 返回重新分析</span>
        <span class="session-info">
          分析标的: {{ store.selectedStock?.name }} ({{ store.selectedStock?.code }})
        </span>
      </div>

      <AnalysisDashboard
        :stockCode="store.selectedStock?.code || ''"
        :stockName="store.selectedStock?.name || ''"
        :directionList="store.directionList"
        :completedCount="store.completedCount"
        :totalCount="store.totalDirections"
        :sessionStatus="store.sessionStatus"
      />
    </section>
  </div>
</template>

<script setup lang="ts">
import { onUnmounted } from 'vue'
import { useAnalysisStore } from '../stores/analysis'
import StockResolver from '../components/StockResolver.vue'
import DirectionSelector from '../components/DirectionSelector.vue'
import ModelSelector from '../components/ModelSelector.vue'
import AnalysisDashboard from '../components/AnalysisDashboard.vue'

const store = useAnalysisStore()

onUnmounted(() => {
  store.reset()
})
</script>
