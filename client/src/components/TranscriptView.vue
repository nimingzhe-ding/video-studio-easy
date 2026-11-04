<script setup>
import { computed, ref, watch } from 'vue';
import { api } from '../api';
import { formatDuration } from '../format';
import { highlight } from '../markdown';

const props = defineProps({
  jobId: { type: Number, required: true },
  durationMs: { type: Number, default: 0 }
});
const emit = defineEmits(['seek', 'notice']);

const loading = ref(false);
const segments = ref([]);
const evidence = ref([]);
const filter = ref('all');
const keyword = ref('');
const zoom = ref('');
const error = ref('');

async function load() {
  if (!props.jobId) {
    return;
  }
  loading.value = true;
  error.value = '';
  try {
    const [transcript, visuals] = await Promise.all([api.transcript(props.jobId), api.evidence(props.jobId)]);
    segments.value = transcript?.items || [];
    evidence.value = visuals || [];
  } catch (e) {
    error.value = e.message;
  } finally {
    loading.value = false;
  }
}

watch(() => props.jobId, load, { immediate: true });

const visibleSegments = computed(() => {
  const word = keyword.value.trim().toLowerCase();
  if (!word) {
    return segments.value;
  }
  return segments.value.filter((seg) => (seg.text || '').toLowerCase().includes(word));
});

// 带上「在全量证据里的下标」：服务端 /frames/{seq} 用的是全量下标，
// 过滤后如果直接用 v-for 的 index 会取到别的帧。
const visibleEvidence = computed(() => {
  const word = keyword.value.trim().toLowerCase();
  return evidence.value
    .map((item, index) => ({ item, index }))
    .filter(({ item }) => !word || (displayText(item) || '').toLowerCase().includes(word));
});

function displayText(item) {
  return item.ocrText || item.caption || '';
}

// 说话人配色：按出现的先后顺序分配，同一份转写里颜色稳定
const SPEAKER_COLORS = ['#4f8cff', '#35c07f', '#e0a63c', '#c07ce0', '#3fb9c9', '#e5824b'];
const speakerNames = computed(() => {
  const seen = [];
  for (const seg of segments.value) {
    if (seg.speaker && !seen.includes(seg.speaker)) {
      seen.push(seg.speaker);
    }
  }
  return seen;
});
const speakerColor = computed(() => {
  const map = {};
  speakerNames.value.forEach((name, i) => {
    map[name] = SPEAKER_COLORS[i % SPEAKER_COLORS.length];
  });
  return map;
});

const totalWords = computed(() =>
  segments.value.reduce((sum, seg) => sum + (seg.text ? seg.text.replace(/\s/g, '').length : 0), 0)
);

function jump(ms) {
  emit('seek', ms);
}

function openImage(url) {
  zoom.value = url;
}
</script>

<template>
  <div>
    <div class="transcript-toolbar">
      <div class="search">
        <input v-model="keyword" type="search" placeholder="搜索语音内容或画面文字" />
      </div>
      <button class="secondary" :class="{ on: filter === 'all' }" @click="filter = 'all'">
        全部
      </button>
      <button class="secondary" :class="{ on: filter === 'asr' }" @click="filter = 'asr'">
        语音 {{ segments.length }}
      </button>
      <button class="secondary" :class="{ on: filter === 'ocr' }" @click="filter = 'ocr'">
        画面 {{ evidence.length }}
      </button>
      <button class="ghost icon" title="刷新" :disabled="loading" @click="load">
        <span :class="{ spin: loading }">↻</span>
      </button>
      <div class="export-group" v-if="segments.length">
        <span class="muted small">导出字幕</span>
        <a class="chip" :href="api.subtitleUrl(jobId, 'srt')">SRT</a>
        <a class="chip" :href="api.subtitleUrl(jobId, 'vtt')">VTT</a>
        <a class="chip" :href="api.subtitleUrl(jobId, 'txt')">TXT</a>
      </div>
    </div>

    <div v-if="error" class="notice err" style="margin: 12px 16px">{{ error }}</div>

    <div v-if="loading && !segments.length" class="empty">正在读取转写与画面证据…</div>

    <template v-else>
      <div v-if="filter !== 'ocr'" class="seg-list">
        <div v-if="!visibleSegments.length" class="empty">
          <template v-if="keyword">没有匹配「{{ keyword }}」的语音片段</template>
          <template v-else-if="!segments.length">
            <strong>没有语音内容</strong>
            该素材可能没有音轨，或语音识别未产出结果。
          </template>
        </div>
        <div v-for="(seg, index) in visibleSegments" :key="index" class="seg">
          <div class="seg-time">
            <span class="ts" @click="jump(seg.startMs)">{{ formatDuration(seg.startMs) }}</span>
            <div class="dur">{{ formatDuration(seg.endMs - seg.startMs) }}</div>
          </div>
          <div class="seg-body">
            <span
              v-if="seg.speaker"
              class="speaker"
              :style="{ color: speakerColor[seg.speaker], borderColor: speakerColor[seg.speaker] }"
            >{{ seg.speaker }}</span>
            <div class="seg-text" v-html="highlight(seg.text, keyword)" />
          </div>
        </div>
      </div>

      <div v-if="filter !== 'asr'">
        <div v-if="!visibleEvidence.length" class="empty">
          <template v-if="keyword">没有匹配「{{ keyword }}」的画面文字</template>
          <template v-else><strong>没有画面文字</strong>纯音频素材或画面中没有可识别文本。</template>
        </div>
        <div v-else class="evidence-grid">
          <div v-for="row in visibleEvidence" :key="row.index" class="evidence-card">
            <img
              :src="api.frameUrl(jobId, row.index)"
              :alt="row.item.ocrText || row.item.caption || '关键帧'"
              loading="lazy"
              @click="openImage(api.frameUrl(jobId, row.index))"
            />
            <div class="cap-head">
              <span class="ts" @click="jump(row.item.timeMs)">{{ formatDuration(row.item.timeMs) }}</span>
              <span class="small muted">{{ !row.item.ocrText && row.item.caption ? 'VISION' : row.item.source }}</span>
            </div>
            <div v-if="row.item.ocrText" class="cap" v-html="highlight(row.item.ocrText, keyword)" />
            <div v-if="row.item.caption" class="cap caption" v-html="highlight(row.item.caption, keyword)" />
            <div v-if="!row.item.ocrText && !row.item.caption" class="cap">（该帧未识别到文字）</div>
          </div>
        </div>
      </div>

      <div v-if="!keyword" class="transcript-foot">
        共 {{ segments.length }} 段语音、{{ evidence.length }} 条画面证据，约 {{ totalWords }} 字
        <template v-if="speakerNames.length > 1">· {{ speakerNames.length }} 位说话人</template>
        <template v-if="durationMs">· 覆盖 {{ formatDuration(durationMs) }}</template>
      </div>
    </template>

    <div v-if="zoom" class="lightbox" @click="zoom = ''">
      <img :src="zoom" alt="关键帧预览" />
    </div>
  </div>
</template>

<style scoped>
.dur {
  font-size: 10.5px;
  color: var(--faint);
  margin-top: 2px;
}

/* 说话人标签和正文放在同一列，避免标签占掉一整行的高度 */
.seg-body {
  flex: 1;
  min-width: 0;
}

.speaker {
  display: inline-block;
  margin-bottom: 4px;
  padding: 1px 7px;
  border: 1px solid currentColor;
  border-radius: 20px;
  font-size: 10.5px;
  line-height: 1.5;
  opacity: 0.9;
}

.export-group {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-left: auto;
}

.export-group .chip {
  padding: 4px 10px;
  border-radius: 20px;
  border: 1px solid var(--border);
  background: var(--panel-2);
  color: var(--text-soft);
  font-size: 12px;
  text-decoration: none;
}

.export-group .chip:hover {
  border-color: var(--accent);
  color: var(--accent-strong);
}

.evidence-card .cap.caption {
  color: var(--muted);
  font-style: italic;
  border-top: 1px dashed var(--border);
}

.transcript-foot {
  padding: 12px 16px;
  border-top: 1px solid var(--border);
  color: var(--faint);
  font-size: 12px;
  text-align: center;
}
</style>
