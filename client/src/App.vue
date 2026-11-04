<script setup>
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue';
import { api } from './api';
import {
  MODE_LABELS,
  STAGE_LABELS,
  STATUS_LABELS,
  formatDateTime,
  formatDuration,
  formatRelative,
  formatSize
} from './format';
import { parseClock } from './markdown';
import ChatPanel from './components/ChatPanel.vue';
import JobCard from './components/JobCard.vue';
import RealtimePanel from './components/RealtimePanel.vue';
import ReportView from './components/ReportView.vue';
import TranscriptView from './components/TranscriptView.vue';

const media = ref([]);
const jobs = ref([]);
const runtime = ref({});
const selected = ref(null);
const activeAssetId = ref(null);
const detail = ref(null);
const events = ref([]);
const TAB_KEYS = ['report', 'chat', 'transcript', 'log', 'realtime'];
const hashTab = location.hash.replace('#', '');
const tab = ref(TAB_KEYS.includes(hashTab) ? hashTab : 'report');
// URL 里指定了 tab 时（例如 #transcript），首次打开任务不要把它切回去
let keepTabOnce = TAB_KEYS.includes(hashTab);
const notice = ref(null);
const busy = ref(false);
const dragging = ref(false);
const drawer = ref(false);
const mediaFilter = ref('');
const themeParam = new URLSearchParams(location.search).get('theme');
const theme = ref(themeParam === 'light' || themeParam === 'dark' ? themeParam : localStorage.getItem('vse-theme') || 'dark');
const player = ref(null);
const upload = reactive({ percent: 0, name: '', active: false });
const form = reactive({ mode: 'GENERAL', goal: '', title: '' });

const modes = Object.entries(MODE_LABELS).map(([value, label]) => ({ value, label }));

const asset = computed(() => {
  const assetId = activeAssetId.value ?? selected.value?.assetId ?? detail.value?.job?.assetId;
  return media.value.find((item) => item.id === assetId) || null;
});

const assetName = computed(() => asset.value?.name || '');
const job = computed(() => detail.value?.job || selected.value || null);
const report = computed(() => detail.value?.report || null);
const running = computed(() => job.value && ['PENDING', 'RUNNING'].includes(job.value.status));
const progress = computed(() => job.value?.progress ?? 0);
const stageText = computed(() => STAGE_LABELS[job.value?.stage] || job.value?.stage || '排队');
const jobsOfAsset = computed(() => {
  if (!asset.value) {
    return [];
  }
  return jobs.value.filter((item) => item.assetId === asset.value.id);
});

const filteredMedia = computed(() => {
  const word = mediaFilter.value.trim().toLowerCase();
  if (!word) {
    return media.value;
  }
  return media.value.filter((item) => (item.name || '').toLowerCase().includes(word));
});

const assetNames = computed(() => {
  const map = {};
  media.value.forEach((item) => {
    map[item.id] = item.name;
  });
  return map;
});

watch(theme, (value) => {
  document.documentElement.dataset.theme = value;
  localStorage.setItem('vse-theme', value);
}, { immediate: true });

let pollTimer = null;
let closeStream = null;
let reconnectTimer = null;

onMounted(async () => {
  await refreshAll();
  pollTimer = setInterval(pollJobs, 5000);
});

onUnmounted(() => {
  if (pollTimer) {
    clearInterval(pollTimer);
  }
  closeStream?.();
  if (reconnectTimer) {
    clearTimeout(reconnectTimer);
  }
});

async function refreshAll() {
  try {
    const [mediaList, jobList, info] = await Promise.all([api.listMedia(100), api.listJobs(100), api.runtime()]);
    media.value = mediaList || [];
    jobs.value = jobList || [];
    runtime.value = info || {};
    if (!selected.value && jobs.value.length) {
      await open(jobs.value[0]);
    } else if (selected.value) {
      const fresh = jobs.value.find((item) => item.id === selected.value.id);
      if (fresh) {
        selected.value = fresh;
      }
    }
  } catch (e) {
    notify(e.message, 'err');
  }
}

async function pollJobs() {
  try {
    const jobList = (await api.listJobs(100)) || [];
    jobs.value = jobList;
    if (!selected.value) {
      return;
    }
    const fresh = jobList.find((item) => item.id === selected.value.id);
    if (!fresh) {
      return;
    }
    const wasRunning = running.value;
    selected.value = fresh;
    if (detail.value?.job) {
      detail.value.job = { ...detail.value.job, ...fresh };
    }
    if (wasRunning && !['PENDING', 'RUNNING'].includes(fresh.status)) {
      await loadDetail(fresh.id);
    }
  } catch (e) {
    /* 轮询失败保持静默，下一轮再试 */
  }
}

async function open(item) {
  if (!item) {
    return;
  }
  closeStream?.();
  closeStream = null;
  selected.value = item;
  activeAssetId.value = item.assetId;
  drawer.value = false;
  if (!['PENDING', 'RUNNING'].includes(item.status)) {
    if (keepTabOnce) {
      keepTabOnce = false;
    } else {
      tab.value = item.status === 'SUCCEEDED' ? 'report' : 'log';
    }
  }
  await loadDetail(item.id);
  if (['PENDING', 'RUNNING'].includes(item.status)) {
    attachStream(item.id);
  }
}

async function loadDetail(id) {
  try {
    const data = await api.jobDetail(id);
    detail.value = data;
    events.value = data.events || [];
    if (data.job) {
      selected.value = data.job;
    }
  } catch (e) {
    notify(e.message, 'err');
  }
}

function attachStream(id) {
  closeStream?.();
  const lastSeq = events.value.length ? Math.max(...events.value.map((item) => item.seq || 0)) : 0;
  closeStream = api.streamJob(id, {
    after: lastSeq,
    onEvent: (event) => {
      events.value = [...events.value, event].slice(-800);
      if (event.progress != null && detail.value?.job) {
        detail.value.job.progress = event.progress;
        detail.value.job.stage = event.stage || detail.value.job.stage;
      }
    },
    onDone: async (payload) => {
      closeStream = null;
      await loadDetail(id);
      if (payload?.status === 'SUCCEEDED') {
        tab.value = 'report';
      }
      notify(
        payload?.status === 'SUCCEEDED' ? '分析完成，报告已生成' : `任务结束：${STATUS_LABELS[payload?.status] || payload?.status || '未知'}`,
        payload?.status === 'SUCCEEDED' ? 'ok' : 'warn'
      );
    },
    onError: () => {
      closeStream = null;
      reconnectTimer = setTimeout(async () => {
        try {
          const data = await api.jobDetail(id);
          detail.value = data;
          events.value = data.events || [];
          if (['PENDING', 'RUNNING'].includes(data.job?.status)) {
            attachStream(id);
          }
        } catch (e) {
          /* 服务不可用时不再递归重连 */
        }
      }, 3000);
    }
  });
}

function pickFile() {
  document.getElementById('file-input')?.click();
}

async function onFiles(files) {
  const file = files?.[0];
  if (!file) {
    return;
  }
  busy.value = true;
  upload.active = true;
  upload.name = file.name;
  upload.percent = 0;
  try {
    const asset = await api.upload(file, (percent) => (upload.percent = percent));
    notify(`已上传「${asset.name}」，时长 ${formatDuration(asset.durationMs)}`, 'ok');
    media.value = (await api.listMedia(100)) || [];
    activeAssetId.value = asset.id;
  } catch (e) {
    notify(e.message, 'err');
  } finally {
    busy.value = false;
    upload.active = false;
    upload.percent = 0;
    upload.name = '';
  }
}

function onDrop(event) {
  dragging.value = false;
  onFiles(event.dataTransfer?.files);
}

function onDragOver() {
  dragging.value = true;
}

function onDragLeave() {
  dragging.value = false;
}

async function startAnalysis(target = asset.value) {
  if (!target) {
    notify('请先上传或选择一段媒体', 'warn');
    return;
  }
  busy.value = true;
  try {
    const created = await api.submitJob({
      assetId: target.id,
      mode: form.mode,
      goal: form.goal.trim(),
      title: form.title.trim()
    });
    notify('已加入分析队列', 'ok');
    form.title = '';
    jobs.value = (await api.listJobs(100)) || [];
    await open(created);
  } catch (e) {
    notify(e.message, 'err');
  } finally {
    busy.value = false;
  }
}

async function reanalyze() {
  if (!job.value) {
    return;
  }
  busy.value = true;
  try {
    const created = await api.reanalyze(job.value.id, {
      goal: form.goal.trim() || job.value.focus,
      mode: form.mode
    });
    notify('已用同一份证据重新分析', 'ok');
    jobs.value = (await api.listJobs(100)) || [];
    await open(created);
  } catch (e) {
    notify(e.message, 'err');
  } finally {
    busy.value = false;
  }
}

async function cancelJob(target) {
  try {
    await api.cancelJob(target.id);
    notify('已请求取消', 'warn');
    jobs.value = (await api.listJobs(100)) || [];
    if (selected.value?.id === target.id) {
      await loadDetail(target.id);
    }
  } catch (e) {
    notify(e.message, 'err');
  }
}

async function removeJob(target) {
  if (!window.confirm(`删除任务「${target.title || target.focus || target.id}」？该任务的证据与报告会一并清理。`)) {
    return;
  }
  try {
    await api.deleteJob(target.id);
    if (selected.value?.id === target.id) {
      selected.value = null;
      detail.value = null;
      events.value = [];
      closeStream?.();
      closeStream = null;
    }
    jobs.value = (await api.listJobs(100)) || [];
    notify('任务已删除', 'ok');
  } catch (e) {
    notify(e.message, 'err');
  }
}

async function removeMedia(item) {
  if (!window.confirm(`删除媒体「${item.name}」？其下所有任务与文件都会被清理。`)) {
    return;
  }
  try {
    await api.deleteMedia(item.id);
    if (asset.value?.id === item.id) {
      selected.value = null;
      detail.value = null;
      events.value = [];
    }
    media.value = (await api.listMedia(100)) || [];
    jobs.value = (await api.listJobs(100)) || [];
    notify('媒体已删除', 'ok');
  } catch (e) {
    notify(e.message, 'err');
  }
}

/** 报告与转写里的 [mm:ss] 点击后跳到播放器 */
function seek(clock) {
  const ms = typeof clock === 'number' ? clock : parseClock(clock);
  if (player.value) {
    player.value.currentTime = ms / 1000;
    player.value.play?.().catch(() => {});
  }
}

/** 点媒体库只是「选中素材」，真正开跑要点主按钮，避免误点浪费一次分析 */
function selectAsset(item) {
  activeAssetId.value = item.id;
  drawer.value = false;
  const latest = jobs.value.find((job) => job.assetId === item.id);
  if (latest) {
    open(latest);
    return;
  }
  // 该素材还没有任务：清空右侧，让用户先看素材信息再决定是否开跑
  closeStream?.();
  closeStream = null;
  selected.value = null;
  detail.value = null;
  events.value = [];
  tab.value = 'report';
}

function notify(message, type = 'ok') {
  notice.value = { message, type };
  setTimeout(() => {
    if (notice.value?.message === message) {
      notice.value = null;
    }
  }, 5000);
}

function toggleTheme() {
  theme.value = theme.value === 'dark' ? 'light' : 'dark';
}
</script>

<template>
  <div class="app">
    <header class="topbar">
      <button class="ghost icon mobile-only" title="切换侧栏" @click="drawer = !drawer">☰</button>
      <div class="brand">
        <div class="brand-mark">▶</div>
        <span>Video Studio Easy</span>
        <span class="brand-sub">长视频 / 长音频理解</span>
      </div>
      <div class="topbar-spacer" />
      <div class="topbar-chips">
        <span class="badge" :class="runtime.aiEnabled ? 'ok' : 'warn'" :title="runtime.aiEnabled ? '' : '未配置 AI_API_KEY，报告与追问使用离线模板'">
          <span class="dot" />
          {{ runtime.aiEnabled ? `模型 ${runtime.model}` : '离线模式' }}
        </span>
        <span v-if="runtime.realtimeEnabled" class="badge accent"><span class="dot" />实时对话</span>
        <button class="ghost icon" :title="theme === 'dark' ? '切换到亮色' : '切换到暗色'" @click="toggleTheme">
          {{ theme === 'dark' ? '☀' : '☾' }}
        </button>
      </div>
    </header>

    <div v-if="drawer" class="scrim" @click="drawer = false" />

    <aside class="sidebar" :class="{ open: drawer }">
      <div class="card">
        <div class="card-head">
          <h3>上传素材</h3>
          <div class="spacer" />
          <span class="count">视频 / 音频</span>
        </div>
        <div class="card-body">
          <div
            class="drop"
            :class="{ active: dragging }"
            role="button"
            tabindex="0"
            @click="pickFile"
            @keyup.enter="pickFile"
            @dragover.prevent="onDragOver"
            @dragleave.prevent="onDragLeave"
            @drop.prevent="onDrop"
          >
            <div class="drop-icon">⤒</div>
            <div class="drop-title">{{ upload.active ? upload.name : '拖入文件或点击选择' }}</div>
            <div class="drop-hint">支持 mp4 / mov / mkv / mp3 / wav / m4a</div>
            <div v-if="upload.active" class="progress" style="margin-top: 10px">
              <i :style="{ width: `${upload.percent}%` }" />
            </div>
          </div>
          <input
            id="file-input"
            type="file"
            accept="video/*,audio/*,.mkv,.m4a"
            hidden
            @change="onFiles($event.target.files)"
          />

          <label class="field">分析模式</label>
          <select v-model="form.mode">
            <option v-for="mode in modes" :key="mode.value" :value="mode.value">{{ mode.label }}</option>
          </select>

          <label class="field">分析重点（可留空）</label>
          <textarea
            v-model="form.goal"
            rows="2"
            placeholder="例：整理成可复习的学习笔记，并标出每个结论对应的时间点"
          />

          <button
            class="primary block"
            style="margin-top: 12px"
            :disabled="busy || !asset"
            @click="startAnalysis()"
          >
            {{ asset ? `分析「${asset.name}」` : '请先选择媒体' }}
          </button>
        </div>
      </div>

      <div class="card">
        <div class="card-head">
          <h3>媒体库</h3>
          <div class="spacer" />
          <span class="count">{{ media.length }}</span>
        </div>
        <div class="card-body tight">
          <div class="search" style="margin: 0 0 6px">
            <input v-model="mediaFilter" type="search" placeholder="按文件名搜索" />
          </div>
          <div class="list">
            <div v-if="!filteredMedia.length" class="empty">还没有素材，先上传一段视频或音频。</div>
            <div
              v-for="item in filteredMedia"
              :key="item.id"
              class="item"
              :class="{ active: asset?.id === item.id }"
              role="button"
              tabindex="0"
              @click="selectAsset(item)"
              @keyup.enter="selectAsset(item)"
            >
              <img
                v-if="item.kind === 'VIDEO'"
                class="thumb"
                :src="api.thumbnailUrl(item.id, 1)"
                :alt="item.name"
                loading="lazy"
              />
              <div v-else class="thumb audio">♪</div>
              <div class="item-main">
                <div class="item-title">{{ item.name }}</div>
                <div class="item-sub">
                  {{ formatDuration(item.durationMs) }} · {{ formatSize(item.sizeBytes) }}
                  <template v-if="item.width"> · {{ item.width }}×{{ item.height }}</template>
                </div>
              </div>
              <div class="item-actions" @click.stop>
                <button class="ghost icon" title="删除媒体" @click="removeMedia(item)">🗑</button>
              </div>
            </div>
          </div>
        </div>
      </div>

      <div class="card">
        <div class="card-head">
          <h3>分析任务</h3>
          <div class="spacer" />
          <span class="count">{{ jobs.length }}</span>
        </div>
        <div class="card-body tight">
          <div class="list">
            <div v-if="!jobs.length" class="empty">还没有分析任务。</div>
            <JobCard
              v-for="item in jobs"
              :key="item.id"
              :job="item"
              :active="selected?.id === item.id"
              :asset-name="assetNames[item.assetId] || ''"
              @open="open"
              @cancel="cancelJob"
              @remove="removeJob"
            />
          </div>
        </div>
      </div>

      <div class="card">
        <div class="card-head"><h3>运行环境</h3></div>
        <div class="card-body">
          <div class="stack small muted">
            <div class="row"><span>大模型</span><div class="grow" /><span class="mono">{{ runtime.model || '-' }}</span></div>
            <div class="row"><span>向量模型</span><div class="grow" /><span class="mono">{{ runtime.embeddingModel || '-' }}</span></div>
            <div class="row">
              <span>实时对话</span><div class="grow" />
              <span class="badge" :class="runtime.realtimeEnabled ? 'ok' : ''">{{ runtime.realtimeEnabled ? '可用' : '未配置' }}</span>
            </div>
            <div class="hint">未配置 AI_API_KEY 时，转写、OCR、检索仍可用，报告走离线模板。</div>
          </div>
        </div>
      </div>
    </aside>

    <main class="main">
      <div class="main-inner">
        <div v-if="notice" class="notice" :class="notice.type">{{ notice.message }}</div>

        <div v-if="!job" class="card">
          <div class="empty" style="padding: 56px 20px">
            <strong>从一段长视频开始</strong>
            <span>上传素材 → 选择分析模式 → 得到带时间戳证据的报告，并可以继续追问。</span>
          </div>
        </div>

        <template v-else>
          <div class="card">
            <div class="card-body job-header">
              <div class="job-title-row">
                <h2 class="job-title">{{ job.title || job.focus || '未命名分析' }}</h2>
                <span class="badge" :class="job.status === 'SUCCEEDED' ? 'ok' : job.status === 'RUNNING' ? 'warn' : job.status === 'FAILED' || job.status === 'CANCELED' ? 'err' : ''">
                  <span class="dot" :class="{ pulse: running }" />
                  {{ STATUS_LABELS[job.status] || job.status }}
                </span>
              </div>

              <div class="job-meta">
                <span>{{ MODE_LABELS[job.mode] || job.mode }}</span>
                <span v-if="assetName">素材 {{ assetName }}</span>
                <span v-if="asset">时长 {{ formatDuration(asset.durationMs) }}</span>
                <span>创建 {{ formatDateTime(job.createdAt) }}</span>
                <span v-if="job.finishedAt">完成 {{ formatRelative(job.finishedAt) }}</span>
              </div>

              <div v-if="job.focus" class="hint">目标：{{ job.focus }}</div>

              <template v-if="running">
                <div class="stage-row">
                  <span>{{ stageText }}</span>
                  <div class="progress thin grow"><i :style="{ width: `${progress}%` }" /></div>
                  <span class="pct">{{ progress }}%</span>
                </div>
              </template>
              <div v-else-if="job.error" class="notice err">{{ job.error }}</div>
              <div v-if="job.degraded" class="notice warn">{{ job.notices || '该任务有部分降级处理，请注意核对证据。' }}</div>

              <div class="row wrap">
                <button class="secondary" :disabled="!asset" @click="reanalyze">换个目标重跑</button>
                <button v-if="running" class="ghost" @click="cancelJob(job)">取消</button>
                <div class="grow" />
                <span class="hint">重跑会复用已提取的语音与画面证据，只重新检索、分析与生成报告。</span>
              </div>
            </div>

            <div v-if="asset && job.status === 'SUCCEEDED'" class="player">
              <div class="player-head">
                <span>{{ asset.kind === 'VIDEO' ? '原片回放' : '音频回放' }}</span>
                <div class="grow" />
                <span class="hint">点击报告里的 [mm:ss] 可跳到对应位置</span>
              </div>
              <video
                v-if="asset.kind === 'VIDEO'"
                ref="player"
                :src="api.streamUrl(asset.id)"
                :poster="api.thumbnailUrl(asset.id, 1)"
                controls
                preload="metadata"
              />
              <audio v-else ref="player" :src="api.streamUrl(asset.id)" controls preload="metadata" />
            </div>

            <div class="tabs">
              <button :class="{ on: tab === 'report' }" @click="tab = 'report'">报告</button>
              <button :class="{ on: tab === 'chat' }" @click="tab = 'chat'">追问</button>
              <button :class="{ on: tab === 'transcript' }" @click="tab = 'transcript'">转写与画面</button>
              <button :class="{ on: tab === 'log' }" @click="tab = 'log'">
                执行日志 <span class="tab-count">{{ events.length }}</span>
              </button>
              <button :class="{ on: tab === 'realtime' }" @click="tab = 'realtime'">实时对话</button>
            </div>

            <div v-show="tab === 'report'">
              <ReportView
                :report="report"
                :job-id="job.id"
                :asset="asset"
                :job="job"
                :job-loading="running"
                @seek="seek"
                @notice="notify"
              />
            </div>

            <div v-show="tab === 'chat'" class="card-body">
              <ChatPanel :job-id="job.id" :ai-enabled="!!runtime.aiEnabled" :mode="job.mode" @seek="seek" @notice="notify" />
            </div>

            <div v-show="tab === 'transcript'">
              <TranscriptView :job-id="job.id" :duration-ms="asset?.durationMs || 0" @seek="seek" @notice="notify" />
            </div>

            <div v-show="tab === 'log'" class="log">
              <div v-if="!events.length" class="empty">暂无事件</div>
              <div
                v-for="(event, index) in events"
                :key="index"
                class="log-row"
                :class="{ warn: event.level === 'WARN' || event.type === 'WARNING', error: event.type === 'ERROR' || event.level === 'ERROR' }"
              >
                <span class="log-seq">{{ event.seq }}</span>
                <span class="log-stage">{{ event.stage }}</span>
                <span class="log-msg">{{ event.message }}</span>
                <span v-if="event.progress != null" class="mono small muted">{{ event.progress }}%</span>
              </div>
            </div>

            <div v-show="tab === 'realtime'">
              <RealtimePanel :enabled="!!runtime.realtimeEnabled" />
            </div>
          </div>

          <div v-if="jobsOfAsset.length > 1" class="card">
            <div class="card-head">
              <h3>同一素材的历史分析</h3>
              <div class="spacer" />
              <span class="count">{{ jobsOfAsset.length }}</span>
            </div>
            <div class="card-body tight">
              <div class="list">
                <div
                  v-for="item in jobsOfAsset"
                  :key="item.id"
                  class="item"
                  :class="{ active: item.id === job.id }"
                  role="button"
                  tabindex="0"
                  @click="open(item)"
                >
                  <div class="item-main">
                    <div class="item-title">{{ item.title || item.focus || '未命名分析' }}</div>
                    <div class="item-sub">
                      {{ MODE_LABELS[item.mode] }} · {{ STATUS_LABELS[item.status] }} · {{ formatDateTime(item.createdAt) }}
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </template>
      </div>
    </main>
  </div>
</template>
