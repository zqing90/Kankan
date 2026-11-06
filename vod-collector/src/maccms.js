/**
 * 苹果CMS(maccms V10) 通用适配器。
 * 23 个资源站实测：接口路径、参数、返回结构 82/85 个公共字段完全一致，
 * 差异只有「播放源 flag」「播放地址形态」「搜索是否开放」—— 全部放到 config 里。
 */
import { requestWithFailover, parseJson } from './http.js';

export const ACTION = {
  list: (p) => ({ ac: 'list', ...p }),
  detail: (p) => ({ ac: 'detail', ...p }),
  search: (kw, p) => ({ ac: 'detail', wd: kw, ...p }),
  byIds: (ids, p) => ({ ac: 'detail', ids: Array.isArray(ids) ? ids.join(',') : ids, ...p }),
};

export class MaccmsStation {
  constructor(station, defaults = {}) {
    this.s = station;
    this.d = defaults;
    this.id = station.id;
    this.name = station.name;
    this.hosts = station.hosts;
    this.path = station.path || '/api.php/provide/vod/';
    this.opts = {
      timeoutMs: station.timeoutMs || defaults.timeoutMs || 15000,
      retry: defaults.retry ?? 1,
      limit: defaults.concurrencyPerHost || 2,
      headers: { ...(defaults.headers || {}), ...(station.headers || {}) },
    };
  }

  async call(params) {
    const q = { ...params, at: this.d.format || 'json' }; // at=json，实测全站支持
    const res = await requestWithFailover(this.hosts, this.path, q, this.opts);
    return parseJson(res.text);
  }

  /** 分页列表（整站增量采集用） */
  async list({ pg = 1, t = 0, h = 0, pagesize = 20, detail = false } = {}) {
    // ac=list 只返回精简字段（没有 vod_pic），ac=detail 才带海报/简介
    const params = detail ? ACTION.detail({ pg, t, h, pagesize }) : ACTION.list({ pg, t, h, pagesize });
    const j = await this.call(params);
    return { total: j.total ?? 0, page: j.page ?? pg, pagecount: j.pagecount ?? 1, list: (j.list || []).map((x) => normalizeVod(x, this.s)) };
  }

  /** 关键字搜索：部分站关闭了 wd，需靠 config 里 search:'none' 标记 */
  async search(kw) {
    if (this.s.search === 'none') {
      const err = new Error(`${this.name} 未开放 wd 搜索`);
      err.code = 'SEARCH_DISABLED';
      throw err;
    }
    const j = await this.call(ACTION.search(kw));
    return (j.list || []).map((x) => normalizeVod(x, this.s));
  }

  /** 详情（含播放地址），ids 可批量 */
  async detail(ids) {
    const j = await this.call(ACTION.byIds(ids));
    return (j.list || []).map((x) => normalizeVod(x, this.s, true));
  }

  /** 分类表（整站采集时对齐 tid 用） */
  async classes() {
    const j = await this.call({ ac: 'list' });
    return (j.class || []).map((c) => ({ id: c.type_id, pid: c.type_pid, name: c.type_name }));
  }
}

/* ---------------- 归一化：23 站的差异在这里被抹平 ---------------- */

/** "第01集$http://...#第02集$http://..." + "线路A$$$线路B" */
export function parsePlayUrl(playFrom = '', playUrl = '') {
  const froms = String(playFrom || '').split('$$$');
  return String(playUrl || '')
    .split('$$$')
    .map((block, i) => {
      const episodes = block
        .split('#')
        .map((seg) => {
          const idx = seg.indexOf('$');
          if (idx < 0) return null;
          return { name: seg.slice(0, idx).trim(), url: seg.slice(idx + 1).trim() };
        })
        .filter((e) => e && /^https?:/i.test(e.url));
      return {
        flag: froms[i] || froms[0] || 'unknown',
        broken: episodes.length === 0, // 部分站的 $$ 线路是空的
        episodes,
      };
    })
    .filter((l) => l.episodes.length > 0);
}

export function normalizeVod(raw, station, withPlay = false) {
  const lines = withPlay ? parsePlayUrl(raw.vod_play_from, raw.vod_play_url) : [];
  return {
    uid: `${station.id}:${raw.vod_id}`,
    station: station.id,
    stationName: station.name,
    vodId: raw.vod_id,
    name: clean(raw.vod_name),
    sub: clean(raw.vod_sub),
    year: Number(raw.vod_year) || null,
    type: clean(raw.type_name),
    typeId: raw.type_id,
    area: clean(raw.vod_area),
    lang: clean(raw.vod_lang),
    remarks: clean(raw.vod_remarks),
    score: Number(raw.vod_score) || null,
    pic: raw.vod_pic || '',
    director: clean(raw.vod_director),
    actors: clean(raw.vod_actor).split(/[,，]/).map((s) => s.trim()).filter(Boolean),
    updatedAt: raw.vod_time,
    content: clean(raw.vod_content).replace(/<[^>]+>/g, ''),
    totalEpisodes: Number(raw.vod_total) || 0,
    serial: clean(raw.vod_serial),
    lines,
  };
}

function clean(v) {
  return String(v ?? '').replace(/&nbsp;/g, ' ').replace(/\s+/g, ' ').trim();
}

/** 把详情合并进搜索结果（同 uid） */
export function mergeDetail(base, detail) {
  return { ...base, ...detail, lines: detail.lines };
}
