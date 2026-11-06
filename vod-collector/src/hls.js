/**
 * 播放地址解析层：把「资源站给的播放地址」统一成可播放信息。
 * 实测到 4 种形态，全部在这里吸收，站点配置里不需要关心：
 *   A. 直接媒体列表（暴风：相对 .ts）
 *   B. 多级 master（牛牛：index.m3u8 -> 2000k_1080/hls/index.m3u8 -> .ts）
 *   C. 分享页 HTML（量子/非凡：页面里内嵌 /2026xxxx/xxx/index.m3u8?sign=）
 *   D. AES-128 加密（光速/红牛/极速/新浪/金鹰/魔都/飘零/虎牙/360）
 */
import { request } from './http.js';

const isPlaylist = (t) => t.includes('#EXTM3U');
const abs = (base, u) => {
  try { return new URL(u.trim(), base).toString(); } catch { return u.trim(); }
};

function parseMaster(text, url) {
  const variants = [];
  const lines = text.split(/\r?\n/);
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    if (!line.startsWith('#EXT-X-STREAM-INF')) continue;
    const res = /RESOLUTION=(\d+)x(\d+)/.exec(line);
    const bw = /BANDWIDTH=(\d+)/.exec(line);
    const next = (lines[i + 1] || '').trim();
    if (!next || next.startsWith('#')) continue;
    variants.push({
      uri: abs(url, next),
      width: res ? +res[1] : 0,
      height: res ? +res[2] : 0,
      bandwidth: bw ? +bw[1] : 0,
    });
  }
  variants.sort((a, b) => b.height - a.height || b.bandwidth - a.bandwidth);
  return variants;
}

function parseMedia(text, url) {
  const segs = [];
  let dur = 0;
  let encrypted = false;
  let keyUri = null;
  for (const line of text.split(/\r?\n/)) {
    const t = line.trim();
    if (!t) continue;
    if (t.startsWith('#EXTINF:')) dur += parseFloat(t.slice(8)) || 0;
    else if (t.startsWith('#EXT-X-KEY')) {
      const m = /METHOD=([A-Z0-9-]+)/.exec(t);
      const u = /URI="([^"]+)"/.exec(t);
      if (m && m[1] !== 'NONE') {
        encrypted = true;
        keyUri = u ? abs(url, u[1]) : null;
      }
    } else if (!t.startsWith('#')) segs.push(abs(url, t));
  }
  return { segs, dur, encrypted, keyUri };
}

/**
 * @returns {Promise<{ok:boolean,kind:string,media:string,variants:Array,segments:number,
 *   durationSec:number,resolution:string,encrypted:boolean,keyUri:string|null,firstSegmentBytes:number,error?:string}>}
 */
export async function resolvePlay(url, { depth = 0, follow = true } = {}) {
  const out = { ok: false, kind: 'unknown', media: url, source: url, variants: [], segments: 0,
    durationSec: 0, resolution: '', encrypted: false, keyUri: null, firstSegmentBytes: 0 };

  const res = await request(url, { timeoutMs: 15000 });
  const text = res.text;

  // 形态 C：分享页 → 从 HTML 里抠 m3u8（相对/绝对都试）
  if (!isPlaylist(text)) {
    const m = /["'](\/[^"']*\.m3u8[^"']*)["']/.exec(text) || /(https?:\/\/[^"'\s]+\.m3u8[^"'\s]*)/.exec(text);
    if (!m) { out.error = '页面内未找到 m3u8'; return out; }
    out.kind = 'share-page';
    const next = abs(url, m[1]);
    if (depth > 4 || !follow) { out.playlist = next; return out; }
    const r = await resolvePlay(next, { depth: depth + 1, follow });
    return { ...r, kind: 'share-page', source: url };
  }

  // 形态 B：master → 选最高清晰度
  const variants = parseMaster(text, url);
  if (variants.length) {
    out.kind = 'master';
    out.variants = variants;
    if (depth > 4) return out;
    const r = await resolvePlay(variants[0].uri, { depth: depth + 1, follow });
    return {
      ...r,
      kind: 'master',
      variants,
      media: r.media,
      resolution: variants[0].height ? `${variants[0].width}x${variants[0].height}` : r.resolution,
    };
  }

  // 形态 A/D：媒体列表
  const media = parseMedia(text, url);
  out.kind = 'media';
  out.media = url;
  out.segments = media.segs.length;
  out.durationSec = Math.round(media.dur);
  out.encrypted = media.encrypted;
  out.keyUri = media.keyUri;
  out.ok = media.segs.length > 0;

  if (out.ok) {
    try {
      const head = await request(media.segs[0], { timeoutMs: 15000 });
      out.firstSegmentBytes = head.buf.length;
      out.firstSegmentOk = head.status === 200 || head.status === 206;
    } catch (e) {
      out.firstSegmentOk = false;
      out.error = '首个分片取流失败: ' + e.message;
    }
  }
  return out;
}

export const readableDuration = (sec) => {
  if (!sec) return '—';
  const h = Math.floor(sec / 3600);
  const m = Math.round((sec % 3600) / 60);
  return h ? `${h}h${String(m).padStart(2, '0')}m` : `${m}min`;
};
