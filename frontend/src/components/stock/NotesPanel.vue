<template>
  <div class="notes-panel">
    <!-- 分类过滤 -->
    <div class="wf-tabs">
      <button class="wf-tab" :class="{ active: filterCategory === '' }" @click="setFilter('')">全部</button>
      <button v-for="(label, cat) in CATEGORY_LABELS" :key="cat" class="wf-tab"
              :class="{ active: filterCategory === cat }" @click="setFilter(cat)">{{ label }}</button>
    </div>

    <!-- 新增/编辑表单 -->
    <div class="note-form">
      <div class="form-row">
        <select v-model="form.category">
          <option v-for="(label, cat) in CATEGORY_LABELS" :key="cat" :value="cat">{{ label }}</option>
        </select>
        <span class="content-count" :class="{ over: form.content.length > MAX_LEN }">
          {{ form.content.length }}/{{ MAX_LEN }}
        </span>
      </div>
      <textarea v-model="form.content" rows="4" :placeholder="editingId ? '编辑笔记内容...' : '记录你的投资思考（1-10000字）...'"></textarea>
      <div class="form-actions">
        <button class="btn-primary" :disabled="!canSubmit || saving" @click="submit">
          {{ editingId ? '保存修改' : '添加笔记' }}
        </button>
        <button v-if="editingId" class="btn-plain" @click="cancelEdit">取消编辑</button>
        <span v-if="error" class="form-error">{{ error }}</span>
      </div>
    </div>

    <!-- 笔记列表 -->
    <div v-if="loading" class="panel-empty">加载笔记中...</div>
    <div v-else-if="notes.length === 0" class="panel-empty">暂无笔记，写下第一条投资思考吧</div>
    <div v-else class="note-list">
      <div v-for="n in notes" :key="n.id" class="note-item">
        <div class="note-head">
          <span class="note-category" :class="'cat-' + n.category">{{ CATEGORY_LABELS[n.category] }}</span>
          <span class="note-time">{{ fmtTime(n.updatedAt) }}</span>
          <span class="note-ops">
            <button class="op-btn" @click="startEdit(n)">编辑</button>
            <button class="op-btn danger" @click="remove(n)">删除</button>
          </span>
        </div>
        <p class="note-content">{{ n.content }}</p>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { getNotes, createNote, updateNote, deleteNote, type InvestNote, type NoteCategory } from '../../api/stockDetail'

const props = defineProps<{ code: string }>()

const MAX_LEN = 10000

const CATEGORY_LABELS: Record<NoteCategory, string> = {
  INVEST_LOGIC: '投资逻辑',
  RISK_POINT: '风险点',
  BUY_CONDITION: '买入条件',
  SELL_CONDITION: '卖出条件',
  FREE_NOTE: '自由笔记'
}

const loading = ref(true)
const saving = ref(false)
const error = ref('')
const notes = ref<InvestNote[]>([])
const filterCategory = ref<NoteCategory | ''>('')
const editingId = ref<number | null>(null)
const form = ref<{ category: NoteCategory; content: string }>({ category: 'INVEST_LOGIC', content: '' })

const canSubmit = computed(() => form.value.content.length >= 1 && form.value.content.length <= MAX_LEN)

function fmtTime(t: string): string {
  return t ? t.replace('T', ' ').slice(0, 16) : ''
}

async function load() {
  loading.value = true
  try {
    const res = await getNotes(props.code, filterCategory.value || undefined)
    notes.value = res.data
  } catch (e) {
    console.error('笔记加载失败', e)
  } finally {
    loading.value = false
  }
}

function setFilter(cat: NoteCategory | '') {
  filterCategory.value = cat
  load()
}

function startEdit(n: InvestNote) {
  editingId.value = n.id
  form.value = { category: n.category, content: n.content }
}

function cancelEdit() {
  editingId.value = null
  form.value = { category: 'INVEST_LOGIC', content: '' }
  error.value = ''
}

async function submit() {
  if (!canSubmit.value) return
  saving.value = true
  error.value = ''
  try {
    if (editingId.value != null) {
      await updateNote(props.code, editingId.value, { category: form.value.category, content: form.value.content })
    } else {
      await createNote(props.code, form.value.category, form.value.content)
    }
    cancelEdit()
    await load()
  } catch (e: any) {
    error.value = e?.response?.data?.message || '保存失败'
  } finally {
    saving.value = false
  }
}

async function remove(n: InvestNote) {
  if (!confirm(`确认删除这条「${CATEGORY_LABELS[n.category]}」笔记？`)) return
  try {
    await deleteNote(props.code, n.id)
    if (editingId.value === n.id) cancelEdit()
    await load()
  } catch (e: any) {
    error.value = e?.response?.data?.message || '删除失败'
  }
}

onMounted(load)
</script>

<style scoped>
.note-form { background: #fff; border-radius: 8px; padding: 14px 16px; margin-bottom: 16px; box-shadow: 0 1px 4px rgba(0,0,0,0.06); }
.note-form .form-row { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.note-form select { padding: 6px 10px; border: 1px solid #ddd; border-radius: 4px; font-size: 14px; }
.note-form textarea { width: 100%; padding: 10px 12px; border: 1px solid #ddd; border-radius: 6px; font-size: 14px; resize: vertical; font-family: inherit; box-sizing: border-box; }
.form-actions { display: flex; align-items: center; gap: 10px; margin-top: 10px; }
.form-error { color: #ff4d4f; font-size: 13px; }
.content-count { font-size: 12px; color: #999; }
.content-count.over { color: #ff4d4f; }

.note-list { display: flex; flex-direction: column; gap: 10px; }
.note-item { background: #fff; border-radius: 8px; padding: 12px 16px; box-shadow: 0 1px 4px rgba(0,0,0,0.06); }
.note-head { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
.note-category { padding: 2px 10px; border-radius: 10px; font-size: 12px; background: #f0f0f0; color: #555; }
.cat-INVEST_LOGIC { background: #e6f7ff; color: #1890ff; }
.cat-RISK_POINT { background: #fff1f0; color: #ff4d4f; }
.cat-BUY_CONDITION { background: #fff7e6; color: #fa8c16; }
.cat-SELL_CONDITION { background: #f6ffed; color: #52c41a; }
.cat-FREE_NOTE { background: #f9f0ff; color: #722ed1; }
.note-time { color: #999; font-size: 12px; }
.note-ops { margin-left: auto; display: flex; gap: 6px; }
.op-btn { padding: 2px 10px; border: 1px solid #ddd; background: #fff; border-radius: 4px; cursor: pointer; font-size: 12px; }
.op-btn:hover { border-color: #1890ff; color: #1890ff; }
.op-btn.danger:hover { border-color: #ff4d4f; color: #ff4d4f; }
.note-content { font-size: 14px; line-height: 1.7; white-space: pre-wrap; word-break: break-word; }
</style>
