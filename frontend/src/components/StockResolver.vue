<template>
  <div class="stock-resolver">
    <div class="search-bar">
      <input
        v-model="query"
        placeholder="输入股票名称或代码（支持模糊匹配）"
        @keyup.enter="$emit('search', query)"
      />
      <button @click="$emit('search', query)" :disabled="!query.trim() || loading">
        {{ loading ? '搜索中...' : '搜索' }}
      </button>
    </div>
    <div v-if="candidates.length > 0" class="candidate-list">
      <div
        v-for="c in candidates"
        :key="c.code"
        class="candidate-card"
        :class="{ selected: selectedCode === c.code }"
        @click="$emit('select', c)"
      >
        <span class="candidate-name">{{ c.name }}</span>
        <span class="candidate-code">{{ c.code }}</span>
        <span class="candidate-market">{{ c.market === 'SH' ? '上海' : '深圳' }}</span>
        <span v-if="c.industry" class="candidate-industry">{{ c.industry }}</span>
      </div>
    </div>
    <div v-else-if="searched && !loading" class="empty-result">未找到匹配的股票</div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import type { StockCandidate } from '../types/analysis'

defineProps<{
  candidates: StockCandidate[]
  loading: boolean
  searched: boolean
  selectedCode: string
}>()

defineEmits<{
  search: [query: string]
  select: [candidate: StockCandidate]
}>()

const query = ref('')
</script>
