/** 展示用的格式化工具：时长、体积、时间与相对时间 */

export function formatDuration(ms) {
  // 0 是合法时间点（视频开头），只有缺失值才显示占位
  if (ms === null || ms === undefined || Number.isNaN(Number(ms)) || ms < 0) {
    return '--:--';
  }
  const total = Math.round(ms / 1000);
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const seconds = total % 60;
  const pad = (value) => String(value).padStart(2, '0');
  return hours > 0 ? `${hours}:${pad(minutes)}:${pad(seconds)}` : `${pad(minutes)}:${pad(seconds)}`;
}

export function formatSize(bytes) {
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  if (!bytes || bytes < 0) {
    return '0 B';
  }
  let index = 0;
  let value = bytes;
  while (value >= 1024 && index < units.length - 1) {
    value /= 1024;
    index += 1;
  }
  return `${index === 0 ? Math.round(value) : value.toFixed(1)} ${units[index]}`;
}

/** 毫秒 → 00:00 的短格式，用于列表与徽章 */
export function formatClock(ms) {
  return formatDuration(ms);
}

export function formatDateTime(iso) {
  if (!iso) {
    return '';
  }
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return '';
  }
  const pad = (value) => String(value).padStart(2, '0');
  return `${date.getMonth() + 1}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/** 相对时间：「刚刚 / 12 分钟前 / 3 小时前 / 2 天前」 */
export function formatRelative(iso) {
  if (!iso) {
    return '';
  }
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return '';
  }
  const diff = Date.now() - date.getTime();
  if (diff < 60_000) {
    return '刚刚';
  }
  if (diff < 3_600_000) {
    return `${Math.floor(diff / 60_000)} 分钟前`;
  }
  if (diff < 86_400_000) {
    return `${Math.floor(diff / 3_600_000)} 小时前`;
  }
  if (diff < 7 * 86_400_000) {
    return `${Math.floor(diff / 86_400_000)} 天前`;
  }
  return formatDateTime(iso);
}

/**
 * 模式标签的兜底表。
 *
 * <p>真实清单来自后端 /api/jobs/runtime 的 modes 字段——模式已改为配置驱动，
 * 新增模式不该要求前端跟着发版。这里只用于接口尚未返回、或历史任务用了
 * 已经下线的模式时兜底显示。
 */
export const MODE_LABELS = {
  GENERAL: '通用理解',
  LEARNING: '学习笔记',
  REVIEW: '评审纪要',
  CREATION: '创作素材'
};

/** 把后端返回的模式清单合成 code → label 映射；接口不可用时退回内置表 */
export function modeLabels(runtimeModes) {
  const map = { ...MODE_LABELS };
  for (const item of runtimeModes || []) {
    if (item && item.code) {
      map[item.code] = item.label || item.code;
    }
  }
  return map;
}

export const STATUS_LABELS = {
  PENDING: '排队中',
  RUNNING: '分析中',
  SUCCEEDED: '已完成',
  FAILED: '失败',
  CANCELED: '已取消'
};

export const STAGE_LABELS = {
  QUEUED: '排队',
  PROBE: '解析媒体',
  AUDIO: '抽取音轨',
  TRANSCRIBE: '语音识别',
  FRAMES: '关键帧抽取',
  OCR: '画面文字识别',
  SEGMENT: '时间轴对齐',
  CHUNK: '分块与索引',
  RETRIEVE: '检索证据',
  PLAN: '规划提纲',
  EXECUTE: '撰写正文',
  CRITIC: '证据校验',
  REPORT: '生成文档',
  DONE: '完成',
  FAILED: '失败'
};

/** 报告 trace 里的引擎标识 → 中文说明 */
export const ENGINE_LABELS = {
  'cloud-asr': '云端语音识别',
  'vision-ocr': '本地 OCR',
  tesseract: '本地 tesseract'
};

export function engineText(trace) {
  const parts = [];
  if (trace?.asrEngine) {
    parts.push(ENGINE_LABELS[trace.asrEngine] || trace.asrEngine);
  }
  if (trace?.visualEngine) {
    parts.push(ENGINE_LABELS[trace.visualEngine] || trace.visualEngine);
  }
  if (trace?.engine) {
    parts.push(ENGINE_LABELS[trace.engine] || trace.engine);
  }
  return parts.join(' + ');
}
