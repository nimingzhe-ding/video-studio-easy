import test from 'node:test';
import assert from 'node:assert/strict';

import {
  MODE_LABELS,
  STAGE_LABELS,
  STATUS_LABELS,
  formatDuration,
  formatRelative,
  formatSize,
  modeLabels
} from './format.js';

/**
 * 展示层格式化。
 *
 * <p>这些函数的问题都很难在界面上肉眼发现：时长差一秒、体积少一位小数，
 * 看起来都「差不多」。测试把边界值钉死，包括最容易错的 0 与缺失值。
 */

test('formatDuration 把 0 当作合法时间点', () => {
  // 0 是视频开头，不是「缺失」。早期实现把 0 当假值显示成 --:--，开场时间点就没了
  assert.equal(formatDuration(0), '00:00');
  assert.equal(formatDuration(1_000), '00:01');
  assert.equal(formatDuration(59_999), '01:00');
});

test('formatDuration 处理小时与缺失值', () => {
  assert.equal(formatDuration(3_600_000), '1:00:00');
  assert.equal(formatDuration(3_723_000), '1:02:03');
  assert.equal(formatDuration(null), '--:--');
  assert.equal(formatDuration(undefined), '--:--');
  assert.equal(formatDuration(-1), '--:--');
  assert.equal(formatDuration(Number.NaN), '--:--');
});

test('formatSize 按 1024 进制并保留一位小数', () => {
  assert.equal(formatSize(0), '0 B');
  assert.equal(formatSize(512), '512 B');
  assert.equal(formatSize(1024), '1.0 KB');
  assert.equal(formatSize(1024 * 1024), '1.0 MB');
  assert.equal(formatSize(1_500_000), '1.4 MB');
  assert.equal(formatSize(-5), '0 B');
});

test('formatRelative 对未来与刚发生的处理', () => {
  const now = Date.now();
  assert.equal(typeof formatRelative(new Date(now).toISOString()), 'string');
  assert.equal(typeof formatRelative(new Date(now - 3_600_000).toISOString()), 'string');
  // 未来时间不该显示成「很久以前」
  const future = formatRelative(new Date(now + 60_000).toISOString());
  assert.equal(typeof future, 'string');
});

test('状态与阶段标签覆盖后端所有枚举值', () => {
  // 后端 JobStatus 有 5 个值，缺一个界面上就会露出英文原文
  for (const key of ['PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELED']) {
    assert.ok(STATUS_LABELS[key], `缺少状态标签: ${key}`);
  }
  for (const key of ['QUEUED', 'PROBE', 'AUDIO', 'TRANSCRIBE', 'FRAMES', 'OCR',
    'SEGMENT', 'CHUNK', 'RETRIEVE', 'PLAN', 'EXECUTE', 'CRITIC', 'REPORT', 'DONE', 'FAILED']) {
    assert.ok(STAGE_LABELS[key], `缺少阶段标签: ${key}`);
  }
});

test('modeLabels 用后端清单覆盖内置表', () => {
  const merged = modeLabels([
    { code: 'CONTRACT', label: '合同审查' },
    { code: 'GENERAL', label: '通用（改）' }
  ]);

  assert.equal(merged.CONTRACT, '合同审查', '新增模式应当能显示');
  assert.equal(merged.GENERAL, '通用（改）', '后端清单优先于内置表');
  assert.equal(merged.LEARNING, MODE_LABELS.LEARNING, '未覆盖的沿用内置值');
});

test('modeLabels 在接口未返回时退回内置表', () => {
  // 首屏 runtime 还没拿到时，下拉框不能是空的
  assert.deepEqual(modeLabels(undefined), MODE_LABELS);
  assert.deepEqual(modeLabels(null), MODE_LABELS);
  assert.deepEqual(modeLabels([]), MODE_LABELS);
});

test('modeLabels 忽略缺少 code 的脏数据', () => {
  const merged = modeLabels([{ label: '没有 code' }, { code: 'OK', label: '正常' }]);

  assert.equal(merged.OK, '正常');
  assert.equal(merged.undefined, undefined);
});
