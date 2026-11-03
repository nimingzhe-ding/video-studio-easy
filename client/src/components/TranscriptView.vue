<script setup>
import { onMounted, ref, watch } from 'vue'
import { api } from '../api'
import { formatDuration } from '../format'

const props = defineProps({ jobId: { type: Number, required: true } })

const segments = ref([])
const visuals = ref([])
const loading = ref(false)
const error = ref('')
const only = ref('all')

onMounted(load)
watch(() => props.jobId, load)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [t, v] = await Promise.all([api.transcript(props.jobId), api.evidence(props.jobId)])
    segments.value = (t && t.items) || []
    visuals.value = v || []
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

function filtered(list, key) {
  if (only.value === 'all') return list
  return list.filter((item) => (item[key] || '').toLowerCase().includes(only.value))
}
</script>

<template>
  <div class="card">
    <div class="row" style="margin-bottom: 10px">
      <h3 style="margin: 0">转写与画面证据</h3>
      <span class="spacer"></span>
      <span class="muted">语音 {{ segments.length }} 条 · 画面 {{ visuals.length }} 帧</span>
    </div>
    <div class="row" style="margin-bottom: 10px">
      <button class="secondary" :class="{ on: only === 'all' }" @click="only = 'all'">全部</button>
      <button class="secondary" @click="only = 'asr'">仅语音</button>
      <button class="secondary" @click="only = 'ocr'">仅画面文字</button>
    </div>
    <p v-if="error" style="color: var(--err)">{{ error }}</p>
    <p v-if="loading" class="muted">加载中…</p>

    <div v-if="only !== 'ocr'" class="list">
      <div v-for="(s, i) in filtered(segments, 'source')" :key="'s' + i" class="item" style="cursor: default">
        <span class="muted">[{{ formatDuration(s.startMs) }} - {{ formatDuration(s.endMs) }}]</span>
        {{ s.text }}
      </div>
    </div>

    <div v-if="only !== 'asr'" class="list" style="margin-top: 10px">
      <div v-for="(v, i) in visuals" :key="'v' + i" class="item" style="cursor: default">
        <span class="muted">[{{ formatDuration(v.timeMs) }}]</span>
        <span style="white-space: pre-wrap">{{ v.ocrText || '（无文字）' }}</span>
      </div>
    </div>
  </div>
</template>
