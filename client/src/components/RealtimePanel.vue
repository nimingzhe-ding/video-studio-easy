<script setup>
import { onUnmounted, ref } from 'vue'

const props = defineProps({ enabled: { type: Boolean, default: false } })

const state = ref('idle')
const log = ref([])
const error = ref('')
let socket = null
let media = null
let recorder = null
let audioCtx = null
let playbackCtx = null
let pendingChunks = []
let playing = false

function note(message) {
  log.value.push(`${new Date().toLocaleTimeString()} ${message}`)
  if (log.value.length > 60) log.value.shift()
}

async function start() {
  if (!props.enabled) {
    error.value = '服务端未配置 REALTIME_URL / REALTIME_API_KEY'
    return
  }
  error.value = ''
  state.value = 'connecting'
  try {
    media = await navigator.mediaDevices.getUserMedia({ audio: true })
  } catch (e) {
    error.value = '无法获取麦克风权限：' + e.message
    state.value = 'idle'
    return
  }

  const protocol = location.protocol === 'https:' ? 'wss' : 'ws'
  socket = new WebSocket(`${protocol}://${location.host}/ws/realtime`)
  socket.binaryType = 'arraybuffer'

  socket.onopen = () => {
    state.value = 'live'
    note('通道已建立，开始推流')
    startRecording()
  }
  socket.onmessage = (event) => {
    if (typeof event.data === 'string') {
      note(event.data.slice(0, 200))
      handleServerEvent(event.data)
    } else {
      playPcm(event.data)
    }
  }
  socket.onerror = () => note('连接出错')
  socket.onclose = (e) => {
    note('通道已关闭：' + (e.reason || e.code))
    stopEverything()
  }
}

function handleServerEvent(raw) {
  try {
    const payload = JSON.parse(raw)
    if (payload.type === 'response.audio_transcript.delta' && payload.delta) {
      log.value.push('模型：' + payload.delta)
    }
  } catch {
    /* 非 JSON 帧原样记录即可 */
  }
}

function startRecording() {
  audioCtx = new AudioContext({ sampleRate: 16000 })
  const source = audioCtx.createMediaStreamSource(media)
  const processor = audioCtx.createScriptProcessor(4096, 1, 1)
  processor.onaudioprocess = (event) => {
    if (!socket || socket.readyState !== WebSocket.OPEN) return
    const input = event.inputBuffer.getChannelData(0)
    const pcm = new Int16Array(input.length)
    for (let i = 0; i < input.length; i += 1) {
      pcm[i] = Math.max(-1, Math.min(1, input[i])) * 0x7fff
    }
    socket.send(pcm.buffer)
  }
  source.connect(processor)
  processor.connect(audioCtx.destination)
  recorder = { source, processor }
}

function playPcm(buffer) {
  try {
    playbackCtx = playbackCtx || new AudioContext({ sampleRate: 24000 })
    const samples = new Int16Array(buffer)
    if (samples.length === 0) return
    const audio = playbackCtx.createBuffer(1, samples.length, 24000)
    const channel = audio.getChannelData(0)
    for (let i = 0; i < samples.length; i += 1) channel[i] = samples[i] / 0x8000
    const source = playbackCtx.createBufferSource()
    source.buffer = audio
    source.connect(playbackCtx.destination)
    source.start()
    playing = true
  } catch (e) {
    note('播放失败：' + e.message)
  }
}

function stop() {
  stopEverything()
  note('已停止')
}

function stopEverything() {
  if (recorder) {
    recorder.processor.disconnect()
    recorder.source.disconnect()
    recorder = null
  }
  if (audioCtx) {
    audioCtx.close()
    audioCtx = null
  }
  if (media) {
    media.getTracks().forEach((track) => track.stop())
    media = null
  }
  if (socket) {
    try {
      socket.close()
    } catch {
      /* 已关闭 */
    }
    socket = null
  }
  state.value = 'idle'
}

onUnmounted(stopEverything)
</script>

<template>
  <div class="card">
    <div class="row" style="margin-bottom: 10px">
      <h3 style="margin: 0">实时音视频对话</h3>
      <span class="spacer"></span>
      <span class="badge" :class="state === 'live' ? 'ok' : ''">{{ state }}</span>
    </div>
    <p class="muted">
      浏览器麦克风 → 本服务（/ws/realtime）→ 云端实时模型 → 回传语音。仅做帧转发，服务端不落盘。
    </p>
    <p v-if="error" style="color: var(--err)">{{ error }}</p>
    <div class="row" style="margin-bottom: 10px">
      <button v-if="state === 'idle'" :disabled="!enabled" @click="start">开始对话</button>
      <button v-else class="secondary" @click="stop">停止</button>
    </div>
    <div class="log">
      <div v-for="(line, i) in log.slice().reverse()" :key="i">{{ line }}</div>
      <div v-if="log.length === 0" class="muted">暂无日志</div>
    </div>
  </div>
</template>
