<template>
  <div class="market-view">
    <h1>市场中心</h1>

    <div class="wf-tabs">
      <div
        v-for="tab in tabs"
        :key="tab.key"
        class="wf-tab"
        :class="{ active: activeTab === tab.key }"
        @click="activeTab = tab.key"
      >
        {{ tab.label }}
      </div>
    </div>

    <!-- keep-alive 保留各页签的日期选择与已加载数据 -->
    <keep-alive>
      <component :is="activeComponent" />
    </keep-alive>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import ZtPoolPanel from '../components/market/ZtPoolPanel.vue'
import LhbPanel from '../components/market/LhbPanel.vue'
import NorthMarginPanel from '../components/market/NorthMarginPanel.vue'
import BlockTradePanel from '../components/market/BlockTradePanel.vue'

const tabs = [
  { key: 'zt', label: '涨停天梯' },
  { key: 'lhb', label: '龙虎榜' },
  { key: 'north', label: '北向 & 两融' },
  { key: 'block', label: '大宗交易' },
] as const

type TabKey = typeof tabs[number]['key']
const activeTab = ref<TabKey>('zt')

const componentMap = {
  zt: ZtPoolPanel,
  lhb: LhbPanel,
  north: NorthMarginPanel,
  block: BlockTradePanel,
}

const activeComponent = computed(() => componentMap[activeTab.value])
</script>
