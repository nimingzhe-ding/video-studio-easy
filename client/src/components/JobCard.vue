<script setup>
import { computed } from 'vue';
import { MODE_LABELS, STAGE_LABELS, STATUS_LABELS, formatRelative } from '../format';

const props = defineProps({
  job: { type: Object, required: true },
  active: { type: Boolean, default: false },
  assetName: { type: String, default: '' }
});
const emit = defineEmits(['open', 'cancel', 'remove']);

const statusClass = computed(() => {
  switch (props.job.status) {
    case 'SUCCEEDED':
      return 'ok';
    case 'RUNNING':
      return 'warn';
    case 'FAILED':
    case 'CANCELED':
      return 'err';
    default:
      return '';
  }
});

const running = computed(() => props.job.status === 'RUNNING' || props.job.status === 'PENDING');
const stageText = computed(() => STAGE_LABELS[props.job.stage] || props.job.stage || '排队');
const title = computed(() => props.job.title || props.job.focus || '未命名分析');
const code = computed(() => `#${String(props.job.id).padStart(3, '0')}`);
</script>

<template>
  <div
    class="item job-item"
    :class="{ active }"
    role="button"
    tabindex="0"
    @click="emit('open', job)"
    @keyup.enter="emit('open', job)"
  >
    <div class="job-rail" :class="statusClass">
      <span class="dot" :class="{ pulse: running }" />
    </div>
    <div class="item-main">
      <div class="item-title">{{ title }}</div>
      <div class="item-sub">
        <span class="mono" style="font-size: 10.5px">{{ code }}</span>
        <span class="sep">·</span>
        <span>{{ MODE_LABELS[job.mode] || job.mode }}</span>
        <span class="sep">·</span>
        <span v-if="running">{{ stageText }} {{ job.progress }}%</span>
        <span v-else>{{ STATUS_LABELS[job.status] || job.status }}</span>
      </div>
      <div class="item-sub asset-line">
        <template v-if="assetName">{{ assetName }}</template>
        <template v-if="!running"> · {{ formatRelative(job.finishedAt || job.createdAt) }}</template>
      </div>
    </div>
    <div class="item-actions" @click.stop>
      <button v-if="running" class="ghost icon" title="取消任务" @click="emit('cancel', job)">
        <svg width="12" height="12" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.6">
          <path d="M3.6 3.6l8.8 8.8M12.4 3.6l-8.8 8.8" stroke-linecap="round" />
        </svg>
      </button>
      <button v-else class="ghost icon" title="删除任务" @click="emit('remove', job)">
        <svg width="13" height="13" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.4">
          <path d="M3 4.4h10M6.3 4.4V3.1h3.4v1.3M4.3 4.4l.6 8.2a1 1 0 0 0 1 .9h4.2a1 1 0 0 0 1-.9l.6-8.2" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
      </button>
    </div>
  </div>
</template>

<style scoped>
.sep {
  color: var(--faint);
  opacity: 0.7;
}

.asset-line {
  color: var(--faint);
  font-size: 11px;
  margin-top: 2px;
  letter-spacing: 0.02em;
}
</style>
