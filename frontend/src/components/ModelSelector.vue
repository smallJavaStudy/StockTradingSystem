<template>
  <div class="model-selector">
    <h3>选择分析模型</h3>
    <div class="model-options">
      <label
        v-for="m in models"
        :key="m.value"
        class="model-option"
        :class="{ selected: modelValue === m.value }"
      >
        <input
          type="radio"
          :value="m.value"
          :checked="modelValue === m.value"
          @change="$emit('update:modelValue', m.value)"
        />
        <span class="model-name">{{ m.label }}</span>
      </label>
    </div>
  </div>
</template>

<script setup lang="ts">
defineProps<{
  modelValue: string
}>()

defineEmits<{
  'update:modelValue': [value: string]
}>()

const models = [
  { value: 'deepseek-v4', label: 'DeepSeek V4 PRO' },
  { value: 'deepseek-v5', label: 'DeepSeek V5 FLASH' },
  { value: 'kimi', label: 'Kimi' }
]
</script>
