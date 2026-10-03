<template>
  <div class="direction-selector">
    <h3>选择分析方向</h3>
    <div class="direction-grid">
      <label v-for="(name, key) in directions" :key="key" class="direction-item">
        <input
          type="checkbox"
          :checked="selected.includes(key)"
          @change="$emit('toggle', key)"
        />
        <span>{{ name }}</span>
      </label>
    </div>
    <div class="direction-actions">
      <button @click="$emit('selectAll')">全选</button>
      <button @click="$emit('deselectAll')">取消全选</button>
    </div>
  </div>
</template>

<script setup lang="ts">
defineProps<{
  directions: Record<string, string>
  selected: string[]
}>()

defineEmits<{
  toggle: [key: string]
  selectAll: []
  deselectAll: []
}>()
</script>
