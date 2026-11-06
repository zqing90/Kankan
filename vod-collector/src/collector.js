/**
 * 采集编排：并发拉多站、按片名聚合、解析播放地址、输出统一结构。
 * 站点全部来自 config/stations.json，新增一个站 = 加一段 JSON，不用改代码。
 */
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { MaccmsStation } from './maccms.js';
import { resolvePlay, readableDuration } from './hls.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const CONFIG_PATH = path.join(__dirname, '..', 'config', 'stations.json');

let cached = null;
export async function loadConfig(file = CONFIG_PATH) {
  if (cached) return cached;
  const cfg = JSON.parse(await readFile(file, 'utf8'));
  const defaults = cfg.defaults || {};
  const all = (cfg.stations || []).map((s) => new MaccmsStation(s, defaults));
  cached = { raw: cfg, defaults, all, byId: new Map(all.map((s) => [s.id, s])) };
  return cached;
}

export async function pickStations({ only, tags, includeDisabled = false } = {}) {
  const { all, byId } = await loadConfig();
  let list = all;
  if (only?.length) list = only.map((id) => byId.get(id)).filter(Boolean);
  if (!includeDisabled) list = list.filter((s) => s.s.enabled !== false);
  if (tags?.length) list = list.filter((s) => (s.s.tags || []).some((t) => tags.includes(t)));
  return list;
}

/** 站点健康检查：列表接口能不能通 + 是否开放搜索 */
export async function health({ concurrency = 8, probeSearch = true, keyword = '测试' } = {}) {
  const list = await pickStations();
  const results = new Array(list.length);
  let cursor = 0;

  async function worker() {
    while (cursor < list.length) {
      const i = cursor++;
      const st = list[i];
      const t0 = Date.now();
      const r = { id: st.id, name: st.name, host: st.hosts[0], ok: false, total: 0, search: 'unknown', ms: 0, error: '' };
      try {
        const { total } = await st.list({ pg: 1, pagesize: 1 });
        r.ok = true;
        r.total = total;
        if (probeSearch) {
          if (st.s.search === 'none') r.search = 'disabled';
          else {
            try { await st.search(keyword); r.search = 'ok'; }
            catch (e) { r.search = e.code === 'SEARCH_DISABLED' ? 'disabled' : 'error'; }
          }
        }
      } catch (e) {
        r.error = e.message.slice(0, 120);
      }
      r.ms = Date.now() - t0;
      results[i] = r;
    }
  }
  await Promise.all(Array.from({ length: Math.min(concurrency, list.length) }, worker));
  return results;
}

/** 关键字搜索所有站（跳过关闭搜索的站，或走 fallback） */
export async function searchAll(keyword, { only, resolve = false, maxPerStation = 5 } = {}) {
  const list = await pickStations({ only });
  const started = Date.now();
  const jobs = list.map(async (st) => {
    const t0 = Date.now();
    try {
      const items = await st.search(keyword);
      const hits = items.filter((x) => x.name === keyword || x.name.includes(keyword)).slice(0, maxPerStation);
      return { station: st, ok: true, count: hits.length, items: hits.length ? hits : items.slice(0, 1), ms: Date.now() - t0 };
    } catch (e) {
      return { station: st, ok: false, count: 0, items: [], ms: Date.now() - t0, error: e.code === 'SEARCH_DISABLED' ? '搜索关闭' : e.message.slice(0, 80) };
    }
  });
  const raw = await Promise.all(jobs);

  // 命中但列表里没播放地址 → 拉详情拿
  const withPlay = raw.filter((r) => r.ok && r.count);
  await Promise.all(
    withPlay.map(async (r) => {
      try {
        const details = await r.station.detail(r.items.map((x) => x.vodId));
        const map = new Map(details.map((d) => [String(d.vodId), d]));
        r.items = r.items.map((x) => map.get(String(x.vodId)) || x);
      } catch { /* 详情失败保留列表信息 */ }
    }),
  );

  // 播放地址解析（可选）
  if (resolve) {
    const tasks = [];
    for (const r of withPlay) {
      for (const it of r.items) {
        for (const line of it.lines) {
          const url = line.episodes.at(-1)?.url; // 多集取最后一集，单片就是正片
          if (!url) continue;
          tasks.push(
            resolvePlay(url)
              .then((p) => { line.play = p; })
              .catch((e) => { line.play = { ok: false, error: e.message.slice(0, 80) }; }),
          );
        }
      }
    }
    await Promise.all(tasks);
  }

  const groups = aggregate(raw.filter((r) => r.ok && r.count));
  return {
    keyword,
    ms: Date.now() - started,
    stats: {
      stations: raw.length,
      responded: raw.filter((r) => r.ok).length,
      hit: raw.filter((r) => r.ok && r.count).length,
      playable: groups.filter((g) => g.playable).length,
    },
    byStation: raw.map(({ station, ok, count, ms, error }) => ({ id: station.id, name: station.name, ok, count, ms, error })),
    items: groups,
  };
}

/** 同一部片在多个站 → 合并成一条，lines 里平铺所有站点的播放源 */
export function aggregate(rows) {
  const map = new Map();
  for (const r of rows) {
    for (const it of r.items) {
      const key = `${normName(it.name)}|${it.year || ''}`;
      if (!map.has(key)) {
        map.set(key, {
          key, name: it.name, year: it.year, type: it.type, area: it.area,
          remarks: it.remarks, pic: it.pic || r.items[0]?.pic, director: it.director,
          actors: it.actors, content: it.content, updatedAt: it.updatedAt,
          sources: [], lines: [], playable: false, aliases: new Set(),
        });
      }
      const g = map.get(key);
      g.aliases.add(it.name);
      g.sources.push({ station: r.station.id, stationName: r.station.name, vodId: it.vodId, remarks: it.remarks });
      for (const l of it.lines) {
        const p = l.play || {};
        const ep = pickEpisode(l.episodes);
        const url = ep?.url || '';
        const sig = `${r.station.id}|${url}`; // 同站同地址（多别名源）只留一条
        if (g.lines.some((x) => x._sig === sig)) continue;
        g.lines.push({
          _sig: sig,
          station: r.station.id,
          stationName: r.station.name,
          flag: l.flag,
          episodes: l.episodes.length,
          episodeName: ep?.name || '',
          url,
          media: p.media || '',   // 解析后的真实 m3u8（share-page 也能直接播）
          playable: Boolean(p.ok && p.firstSegmentOk !== false),
          resolution: p.resolution || '',
          duration: readableDuration(p.durationSec),
          durationSec: p.durationSec || 0,
          segments: p.segments || 0,
          encrypted: Boolean(p.encrypted),
          kind: p.kind || 'unknown',
          error: p.error || '',
        });
        if (p.ok) g.playable = true;
      }
    }
  }
  const items = [...map.values()];
  for (const g of items) {
    g.aliases = [...g.aliases];
    g.lines.sort((a, b) => Number(b.playable) - Number(a.playable) || resScore(b) - resScore(a));
    g.best = g.lines.find((l) => l.playable) || null;
    // 资源站常见「枪版 / 删减版」：正片时长明显偏短
    const maxDur = Math.max(0, ...g.lines.map((l) => l.durationSec));
    g.shortVersion = maxDur > 0 && maxDur < 8000 && !/解说|预告|花絮/.test(g.name);
    g.lines.forEach((l) => { l._sig = undefined; });
  }
  items.sort((a, b) => Number(b.playable) - Number(a.playable) || b.sources.length - a.sources.length);
  return items;
}

/** 集数选择：优先正片/HD 命名的条目，否则第一条（多集剧取第一集，便于校验完整性） */
function pickEpisode(eps = []) {
  if (!eps.length) return null;
  return eps.find((e) => /HD|正片|国语|超清|4K/i.test(e.name)) || eps[0];
}

const resScore = (l) => {
  const m = /^(\d+)x(\d+)$/.exec(l.resolution || '');
  return m ? +m[1] * +m[2] : 0;
};

/** “空枪（粤语）” 与 “空枪粤语” 归一，避免同片被拆成两条 */
function normName(s = '') {
  return String(s).replace(/[（(【\[]/g, '').replace(/[）)】\]]/g, '').replace(/\s+/g, '').trim();
}
