/**
 * 零依赖 HTTP 接口，方便前端（daisyUI / 自建 CMS）直接调用。
 *   node src/server.js 8765
 *   GET /api/health
 *   GET /api/search?kw=空枪&resolve=1&only=bfzy,liangzi
 *   GET /api/detail?station=bfzy&id=169057
 *   GET /api/play?url=<m3u8>
 *   GET /api/stations
 */
import http from 'node:http';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const WEB_DIR = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'web');
import { health, searchAll, loadConfig, pickStations } from './collector.js';
import { resolvePlay, readableDuration } from './hls.js';
import { getChart } from './chart.js';
import { getRank, fetchPoster } from './douban.js';

const PORT = Number(process.argv[2] || 8765);
const send = (res, code, data) => {
  const body = JSON.stringify(data, null, 2);
  res.writeHead(code, {
    'Content-Type': 'application/json; charset=utf-8',
    'Access-Control-Allow-Origin': '*',
    'Cache-Control': 'no-store',
    'Content-Length': Buffer.byteLength(body),
  });
  res.end(body);
};

http
  .createServer(async (req, res) => {
    const url = new URL(req.url, `http://localhost:${PORT}`);
    const q = url.searchParams;
    try {
      if (url.pathname === '/api/health') return send(res, 200, await health({ probeSearch: true }));
      if (url.pathname === '/api/rank') {
        return send(res, 200, await getRank({ force: q.get('force') === '1' }));
      }
      if (url.pathname === '/api/poster') {
        // 豆瓣海报必须带 Referer，浏览器直连会 418，只能走这里代理
        const target = q.get('u');
        if (!target) return send(res, 400, { error: '缺少 u 参数' });
        try {
          const p = await fetchPoster(target);
          if (p.buf) {
            res.writeHead(200, {
              'Content-Type': p.type,
              'Access-Control-Allow-Origin': '*',
              'Cache-Control': 'public, max-age=604800',
            });
            return res.end(p.buf);
          }
          const buf = await readFile(p.file);
          res.writeHead(200, {
            'Content-Type': p.type,
            'Access-Control-Allow-Origin': '*',
            'Cache-Control': 'public, max-age=604800',
          });
          return res.end(buf);
        } catch (e) {
          return send(res, 502, { error: e.message });
        }
      }
      if (url.pathname === '/api/chart') {
        const chart = await getChart({
          force: q.get('force') === '1',
          hours: Number(q.get('hours') || 168),
          perStation: Number(q.get('per') || 100),
        });
        return send(res, 200, chart);
      }
      if (url.pathname === '/api/stations') {
        const list = await pickStations();
        return send(res, 200, list.map((s) => ({
          id: s.id,
          name: s.name,
          hosts: s.hosts,
          path: s.s.path || '/api.php/provide/vod/',
          search: s.s.search,
          priority: s.s.priority || 2,
          note: s.s.note,
        })));
      }
      if (url.pathname === '/api/search') {
        const kw = q.get('kw');
        if (!kw) return send(res, 400, { error: '缺少 kw 参数' });
        const only = q.get('only') ? q.get('only').split(',').filter(Boolean) : undefined;
        return send(res, 200, await searchAll(kw, { only, resolve: q.get('resolve') === '1' || q.get('resolve') === 'true' }));
      }
      if (url.pathname === '/api/detail') {
        const { byId } = await loadConfig();
        const st = byId.get(q.get('station'));
        if (!st || !q.get('id')) return send(res, 400, { error: '需要 station 与 id' });
        const [d] = await st.detail(q.get('id'));
        return send(res, 200, d || { error: '未找到' });
      }
      if (url.pathname === '/api/play') {
        const target = q.get('url');
        if (!target) return send(res, 400, { error: '缺少 url 参数' });
        const p = await resolvePlay(target);
        return send(res, 200, { ...p, duration: readableDuration(p.durationSec) });
      }
      // 静态页（热榜首页 / 旧版海报墙）
      const file = url.pathname === '/' ? 'rank.html' : url.pathname.replace(/^\//, '');
      const full = path.join(WEB_DIR, file);
      if (full.startsWith(WEB_DIR)) {
        try {
          const html = await readFile(full);
          res.writeHead(200, { 'Content-Type': file.endsWith('.html') ? 'text/html; charset=utf-8' : 'application/octet-stream' });
          return res.end(html);
        } catch { /* 落到 404 */ }
      }
      send(res, 404, { error: '未知接口', routes: ['/api/rank', '/api/poster', '/api/health', '/api/stations', '/api/chart', '/api/search', '/api/detail', '/api/play'] });
    } catch (e) {
      send(res, 500, { error: e.message });
    }
  })
  .listen(PORT, () => console.log(`vod-collector API 已启动: http://localhost:${PORT}/api/search?kw=空枪`));
