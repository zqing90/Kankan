/**
 * 热播榜聚合：没有第三方榜单也能自算榜单。
 *
 * 思路：资源站的 API 不支持按热度排序（order 参数全部无效，实测），
 * 但支持「最近 N 小时更新」。于是用两个信号合成热度：
 *   1. 共现度 —— 同一部片同时被多少个站收录（站越多 = 越热）
 *   2. 新鲜度 —— 最近 24h 内是否更新（连载中 = 在播）
 * 优点：不依赖任何第三方接口，海报直接来自资源站，点进去就能播。
 */
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { pickStations } from './collector.js';

const OUT_DIR = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'out');
const CACHE_FILE = path.join(OUT_DIR, 'chart.json');

const GROUP_RULES = [
  [/体育|赛事|足球|篮球|网球|运动会|VS|联赛|杯$/, 'sport'],
  [/短剧|微短/, 'short'],
  [/动漫|动画|国漫|日漫/, 'anime'],
  [/综艺|真人秀|脱口秀/, 'show'],
  [/纪录/, 'doc'],
  [/剧|连续/, 'tv'],
  [/电影|片$|片\b/, 'movie'],
];

export const GROUPS = {
  movie: '电影', tv: '剧集', show: '综艺', anime: '动漫', short: '短剧', doc: '纪录',
  sport: '体育', other: '其他',
};
export const HIDDEN_GROUPS = new Set(['sport', 'other']);

const classCache = new Map(); // stationId -> Map(typeId -> group)
const CLASS_FILE = path.join(OUT_DIR, 'classes.json');
let classLoaded = false;

async function loadClassCache() {
  if (classLoaded) return;
  classLoaded = true;
  try {
    const raw = JSON.parse(await readFile(CLASS_FILE, 'utf8'));
    for (const [sid, obj] of Object.entries(raw)) classCache.set(sid, new Map(Object.entries(obj)));
  } catch { /* 首次无缓存 */ }
}
async function saveClassCache() {
  try {
    await mkdir(OUT_DIR, { recursive: true });
    const obj = {};
    for (const [sid, m] of classCache) obj[sid] = Object.fromEntries(m);
    await writeFile(CLASS_FILE, JSON.stringify(obj));
  } catch { /* 忽略 */ }
}

async function groupOf(station, typeId, typeName) {
  await loadClassCache();
  if (!classCache.has(station.id)) {
    try {
      const classes = await station.classes();
      const byId = new Map(classes.map((c) => [c.id, c]));
      const map = new Map();
      for (const c of classes) {
        const top = c.pid && c.pid !== 0 ? byId.get(c.pid) || c : c; // 归到一级分类
        map.set(String(c.id), detectGroup(top.name));
      }
      classCache.set(station.id, map);
    } catch {
      classCache.set(station.id, new Map());
    }
  }
  return classCache.get(station.id).get(String(typeId)) || detectGroup(typeName) || 'other';
}

function detectGroup(name = '') {
  for (const [re, g] of GROUP_RULES) if (re.test(name)) return g;
  return 'other';
}

const norm = (s = '') => String(s).replace(/[（(【\[]/g, '').replace(/[）)】\]]/g, '').replace(/\s+/g, '').trim();

/**
 * @returns {Promise<{generatedAt:string, hours:number, groups:Object, items:Array}>}
 */
export async function buildChart({ hours = 168, perStation = 60, only, minStations = 1, limitPerGroup = 40 } = {}) {
  const stations = (await pickStations({ only })).filter((s) => (s.s.priority ?? 3) <= 2);
  const t0 = Date.now();

  const buckets = await Promise.all(
    stations.map(async (st) => {
      try {
        const { list } = await st.list({ h: hours, pagesize: perStation, pg: 1, detail: true });
        const withGroup = await Promise.all(
          list.map(async (v) => ({ v, group: await groupOf(st, v.typeId, v.type) })),
        );
        return { station: st, items: withGroup };
      } catch (e) {
        return { station: st, items: [], error: e.message.slice(0, 60) };
      }
    }),
  );

  const map = new Map();
  for (const b of buckets) {
    for (const { v, group } of b.items) {
      if (!v.name) continue;
      const key = norm(v.name);
      if (!map.has(key)) {
        map.set(key, {
          name: v.name, year: v.year, group, remarks: v.remarks, pic: v.pic,
          content: v.content, actors: v.actors, updatedAt: v.updatedAt,
          stations: [], latest: 0, score: 0,
        });
      }
      const it = map.get(key);
      if (!it.pic && v.pic) it.pic = v.pic;
      if (v.updatedAt > (it.updatedAt || '')) it.updatedAt = v.updatedAt;
      if (!it.content && v.content) it.content = v.content;
      if (!it.stations.some((s) => s.id === b.station.id)) {
        it.stations.push({ id: b.station.id, name: b.station.name, vodId: v.vodId, remarks: v.remarks });
      }
      if (group !== 'other') it.group = group;
    }
  }

  const now = Date.now();
  const items = [...map.values()]
    .map((it) => {
      const ts = Date.parse((it.updatedAt || '').replace(/-/g, '/')) || 0;
      const ageH = ts ? (now - ts) / 3.6e6 : 999;
      const fresh = ageH <= 24 ? 8 : ageH <= 48 ? 4 : 0;
      const serial = /更新至(第)?\d+集/.test(it.remarks || '') ? 5 : 0;
      const complete = /全\d+集|完结|HD|正片/.test(it.remarks || '') ? 3 : 0;
      it.score = it.stations.length * 10 + fresh + serial + complete;
      it.ageHours = Math.round(ageH);
      return it;
    })
    .filter((it) => it.stations.length >= minStations && !HIDDEN_GROUPS.has(it.group))
    .sort((a, b) => b.score - a.score);

  const groups = {};
  for (const it of items) (groups[it.group] ||= []).push(it);
  for (const k of Object.keys(groups)) groups[k] = groups[k].slice(0, limitPerGroup);

  await saveClassCache();
  return {
    generatedAt: new Date().toISOString(),
    hours,
    tookMs: Date.now() - t0,
    stations: buckets.map((b) => ({ id: b.station.id, name: b.station.name, count: b.items.length, error: b.error || '' })),
    groups: Object.fromEntries(Object.entries(groups).map(([k, v]) => [k, { label: GROUPS[k] || k, items: v }])),
    items,
  };
}


/** 读缓存（默认 10 分钟有效），force 或过期才重建 */
export async function getChart({ ttlMs = 10 * 60 * 1000, force = false, ...opts } = {}) {
  if (!force) {
    try {
      const raw = JSON.parse(await readFile(CACHE_FILE, 'utf8'));
      if (Date.now() - Date.parse(raw.generatedAt) < ttlMs) return { ...raw, cached: true };
    } catch { /* 无缓存 */ }
  }
  const chart = await buildChart(opts);
  await mkdir(OUT_DIR, { recursive: true });
  await writeFile(CACHE_FILE, JSON.stringify(chart));
  return { ...chart, cached: false };
}
