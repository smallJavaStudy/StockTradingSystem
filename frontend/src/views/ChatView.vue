<template>
  <div class="chat-view">
    <!-- 左侧：会话列表 -->
    <aside class="chat-sidebar">
      <button class="btn-primary chat-new-btn" @click="startNewConversation">＋ 新建对话</button>
      <div class="chat-conv-list">
        <div v-if="conversations.length === 0" class="chat-conv-empty">暂无历史会话</div>
        <div
          v-for="conv in conversations"
          :key="conv.id"
          class="chat-conv-item"
          :class="{ active: conv.id === activeConversationId }"
          @click="selectConversation(conv.id)"
        >
          <div class="chat-conv-title">{{ conv.title || '未命名会话' }}</div>
          <div class="chat-conv-last">{{ conv.lastMessage || '' }}</div>
        </div>
      </div>
    </aside>

    <!-- 右侧：对话区 -->
    <section class="chat-main">
      <div ref="messageListEl" class="chat-messages">
        <div v-if="messages.length === 0 && !streaming" class="chat-welcome">
          <p>👋 我是智能助手，有什么想聊的？</p>
        </div>
        <div
          v-for="(msg, i) in messages"
          :key="i"
          class="chat-msg"
          :class="msg.role === 'user' ? 'msg-user' : 'msg-assistant'"
        >
          <div class="chat-msg-bubble">{{ msg.content }}</div>
        </div>
        <!-- 流式中的回复 -->
        <div v-if="streaming" class="chat-msg msg-assistant">
          <div class="chat-msg-bubble">
            <span v-if="streamingReply">{{ streamingReply }}</span>
            <span v-else class="spinner"></span>
          </div>
        </div>
        <div v-if="errorMsg" class="chat-error">⚠ {{ errorMsg }}</div>
      </div>

      <div class="chat-input-bar">
        <textarea
          v-model="input"
          class="chat-input"
          rows="2"
          placeholder="输入消息，Enter 发送，Shift+Enter 换行"
          :disabled="streaming"
          @keydown.enter.exact.prevent="send"
        ></textarea>
        <button class="btn-primary chat-send-btn" :disabled="streaming || !input.trim()" @click="send">
          {{ streaming ? '回复中…' : '发送' }}
        </button>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, ref } from 'vue'
import {
  getChatUserId,
  listConversations,
  listMessages,
  streamChat,
  type ConversationItem,
  type MessageItem,
} from '../api/chat'

const userId = getChatUserId()

const conversations = ref<ConversationItem[]>([])
const activeConversationId = ref<string | null>(null)
const messages = ref<MessageItem[]>([])
const input = ref('')
const streaming = ref(false)
const streamingReply = ref('')
const errorMsg = ref('')
const messageListEl = ref<HTMLElement | null>(null)

let abortController: AbortController | null = null

async function loadConversations() {
  try {
    const { data } = await listConversations(userId)
    conversations.value = data
  } catch (e) {
    console.error('加载会话列表失败:', e)
  }
}

async function selectConversation(id: string) {
  if (streaming.value) return
  activeConversationId.value = id
  errorMsg.value = ''
  try {
    const { data } = await listMessages(id)
    messages.value = data
    scrollToBottom()
  } catch (e) {
    console.error('加载会话消息失败:', e)
    errorMsg.value = '加载会话消息失败'
  }
}

function startNewConversation() {
  if (streaming.value) return
  activeConversationId.value = null
  messages.value = []
  errorMsg.value = ''
}

async function send() {
  const text = input.value.trim()
  if (!text || streaming.value) return
  input.value = ''
  errorMsg.value = ''
  messages.value.push({ role: 'user', content: text, timestamp: null })
  streaming.value = true
  streamingReply.value = ''
  scrollToBottom()

  abortController = new AbortController()
  try {
    await streamChat(userId, activeConversationId.value, text, {
      onMeta: (conversationId) => {
        activeConversationId.value = conversationId
      },
      onDelta: (content) => {
        streamingReply.value += content
        scrollToBottom()
      },
      onDone: () => {
        finishStream()
      },
      onError: (message) => {
        errorMsg.value = message
        finishStream()
      },
    }, abortController.signal)
    // 流自然结束但没收到 done/error 事件时兜底收尾
    if (streaming.value) finishStream()
  } catch (e: any) {
    if (e?.name !== 'AbortError') {
      errorMsg.value = e?.message || '对话请求失败'
    }
    finishStream()
  }
}

function finishStream() {
  if (streamingReply.value) {
    messages.value.push({ role: 'assistant', content: streamingReply.value, timestamp: null })
  }
  streamingReply.value = ''
  streaming.value = false
  abortController = null
  scrollToBottom()
  loadConversations()
}

function scrollToBottom() {
  nextTick(() => {
    const el = messageListEl.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

onMounted(loadConversations)
onUnmounted(() => abortController?.abort())
</script>

<style scoped>
.chat-view { display: flex; gap: 14px; height: calc(100vh - 110px); min-height: 480px; }

/* -- 会话列表 -- */
.chat-sidebar { width: 240px; flex-shrink: 0; display: flex; flex-direction: column; gap: 10px; }
.chat-new-btn { width: 100%; }
.chat-conv-list { flex: 1; overflow-y: auto; background: #fff; border-radius: 8px; box-shadow: 0 1px 4px rgba(0,0,0,0.06); }
.chat-conv-empty { padding: 24px; text-align: center; color: #999; font-size: 13px; }
.chat-conv-item { padding: 10px 14px; border-bottom: 1px solid #f5f5f5; cursor: pointer; transition: background 0.2s; }
.chat-conv-item:hover { background: #f0f7ff; }
.chat-conv-item.active { background: #e6f7ff; }
.chat-conv-title { font-size: 14px; font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.chat-conv-last { font-size: 12px; color: #999; margin-top: 2px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

/* -- 对话区 -- */
.chat-main { flex: 1; display: flex; flex-direction: column; background: #fff; border-radius: 8px; box-shadow: 0 1px 4px rgba(0,0,0,0.06); overflow: hidden; }
.chat-messages { flex: 1; overflow-y: auto; padding: 20px; }
.chat-welcome { text-align: center; color: #999; padding: 60px 0; font-size: 15px; }

.chat-msg { display: flex; margin-bottom: 14px; }
.chat-msg.msg-user { justify-content: flex-end; }
.chat-msg.msg-assistant { justify-content: flex-start; }
.chat-msg-bubble { max-width: 72%; padding: 10px 14px; border-radius: 10px; font-size: 14px; line-height: 1.7; white-space: pre-wrap; word-break: break-word; }
.msg-user .chat-msg-bubble { background: #1890ff; color: #fff; border-bottom-right-radius: 2px; }
.msg-assistant .chat-msg-bubble { background: #f5f5f5; color: #333; border-bottom-left-radius: 2px; }

.chat-error { color: #ff4d4f; font-size: 13px; text-align: center; padding: 6px 0; }

/* -- 输入区 -- */
.chat-input-bar { display: flex; gap: 10px; padding: 12px 16px; border-top: 1px solid #f0f0f0; align-items: flex-end; }
.chat-input { flex: 1; padding: 8px 12px; border: 1px solid #ddd; border-radius: 6px; font-size: 14px; font-family: inherit; resize: none; }
.chat-input:focus { outline: none; border-color: #1890ff; }
.chat-input:disabled { background: #fafafa; }
.chat-send-btn { white-space: nowrap; }
</style>
