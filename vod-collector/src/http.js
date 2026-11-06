/**
 * 统一 HTTP 层：超时、重试、多域名故障切换、轻量限速。
 * 所有站点差异都收敛到「hosts 数组」上，站点挂了自动换下一个域名。
 */
import { setTimeout as sleep } from 'node:timers/promises';

export class HttpError extends Error {
  constructor(status, url, body = '') {
    super(`HTTP ${status} @ ${url}`);
    this.status = status;
    this.url = url;
    this.body = body.slice(0, 200);
  }
}

const hostQueue = new Map(); // 简易每域名并发控制

async function acquire(host, limit) {
  const q = hostQueue.get(host) || { running: 0, wait: [] };
  hostQueue.set(host, q);
  if (q.running < limit) { q.running++; return; }
  await new Promise((r) => q.wait.push(r));
  q.running++;
}

function release(host) {
  const q = hostQueue.get(host);
  if (!q) return;
  q.running = Math.max(0, q.running - 1);
  const next = q.wait.shift();
  if (next) next();
}

export function buildUrl(base, path, params = {}) {
  const u = new URL(path.replace(/^\//, ''), base.endsWith('/') ? base : base + '/');
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== null && v !== '') u.searchParams.set(k, String(v));
  }
  return u.toString();
}

/** 单次请求（不重试） */
export async function request(url, opts = {}) {
  const { timeoutMs = 15000, headers = {}, limit = 2, signal } = opts;
  const host = new URL(url).host;
  await acquire(host, limit);
  try {
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), timeoutMs);
    if (signal) signal.addEventListener('abort', () => ctrl.abort(), { once: true });
    try {
      const res = await fetch(url, {
        headers: {
          'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36',
          Accept: '*/*',
          ...headers,
        },
        redirect: 'follow',
        signal: ctrl.signal,
      });
      const buf = Buffer.from(await res.arrayBuffer());
      const text = buf.toString('utf8');
      if (!res.ok) throw new HttpError(res.status, url, text);
      return { status: res.status, headers: res.headers, text, buf, url: res.url };
    } finally {
      clearTimeout(timer);
    }
  } finally {
    release(host);
  }
}

/** 请求 + 重试 + 多域名切换 */
export async function requestWithFailover(hosts, path, params, opts = {}) {
  const { retry = 1, retryDelayMs = 400 } = opts;
  const errors = [];
  for (const host of hosts) {
    for (let attempt = 0; attempt <= retry; attempt++) {
      const url = buildUrl(host, path, params);
      try {
        return await request(url, opts);
      } catch (e) {
        errors.push(`${url} -> ${e.message}`);
        if (attempt < retry) await sleep(retryDelayMs);
      }
    }
  }
  const err = new Error(`全部域名失败: ${errors.slice(-2).join(' | ')}`);
  err.causes = errors;
  throw err;
}

/** 解析 JSON；服务端偶尔带 BOM 或返回 HTML 错误页 */
export function parseJson(text) {
  const t = text.replace(/^\uFEFF/, '').trim();
  if (!t.startsWith('{') && !t.startsWith('[')) {
    throw new Error('非 JSON 响应: ' + t.slice(0, 80));
  }
  return JSON.parse(t);
}
