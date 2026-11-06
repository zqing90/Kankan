/**
 * 豆瓣榜单数据源（官方维护，非自算）。
 *
 * 用的是豆瓣移动端自己页面调用的 rexxar 接口，豆瓣官方每天维护，
 * 比"资源站共现度"这种自造指标更接近真实的「热播」。
 *
 *   GET https://m.douban.com/rexxar/api/v2/subject_collection/{board}/items?start=0&count=60
 *   需 Referer: https://m.douban.com/
 *
 * 关键坑：
 *   1. 老的 /j/search_subjects 已全面 403，别再用；
 *   2. 海报 img*.doubanio.com 无 Referer 直接 418，必须由服务端代理；
 *   3. 连续请求间隔 0.6s 以上，实测 12 连击不封（但仍加节流+缓存兜底）。
 */
import { readFile, writeFile, mkdir, stat } from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const OUT_DIR = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'out');
const CACHE_FILE = path.join(OUT_DIR, 'douban.json');
const POSTER_DIR = path.join(OUT_DIR, 'posters');

const UA = 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1';
const HEADERS = { 'User-Agent': UA, Referer: 'https://m.douban.com/', Accept: 'application/json' };

/** 榜单目录：group 决定首页分区，order 决定 tab 内顺序 */
export const BOARDS = [
  { id: 'tv_hot', label: '近期热门剧集', group: 'tv', order: 1, count: 60 },
  { id: 'tv_domestic', label: '热门国产剧', group: 'tv', order: 2, count: 40 },
  { id: 'tv_american', label: '热门美剧', group: 'tv', order: 3, count: 40 },
  { id: 'tv_korean', label: '热门韩剧', group: 'tv', order: 4, count: 30 },
  { id: 'tv_japanese', label: '热门日剧', group: 'tv', order: 5, count: 30 },
  { id: 'tv_chinese_best_weekly', label: '华语口碑剧集榜', group: 'tv', order: 6, count: 20 },
  { id: 'tv_global_best_weekly', label: '全球口碑剧集榜', group: 'tv', order: 7, count: 20 },

  { id: 'movie_hot_gaia', label: '豆瓣热门电影', group: 'movie', order: 1, count: 60 },
  { id: 'movie_showing', label: '影院热映', group: 'movie', order: 2, count: 60 },
  { id: 'movie_hot', label: '新片榜', group: 'movie', order: 3, count: 30 },
  { id: 'movie_weekly_best', label: '一周口碑电影榜', group: 'movie', order: 4, count: 20 },
  { id: 'movie_top250', label: '豆瓣电影 Top250', group: 'movie', order: 5, count: 60 },

  { id: 'show_hot', label: '近期热门综艺', group: 'show', order: 1, count: 50 },
  { id: 'show_domestic', label: '热门国内综艺', group: 'show', order: 2, count: 30 },
  { id: 'show_chinese_best_weekly', label: '国内口碑综艺榜', group: 'show', order: 3, count: 20 },

  { id: 'tv_animation', label: '近期热门动画', group: 'anime', order: 1, count: 40 },
  { id: 'tv_documentary', label: '近期热门纪录片', group: 'doc', order: 1, count: 40 },
];

export const BOARD_GROUPS = {
  tv: '剧集', movie: '电影', show: '综艺', anime: '动画', doc: '纪录片',
};

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** "2026 / 中国大陆 / 剧情 / 张晓波 / 肖战 倪大红" */
function parseSubtitle(s = '') {
  const parts = String(s).split('/').map((x) => x.trim()).filter(Boolean);
  return {
    year: /^(19|20)\d{2}$/.test(parts[0] || '') ? Number(parts[0]) : null,
    region: /^(19|20)\d{2}$/.test(parts[0] || '') ? parts[1] || '' : parts[0] || '',
    genres: (parts[2] || '').split(/\s+/).filter(Boolean),
    director: parts[3] || '',
    actors: (parts[4] || '').split(/\s+/).filter(Boolean),
    raw: s,
  };
}

/**
 * 豆瓣榜单实际混着两套数据结构，必须分别处理：
 *
 *  A 剧集/综艺/动画/纪录：pic{normal,large} + card_subtitle + comment + episodes_info
 *  B 电影（购票/票房榜）：cover{url,width,height} + info + directors[] + actors[] + release_date
 *
 * B 类没有 pic 字段，直接用 A 的取法会拿到空字符串，前端表现为整排破图。
 */
function normalize(it, board) {
  const isTicket = !it.pic && !!it.cover; // 电影购票结构
  const raw = isTicket ? it.cover?.url || '' : it.pic?.normal || '';
  const large = isTicket ? it.cover?.url || '' : it.pic?.large || '';
  const sub = isTicket ? parseInfo(it.info) : parseSubtitle(it.card_subtitle);
  const subjectId = String(it.id || (it.uri || '').match(/\/movie\/(\d+)/)?.[1] || (it.url || '').match(/movie\/(\d+)/)?.[1] || '');

  return {
    id: subjectId,
    board: board.id,
    boardLabel: board.label,
    group: board.group,
    title: it.title,
    year: Number(it.year) || sub.year || null,
    type: it.type,
    rating: it.rating?.value ?? null,
    ratingCount: it.rating?.count ?? 0,
    stars: it.rating?.star_count ?? 0,
    region: sub.region,
    genres: sub.genres,
    director: Array.isArray(it.directors) && it.directors.length ? it.directors[0] : sub.director,
    actors: Array.isArray(it.actors) && it.actors.length ? it.actors : sub.actors,
    episodes: it.episodes_info || (it.release_date ? `${it.release_date} 上映` : ''),
    subtitle: it.card_subtitle || it.info || '',
    comment: it.comment || it.description || '',
    hasPlayable: Boolean(it.has_linewatch),
    // 卡片用 s_ratio（约 30KB），详情用 m_ratio（约 100KB）
    pic: raw ? raw.replace('/m_ratio_poster/', '/s_ratio_poster/') : '',
    picLarge: large,
    url: subjectId ? `https://movie.douban.com/subject/${subjectId}/` : (it.url || ''),
  };
}

/** "中国大陆 中国香港 / 剧情 犯罪 / 韩延 / 朱一龙 檀健次" */
function parseInfo(s = '') {
  const p = String(s).split('/').map((x) => x.trim()).filter(Boolean);
  return {
    year: null,
    region: p[0] || '',
    genres: (p[1] || '').split(/\s+/).filter(Boolean),
    director: p[2] || '',
    actors: (p[3] || '').split(/\s+/).filter(Boolean),
    raw: s,
  };
}

async function fetchBoard(board, { start = 0, count } = {}) {
  const n = count || board.count;
  const u = `https://m.douban.com/rexxar/api/v2/subject_collection/${board.id}/items?start=${start}&count=${n}`;
  const r = await fetch(u, { headers: HEADERS, signal: AbortSignal.timeout(15000) });
  if (!r.ok) throw new Error(`${board.id} HTTP ${r.status}`);
  const j = await r.json();
  const items = (j.subject_collection_items || []).map((it) => normalize(it, board));
  return { total: j.total ?? items.length, items, name: j.subject_collection?.name || board.label };
}

/** 并发拉全部榜单，节流 4 路，任一失败不影响整体 */
export async function fetchAllBoards({ boards = BOARDS } = {}) {
  const t0 = Date.now();
  const out = [];
  const queue = [...boards];
  const workers = Array.from({ length: 4 }, async () => {
    while (queue.length) {
      const b = queue.shift();
      try {
        const r = await fetchBoard(b);
        out.push({ ...b, ...r, ok: true });
      } catch (e) {
        out.push({ ...b, items: [], total: 0, ok: false, error: e.message.slice(0, 80) });
      }
      await sleep(400);
    }
  });
  await Promise.all(workers);
  out.sort((a, b) => listIndex(a) - listIndex(b));

  return {
    source: 'douban',
    sourceName: '豆瓣',
    fetchedAt: new Date().toISOString(),
    tookMs: Date.now() - t0,
    groups: [...new Set(out.map((b) => b.group))].map((g) => ({
      key: g,
      label: BOARD_GROUPS[g] || g,
      boards: out.filter((b) => b.group === g).map((b) => ({
        id: b.id, label: b.label, total: b.total, ok: b.ok, error: b.error || '',
        count: b.items.length,
        items: b.items,
      })),
    })),
  };
}

function listIndex(b) {
  const gi = Object.keys(BOARD_GROUPS).indexOf(b.group);
  return gi * 100 + (b.order || 0);
}

/**
 * 带磁盘缓存（默认 30 分钟）。豆瓣榜单本身更新频率在小时级，30 分钟足够新。
 * 抓取失败时若有过期缓存，退回旧数据而不是报错。
 */
export async function getRank({ force = false, ttlMs = 30 * 60 * 1000 } = {}) {
  let cached = null;
  try {
    cached = JSON.parse(await readFile(CACHE_FILE, 'utf8'));
  } catch { /* 无缓存 */ }

  if (!force && cached && Date.now() - Date.parse(cached.fetchedAt) < ttlMs) {
    return { ...cached, cached: true };
  }

  try {
    const fresh = await fetchAllBoards();
    const empty = fresh.groups.every((g) => g.boards.every((b) => !b.count));
    if (empty) throw new Error('所有榜单返回为空');
    await mkdir(OUT_DIR, { recursive: true });
    await writeFile(CACHE_FILE, JSON.stringify(fresh));
    return { ...fresh, cached: false };
  } catch (e) {
    if (cached) return { ...cached, cached: true, stale: true, error: e.message };
    throw e;
  }
}

const ALLOWED_POSTER = /^https:\/\/img\d*\.doubanio\.com\//;

/**
 * 海报代理：豆瓣图必须带 Referer，浏览器直连一律 418。
 * 只允许 doubanio 域名，防 SSRF；本地落盘缓存，二次访问不再回源。
 */
export async function fetchPoster(rawUrl) {
  if (!ALLOWED_POSTER.test(rawUrl)) throw new Error('不允许的图片域名');
  const u = rawUrl; // 尺寸由调用方决定：卡片传 s_ratio（小图），详情传 m_ratio（大图）
  const hash = crypto.createHash('sha1').update(u).digest('hex');
  const file = path.join(POSTER_DIR, `${hash}.jpg`);

  try {
    const s = await stat(file);
    if (s.size > 0) return { file, type: 'image/jpeg', cached: true };
  } catch { /* 未缓存 */ }

  const r = await fetch(u, {
    headers: { 'User-Agent': UA, Referer: 'https://movie.douban.com/' },
    signal: AbortSignal.timeout(15000),
  });
  if (!r.ok) throw new Error(`海报 HTTP ${r.status}`);
  const buf = Buffer.from(await r.arrayBuffer());
  if (!buf.length) throw new Error('海报内容为空');
  await mkdir(POSTER_DIR, { recursive: true });
  await writeFile(file, buf);
  return { file, buf, type: r.headers.get('content-type') || 'image/jpeg', cached: false };
}
