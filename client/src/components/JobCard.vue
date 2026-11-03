<script setup>
import { computed } from 'vue'

const props = defineProps({
  job: { type: Object, required: true },
  active: { type: Boolean, default: false }
})

const emit = defineEmits(['open', 'cancel'])

const statusClass = computed(() => {
  const s = props.job.status
  if (s === 'SUCCEEDED') return 'ok'
  if (s === 'FAILED' || s === 'CANCELED') return 'err'
  if (s === 'RUNNING') return 'warn'
  return ''
})

const statusText = computed(() => {
  const map = { PENDING: '排队中', RUNNING: '分析中', SUCCEEDED: '已完成', FAILED: '失败', CANCELED: '已取消' }
  return map[props.job.status] || props.job.status
})

const stageText = computed(() => {
  const map = {
    QUEUED: '排队', PROBE: '解析媒体', AUDIO: '提取音频', TRANSCRIBE: '语音转写',
    FRAMES: '抽取关键帧', OCR: '画面文字识别', SEGMENT: '对齐时间轴', CHUNK: '整理分块',
    RETRIEVE: '检索证据', PLAN: '制定计划', EXECUTE: '生成结论', CRITIC: '校验证据',
    REPORT: '生成报告', DONE: '完成', FAILED: '失败'
  }
  return map[props.job.stage] || props.job.stage
})

const running = computed(() => props.job.status === 'PENDING' || props.job.status === 'RUNNING')
</script>

<template>
  <div class="item" :class="{ active }" @click="emit('open', job)">
    <div class="row">
      <strong style="font-size: 13px">{{ job.title || `任务 #${job.id}` }}</strong>
      <span class="spacer"></span>
      <span class="badge" :class="statusClass">{{ statusText }}</span>
    </div>
    <div class="muted" style="margin-top: 4px">
      #{{ job.id }} · {{ job.mode }} · {{ stageText }} {{ job.progress }}%
    </div>
    <div v-if="running" class="progress" style="margin-top: 6px">
      <i :style="{ width: job.progress + '%' }"></i>
    </div>
    <div v-if="running" class="muted" style="margin-top: 6px">
      <a @click.stop="emit('cancel', job)" style="cursor: pointer">取消任务</a>
    </div>
    <div v-if="job.error" class="muted" style="color: var(--err); margin-top: 4px">
      {{ job.error }}
    </div>
  </div>
</template>
