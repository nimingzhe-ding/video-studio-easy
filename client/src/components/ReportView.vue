<script setup>
import { computed, nextTick, onMounted, ref, watch } from 'vue';
import { api } from '../api';
import { engineText, formatDuration, formatSize, MODE_LABELS } from '../format';
import { anchorHeadings, outlineOf, renderMarkdown } from '../markdown';

const props = defineProps({
  report: { type: Object, default: null },
  jobId: { type: Number, required: true },
  asset: { type: Object, default: null },
  job: { type: Object, default: null },
  jobLoading: { type: Boolean, default: false }
});
const emit = defineEmits(['seek', 'notice']);

const body = ref(null);
const copied = ref(false);
const toc = computed(() => outlineOf(props.report?.markdown || ''));
const html = computed(() => renderMarkdown(props.report?.markdown || ''));

const meta = computed(() => {
  const trace = parseTrace(props.report?.traceJson);
  return {
    duration: props.asset ? formatDuration(props.asset.durationMs) : '',
    size: props.asset ? formatSize(props.asset.sizeBytes) : '',
    mode: props.job ? MODE_LABELS[props.job.mode] || props.job.mode : '',
    engine: engineText(trace),
    asr: trace.asrEngine || '',
    rounds: trace.rounds,
    verified: trace.verified,
    chunks: trace.chunks,
    evidences: trace.visualEvidence,
    segments: trace.transcriptSegments
  };
});

function parseTrace(raw) {
  if (!raw) {
    return {};
  }
  try {
    return JSON.parse(raw);
  } catch (e) {
    return {};
  }
}

async function decorate() {
  await nextTick();
  anchorHeadings(body.value);
}

onMounted(decorate);
watch(() => props.report?.markdown, decorate);

function go(id) {
  const target = body.value?.querySelector(`#${CSS.escape(id)}`);
  target?.scrollIntoView({ behavior: 'smooth', block: 'start' });
}

/** 时间戳点击统一抛给外层的播放器 */
function onBodyClick(event) {
  const node = event.target.closest('.ts');
  if (node) {
    emit('seek', node.dataset.ts);
  }
}

async function copyMarkdown() {
  if (!props.report?.markdown) {
    return;
  }
  try {
    await navigator.clipboard.writeText(props.report.markdown);
    copied.value = true;
    setTimeout(() => (copied.value = false), 1600);
  } catch (e) {
    emit('notice', '复制失败，浏览器未授权剪贴板');
  }
}

function printReport() {
  window.open('/api/jobs/' + props.jobId + '/report/markdown', '_blank');
}
</script>

<template>
  <div v-if="!report" class="empty">
    <strong>{{ jobLoading ? '正在生成报告…' : '报告还没有生成' }}</strong>
    <span v-if="job?.status === 'RUNNING'">分析仍在进行，完成后会自动展示。</span>
    <span v-else-if="job?.status === 'FAILED'">任务失败，可在「执行日志」查看原因。</span>
    <span v-else>选择或新建一个分析任务。</span>
  </div>

  <div v-else>
    <div class="report-actions">
      <button class="secondary" @click="copyMarkdown">{{ copied ? '✓ 已复制' : '复制 Markdown' }}</button>
      <a class="btn-link" :href="api.docxUrl(jobId)">下载 Word</a>
      <a class="btn-link" :href="api.markdownUrl(jobId)" target="_blank" rel="noopener">查看原文</a>
      <button class="ghost" @click="printReport">打印</button>
      <div class="grow" />
      <span class="badge" :class="meta.verified === false ? 'warn' : 'ok'">
        <span class="dot" />
        {{ meta.verified === false ? '证据部分待核验' : '证据已校验' }}
      </span>
    </div>

    <div class="report-meta">
      <span v-if="meta.mode">{{ meta.mode }}</span>
      <span v-if="meta.duration">时长 {{ meta.duration }}</span>
      <span v-if="meta.size">大小 {{ meta.size }}</span>
      <span v-if="meta.segments">语音片段 {{ meta.segments }}</span>
      <span v-if="meta.evidences">画面证据 {{ meta.evidences }}</span>
      <span v-if="meta.engine">生成方式 {{ meta.engine }}</span>
      <span v-if="meta.rounds">迭代 {{ meta.rounds }} 轮</span>
    </div>

    <div class="report-layout">
      <article ref="body" class="markdown" v-html="html" @click="onBodyClick" />
      <nav v-if="toc.length > 2" class="report-toc">
        <h4>目录</h4>
        <a
          v-for="item in toc"
          :key="item.id"
          :class="{ lvl3: item.level === 3 }"
          href="javascript:void(0)"
          @click="go(item.id)"
          >{{ item.text }}</a
        >
      </nav>
    </div>
  </div>
</template>

<style scoped>
.report-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  padding: 12px 16px;
  border-bottom: 1px solid var(--border);
}

.report-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 16px;
  padding: 10px 16px;
  color: var(--muted);
  font-size: 12.5px;
  border-bottom: 1px solid var(--border);
  background: var(--panel-2);
}

.report-layout {
  padding: 18px 20px 26px;
}

.btn-link {
  display: inline-flex;
  align-items: center;
  padding: 7px 13px;
  border-radius: var(--radius);
  border: 1px solid var(--border);
  color: var(--text-soft);
  background: transparent;
  font-size: 14px;
}

.btn-link:hover {
  border-color: var(--accent);
  color: var(--accent-strong);
}
</style>
