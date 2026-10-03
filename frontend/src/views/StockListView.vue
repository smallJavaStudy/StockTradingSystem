<template>
  <div class="stock-list">
    <h1>📈 我的股票</h1>
    <div class="add-form">
      <input v-model="newCode" placeholder="股票代码（如 600519）" maxlength="6" />
      <input v-model="newName" placeholder="名称（如 贵州茅台）" />
      <button @click="addStock" :disabled="!newCode || !newName">添加</button>
    </div>
    <div v-if="store.loading">加载中...</div>
    <table v-else-if="store.stocks.length">
      <thead>
        <tr><th>代码</th><th>名称</th><th>市场</th><th>操作</th></tr>
      </thead>
      <tbody>
        <tr v-for="s in store.stocks" :key="s.code">
          <td>{{ s.code }}</td>
          <td>{{ s.name }}</td>
          <td>{{ s.market === 'SH' ? '上海' : '深圳' }}</td>
          <td><router-link :to="`/stock/${s.code}`">查看详情</router-link></td>
        </tr>
      </tbody>
    </table>
    <p v-else>暂无股票，请添加</p>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useStockStore } from '../stores/stock'
import { saveStockBasic } from '../api/stock'

const store = useStockStore()
const newCode = ref('')
const newName = ref('')

async function addStock() {
  const market = newCode.value.startsWith('6') ? 'SH' : 'SZ'
  await saveStockBasic(newCode.value, newName.value, market)
  newCode.value = ''
  newName.value = ''
  await store.fetchStocks()
}

onMounted(() => store.fetchStocks())
</script>
