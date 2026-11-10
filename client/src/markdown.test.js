import test from 'node:test';
import assert from 'node:assert/strict';

import { escapeHtml, highlight, outlineOf, parseClock, slug } from './markdown.js';

/**
 * Markdown 渲染与时间戳解析。
 *
 * <p>报告正文来自模型输出，中间又经过 OCR 与 ASR 的原始文本，是最不可信的内容。
 * 渲染管线必须保证注入不成立，同时不能把正常内容改坏。
 */

test('parseClock 支持 mm:ss 与 hh:mm:ss', () => {
  assert.equal(parseClock('1:05'), 65_000);
  assert.equal(parseClock('00:30'), 30_000);
  assert.equal(parseClock('01:02:03'), 3_723_000);
});

test('parseClock 对非法输入返回 0 而不是 NaN', () => {
  // NaN 会顺着计算流进播放器 seek，导致整段播放异常
  assert.equal(parseClock(''), 0);
  assert.equal(parseClock(null), 0);
  assert.equal(parseClock('abc'), 0);
  assert.equal(parseClock('1:x'), 0);
});

/**
 * 注意：renderMarkdown 依赖 DOMPurify，需要浏览器环境（window），
 * 在 Node 里无法直接调用。它的安全约束由 Playwright e2e 用例
 * 「report HTML cannot inject scripts or event handlers」覆盖。
 * 这里只测不依赖 DOM 的纯函数部分。
 */

test('escapeHtml 转义全部危险字符', () => {
  assert.equal(escapeHtml('<a href="x">&</a>'), '&lt;a href=&quot;x&quot;&gt;&amp;&lt;/a&gt;');
  assert.equal(escapeHtml(null), '');
});

test('highlight 把用户输入当纯文本而不是正则', () => {
  // 用户搜「(」时不该因为非法正则抛错
  const html = highlight('a(b)c', '(');

  assert.match(html, /<mark>/);
  assert.doesNotMatch(html, /<script/);
});

test('highlight 空关键词原样转义返回', () => {
  assert.equal(highlight('<b>x</b>', ''), '&lt;b&gt;x&lt;/b&gt;');
});

test('outlineOf 只取二三级标题', () => {
  const items = outlineOf('# 一级\n\n## 二级\n\n### 三级\n\n正文');

  assert.equal(items.length, 2);
  assert.equal(items[0].text, '二级');
  assert.equal(items[1].text, '三级');
});

test('slug 保留中文并去掉标点', () => {
  const id = slug('核心结论：架构', 0);

  assert.match(id, /核心结论/);
  assert.doesNotMatch(id, /：/);
});
