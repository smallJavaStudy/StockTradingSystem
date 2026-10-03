import axios from 'axios'

// ────────── 智能对话 API（对接 Agent 项目 ChatService，端口 8081）──────────

const CHAT_BASE = 'http://localhost:8081/api/v1'

const chatApi = axios.create({ baseURL: CHAT_BASE })

export interface ConversationItem {
  id: string
  title: string
  updatedAt: string | null
  lastMessage: string | null
}

export interface MessageItem {
  role: 'user' | 'assistant'
  content: string
  timestamp: string | null
}

/** 当前对话身份：可通过 localStorage 覆盖，默认 small */
export function getChatUserId(): string {
  return localStorage.getItem('chat_user_id') || 'small'
}

export function listConversations(userId: string) {
  return chatApi.get<ConversationItem[]>('/conversations', { params: { userId } })
}

export function listMessages(conversationId: string) {
  return chatApi.get<MessageItem[]>(`/conversations/${conversationId}/messages`)
}

// ────────── 流式对话 ──────────
// POST /chat/stream 返回 text/event-stream，每块一行 JSON：
//   {"type":"meta","conversationId":"<id>"}
//   {"type":"delta","content":"片段"} × N
//   {"type":"done"} / {"type":"error","message":"..."}

export interface StreamCallbacks {
  onMeta: (conversationId: string) => void
  onDelta: (content: string) => void
  onDone: () => void
  onError: (message: string) => void
}

export async function streamChat(
  userId: string,
  conversationId: string | null,
  message: string,
  callbacks: StreamCallbacks,
  signal?: AbortSignal
): Promise<void> {
  const resp = await fetch(`${CHAT_BASE}/chat/stream`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ userId, conversationId, message }),
    signal,
  })
  if (!resp.ok || !resp.body) {
    callbacks.onError(`请求失败: HTTP ${resp.status}`)
    return
  }

  const reader = resp.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  const handleLine = (line: string) => {
    const trimmed = line.trim()
    if (!trimmed) return
    try {
      const evt = JSON.parse(trimmed)
      if (evt.type === 'meta') callbacks.onMeta(evt.conversationId)
      else if (evt.type === 'delta') callbacks.onDelta(evt.content ?? '')
      else if (evt.type === 'done') callbacks.onDone()
      else if (evt.type === 'error') callbacks.onError(evt.message ?? 'stream error')
    } catch {
      console.warn('无法解析流式块:', trimmed)
    }
  }

  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    let idx: number
    while ((idx = buffer.indexOf('\n')) >= 0) {
      handleLine(buffer.slice(0, idx))
      buffer = buffer.slice(idx + 1)
    }
  }
  if (buffer) handleLine(buffer)
}
