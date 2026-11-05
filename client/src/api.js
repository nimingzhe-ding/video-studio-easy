const BASE = '';
let csrfToken='';
let csrfFlight=null;
async function ensureCsrf() {
  if(csrfToken) return csrfToken;
  if(!csrfFlight) csrfFlight=fetch('/api/auth/csrf',{credentials:'same-origin'})
    .then(async response => {
      if(!response.ok) throw new Error('无法连接工作空间，请稍后重试');
      const payload=await response.json();
      csrfToken=payload.data.token;
      return csrfToken;
    }).finally(() => { csrfFlight=null; });
  return csrfFlight;
}
function sessionExpired(response,path) {
  if(response.status===401 && !path.startsWith('/api/auth/')) window.dispatchEvent(new Event('auth-required'));
}


async function request(path, options = {}) {
  const unsafe=!['GET','HEAD','OPTIONS'].includes((options.method||'GET').toUpperCase());
  const token=unsafe?await ensureCsrf():null;
  const response = await fetch(BASE + path, {
    ...options, credentials:'same-origin',
    headers: { ...(options.body ? { 'Content-Type': 'application/json' } : {}),
      ...options.headers, ...(token ? {'X-XSRF-TOKEN':token} : {}) }
  });
  sessionExpired(response,path);
  const text = await response.text();
  let payload = null;
  try {
    payload = text ? JSON.parse(text) : null;
  } catch (e) {
    throw new Error(`响应不是合法 JSON：${text.slice(0, 120)}`);
  }
  if (!response.ok || (payload && payload.ok === false)) {
    const error=new Error((payload && payload.message) || '请求失败（'+response.status+'）');
    error.status=response.status;
    if(response.status===403) csrfToken='';
    throw error;
  }
  return payload ? payload.data : null;
}

function json(body) {
  return { method: 'POST', body: JSON.stringify(body) };
}

/**
 * 单片上传。用 fetch + FormData，返回后端的最新会话状态。
 */
async function putChunk(uploadId, index, blob, signal) {
  const form = new FormData();
  form.append('file', blob, `chunk-${index}`);
  const response = await fetch(`${BASE}/api/uploads/${encodeURIComponent(uploadId)}/chunks/${index}`, {
    method: 'PUT',
    body: form,
    credentials: 'same-origin',
    headers: {'X-XSRF-TOKEN':await ensureCsrf()},
    signal
  });
  const text = await response.text();
  let payload = null;
  try {
    payload = text ? JSON.parse(text) : null;
  } catch (e) {
    throw new Error(`HTTP ${response.status}`);
  }
  if (!response.ok || (payload && payload.ok === false)) {
    throw new Error((payload && payload.message) || `HTTP ${response.status}`);
  }
  return payload.data;
}

/**
 * 由文件元信息派生稳定的 uploadId。
 *
 * <p>不用内容哈希：几十 GB 的文件算一遍 SHA-256 可能要几分钟，而「同一个文件再次上传」
 * 在浏览器里几乎总是同名同大小同修改时间，用这三者拼出来足够稳定。
 */
export function chunkUploadId(file) {
  const raw = `${file.name}|${file.size}|${file.lastModified || 0}`;
  let hash = 0;
  for (let i = 0; i < raw.length; i++) {
    hash = (hash * 31 + raw.charCodeAt(i)) >>> 0;
  }
  return `up-${hash.toString(16)}-${file.size}`;
}

/** 超过这个大小就走分片上传；下面的配置从服务端获取失败时用它兜底 */
export const CHUNK_THRESHOLD = 32 * 1024 * 1024;

export const api = {
  async csrf() { csrfToken=''; const info=await request('/api/auth/csrf'); csrfToken=info.token; return info; },
  me: () => request('/api/auth/me'),
  login: (body) => request('/api/auth/login',json(body)),
  register: (body) => request('/api/auth/register',json(body)),
  logout: () => request('/api/auth/logout',{method:'POST'}),
  runtime: () => request('/api/jobs/runtime'),

  listMedia: (limit = 50) => request(`/api/media?limit=${limit}`),
  mediaInfo: (id) => request(`/api/media/${id}/info`),
  deleteMedia: (id) => request(`/api/media/${id}`, { method: 'DELETE' }),

  upload(file, onProgress) {
    return new Promise((resolve, reject) => {
      const form = new FormData();
      form.append('file', file);
      const xhr = new XMLHttpRequest();
      xhr.open('POST', `${BASE}/api/media`);
      xhr.upload.onprogress = (event) => {
        if (event.lengthComputable && onProgress) {
          onProgress(Math.round((event.loaded / event.total) * 100));
        }
      };
      xhr.onload = () => {
        let payload = null;
        try {
          payload = JSON.parse(xhr.responseText);
        } catch (e) {
          reject(new Error(`上传失败：HTTP ${xhr.status}`));
          return;
        }
        if (xhr.status >= 200 && xhr.status < 300 && payload.ok !== false) {
          resolve(payload.data);
        } else {
          reject(new Error(payload.message || `上传失败：HTTP ${xhr.status}`));
        }
      };
      xhr.onerror = () => reject(new Error('上传中断，请检查后端是否在线'));
      ensureCsrf().then(token => {
        xhr.withCredentials=true;
        xhr.setRequestHeader('X-XSRF-TOKEN',token);
        xhr.send(form);
      }).catch(reject);
    });
  },

  uploadPolicy: () => request('/api/uploads/policy'),
  uploadStatus: (uploadId) => request(`/api/uploads/${encodeURIComponent(uploadId)}`),
  cancelUpload: (uploadId) => request(`/api/uploads/${encodeURIComponent(uploadId)}`, { method: 'DELETE' }),

  /**
   * 分片上传：按服务端给的分片大小切片，逐片 PUT，最后合并。
   *
   * <p>断点续传靠 uploadId：同一个文件重新上传时用同一个 id，先 init 拿回
   * {@code receivedIndexes}，跳过已经收到过的片。uploadId 用「文件名+大小+最后修改时间」
   * 拼出来，既稳定又不用把文件内容读一遍算哈希（大文件算哈希本身就要几十秒）。
   *
   * <p>单片失败重试 3 次（指数退避）；仍失败则抛出，由调用方展示"继续上传"。
   */
  async uploadChunked(file, { uploadId, onProgress, signal } = {}) {
    const id = uploadId || chunkUploadId(file);
    const session = await request('/api/uploads', {
      method: 'POST',
      body: JSON.stringify({ uploadId: id, name: file.name, totalBytes: file.size })
    });
    const chunkSize = session.chunkSize;
    const received = new Set(session.receivedIndexes || []);
    const total = session.totalChunks;
    let uploadedBytes = session.receivedBytes || 0;

    const report = (bytes) => {
      if (onProgress) {
        onProgress(Math.min(99, Math.round((bytes / file.size) * 100)));
      }
    };
    report(uploadedBytes);

    for (let index = 0; index < total; index++) {
      if (received.has(index)) {
        continue;
      }
      if (signal && signal.aborted) {
        throw new DOMException('已取消上传', 'AbortError');
      }
      const start = index * chunkSize;
      const blob = file.slice(start, Math.min(start + chunkSize, file.size));
      let attempt = 0;
      for (;;) {
        try {
          await putChunk(id, index, blob, signal);
          break;
        } catch (e) {
          attempt++;
          if (attempt >= 3 || (signal && signal.aborted)) {
            throw new Error(`第 ${index + 1}/${total} 片上传失败：${e.message}`);
          }
          await new Promise((r) => setTimeout(r, 300 * attempt));
        }
      }
      uploadedBytes += blob.size;
      report(uploadedBytes);
    }

    const result = await request(`/api/uploads/${encodeURIComponent(id)}/complete`, { method: 'POST' });
    if (onProgress) {
      onProgress(100);
    }
    return result.media;
  },

  listJobs: (limit = 50) => request(`/api/jobs?limit=${limit}`),
  submitJob: (payload) => request('/api/jobs', json(payload)),
  jobDetail: (id) => request(`/api/jobs/${id}`),
  jobEvents: (id, after = 0) => request(`/api/jobs/${id}/events?after=${after}`),
  retryJob: (id) => request('/api/jobs/'+id+'/retry',{method:'POST'}),
  cancelJob: (id) => request(`/api/jobs/${id}/cancel`, { method: 'POST' }),
  deleteJob: (id) => request(`/api/jobs/${id}`, { method: 'DELETE' }),
  reanalyze: (id, payload) => request(`/api/jobs/${id}/reanalyze`, json(payload)),

  transcript: (id, from = 0, size = 2000) => request(`/api/jobs/${id}/transcript?from=${from}&size=${size}`),
  evidence: (id) => request(`/api/jobs/${id}/evidence`),

  chatHistory: (id) => request(`/api/jobs/${id}/chat`),
  ask: (id, question) => request(`/api/jobs/${id}/chat`, json({ question })),
  clearChat: (id) => request(`/api/jobs/${id}/chat`, { method: 'DELETE' }),

  /** 原片流地址，支持 Range，可直接作为 <video src> */
  streamUrl: (mediaId) => `${BASE}/api/media/${mediaId}/stream`,
  thumbnailUrl: (mediaId, at = 1) => `${BASE}/api/media/${mediaId}/thumbnail?at=${at}`,
  frameUrl: (jobId, seq) => `${BASE}/api/jobs/${jobId}/frames/${seq}`,
  docxUrl: (jobId) => `${BASE}/api/jobs/${jobId}/report/docx`,
  markdownUrl: (jobId) => `${BASE}/api/jobs/${jobId}/report/markdown`,
  /** 字幕导出：srt / vtt / txt，与报告里的时间戳同源 */
  subtitleUrl: (jobId, format = 'srt') => `${BASE}/api/jobs/${jobId}/report/subtitle?format=${format}`,

  /**
   * SSE 订阅任务事件。返回关闭函数，组件卸载时务必调用。
   * 后端在任务终止时会补发 done，因此这里只负责把回调转出去。
   */
  streamJob(id, { after = 0, onEvent, onDone, onError } = {}) {
    const source = new EventSource(`${BASE}/api/jobs/${id}/stream?after=${after}`);
    source.addEventListener('event', (message) => {
      try {
        onEvent?.(JSON.parse(message.data));
      } catch (e) {
        /* 单条事件解析失败不应中断订阅 */
      }
    });
    source.addEventListener('done', (message) => {
      let payload = {};
      try {
        payload = JSON.parse(message.data);
      } catch (e) {
        payload = {};
      }
      source.close();
      onDone?.(payload);
    });
    source.onerror = () => {
      source.close();
      onError?.();
    };
    return () => source.close();
  }
};
