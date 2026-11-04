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

const visibleEvidence = computed(() => {
  const word = keyword.value.trim().toLowerCase();
  if (!word) {
    return evidence.value;
  }
  return evidence.value.filter((item) => (item.ocrText || '').toLowerCase().includes(word));
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
          <div class="seg-text" v-html="highlight(seg.text, keyword)" />
        </div>
      </div>

      <div v-if="filter !== 'asr'">
        <div v-if="!visibleEvidence.length" class="empty">
          <template v-if="keyword">没有匹配「{{ keyword }}」的画面文字</template>
          <template v-else><strong>没有画面文字</strong>纯音频素材或画面中没有可识别文本。</template>
        </div>
        <div v-else class="evidence-grid">
          <div v-for="(item, index) in visibleEvidence" :key="index" class="evidence-card">
            <img
              :src="api.frameUrl(jobId, index)"
              :alt="item.ocrText || '关键帧'"
              loading="lazy"
              @click="openImage(api.frameUrl(jobId, index))"
            />
            <div class="cap-head">
              <span class="ts" @click="jump(item.timeMs)">{{ formatDuration(item.timeMs) }}</span>
              <span class="small muted">{{ item.source }}</span>
            </div>
            <div class="cap" v-html="highlight(item.ocrText || '（该帧未识别到文字）', keyword)" />
          </div>
        </div>
      </div>

      <div v-if="!keyword" class="transcript-foot">
        共 {{ segments.length }} 段语音、{{ visibleEvidence.length }} 条画面证据，约 {{ totalWords }} 字
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

.transcript-foot {
  padding: 12px 16px;
  border-top: 1px solid var(--border);
  color: var(--faint);
  font-size: 12px;
  text-align: center;
}
</style>
