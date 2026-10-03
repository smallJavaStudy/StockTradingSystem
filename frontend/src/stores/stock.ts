import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getStockList } from '../api/stock'

export const useStockStore = defineStore('stock', () => {
  const stocks = ref<any[]>([])
  const loading = ref(false)

  async function fetchStocks() {
    loading.value = true
    try {
      const res = await getStockList()
      stocks.value = res.data
    } finally {
      loading.value = false
    }
  }

  return { stocks, loading, fetchStocks }
})
