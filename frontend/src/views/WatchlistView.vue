<template>
  <div class="watchlist-view">
    <h1>⭐ 自选股</h1>

    <!-- 添加表单 -->
    <div class="watch-add-form">
      <input
        v-model.trim="newCode"
        placeholder="股票代码（如 600519）"
        maxlength="6"
        @blur="autoResolveName"
        @keyup.enter="autoResolveName"
      />
      <input v-model.trim="newName" :placeholder="resolving ? '识别名称中...' : '名称（输入代码自动带出）'" />
      <input v-model.trim="newGroup" placeholder="分组（默认：默认分组）" list="group-options" />
      <datalist id="group-options">
        <option v-for="g in groupNames" :key="g" :value="g" />
      </datalist>
      <input v-model.trim="newTags" placeholder="标签（逗号分隔，选填）" />
      <button class="btn-primary" :disabled="!newCode || !newName || submitting" @click="add">
        {{ submitting ? '添加中...' : '+ 添加自选' }}
      </button>
    </div>
    <p v-if="formError" class="watch-error">{{ formError }}</p>

    <div v-if="loading" class="panel-empty"><span class="spinner"></span> 加载自选股中...</div>
    <div v-else-if="loadError" class="panel-empty">加载失败：{{ loadError }}</div>
    <div v-else-if="!items.length" class="panel-empty">暂无自选股，输入股票代码添加第一只吧</div>

    <!-- 分组展示 -->
    <section v-for="group in groups" :key="group.name" class="watch-group">
      <h2>📁 {{ group.name }} <span class="group-count">{{ group.items.length }} 只</span></h2>
      <table>
        <thead>
          <tr><th>代码</th><th>名称</th><th>最新价</th><th>涨跌幅</th><th>标签</th><th>备注</th><th>加入时间</th><th>操作</th></tr>
        </thead>
        <tbody>
          <template v-for="item in group.items" :key="item.id">
            <tr>
              <td>{{ item.stockCode }}</td>
              <td><router-link :to="`/stock/${item.stockCode}`" class="watch-name">{{ item.stockName }}</router-link></td>
              <td :title="item.quoteDate ? `行情日期：${item.quoteDate}` : ''">{{ item.latestPrice != null ? item.latestPrice.toFixed(2) : '-' }}</td>
              <td :class="item.changePct != null ? (item.changePct >= 0 ? 'up' : 'down') : ''">
                {{ item.changePct != null ? (item.changePct >= 0 ? '+' : '') + item.changePct.toFixed(2) + '%' : '-' }}
              </td>
              <td>
                <span v-for="t in splitTags(item.tags)" :key="t" class="watch-tag">{{ t }}</span>
                <span v-if="!splitTags(item.tags).length" class="watch-none">-</span>
              </td>
              <td class="watch-note" :title="item.note || ''">{{ item.note || '-' }}</td>
              <td class="wf-time">{{ fmtTime(item.createdAt) }}</td>
              <td class="wf-actions">
                <a @click="startEdit(item)">{{ editingId === item.id ? '收起' : '编辑' }}</a>
                <a class="danger" @click="remove(item)">删除</a>
              </td>
            </tr>
            <!-- 行内编辑 -->
            <tr v-if="editingId === item.id">
              <td colspan="8" class="watch-edit-row">
                <div class="watch-edit-form">
                  <div class="form-row">
                    <label>分组</label>
                    <input v-model.trim="editForm.groupName" list="group-options" />
                  </div>
                  <div class="form-row">
                    <label>标签（逗号分隔）</label>
                    <input v-model.trim="editForm.tags" placeholder="如：半导体,国产替代" />
                  </div>
                  <div class="form-row">
                    <label>备注</label>
                    <input v-model.trim="editForm.note" />
                  </div>
                  <button class="btn-primary" :disabled="submitting" @click="saveEdit(item)">保存</button>
                  <button class="btn-plain" @click="editingId = null">取消</button>
                </div>
              </td>
            </tr>
          </template>
        </tbody>
      </table>
    </section>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { getWatchlist, addWatchlist, updateWatchlist, deleteWatchlist, type WatchlistItem } from '../api/watchlist'
import { resolveStockName } from '../api/stock'

const items = ref<WatchlistItem[]>([])
const loading = ref(false)
const loadError = ref('')

const newCode = ref('')
const newName = ref('')
const newGroup = ref('')
const newTags = ref('')
const resolving = ref(false)
const submitting = ref(false)
const formError = ref('')

const editingId = ref<number | null>(null)
const editForm = ref({ groupName: '', tags: '', note: '' })

const groups = computed(() => {
  const map = new Map<string, WatchlistItem[]>()
  for (const it of items.value) {
    const g = it.groupName || '默认分组'
    if (!map.has(g)) map.set(g, [])
    map.get(g)!.push(it)
  }
  return [...map.entries()].map(([name, list]) => ({ name, items: list }))
})

const groupNames = computed(() => groups.value.map(g => g.name))

function splitTags(tags: string | null) {
  return (tags || '').split(/[,，]/).map(t => t.trim()).filter(Boolean)
}

function fmtTime(t: string) {
  return t ? t.slice(0, 10) : '-'
}

// 输入代码后自动带出股票名称（复用现有股票识别 API）
async function autoResolveName() {
  if (!/^\d{6}$/.test(newCode.value) || newName.value) return
  resolving.value = true
  try {
    const res = await resolveStockName(newCode.value)
    const candidates = res.data || []
    const hit = candidates.find((c: { code: string }) => c.code === newCode.value) || candidates[0]
    if (hit) newName.value = hit.name
  } catch {
    // 识别失败不阻塞，允许手动输入名称
  } finally {
    resolving.value = false
  }
}

async function fetchList() {
  loading.value = true
  loadError.value = ''
  try {
    const res = await getWatchlist()
    items.value = res.data || []
  } catch (e: any) {
    // 接口未部署（后端待集成重启）按空态处理
    const msg: string = e?.response?.data?.message || ''
    if (msg.includes('No static resource')) {
      loadError.value = ''
    } else {
      loadError.value = msg || e.message
    }
  } finally {
    loading.value = false
  }
}

async function add() {
  submitting.value = true
  formError.value = ''
  try {
    await addWatchlist({
      stockCode: newCode.value,
      stockName: newName.value,
      groupName: newGroup.value || undefined,
      tags: newTags.value || undefined,
    })
    newCode.value = ''
    newName.value = ''
    newTags.value = ''
    await fetchList()
  } catch (e: any) {
    formError.value = e?.response?.data?.message || '添加失败'
  } finally {
    submitting.value = false
  }
}

function startEdit(item: WatchlistItem) {
  if (editingId.value === item.id) {
    editingId.value = null
    return
  }
  editingId.value = item.id
  editForm.value = {
    groupName: item.groupName || '',
    tags: item.tags || '',
    note: item.note || '',
  }
}

async function saveEdit(item: WatchlistItem) {
  submitting.value = true
  try {
    await updateWatchlist(item.id, { ...editForm.value })
    editingId.value = null
    await fetchList()
  } catch (e: any) {
    formError.value = e?.response?.data?.message || '保存失败'
  } finally {
    submitting.value = false
  }
}

async function remove(item: WatchlistItem) {
  if (!confirm(`确定从自选股中删除 ${item.stockName}（${item.stockCode}）？`)) return
  try {
    await deleteWatchlist(item.id)
    await fetchList()
  } catch (e: any) {
    formError.value = e?.response?.data?.message || '删除失败'
  }
}

onMounted(fetchList)
</script>

<style scoped>
.watch-add-form { display: flex; gap: 10px; margin: 16px 0 8px; flex-wrap: wrap; }
.watch-add-form input { padding: 8px 12px; border: 1px solid #ddd; border-radius: 6px; font-size: 14px; }
.watch-error { color: #ff4d4f; font-size: 13px; margin-bottom: 8px; }
.watch-group { margin-bottom: 8px; }
.group-count { font-size: 13px; color: #999; font-weight: 400; }
.watch-name { color: #1890ff; text-decoration: none; font-weight: 600; }
.watch-name:hover { text-decoration: underline; }
.watch-tag { display: inline-block; background: #f0f7ff; color: #1890ff; font-size: 12px; padding: 1px 8px; border-radius: 4px; margin-right: 4px; }
.watch-none { color: #ccc; }
.watch-note { max-width: 220px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: #666; }
.watch-edit-row { background: #fafcff; }
.watch-edit-form { display: flex; gap: 12px; align-items: flex-end; flex-wrap: wrap; }
.watch-edit-form .form-row { margin-bottom: 0; min-width: 180px; }
</style>
