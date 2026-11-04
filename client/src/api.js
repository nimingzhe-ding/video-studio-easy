const BASE = '';

async function request(path, options = {}) {
  const response = await fetch(BASE + path, {
    headers: options.body ? { 'Content-Type': 'application/json' } : {},
    ...options
  });
  const text = await response.text();
  let payload = null;
  try {
    payload = text ? JSON.parse(text) : null;
  } catch (e) {
    throw new Error(`响应不是合法 JSON：${text.slice(0, 120)}`);
  }
  if (!response.ok || (payload && payload.ok === false)) {
    throw new Error((payload && payload.message) || `请求失败（${response.status}）`);
  }
  return payload ? payload.data : null;
}

function json(body) {
  return { method: 'POST', body: JSON.stringify(body) };
}

export const api = {
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
      xhr.send(form);
    });
  },

  listJobs: (limit = 50) => request(`/api/jobs?limit=${limit}`),
  submitJob: (payload) => request('/api/jobs', json(payload)),
  jobDetail: (id) => request(`/api/jobs/${id}`),
  jobEvents: (id, after = 0) => request(`/api/jobs/${id}/events?after=${after}`),
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
