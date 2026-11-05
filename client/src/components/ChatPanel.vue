<script setup>
import { computed, nextTick, ref, watch } from 'vue';
import { api } from '../api';
import { renderMarkdown } from '../markdown';

const props = defineProps({
  jobId: { type: Number, required: true },
  mode: { type: String, default: 'GENERAL' }
});
const emit = defineEmits(['seek', 'notice']);

const messages = ref([]);
const question = ref('');
const sending = ref(false);
const error = ref('');
const box = ref(null);

const suggestions = computed(() => {
  if (props.mode === 'LEARNING') {
    return ['这门课的核心概念是什么？', '有哪些容易混淆的知识点？', '给我三道自测题'];
  }
  if (props.mode === 'REVIEW') {
    return ['这次评审最终决定了什么？', '有哪些待办没有明确责任人？', '哪些说法缺乏证据支撑？'];
  }
  if (props.mode === 'CREATION') {
    return ['有哪些可以直接剪成短视频的金句？', '这段内容适合什么标题？', '哪些片段最有传播力？'];
  }
  return ['这段视频讲了什么？', '最重要的三个结论是什么？', '帮我找出和「关键设计」相关的片段'];
});

watch(
  () => props.jobId,
  load,
  { immediate: true }
);

async function load() {
  error.value = '';
  messages.value = [];
  try {
    messages.value = (await api.chatHistory(props.jobId)) || [];
    await scroll();
  } catch (e) {
    error.value = e.message;
  }
}

async function ask(text) {
  const value = (text ?? question.value).trim();
  if (!value || sending.value) {
    return;
  }
  sending.value = true;
  error.value = '';
  messages.value.push({ role: 'user', content: value, local: true, key: `u-${Date.now()}` });
  question.value = '';
  await scroll();
  try {
    const result = await api.ask(props.jobId, value);
    messages.value.push({ role: 'assistant', content: result.answer, local: true, key: `a-${Date.now()}` });
  } catch (e) {
    error.value = e.message;
    messages.value.pop();
  } finally {
    sending.value = false;
    await scroll();
  }
}

async function clearAll() {
  try {
    await api.clearChat(props.jobId);
    messages.value = [];
  } catch (e) {
    emit('notice', e.message, 'err');
  }
}

async function scroll() {
  await nextTick();
  if (box.value) {
    box.value.scrollTop = box.value.scrollHeight;
  }
}

function html(text) {
  return renderMarkdown(text || '');
}

/** 回答里的 [mm:ss] 点击后跳到播放器对应位置 */
function onClick(event) {
  const node = event.target.closest('.ts');
  if (node) {
    emit('seek', node.dataset.ts);
  }
}

function keydown(event) {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault();
    ask();
  }
}
</script>

<template>
  <div class="chat">
    <div ref="box" class="chat-body" @click="onClick">
      <div v-if="!messages.length && !sending" class="empty" style="margin: auto">
        <strong>围绕这份报告继续追问</strong>
        <span>回答会带 [mm:ss] 时间戳，点击可直接跳到原片。</span>
      </div>

      <template v-for="message in messages" :key="message.key || message.id">
        <div class="bubble" :class="message.role">
          <div v-if="message.role === 'assistant'" class="markdown" v-html="html(message.content)" />
          <template v-else>{{ message.content }}</template>
        </div>
      </template>

      <div v-if="sending" class="bubble assistant pending">正在检索证据并组织回答…</div>
    </div>

    <div v-if="error" class="notice err" style="margin: 0 16px 8px">{{ error }}</div>

    <div v-if="!messages.length" class="suggestions">
      <button v-for="item in suggestions" :key="item" class="chip" @click="ask(item)">
        {{ item }}
      </button>
    </div>

    <div class="chat-input">
      <textarea
        v-model="question"
        rows="1"
        :placeholder="'围绕这份报告继续提问（Enter 发送，Shift+Enter 换行）'"
        @keydown="keydown"
      />
      <button class="primary" :disabled="sending || !question.trim()" @click="ask()">发送</button>
      <button class="ghost icon" title="清空对话" @click="clearAll">
        <svg width="13" height="13" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.4">
          <path d="M3 4.4h10M6.3 4.4V3.1h3.4v1.3M4.3 4.4l.6 8.2a1 1 0 0 0 1 .9h4.2a1 1 0 0 0 1-.9l.6-8.2" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
      </button>
    </div>
  </div>
</template>
