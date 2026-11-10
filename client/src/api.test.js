import test from 'node:test';
import assert from 'node:assert/strict';

import { CHUNK_THRESHOLD, chunkUploadId } from './api.js';

/**
 * 分片上传的客户端逻辑。
 *
 * <p>uploadId 是断点续传的全部依据：它变了，服务端就认不出这是同一个文件，
 * 已上传的分片全部作废。所以「同一文件必然得到同一 id」是硬要求。
 * 这里只测不依赖网络的纯函数，请求流程由 Playwright e2e 覆盖。
 */

test('同一个文件总是得到同一个 uploadId', () => {
  const file = { name: 'lecture.mp4', size: 123_456_789, lastModified: 1_700_000_000_000 };

  assert.equal(chunkUploadId(file), chunkUploadId({ ...file }));
});

test('文件名、大小或修改时间任一变化都会换新 id', () => {
  const file = { name: 'lecture.mp4', size: 123_456_789, lastModified: 1_700_000_000_000 };

  assert.notEqual(chunkUploadId(file), chunkUploadId({ ...file, name: 'other.mp4' }));
  assert.notEqual(chunkUploadId(file), chunkUploadId({ ...file, size: 123_456_790 }));
  assert.notEqual(chunkUploadId(file), chunkUploadId({ ...file, lastModified: 1_700_000_000_001 }));
});

test('lastModified 缺失时仍然稳定', () => {
  // 某些浏览器与构造出的 File 对象没有 lastModified，不能因此每次算出不同的 id
  const a = chunkUploadId({ name: 'a.mp4', size: 10 });
  const b = chunkUploadId({ name: 'a.mp4', size: 10 });

  assert.equal(a, b);
});

test('uploadId 符合服务端的字符集要求', () => {
  // 服务端只接受 [A-Za-z0-9_-]{1,110}
  for (const name of ['a.mp4', '中文名称.mp4', 'x'.repeat(200) + '.mov', 'with space.mp4']) {
    const id = chunkUploadId({ name, size: 1024, lastModified: 1 });
    assert.match(id, /^[A-Za-z0-9_-]+$/, `非法 id: ${id}`);
  }
});

test('uploadId 长度在服务端上限内', () => {
  const id = chunkUploadId({ name: 'x'.repeat(500) + '.mp4', size: 9_999_999_999, lastModified: Date.now() });

  assert.ok(id.length <= 110, `id 过长: ${id.length}`);
});

test('分片阈值是 32MB', () => {
  // 前端据此决定走单片上传还是分片上传，改这个值要同步服务端策略
  assert.equal(CHUNK_THRESHOLD, 32 * 1024 * 1024);
});
