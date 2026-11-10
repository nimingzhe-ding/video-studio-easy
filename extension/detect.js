/**
 * 从当前标签页的 URL 判断能否提交给 Video Studio。
 *
 * <p>只是把链接交给服务端的 yt-dlp 判断，不在扩展里解析播放地址——
 * 各站点的播放地址随时会变，把解析逻辑放到扩展里等于永远追不上。
 * 这里只做「看起来是不是视频页」的粗筛，避免用户把任意网页提交过去白等一次下载失败。
 */
const VIDEO_HOSTS = [
  'bilibili.com',
  'b23.tv',
  'youtube.com',
  'youtu.be',
  'vimeo.com',
  'ixigua.com',
  'v.qq.com',
  'youku.com',
  'iqiyi.com',
  'douyin.com'
];

/** 是否是受支持的视频站点 */
export function isVideoPage(url) {
  if (!url) {
    return false;
  }
  let parsed;
  try {
    parsed = new URL(url);
  } catch {
    return false;
  }
  if (!['http:', 'https:'].includes(parsed.protocol)) {
    return false;
  }
  const host = parsed.hostname.toLowerCase();
  return VIDEO_HOSTS.some((known) => host === known || host.endsWith(`.${known}`));
}

/** 判断是不是直链媒体文件 */
export function isDirectMedia(url) {
  if (!url) {
    return false;
  }
  try {
    const path = new URL(url).pathname.toLowerCase();
    return /\.(mp4|mov|mkv|webm|m4v|mp3|m4a|wav|flac)$/.test(path);
  } catch {
    return false;
  }
}

/** 规范化服务地址：去掉尾部斜杠，补上协议 */
export function normalizeBaseUrl(value) {
  const raw = (value || '').trim();
  if (!raw) {
    return 'http://127.0.0.1:9090';
  }
  const withScheme = /^https?:\/\//i.test(raw) ? raw : `http://${raw}`;
  return withScheme.replace(/\/+$/, '');
}

/** 提交后展示给用户的摘要文本 */
export function describeJob(job) {
  if (!job) {
    return '已提交';
  }
  const id = String(job.id ?? '').padStart(4, '0');
  return `任务 NO.${id} 已排队`;
}
