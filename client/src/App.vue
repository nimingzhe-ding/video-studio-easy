<script setup>
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue';
import { api, CHUNK_THRESHOLD, chunkUploadId } from './api';
import {
  MODE_LABELS,
  modeLabels,
  modeOf,
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
const props=defineProps({account:{type:Object,required:true}});
const emit=defineEmits(['logout']);
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
const upload = reactive({ percent: 0, name: '', size: 0, active: false, chunked: false, error: '' });
let uploadController = null;
const form = reactive({ mode: 'AUTO', goal: '', title: '' });
/**
 * 失败投递运维面板：只有管理员可见。
 * <p>角色来自登录返回的 account.role；后端 /api/admin/** 也有一层 hasRole("ADMIN")，
 * 这里只是不把用不上的入口展示给普通用户，不承担鉴权职责。
 */
const isAdmin = computed(() => props.account?.role === 'ADMIN');
const deliveries = ref([]);
/** 链接导入：B 站 / YouTube 等，需服务端装有 yt-dlp */
const urlImport = reactive({ open: false, url: '', busy: false });

/**
 * 可选模式：清单来自后端注册表（runtime.modes），新增模式不需要前端发版。
 * 接口还没返回时用内置表兜底，避免下拉框在首屏是空的。
 */
const modes = computed(() => {
  const listed = (runtime.value.modes || []).map((item) => ({ value: item.code, label: item.label }));
  const source = listed.length ? listed : Object.entries(MODE_LABELS).map(([value, label]) => ({ value, label }));
  return [{ value: 'AUTO', label: '自动判断' }, ...source];
});
const labelOf = computed(() => modeLabels(runtime.value.modes));

/**
 * 任务上的 mode 现在是对象（code/label/guidance/…），历史数据仍可能是字符串。
 * 直接用它当 map 的 key 会得到 undefined，界面就会把整个对象渲染成 JSON。
 */
const modeText = (mode) => {
  const detail = modeOf(mode);
  return labelOf.value[detail.code] || detail.label || detail.code || '';
};

const TABS = [
  { key: 'report', label: '报告' },
  { key: 'chat', label: '追问' },
  { key: 'transcript', label: '转写与画面' },
  { key: 'log', label: '执行日志' },
  { key: 'realtime', label: '实时对话' }
];

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
const jobCode = computed(() => (job.value ? `NO.${String(job.value.id).padStart(4, '0')}` : ''));
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

const runningCount = computed(() => jobs.value.filter((item) => ['PENDING', 'RUNNING'].includes(item.status)).length);

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
  // 失败投递不参与 5 秒轮询：它是运维视角，手动刷新即可，没必要让每个管理员页面一直挂着请求
  loadDeliveries();
});

/**
 * 刷新失败投递列表。
 * <p>普通用户不请求这个接口：既省一次必然 403 的往返，也避免控制台里出现无意义的错误。
 */
async function loadDeliveries() {
  if (!isAdmin.value) {
    return;
  }
  try {
    deliveries.value = (await api.failedDeliveries(true, 50)) || [];
  } catch (e) {
    notify(e.message, 'err');
  }
}

/** 重投一条失败投递：后端会递增代次并重新排队，成功后从待处理列表里移除 */
async function replayDelivery(item) {
  if (busy.value || !item.replayable) {
    return;
  }
  busy.value = true;
  try {
    await api.replayDelivery(item.id);
    deliveries.value = deliveries.value.filter((row) => row.id !== item.id);
    notify(`任务 NO.${String(item.jobId).padStart(4, '0')} 已重新排队`, 'ok');
    jobs.value = (await api.listJobs(100)) || [];
  } catch (e) {
    notify(e.message, 'err');
  } finally {
    busy.value = false;
  }
}

async function resolveDelivery(item) {
  if (busy.value || item.failureKind !== 'INVALID_MESSAGE') return;
  busy.value = true;
  try {
    await api.resolveDelivery(item.id);
    deliveries.value = deliveries.value.filter((row) => row.id !== item.id);
    notify('异常消息已标记处理，审计记录已保留', 'ok');
  } catch (e) {
    notify(e.message, 'err');
  } finally {
    busy.value = false;
  }
}

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

/**
 * 从网页链接导入视频。
 * <p>请求会阻塞到下载完成（几十秒到几分钟），所以用 busy 状态锁住输入并给出提示，
 * 避免用户重复提交。
 */
async function submitUrlImport() {
  const url = urlImport.url.trim();
  if (!url) {
    notify('请粘贴视频链接', 'warn');
    return;
  }
  urlImport.busy = true;
  try {
    const asset = await api.importUrl(url);
    notify(`已导入「${asset.name}」，时长 ${formatDuration(asset.durationMs)}`, 'ok');
    media.value = (await api.listMedia(100)) || [];
    activeAssetId.value = asset.id;
    urlImport.url = '';
    urlImport.open = false;
  } catch (e) {
    notify(e.message, 'err');
  } finally {
    urlImport.busy = false;
  }
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
  upload.size = file.size;
  upload.percent = 0;
  upload.chunked = file.size > CHUNK_THRESHOLD;
  upload.error = '';
  const controller = new AbortController();
  uploadController = controller;
  try {
    const onProgress = (percent) => (upload.percent = percent);
    const asset = upload.chunked
      ? await api.uploadChunked(file, {
          uploadId: chunkUploadId(file),
          onProgress,
          signal: controller.signal
        })
      : await api.upload(file, onProgress);
    notify(`已收录「${asset.name}」，时长 ${formatDuration(asset.durationMs)}`, 'ok');
    media.value = (await api.listMedia(100)) || [];
    activeAssetId.value = asset.id;
  } catch (e) {
    if (e.name === 'AbortError') {
      notify('已取消上传', 'warn');
    } else {
      // 大文件保留在队列里，用户可以原样点"继续上传"从断点续传
      upload.error = e.message;
      notify(e.message, 'err');
    }
  } finally {
    uploadController = null;
    busy.value = false;
    upload.active = false;
    upload.percent = 0;
    upload.name = '';
    upload.size = 0;
  }
}

function cancelUpload() {
  uploadController?.abort();
}

async function resumeUpload(event) {
  const file = event?.target?.files?.[0];
  if (file) {
    onFiles([file]);
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
      mode: form.mode === 'AUTO' ? '' : form.mode,
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
      mode: form.mode === 'AUTO' ? '' : form.mode
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
async function retryCurrentJob() {
  try { const updated=await api.retryJob(job.value.id); await refreshAll(); await open(updated); }
  catch(e) { notify(e.message); }
}
</script>

<template>
  <div class="app">
    <header class="topbar">
      <button class="ghost icon mobile-only" title="打开侧栏" @click="drawer = !drawer">
        <svg width="17" height="17" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5">
          <path d="M2 4h12M2 8h12M2 12h8" stroke-linecap="round" />
        </svg>
      </button>

      <div class="brand">
        <div class="brand-mark">
          <svg width="17" height="17" viewBox="0 0 18 18" fill="none" stroke="currentColor" stroke-width="1.3">
            <rect x="2.2" y="3.6" width="13.6" height="10.8" rx="1.2" />
            <path d="M2.4 6.4h13.2M2.4 11.6h13.2" opacity="0.55" />
            <path d="M7.6 7.6l3.4 2-3.4 2z" fill="currentColor" stroke="none" />
          </svg>
        </div>
        <div class="brand-text">
          <span class="brand-name">Video Studio</span>
          <span class="brand-sub">长视频 · 长音频理解</span>
        </div>
      </div>

      <div class="topbar-spacer" />

      <div class="topbar-chips">
        <button class="ghost" @click="emit('logout')" :aria-label="'退出账户 '+props.account.username">{{ props.account.username }} · 退出</button>
        <span
          class="status-pill"
          :class="runtime.aiEnabled ? 'on' : 'off'"
          :title="runtime.aiEnabled ? '分析模型已配置' : '模型服务不可用，请联系管理员'"
        >
          <span class="led" />
          <span class="key">MODEL</span>
          <span class="val">{{ runtime.aiEnabled ? runtime.model || '已连接' : '不可用' }}</span>
        </span>
        <span v-if="runningCount" class="status-pill on" title="正在运行的任务数">
          <span class="led" />
          <span class="key">RUN</span>
          <span class="val">{{ runningCount }}</span>
        </span>
        <button class="ghost icon" :title="theme === 'dark' ? '切换到宣纸（亮色）' : '切换到墨室（暗色）'" @click="toggleTheme">
          <svg v-if="theme === 'dark'" width="16" height="16" viewBox="0 0 18 18" fill="none" stroke="currentColor" stroke-width="1.35">
            <circle cx="9" cy="9" r="3.5" />
            <path d="M9 1.4v1.9M9 14.7v1.9M1.4 9h1.9M14.7 9h1.9M3.6 3.6l1.35 1.35M13.05 13.05l1.35 1.35M14.4 3.6l-1.35 1.35M4.95 13.05L3.6 14.4" stroke-linecap="round" />
          </svg>
          <svg v-else width="16" height="16" viewBox="0 0 18 18" fill="none" stroke="currentColor" stroke-width="1.35">
            <path d="M15.2 11.1A6.6 6.6 0 0 1 6.9 2.8a6.7 6.7 0 1 0 8.3 8.3z" stroke-linejoin="round" />
          </svg>
        </button>
      </div>
    </header>

    <div v-if="drawer" class="scrim" @click="drawer = false" />

    <aside class="sidebar" :class="{ open: drawer }">
      <section class="panel">
        <div class="panel-head">
          <span class="index">01</span>
          <h3>投递素材</h3>
          <div class="spacer" />
          <span class="count">视频 / 音频</span>
        </div>
        <div class="panel-body">
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
            <div class="drop-icon">
              <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.4">
                <path d="M12 15.5V3.8M8.2 7.4 12 3.6l3.8 3.8" stroke-linecap="round" stroke-linejoin="round" />
                <path d="M3.8 14.6v3.9a1.6 1.6 0 0 0 1.6 1.6h13.2a1.6 1.6 0 0 0 1.6-1.6v-3.9" stroke-linecap="round" />
              </svg>
            </div>
            <div class="drop-title">{{ upload.active ? upload.name : '拖入文件或点击选择' }}</div>
            <div class="drop-hint">
              <template v-if="upload.active">
                {{ upload.chunked ? '分片上传' : '直传' }} · {{ formatSize(upload.size) }} ·
                {{ upload.percent }}%
              </template>
              <template v-else>mp4 / mov / mkv / mp3 / wav / m4a</template>
            </div>
            <div v-if="upload.active" class="tape" style="margin-top: 10px; width: 100%">
              <i :style="{ width: `${upload.percent}%` }" />
            </div>
          </div>

          <div v-if="runtime.urlImportEnabled" style="margin-top: 10px">
            <button
              v-if="!urlImport.open"
              class="ghost small"
              type="button"
              @click="urlImport.open = true"
            >或用链接导入（B 站 / YouTube）</button>
            <div v-else class="stack" style="gap: 6px">
              <input
                v-model="urlImport.url"
                type="url"
                placeholder="粘贴视频链接"
                :disabled="urlImport.busy"
                @keyup.enter="submitUrlImport"
              />
              <div class="row">
                <span class="hint grow">{{ urlImport.busy ? '正在下载，请稍候…' : '下载完成后会自动入库' }}</span>
                <button class="ghost small" type="button" :disabled="urlImport.busy" @click="urlImport.open = false">取消</button>
                <button class="primary small" type="button" :disabled="urlImport.busy" @click="submitUrlImport">导入</button>
              </div>
            </div>
          </div>

          <div v-if="upload.active && upload.chunked" class="row" style="margin-top: 8px">
            <span class="hint grow">分片上传支持断点续传，中断后重新选择同一文件即可继续</span>
            <button class="ghost small" type="button" @click.stop="cancelUpload">取消</button>
          </div>

          <div v-if="upload.error" class="notice err" style="margin-top: 8px">
            <span class="grow">{{ upload.error }}</span>
            <label class="chip" style="cursor: pointer">
              继续上传
              <input type="file" hidden accept="video/*,audio/*,.mkv,.m4a" @change="resumeUpload" />
            </label>
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

          <label class="field">分析重点</label>
          <textarea
            v-model="form.goal"
            rows="2"
            placeholder="例：整理成可复习的学习笔记，并标出每个结论对应的时间点"
          />

          <button
            class="primary block"
            style="margin-top: 13px"
            :disabled="busy || !asset"
            @click="startAnalysis()"
          >
            {{ asset ? `分析「${asset.name}」` : '请先选择媒体' }}
          </button>
        </div>
      </section>

      <section class="panel">
        <div class="panel-head">
          <span class="index">02</span>
          <h3>素材库</h3>
          <div class="spacer" />
          <span class="count">{{ media.length }}</span>
        </div>
        <div class="panel-body tight">
          <div class="search" style="margin: 0 3px 7px">
            <svg width="13" height="13" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5">
              <circle cx="7" cy="7" r="4.3" />
              <path d="M10.2 10.2 14 14" stroke-linecap="round" />
            </svg>
            <input v-model="mediaFilter" type="search" placeholder="按文件名搜索" />
          </div>
          <div class="list">
            <div v-if="!filteredMedia.length" class="empty">还没有素材，先投递一段视频或音频。</div>
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
              <div v-else class="thumb audio">
                <svg width="15" height="15" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.4">
                  <path d="M5.5 11.6V4.4l6-1.2v7" stroke-linecap="round" stroke-linejoin="round" />
                  <circle cx="4.1" cy="11.9" r="1.5" />
                  <circle cx="10.1" cy="10.4" r="1.5" />
                </svg>
              </div>
              <div class="item-main">
                <div class="item-title">{{ item.name }}</div>
                <div class="item-sub">
                  {{ formatDuration(item.durationMs) }} · {{ formatSize(item.sizeBytes) }}
                  <template v-if="item.width"> · {{ item.width }}×{{ item.height }}</template>
                </div>
              </div>
              <div class="item-actions" @click.stop>
                <button class="ghost icon" title="删除媒体" @click="removeMedia(item)">
                  <svg width="13" height="13" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.4">
                    <path d="M3 4.4h10M6.3 4.4V3.1h3.4v1.3M4.3 4.4l.6 8.2a1 1 0 0 0 1 .9h4.2a1 1 0 0 0 1-.9l.6-8.2" stroke-linecap="round" stroke-linejoin="round" />
                  </svg>
                </button>
              </div>
            </div>
          </div>
        </div>
      </section>

      <section class="panel">
        <div class="panel-head">
          <span class="index">03</span>
          <h3>分析卷宗</h3>
          <div class="spacer" />
          <span class="count">{{ jobs.length }}</span>
        </div>
        <div class="panel-body tight">
          <div class="list">
            <div v-if="!jobs.length" class="empty">还没有分析任务。</div>
            <JobCard
              v-for="item in jobs"
              :key="item.id"
              :job="item"
              :active="selected?.id === item.id"
              :asset-name="assetNames[item.assetId] || ''"
              :mode-labels="labelOf"
              @open="open"
              @cancel="cancelJob"
              @remove="removeJob"
            />
          </div>
        </div>
      </section>

      <section class="panel">
        <div class="panel-head">
          <span class="index">04</span>
          <h3>运行环境</h3>
        </div>
        <div class="panel-body">
          <div class="stack">
            <div class="env-row"><span class="k">大模型</span><span class="v">{{ runtime.model || '-' }}</span></div>
            <div class="env-row"><span class="k">向量模型</span><span class="v">{{ runtime.embeddingModel || '-' }}</span></div>
            <div class="env-row">
              <span class="k">实时对话</span>
              <span class="badge" :class="runtime.realtimeEnabled ? 'ok' : ''" style="margin-left: auto">
                {{ runtime.realtimeEnabled ? '可用' : '未配置' }}
              </span>
            </div>
            <div class="env-row">
              <span class="k">画面描述</span>
              <span
                class="badge"
                :class="runtime.visionCaptionEnabled ? 'accent' : ''"
                style="margin-left: auto"
                :title="runtime.visionCaptionEnabled ? '会对没有文字的帧调用视觉模型' : '默认关闭，避免按帧计费'"
              >
                {{ runtime.visionCaptionEnabled ? '已开启' : '关闭' }}
              </span>
            </div>
            <div class="env-row" title="媒体源文件、关键帧与报告会同步到这个对象存储">
              <span class="k">对象存储</span>
              <span class="badge" :class="runtime.objectStorageEnabled ? 'accent' : ''" style="margin-left: auto">
                {{ runtime.objectStorageEnabled ? runtime.objectStorage || '已连接' : '不可用' }}
              </span>
            </div>
            <div class="env-row" title="优先使用向量库；异常重试后，可使用已有向量或关键词，并标记检索状态">
              <span class="k">向量检索</span>
              <span class="badge" :class="runtime.vectorStoreEnabled ? 'accent' : ''" style="margin-left: auto">
                {{ runtime.vectorStoreEnabled ? runtime.vectorStore || '已连接' : '不可用' }}
              </span>
            </div>
            <div class="hint">模型异常会停止任务；检索服务异常时按配置尝试恢复，并在报告中说明。</div>
          </div>
        </div>
      </section>

      <section v-if="isAdmin" class="panel">
        <div class="panel-head">
          <span class="index">05</span>
          <h3>失败投递</h3>
          <div class="spacer" />
          <button class="ghost icon" title="刷新" @click="loadDeliveries">↻</button>
        </div>
        <div class="panel-body tight">
          <div class="list">
            <div v-if="!deliveries.length" class="empty">没有待处理的失败投递。</div>
            <div v-for="item in deliveries" :key="item.id" class="item delivery-item">
              <div class="item-main">
                <div v-if="item.failureKind === 'INVALID_MESSAGE'" class="item-title">异常队列消息</div>
                <div v-else class="item-title">任务 NO.{{ String(item.jobId).padStart(4, '0') }} · 第 {{ item.generation }} 代</div>
                <div class="item-sub" :title="item.reason">{{ item.reason }}</div>
                <div v-if="item.failureKind === 'INVALID_MESSAGE'" class="item-sub">请排查消息发送方；此消息不能直接重投。</div>
                <div class="item-sub asset-line">{{ formatRelative(item.createdAt) }}</div>
              </div>
              <div class="item-actions">
                <button v-if="item.failureKind === 'INVALID_MESSAGE'" class="ghost" :disabled="busy" @click="resolveDelivery(item)">标记已处理</button>
                <button v-else class="ghost" :disabled="busy || !item.replayable" @click="replayDelivery(item)">重投</button>
              </div>
            </div>
          </div>
        </div>
      </section>
    </aside>

    <main class="stage">
      <div class="stage-inner">
        <div v-if="notice" class="notice" :class="notice.type">{{ notice.message }}</div>

        <div v-if="!job" class="panel">
          <div class="empty" style="padding: 64px 24px">
            <svg class="enso" viewBox="0 0 100 100" aria-hidden="true">
              <circle cx="50" cy="50" r="37" />
            </svg>
            <strong>从一段长视频开始</strong>
            <span>投递素材 → 选择分析模式 → 得到每条结论都能点回原片的报告，并可以继续追问。</span>
          </div>
        </div>

        <template v-else>
          <section class="dossier">
            <div class="dossier-top">
              <div style="min-width: 0; flex: 1">
                <div class="job-code">
                  <span>档案</span>
                  <b>{{ jobCode }}</b>
                  <span>·</span>
                  <span>{{ formatDateTime(job.createdAt) }}</span>
                </div>
                <h2 class="dossier-title">{{ job.title || job.focus || '未命名分析' }}</h2>
              </div>
              <span
                class="badge"
                :class="job.status === 'SUCCEEDED' ? 'ok' : job.status === 'RUNNING' ? 'warn' : job.status === 'FAILED' || job.status === 'CANCELED' ? 'err' : ''"
              >
                <span class="dot" :class="{ pulse: running }" />
                {{ STATUS_LABELS[job.status] || job.status }}
              </span>
            </div>

            <dl class="meta-ledger">
              <div>
                <dt>模式</dt>
                <dd>
                  {{ modeText(job.mode) }}
                  <span v-if="job.modeSource === 'AUTO'" style="color: var(--faint)"> / 模型判定</span>
                </dd>
              </div>
              <div v-if="assetName"><dt>素材</dt><dd>{{ assetName }}</dd></div>
              <div v-if="asset"><dt>时长</dt><dd>{{ formatDuration(asset.durationMs) }}</dd></div>
              <div v-if="job.finishedAt"><dt>完成</dt><dd>{{ formatRelative(job.finishedAt) }}</dd></div>
            </dl>

            <div v-if="job.focus" class="dossier-note">目标 · {{ job.focus }}</div>
            <div v-if="job.modeReason" class="dossier-note muted">判定 · {{ job.modeReason }}</div>

            <div v-if="running" class="tape-row">
              <span class="tape-label">{{ stageText }}</span>
              <div class="tape"><i :style="{ width: `${progress}%` }" /></div>
              <span class="pct mono" style="font-size: 11.5px; color: var(--faint)">{{ progress }}%</span>
            </div>
            <div v-else-if="job.error" class="notice err" style="margin-top: 12px">{{ job.error }}</div>
            <div v-if="job.notices" class="notice warn" style="margin-top: 10px">
              {{ job.notices || '任务提示' }}
            </div>

            <div class="dossier-actions">
              <button v-if="['FAILED','CANCELED'].includes(job.status)" class="secondary" @click="retryCurrentJob">重试任务</button>
              <button class="secondary" :disabled="!asset" @click="reanalyze">换个目标重跑</button>
              <button v-if="running" class="ghost" @click="cancelJob(job)">取消</button>
              <div class="grow" />
              <span class="hint">重跑会复用已提取的语音与画面证据，只重新检索、分析与成文。</span>
            </div>
          </section>

          <div v-if="asset && job.status === 'SUCCEEDED'" class="player">
            <div class="player-head">
              <span>{{ asset.kind === 'VIDEO' ? '原片回放' : '音频回放' }}</span>
              <div class="grow" />
              <span>点击报告里的 [mm:ss] 可跳到对应位置</span>
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

          <nav class="tabbar">
            <button
              v-for="item in TABS"
              :key="item.key"
              :class="{ on: tab === item.key }"
              @click="tab = item.key"
            >
              {{ item.label }}
              <span v-if="item.key === 'log'" class="tab-count">{{ events.length }}</span>
            </button>
          </nav>

          <div v-show="tab === 'report'" class="sheet">
            <ReportView
              :report="report"
              :job-id="job.id"
              :asset="asset"
              :job="job"
              :job-loading="running"
              :mode-labels="labelOf"
              @seek="seek"
              @notice="notify"
            />
          </div>

          <div v-show="tab === 'chat'" class="sheet">
            <ChatPanel :job-id="job.id" :mode="modeOf(job.mode).code" @seek="seek" @notice="notify" />
          </div>

          <div v-show="tab === 'transcript'" class="sheet">
            <TranscriptView :job-id="job.id" :duration-ms="asset?.durationMs || 0" :job-status="job.status" @seek="seek" @notice="notify" />
          </div>

          <div v-show="tab === 'log'" class="sheet">
            <div class="log">
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
                <span v-if="event.progress != null" class="log-pct">{{ event.progress }}%</span>
              </div>
            </div>
          </div>

          <div v-show="tab === 'realtime'" class="sheet">
            <RealtimePanel :enabled="!!runtime.realtimeEnabled" />
          </div>

          <section v-if="jobsOfAsset.length > 1" class="panel" style="border-radius: var(--radius)">
            <div class="panel-head">
              <span class="index">↺</span>
              <h3>同一素材的历史分析</h3>
              <div class="spacer" />
              <span class="count">{{ jobsOfAsset.length }}</span>
            </div>
            <div class="panel-body tight">
              <div class="list scroll-area">
                <div
                  v-for="item in jobsOfAsset"
                  :key="item.id"
                  class="item"
                  :class="{ active: item.id === job.id }"
                  role="button"
                  tabindex="0"
                  @click="open(item)"
                  @keyup.enter="open(item)"
                >
                  <div class="item-main">
                    <div class="item-title">{{ item.title || item.focus || '未命名分析' }}</div>
                    <div class="item-sub">
                      <span class="mono" style="font-size: 10.5px">#{{ String(item.id).padStart(3, '0') }}</span>
                      · {{ modeText(item.mode) }} · {{ STATUS_LABELS[item.status] }} ·
                      {{ formatDateTime(item.createdAt) }}
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </section>
        </template>
      </div>
    </main>
  </div>
</template>
