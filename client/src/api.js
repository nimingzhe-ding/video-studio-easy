const BASE = ''

async function request(path, options = {}) {
  const response = await fetch(BASE + path, options)
  const text = await response.text()
  let body = null
  try {
    body = text ? JSON.parse(text) : null
  } catch {
    throw new Error(`响应不是 JSON（HTTP ${response.status}）：${text.slice(0, 200)}`)
  }
  if (!response.ok || (body && body.ok === false)) {
    throw new Error((body && body.message) || `HTTP ${response.status}`)
  }
  return body ? body.data : null
}

export const api = {
  runtime: () => request('/api/jobs/runtime'),

  listMedia: (limit = 50) => request(`/api/media?limit=${limit}`),
  deleteMedia: (id) => request(`/api/media/${id}`, { method: 'DELETE' }),

  upload(file, onProgress) {
    return new Promise((resolve, reject) => {
      const form = new FormData()
      form.append('file', file)
      const xhr = new XMLHttpRequest()
      xhr.open('POST', '/api/media')
      xhr.upload.onprogress = (event) => {
        if (event.lengthComputable && onProgress) onProgress(Math.round((event.loaded / event.total) * 100))
      }
      xhr.onload = () => {
        try {
          const body = JSON.parse(xhr.responseText)
          if (xhr.status >= 200 && xhr.status < 300 && body.ok) resolve(body.data)
          else reject(new Error(body.message || `HTTP ${xhr.status}`))
        } catch (e) {
          reject(new Error(`上传响应解析失败：HTTP ${xhr.status}`))
        }
      }
      xhr.onerror = () => reject(new Error('上传失败，请检查后端是否在线'))
      xhr.send(form)
    })
  },

  submitJob: (payload) =>
    request('/api/jobs', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    }),
  listJobs: (limit = 50) => request(`/api/jobs?limit=${limit}`),
  jobDetail: (id) => request(`/api/jobs/${id}`),
  jobEvents: (id, after = 0) => request(`/api/jobs/${id}/events?after=${after}`),
  cancelJob: (id) => request(`/api/jobs/${id}/cancel`, { method: 'POST' }),
  reanalyze: (id, payload) =>
    request(`/api/jobs/${id}/reanalyze`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload || {})
    }),
  transcript: (id, from = 0, size = 2000) => request(`/api/jobs/${id}/transcript?from=${from}&size=${size}`),
  evidence: (id) => request(`/api/jobs/${id}/evidence`),

  chatHistory: (id) => request(`/api/jobs/${id}/chat`),
  ask: (id, question) =>
    request(`/api/jobs/${id}/chat`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ question })
    }),
  clearChat: (id) => request(`/api/jobs/${id}/chat`, { method: 'DELETE' }),

  /** SSE：先回放历史，再增量推送；断线时前端自行重连 */
  streamJob(id, { after = 0, onEvent, onDone, onError }) {
    const source = new EventSource(`/api/jobs/${id}/stream?after=${after}`)
    source.addEventListener('event', (e) => onEvent(JSON.parse(e.data)))
    source.addEventListener('done', (e) => {
      source.close()
      onDone(JSON.parse(e.data))
    })
    source.onerror = () => {
      source.close()
      if (onError) onError()
    }
    return source
  }
}
