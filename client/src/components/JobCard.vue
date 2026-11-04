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
        <span>{{ MODE_LABELS[job.mode] || job.mode }}</span>
        <span class="sep">·</span>
        <span>{{ STATUS_LABELS[job.status] || job.status }}</span>
        <template v-if="running">
          <span class="sep">·</span>
          <span>{{ stageText }} {{ job.progress }}%</span>
        </template>
        <template v-else>
          <span class="sep">·</span>
          <span>{{ formatRelative(job.finishedAt || job.createdAt) }}</span>
        </template>
      </div>
      <div v-if="assetName" class="item-sub asset-line">来自 {{ assetName }}</div>
    </div>
    <div class="item-actions" @click.stop>
      <button v-if="running" class="ghost icon" title="取消任务" @click="emit('cancel', job)">✕</button>
      <button v-else class="ghost icon" title="删除任务" @click="emit('remove', job)">🗑</button>
    </div>
  </div>
</template>

<style scoped>
.job-item {
  align-items: flex-start;
  padding-top: 9px;
  padding-bottom: 9px;
}

.job-rail {
  width: 8px;
  flex: none;
  display: flex;
  justify-content: center;
  padding-top: 7px;
  color: var(--faint);
}

.job-rail.ok {
  color: var(--ok);
}

.job-rail.warn {
  color: var(--warn);
}

.job-rail.err {
  color: var(--err);
}

.job-item .item-sub {
  display: flex;
  gap: 4px;
  align-items: center;
  flex-wrap: wrap;
}

.sep {
  color: var(--faint);
}

.asset-line {
  color: var(--faint);
  font-size: 11px;
  margin-top: 1px;
}
</style>
