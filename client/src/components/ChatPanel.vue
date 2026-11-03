<script setup>
import { nextTick, onMounted, ref } from 'vue'
import { api } from '../api'
import { marked } from 'marked'

const props = defineProps({
  jobId: { type: Number, required: true },
  aiEnabled: { type: Boolean, default: false }
})

const messages = ref([])
const question = ref('')
const sending = ref(false)
const error = ref('')
const box = ref(null)

onMounted(load)

async function load() {
  try {
    messages.value = (await api.chatHistory(props.jobId)) || []
    await scroll()
  } catch (e) {
    error.value = e.message
  }
}

async function ask() {
  const q = question.value.trim()
  if (!q || sending.value) return
  sending.value = true
  error.value = ''
  messages.value.push({ role: 'user', content: q, id: `local-${Date.now()}` })
  question.value = ''
  await scroll()
  try {
    const { answer } = await api.ask(props.jobId, q)
    messages.value.push({ role: 'assistant', content: answer, id: `local-${Date.now()}-a` })
  } catch (e) {
    error.value = e.message
    messages.value.pop()
  } finally {
    sending.value = false
    await scroll()
  }
}

async function clearAll() {
  await api.clearChat(props.jobId)
  messages.value = []
}

async function scroll() {
  await nextTick()
  if (box.value) box.value.scrollTop = box.value.scrollHeight
}

function asHtml(text) {
  return marked.parse(text || '', { breaks: true })
}
</script>

<template>
  <div class="card">
    <div class="row" style="margin-bottom: 10px">
      <h3 style="margin: 0">追问</h3>
      <span class="spacer"></span>
      <button class="secondary" @click="clearAll">清空</button>
    </div>
    <p v-if="!aiEnabled" class="muted">
      未配置大模型（AI_API_KEY），追问会返回基于时间戳证据的检索片段，而不是生成式回答。
    </p>
    <div ref="box" class="chat">
      <div v-if="messages.length === 0" class="empty" style="padding: 20px 0">
        试试问：「第 12 分钟讲了什么？」「把这个结论对应到具体片段」
      </div>
      <div v-for="m in messages" :key="m.id" class="bubble" :class="m.role">
        <div v-if="m.role === 'assistant'" class="markdown" v-html="asHtml(m.content)"></div>
        <template v-else>{{ m.content }}</template>
      </div>
      <div v-if="sending" class="bubble assistant muted">思考中…</div>
    </div>
    <p v-if="error" style="color: var(--err)">{{ error }}</p>
    <div class="row" style="margin-top: 10px">
      <input
        v-model="question"
        type="text"
        placeholder="围绕这份报告继续提问，回答会带 [mm:ss] 时间戳"
        @keyup.enter="ask"
      />
      <button :disabled="sending || !question.trim()" @click="ask">发送</button>
    </div>
  </div>
</template>
