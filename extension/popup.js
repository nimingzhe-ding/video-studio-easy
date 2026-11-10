import { describeJob, isDirectMedia, isVideoPage, normalizeBaseUrl } from './detect.js';

const DEFAULT_BASE = 'http://127.0.0.1:9090';
const $ = (id) => document.getElementById(id);

/**
 * 扩展与后端的交互全部走 cookie：用户在网页端登录过，
 * 扩展的请求会自动带上同一个会话（同源策略下 fetch 默认携带凭证）。
 * 因此扩展不保存任何凭证，只保存服务地址。
 */
async function baseUrl() {
  const stored = await chrome.storage.local.get('baseUrl');
  return normalizeBaseUrl(stored.baseUrl || DEFAULT_BASE);
}

/** 带 CSRF 的写请求：Spring Security 要求写操作带 X-XSRF-TOKEN */
async function submitJob(base, url, goal) {
  const csrfResponse = await fetch(`${base}/api/auth/csrf`, { credentials: 'include' });
  if (!csrfResponse.ok) {
    throw new Error('无法连接服务，请检查地址与登录状态');
  }
  const csrf = await csrfResponse.json();
  const token = csrf?.data?.token;
  const header = csrf?.data?.headerName || 'X-XSRF-TOKEN';
  if (!token) {
    throw new Error('获取 CSRF 令牌失败');
  }

  const mediaResponse = await fetch(`${base}/api/media/from-url`, {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json', [header]: token },
    body: JSON.stringify({ url })
  });
  const mediaPayload = await mediaResponse.json();
  if (!mediaResponse.ok || mediaPayload.ok === false) {
    throw new Error(mediaPayload.message || `导入失败（HTTP ${mediaResponse.status}）`);
  }

  const jobResponse = await fetch(`${base}/api/jobs`, {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json', [header]: token },
    body: JSON.stringify({ assetId: mediaPayload.data.id, goal, mode: '', title: '' })
  });
  const jobPayload = await jobResponse.json();
  if (!jobResponse.ok || jobPayload.ok === false) {
    throw new Error(jobPayload.message || `提交失败（HTTP ${jobResponse.status}）`);
  }
  return jobPayload.data;
}

function setStatus(kind, text) {
  const el = $('status');
  el.hidden = false;
  el.className = `status ${kind}`;
  el.textContent = text;
}

function showDetected(url) {
  const found = $('detect-found');
  const empty = $('detect-empty');
  if (!url) {
    found.hidden = true;
    empty.hidden = false;
    return;
  }
  found.hidden = false;
  empty.hidden = true;
  $('detected-url').textContent = url;
}

async function init() {
  const base = await baseUrl();
  $('base-url').value = base;

  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  const url = tab?.url || '';
  const supported = isVideoPage(url) || isDirectMedia(url);
  showDetected(supported ? url : '');

  $('settings-toggle').addEventListener('click', () => {
    const panel = $('settings');
    panel.hidden = !panel.hidden;
  });

  $('base-url').addEventListener('change', async (event) => {
    const normalized = normalizeBaseUrl(event.target.value);
    event.target.value = normalized;
    await chrome.storage.local.set({ baseUrl: normalized });
    setStatus('busy', '服务地址已保存');
  });

  $('submit').addEventListener('click', async () => {
    if (!supported) {
      return;
    }
    const button = $('submit');
    button.disabled = true;
    setStatus('busy', '正在下载并提交，长视频可能需要几分钟…');
    try {
      const job = await submitJob(await baseUrl(), url, $('goal').value.trim());
      setStatus('ok', `${describeJob(job)}。到工作空间查看进度。`);
    } catch (error) {
      setStatus('err', error.message || '提交失败');
    } finally {
      button.disabled = false;
    }
  });
}

init();
