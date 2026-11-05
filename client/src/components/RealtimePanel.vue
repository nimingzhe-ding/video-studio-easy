<script setup>
import { computed, onUnmounted, ref } from 'vue';

const props = defineProps({ enabled: { type: Boolean, default: false } });

const state = ref('idle'); // idle | connecting | live | error
const logs = ref([]);
const error = ref('');
const seconds = ref(0);
const level = ref(0);
const bars = ref(new Array(24).fill(0.12));
const transcript = ref([]);

let socket = null;
let media = null;
let audioCtx = null;
let sourceNode = null;
let processor = null;
let playbackCtx = null;
let playhead = 0;
let timer = null;

const stateText = computed(() => {
  switch (state.value) {
    case 'connecting':
      return '正在建立通道';
    case 'live':
      return '对话中';
    case 'error':
      return '连接异常';
    default:
      return '未开始';
  }
});

function note(text) {
  const time = new Date().toLocaleTimeString('zh-CN', { hour12: false });
  logs.value.push(`${time}  ${text}`);
  if (logs.value.length > 80) {
    logs.value.shift();
  }
}

async function start() {
  if (!props.enabled) {
    error.value = '服务端未配置 REALTIME_URL / REALTIME_API_KEY，实时对话不可用。';
    return;
  }
  error.value = '';
  state.value = 'connecting';
  note('请求麦克风权限');
  try {
    media = await navigator.mediaDevices.getUserMedia({ audio: true });
  } catch (e) {
    error.value = '无法获取麦克风权限：' + e.message;
    state.value = 'idle';
    return;
  }

  const protocol = location.protocol === 'https:' ? 'wss' : 'ws';
  socket = new WebSocket(`${protocol}://${location.host}/ws/realtime`);
  socket.binaryType = 'arraybuffer';

  socket.onopen = () => {
    state.value = 'live';
    seconds.value = 0;
    timer = setInterval(() => (seconds.value += 1), 1000);
    note('通道已建立，开始推送麦克风音频');
    startCapture();
  };
  socket.onmessage = (event) => {
    if (typeof event.data === 'string') {
      handleEvent(event.data);
    } else {
      playPcm(event.data);
    }
  };
  socket.onerror = () => {
    note('WebSocket 出错');
    state.value = 'error';
  };
  socket.onclose = (event) => {
    note(`通道关闭（${event.code}${event.reason ? ' ' + event.reason : ''}）`);
    cleanup();
  };
}

function handleEvent(raw) {
  let payload;
  try {
    payload = JSON.parse(raw);
  } catch (e) {
    note(raw.slice(0, 160));
    return;
  }
  const type = payload.type || '';
  if (type === 'error') {
    error.value = payload.error?.message || JSON.stringify(payload).slice(0, 200);
    note('服务端错误：' + error.value);
    return;
  }
  if (type.endsWith('audio_transcript.delta') && payload.delta) {
    const last = transcript.value[transcript.value.length - 1];
    if (last && !last.done) {
      last.text += payload.delta;
    } else {
      transcript.value.push({ text: payload.delta, done: false });
    }
    return;
  }
  if (type.endsWith('audio_transcript.done') && payload.transcript) {
    transcript.value.push({ text: payload.transcript, done: true });
    return;
  }
  note(type || '收到未知事件');
}

function startCapture() {
  audioCtx = new AudioContext({ sampleRate: 16000 });
  sourceNode = audioCtx.createMediaStreamSource(media);
  processor = audioCtx.createScriptProcessor(4096, 1, 1);
  processor.onaudioprocess = (event) => {
    const input = event.inputBuffer.getChannelData(0);
    let peak = 0;
    const pcm = new Int16Array(input.length);
    for (let i = 0; i < input.length; i += 1) {
      const sample = Math.max(-1, Math.min(1, input[i]));
      peak = Math.max(peak, Math.abs(sample));
      pcm[i] = sample * 0x7fff;
    }
    const next = Math.round(Math.min(1, peak * 2.6) * 20) / 20;
    level.value = next;
    bars.value = [...bars.value.slice(1), Math.max(0.1, next)];
    if (socket && socket.readyState === WebSocket.OPEN) {
      socket.send(pcm.buffer);
    }
  };
  sourceNode.connect(processor);
  processor.connect(audioCtx.destination);
}

function playPcm(buffer) {
  try {
    playbackCtx = playbackCtx || new AudioContext({ sampleRate: 24000 });
    const samples = new Int16Array(buffer);
    if (!samples.length) {
      return;
    }
    const audio = playbackCtx.createBuffer(1, samples.length, 24000);
    const channel = audio.getChannelData(0);
    for (let i = 0; i < samples.length; i += 1) {
      channel[i] = samples[i] / 0x8000;
    }
    const node = playbackCtx.createBufferSource();
    node.buffer = audio;
    node.connect(playbackCtx.destination);
    // 顺序播放：让播放头跟在上一次结束时间之后，避免重叠
    playhead = Math.max(playhead, playbackCtx.currentTime);
    node.start(playhead);
    playhead += audio.duration;
  } catch (e) {
    note('播放失败：' + e.message);
  }
}

function stop() {
  note('用户主动停止');
  cleanup();
}

function cleanup() {
  if (timer) {
    clearInterval(timer);
    timer = null;
  }
  if (processor) {
    processor.onaudioprocess = null;
    processor.disconnect();
    processor = null;
  }
  if (sourceNode) {
    sourceNode.disconnect();
    sourceNode = null;
  }
  if (audioCtx) {
    audioCtx.close().catch(() => {});
    audioCtx = null;
  }
  if (media) {
    media.getTracks().forEach((track) => track.stop());
    media = null;
  }
  if (socket) {
    try {
      socket.close();
    } catch (e) {
      /* 已经关闭 */
    }
    socket = null;
  }
  level.value = 0;
  bars.value = new Array(24).fill(0.12);
  if (state.value !== 'error') {
    state.value = 'idle';
  }
}

onUnmounted(cleanup);
</script>

<template>
  <div class="realtime">
    <div v-if="!enabled" class="notice warn">
      未配置 REALTIME_URL / REALTIME_API_KEY，实时对话不可用。其余分析能力不受影响。
    </div>

    <div class="rt-status">
      <span class="badge" :class="state === 'live' ? 'ok' : state === 'error' ? 'err' : ''">
        <span class="dot" :class="{ pulse: state === 'live' }" />
        {{ stateText }}
      </span>
      <div class="wave">
        <i v-for="(bar, index) in bars" :key="index" :style="{ height: `${Math.max(8, bar * 100)}%` }" />
      </div>
      <span class="mono small muted" v-if="state === 'live'">
        {{ String(Math.floor(seconds / 60)).padStart(2, '0') }}:{{ String(seconds % 60).padStart(2, '0') }}
      </span>
      <div class="grow" />
      <button v-if="state === 'idle' || state === 'error'" class="primary" :disabled="!enabled" @click="start">
        开始对话
      </button>
      <button v-else class="secondary" @click="stop">结束</button>
    </div>

    <div v-if="error" class="notice err">{{ error }}</div>

    <div class="hint">
      浏览器麦克风 → 本服务 /ws/realtime → 云端实时模型 → 音频与文字回传。服务端只做帧转发，不落盘。
    </div>

    <div v-if="transcript.length" class="rt-transcript">
      <div v-for="(line, index) in transcript" :key="index" class="rt-line">
        {{ line.text }}
      </div>
    </div>

    <div class="rt-log">
      <div v-for="(line, index) in logs.slice().reverse()" :key="index">{{ line }}</div>
      <div v-if="!logs.length" class="muted">暂无事件</div>
    </div>
  </div>
</template>

<style scoped>
/* 波形柱在这里只负责几何，颜色与动效走全局设计 token */
.wave i {
  transition: height 0.12s linear, opacity 0.2s ease;
}
</style>
