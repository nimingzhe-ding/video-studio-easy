import test from 'node:test';
import assert from 'node:assert/strict';

import { describeJob, isDirectMedia, isVideoPage, normalizeBaseUrl } from './detect.js';

/**
 * 扩展的链接识别规则。
 *
 * <p>识别错了的代价是用户白等一次下载失败，所以这里既测「该认的认出来」，
 * 也测「不该认的不要认」——把任意网页提交过去会让 yt-dlp 抓一个非视频页。
 */

test('识别常见视频站', () => {
  for (const url of [
    'https://www.bilibili.com/video/BV1xx411c7mD',
    'https://b23.tv/abcdef',
    'https://www.youtube.com/watch?v=abc',
    'https://youtu.be/abc',
    'https://vimeo.com/76979871',
    'https://v.qq.com/x/page/abc.html'
  ]) {
    assert.equal(isVideoPage(url), true, `应当识别: ${url}`);
  }
});

test('不把普通网页当成视频页', () => {
  for (const url of [
    'https://www.bilibili.com/read/cv123',
    'https://example.com',
    'https://github.com/a/b'
  ]) {
    // bilibili.com 是视频站域名，栏目页也算支持——由 yt-dlp 决定能否下载。
    // 这里只确认其他站点不会被误判。
    if (url.includes('bilibili.com')) {
      continue;
    }
    assert.equal(isVideoPage(url), false, `不应识别: ${url}`);
  }
});

test('拒绝非 http 协议', () => {
  assert.equal(isVideoPage('javascript:alert(1)'), false);
  assert.equal(isVideoPage('file:///etc/passwd'), false);
  assert.equal(isVideoPage('chrome://extensions'), false);
});

test('处理空值与畸形 URL', () => {
  assert.equal(isVideoPage(''), false);
  assert.equal(isVideoPage(null), false);
  assert.equal(isVideoPage('not a url'), false);
});

test('识别直链媒体文件', () => {
  assert.equal(isDirectMedia('https://example.com/video.mp4'), true);
  assert.equal(isDirectMedia('https://example.com/a/b/audio.m4a'), true);
  assert.equal(isDirectMedia('https://example.com/page.html'), false);
  assert.equal(isDirectMedia('https://example.com/video.mp4?token=x'), true);
});

test('服务地址规范化', () => {
  assert.equal(normalizeBaseUrl('http://127.0.0.1:9090/'), 'http://127.0.0.1:9090');
  assert.equal(normalizeBaseUrl('  127.0.0.1:9090  '), 'http://127.0.0.1:9090');
  assert.equal(normalizeBaseUrl('https://studio.example.com//'), 'https://studio.example.com');
  assert.equal(normalizeBaseUrl(''), 'http://127.0.0.1:9090');
  assert.equal(normalizeBaseUrl(null), 'http://127.0.0.1:9090');
});

test('任务摘要带补零的编号', () => {
  assert.equal(describeJob({ id: 7 }), '任务 NO.0007 已排队');
  assert.equal(describeJob(null), '已提交');
});
