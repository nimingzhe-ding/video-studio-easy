<script setup>
import { computed, ref, watch } from 'vue'
import { marked } from 'marked'

const props = defineProps({
  report: { type: Object, default: null },
  jobId: { type: Number, required: true }
})

const tab = ref('report')
const html = computed(() => {
  if (!props.report || !props.report.markdown) return ''
  return marked.parse(props.report.markdown, { breaks: true })
})

const copied = ref(false)
async function copy() {
  try {
    await navigator.clipboard.writeText(props.report?.markdown || '')
    copied.value = true
    setTimeout(() => (copied.value = false), 1500)
  } catch {
    copied.value = false
  }
}
</script>

<template>
  <div class="card">
    <div class="row" style="margin-bottom: 10px">
      <h3 style="margin: 0">分析报告</h3>
      <span class="spacer"></span>
      <button class="secondary" @click="copy">{{ copied ? '已复制' : '复制 Markdown' }}</button>
      <a :href="`/api/jobs/${jobId}/report/docx`"><button class="secondary">下载 Word</button></a>
      <a :href="`/api/jobs/${jobId}/report/markdown`" target="_blank"><button class="secondary">查看原文</button></a>
    </div>
    <div v-if="report" class="markdown" v-html="html"></div>
    <div v-else class="empty">报告还在生成中…</div>
  </div>
</template>
