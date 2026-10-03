<template>
  <div class="market-date-picker">
    <label>日期</label>
    <select v-if="dates.length" v-model="selected" @change="emitChange">
      <option v-for="d in dates" :key="d" :value="d">{{ d }}</option>
    </select>
    <input v-else type="date" v-model="selected" @change="emitChange" />
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { getMarketDates, type MarketDateType } from '../../api/market'

const props = defineProps<{ type: MarketDateType }>()
const emit = defineEmits<{ change: [date: string] }>()

const dates = ref<string[]>([])
const selected = ref('')

function emitChange() {
  if (selected.value) emit('change', selected.value)
}

onMounted(async () => {
  try {
    const res = await getMarketDates(props.type)
    dates.value = res.data || []
  } catch {
    dates.value = []
  }
  // 默认选中最新可用日期；接口未就绪时回退到今天
  selected.value = dates.value[0] || new Date().toISOString().slice(0, 10)
  emitChange()
})
</script>

<style scoped>
.market-date-picker { display: inline-flex; align-items: center; gap: 8px; }
.market-date-picker label { font-size: 13px; color: #666; }
.market-date-picker select, .market-date-picker input { padding: 6px 12px; border: 1px solid #ddd; border-radius: 6px; font-size: 14px; background: #fff; }
</style>
