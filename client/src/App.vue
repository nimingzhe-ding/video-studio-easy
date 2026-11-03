<script setup>
import { onMounted, onUnmounted, reactive, ref } from 'vue'
import { api } from './api'
import JobCard from './components/JobCard.vue'
import ReportView from './components/ReportView.vue'
import ChatPanel from './components/ChatPanel.vue'
import TranscriptView from './components/TranscriptView.vue'
import RealtimePanel from './components/RealtimePanel.vue'
import { formatDuration, formatSize } from './format'

const media = ref([])
const jobs = ref([])
const runtime = ref({ aiEnabled: false, model: '', realtimeEnabled: false })
const selected = ref(null)
const detail = ref(null)
const events = ref([])
const tab = ref('report')
const notice = ref('')
const busy = ref('')
const dragging = ref(false)

const form = reactive({ mode: 'GENERAL', goal: '', title: '' })
const upload = reactive({ percent: 0, name: '' })
const selectedAsset = ref(null)

let stream = null
let poller = null

onMounted(async () => {
  await refreshAll()
  poller = setInterval(refreshJobs, 4000)
})

onUnmounted(() => {
  poller && clearInterval(poller)
  stream && stream.close()
})

async function refreshAll() {
  try {
    runtime.value = await api.runtime()
  } catch {
    notice.value = '后端未就绪：请确认 9090 端口已启动'
  }
  await Promise.all([refreshMedia(), refreshJobs()])
}

async function refreshMedia() {
  try {
    media.value = (await api.listMedia()) || []
  } catch (e) {
    notice.value = e.message
  }
}

async function refreshJobs() {
  try {
    jobs.value = (await api.listJobs()) || []
    const current = selected.value && jobs.value.find((j) => j.id === selected.value.id)
    if (current) selected.value = current
  } catch {
    /* 轮询失败不打扰用户 */
  }
}

async function onFiles(files) {
  const file = files && files[0]
  if (!file) return
  busy.value = 'upload'
  upload.name = file.name
  upload.percent = 0
  notice.value = ''
  try {
    const asset = await api.upload(file, (p) => (upload.percent = p))
    notice.value = `已上传：${asset.name}（${formatSize(asset.sizeBytes)}，时长 ${formatDuration(asset.durationMs)}）`
    selectedAsset.value = asset
    await refreshMedia()
    if (!form.title) form.title = file.name.replace(/\.[^.]+$/, '')
  } catch (e) {
    notice.value = e.message
  } finally {
    busy.value = ''
    upload.percent = 0
  }
}

function pickAsset(asset) {
  selectedAsset.value = asset
  if (!form.title) form.title = asset.name.replace(/\.[^.]+$/, '')
}

async function startJob(assetId) {
  const id = assetId || (selectedAsset.value && selectedAsset.value.id) || (media.value[0] && media.value[0].id)
  if (!id) {
    notice.value = '请先上传一段视频或音频'
    return
  }
  busy.value = 'submit'
  notice.value = ''
  try {
    const job = await api.submitJob({ assetId: id, mode: form.mode, goal: form.goal, title: form.title })
    await refreshJobs()
    open(job)
  } catch (e) {
    notice.value = e.message
  } finally {
    busy.value = ''
  }
}

async function open(job) {
  selected.value = job
  detail.value = null
  events.value = []
  tab.value = 'report'
  stream && stream.close()
  try {
    const body = await api.jobDetail(job.id)
    selected.value = body.job
    detail.value = body
    events.value = body.events || []
    if (!body.job.status || !['SUCCEEDED', 'FAILED', 'CANCELED'].includes(body.job.status)) {
      attachStream(body.job.id, lastSeq())
    }
  } catch (e) {
    notice.value = e.message
  }
}

function lastSeq() {
  return events.value.length ? events.value[events.value.length - 1].seq : 0
}

function attachStream(id, after) {
  stream = api.streamJob(id, {
    after,
    onEvent: (event) => {
      events.value.push(event)
      if (selected.value && selected.value.id === id) {
        selected.value = { ...selected.value, stage: event.stage, progress: event.progress }
      }
    },
    onDone: async (payload) => {
      await refreshJobs()
      if (selected.value && selected.value.id === id) {
        const body = await api.jobDetail(id)
        selected.value = body.job
        detail.value = body
        if (payload.status === 'SUCCEEDED') tab.value = 'report'
      }
    },
    onError: () => {
      if (selected.value && selected.value.id === id) attachStreamLater(id)
    }
  })
}

function attachStreamLater(id) {
  setTimeout(() => {
    if (selected.value && selected.value.id === id) attachStream(id, lastSeq())
  }, 3000)
}

async function cancelJob(job) {
  try {
    await api.cancelJob(job.id)
    await refreshJobs()
  } catch (e) {
    notice.value = e.message
  }
}

async function reanalyze() {
  if (!selected.value) return
  busy.value = 'reanalyze'
  try {
    const job = await api.reanalyze(selected.value.id, { goal: form.goal, mode: form.mode })
    await refreshJobs()
    open(job)
  } catch (e) {
    notice.value = e.message
  } finally {
    busy.value = ''
  }
}

function onDrop(event) {
  dragging.value = false
  onFiles(event.dataTransfer.files)
}
</script>

<template>
  <div class="app">
    <aside class="sidebar">
      <div class="brand">
        Video Studio Easy
        <small>长视频 / 长音频理解与文档生成</small>
      </div>

      <div class="card" style="margin-top: 14px">
        <h3>上传媒体</h3>
        <div
          class="drop"
          :class="{ active: dragging }"
          @dragover.prevent="dragging = true"
          @dragleave.prevent="dragging = false"
          @drop.prevent="onDrop"
          @click="$refs.file.click()"
        >
          <div v-if="busy === 'upload'">{{ upload.name }} · {{ upload.percent }}%</div>
          <div v-else>拖入文件或点击选择<br /><span class="muted">支持 mp4 / mov / mkv / mp3 / m4a / wav</span></div>
        </div>
        <input ref="file" type="file" accept="video/*,audio/*" hidden @change="onFiles($event.target.files)" />

        <div style="margin-top: 10px">
          <label class="muted">分析重点（可留空）</label>
          <textarea v-model="form.goal" rows="2" placeholder="例如：提炼产品评审会里的决策与待办"></textarea>
        </div>
        <div class="row" style="margin-top: 8px">
          <select v-model="form.mode" style="flex: 1">
            <option value="GENERAL">通用总结</option>
            <option value="LEARNING">学习笔记</option>
            <option value="REVIEW">评审纪要</option>
            <option value="CREATION">创作素材</option>
          </select>
          <button :disabled="!!busy" @click="startJob()">开始分析</button>
        </div>
      </div>

      <div class="card">
        <h3>媒体库（{{ media.length }}）</h3>
        <div v-if="media.length === 0" class="muted">还没有上传</div>
        <div class="list">
          <div
            v-for="asset in media"
            :key="asset.id"
            class="item"
            :class="{ active: selectedAsset && selectedAsset.id === asset.id }"
            @click="pickAsset(asset)"
          >
            <div>{{ asset.name }}</div>
            <div class="muted">{{ asset.kind }} · {{ formatDuration(asset.durationMs) }} · {{ formatSize(asset.sizeBytes) }}</div>
          </div>
        </div>
      </div>

      <div class="card">
        <h3>任务（{{ jobs.length }}）</h3>
        <div v-if="jobs.length === 0" class="muted">还没有任务</div>
        <div class="list">
          <JobCard
            v-for="job in jobs"
            :key="job.id"
            :job="job"
            :active="selected && selected.id === job.id"
            @open="open"
            @cancel="cancelJob"
          />
        </div>
      </div>

      <div class="card">
        <h3>运行环境</h3>
        <div class="muted">
          大模型：{{ runtime.aiEnabled ? runtime.model : '未配置（离线模板）' }}<br />
          实时通道：{{ runtime.realtimeEnabled ? '已就绪' : '未配置' }}
        </div>
      </div>
    </aside>

    <main class="main">
      <p v-if="notice" class="card" style="border-color: rgba(79,140,255,.4)">{{ notice }}</p>

      <div v-if="!selected" class="empty">
        上传一段长视频或长音频，然后点击「开始分析」。<br />
        系统会先转写语音、抽取关键帧做 OCR，再分块摘要、检索证据，最后生成带时间戳的报告。
      </div>

      <template v-else>
        <div class="card">
          <div class="row">
            <h2 style="margin: 0; font-size: 18px">{{ selected.title || `任务 #${selected.id}` }}</h2>
            <span class="spacer"></span>
            <span class="badge">{{ selected.mode }}</span>
            <span class="badge">{{ selected.status }}</span>
            <button class="secondary" :disabled="!!busy" @click="reanalyze">换个目标重跑</button>
          </div>
          <div class="progress" style="margin-top: 12px">
            <i :style="{ width: selected.progress + '%' }"></i>
          </div>
          <div class="muted" style="margin-top: 6px">
            {{ selected.stage }} · {{ selected.progress }}%
            <span v-if="selected.error" style="color: var(--err)"> · {{ selected.error }}</span>
          </div>
        </div>

        <div class="tabs">
          <button :class="{ on: tab === 'report' }" @click="tab = 'report'">报告</button>
          <button :class="{ on: tab === 'chat' }" @click="tab = 'chat'">追问</button>
          <button :class="{ on: tab === 'transcript' }" @click="tab = 'transcript'">转写与画面</button>
          <button :class="{ on: tab === 'log' }" @click="tab = 'log'">执行日志</button>
          <button :class="{ on: tab === 'live' }" @click="tab = 'live'">实时对话</button>
        </div>

        <ReportView v-if="tab === 'report'" :job-id="selected.id" :report="detail && detail.report" />
        <ChatPanel v-if="tab === 'chat'" :job-id="selected.id" :ai-enabled="runtime.aiEnabled" />
        <TranscriptView v-if="tab === 'transcript'" :job-id="selected.id" />
        <RealtimePanel v-if="tab === 'live'" :enabled="runtime.realtimeEnabled" />

        <div v-if="tab === 'log'" class="card">
          <h3>执行日志</h3>
          <div class="log">
            <div v-for="event in events" :key="event.seq">
              <span class="t">{{ event.stage }}</span>{{ event.message }}
            </div>
            <div v-if="events.length === 0" class="muted">暂无事件</div>
          </div>
        </div>
      </template>
    </main>
  </div>
</template>
