import { marked } from 'marked';

marked.setOptions({ breaks: true, gfm: true });

const CITATION = /\[(\d{1,2}:\d{2}(?::\d{2})?)\]/g;

/** "1:05" / "01:02:03" → 毫秒 */
export function parseClock(text) {
  if (!text) {
    return 0;
  }
  const parts = String(text).split(':').map((part) => Number.parseInt(part, 10));
  if (parts.some((value) => Number.isNaN(value))) {
    return 0;
  }
  return parts.reduce((total, value) => total * 60 + value, 0) * 1000;
}

/**
 * 把 [mm:ss] 包成可点击的 span。
 * 逐段扫描 HTML 而不是整体替换——整体替换会把 <code> 里的示例时间戳也变成按钮。
 */
function decorate(html) {
  const pieces = html.split(/(<[^>]+>)/);
  let inCode = 0;
  return pieces
    .map((piece) => {
      if (piece.startsWith('<')) {
        const lower = piece.toLowerCase();
        if (lower.startsWith('<code') || lower.startsWith('<pre')) {
          inCode += 1;
        } else if (lower.startsWith('</code') || lower.startsWith('</pre')) {
          inCode = Math.max(0, inCode - 1);
        }
        return piece;
      }
      if (inCode > 0) {
        return piece;
      }
      return piece.replace(
        CITATION,
        (match, clock) => `<span class="ts" data-ts="${clock}" role="button" tabindex="0">${match}</span>`
      );
    })
    .join('');
}

export function renderMarkdown(markdown) {
  if (!markdown) {
    return '';
  }
  const raw = marked.parse(markdown);
  return decorate(typeof raw === 'string' ? raw : String(raw));
}

/** 从 Markdown 里抽 h2，用作报告目录 */
export function outlineOf(markdown) {
  if (!markdown) {
    return [];
  }
  const items = [];
  const pattern = /^(#{2,3})\s+(.+)$/gm;
  let match = pattern.exec(markdown);
  while (match) {
    items.push({ level: match[1].length, text: match[2].trim(), id: slug(match[2].trim(), items.length) });
    match = pattern.exec(markdown);
  }
  return items;
}

export function slug(text, index) {
  return `sec-${index}-${text.replace(/[^\w\u4e00-\u9fa5]+/g, '-').slice(0, 24)}`;
}

/** 给渲染后的 HTML 的 h2/h3 挂上锚点 id，供目录跳转 */
export function anchorHeadings(container) {
  if (!container) {
    return;
  }
  const nodes = container.querySelectorAll('h2, h3');
  nodes.forEach((node, index) => {
    node.id = slug(node.textContent || '', index);
  });
}

/** 把文本里的关键词高亮（避免把用户输入当正则） */
export function highlight(text, keyword) {
  const source = String(text ?? '');
  const word = String(keyword ?? '').trim();
  if (!word) {
    return escapeHtml(source);
  }
  const lower = source.toLowerCase();
  const target = word.toLowerCase();
  let out = '';
  let from = 0;
  let at = lower.indexOf(target);
  while (at >= 0) {
    out += escapeHtml(source.slice(from, at));
    out += `<mark>${escapeHtml(source.slice(at, at + word.length))}</mark>`;
    from = at + word.length;
    at = lower.indexOf(target, from);
  }
  out += escapeHtml(source.slice(from));
  return out;
}

export function escapeHtml(text) {
  return String(text ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}
