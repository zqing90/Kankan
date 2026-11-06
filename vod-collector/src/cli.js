#!/usr/bin/env node
/**
 * CLI:
 *   node src/cli.js health [--json]
 *   node src/cli.js search 空枪 [--resolve] [--only=bfzy,liangzi] [--out=out/kongqiang.json]
 *   node src/cli.js detail bfzy 169057
 *   node src/cli.js play <m3u8地址>
 *   node src/cli.js stations
 */
import { writeFile, mkdir } from 'node:fs/promises';
import path from 'node:path';
import { health, searchAll, loadConfig, pickStations } from './collector.js';
import { getChart } from './chart.js';
import { resolvePlay, readableDuration } from './hls.js';

const argv = process.argv.slice(2);
const [cmd, ...rest] = argv;
const flag = (name, def = false) => {
  const hit = rest.find((a) => a === `--${name}` || a.startsWith(`--${name}=`));
  if (!hit) return def;
  if (hit.includes('=')) return hit.split('=').slice(1).join('=');
  return true;
};
const positional = rest.filter((a) => !a.startsWith('--'));

const pad = (s, n) => String(s ?? '').padEnd(n);
const padS = (s, n) => String(s ?? '').slice(0, n).padStart(n);

async function cmdHealth() {
  const res = await health({ probeSearch: true, keyword: '测试' });
  if (flag('json')) return console.log(JSON.stringify(res, null, 2));
  console.log(`\n站点健康检查（${res.length} 站）\n`);
  console.log(pad('站点', 12), pad('主机', 32), padS('条数', 8), pad('搜索', 8), padS('耗时', 7));
  console.log('-'.repeat(78));
  for (const r of res.sort((a, b) => Number(b.ok) - Number(a.ok) || a.ms - b.ms)) {
    const status = r.ok ? padS(r.total, 8) : padS('DOWN', 8);
    console.log(pad(r.name, 12), pad(r.host.replace(/^https?:\/\//, ''), 32), status, pad(r.search, 8), padS(r.ms + 'ms', 7));
    if (!r.ok && r.error) console.log('   └', r.error);
  }
  const ok = res.filter((r) => r.ok).length;
  console.log(`\n可用 ${ok}/${res.length}，其中开放搜索 ${res.filter((r) => r.search === 'ok').length} 个`);
}

async function cmdSearch() {
  const kw = positional[0];
  if (!kw) return console.error('用法: node src/cli.js search 片名 [--resolve]');
  const resolve = Boolean(flag('resolve'));
  const only = flag('only') ? String(flag('only')).split(',') : undefined;
  const outFlag = flag('out');
  const out = typeof outFlag === 'string' ? outFlag : null;

  const t0 = Date.now();
  if (!resolve) console.log(`\n搜索「${kw}」… (${new Date().toLocaleTimeString()})`);
  const res = await searchAll(kw, { only, resolve });
  const { stats } = res;

  console.log(`\n「${kw}」  ${stats.hit}/${stats.stations} 站命中 · 合并 ${res.items.length} 条 · 耗时 ${Date.now() - t0}ms\n`);
  for (const it of res.items) {
    const best = it.best;
    const alias = it.aliases.length > 1 ? `  别名: ${it.aliases.join(' / ')}` : '';
    console.log(`■ ${it.name}${it.year ? ` (${it.year})` : ''}  ${it.type || ''}  ${it.remarks || ''}${it.shortVersion ? '  ⚠ 时长偏短(疑似枪版/删减)' : ''}`);
    console.log(`  ${it.sources.length} 站收录  ${it.lines.filter((l) => l.playable).length}/${it.lines.length} 条线路可播` +
      (best ? `  最佳: ${best.stationName} ${best.resolution || ''} ${best.duration}` : '') + alias);
    if (resolve) {
      for (const l of it.lines) {
        const mark = l.playable ? '✓' : '✗';
        console.log(`   ${mark} ${pad(l.stationName, 8)} ${pad(l.flag, 14)} ${pad(l.resolution || '—', 10)} ${pad(l.duration, 7)} ${pad(l.segments + '段', 8)} ${l.encrypted ? 'AES ' : '    '} ${l.error || ''}`);
      }
    }
    console.log('');
  }
  const bad = res.byStation.filter((b) => !b.ok);
  if (bad.length) console.log('跳过:', bad.map((b) => `${b.name}(${b.error})`).join('、'), '\n');

  if (out) {
    await mkdir(path.dirname(out), { recursive: true });
    await writeFile(out, JSON.stringify(res, null, 2));
    console.log('已写入', out);
  }
}

async function cmdDetail() {
  const [sid, vid] = positional;
  const { byId } = await loadConfig();
  const st = byId.get(sid);
  if (!st || !vid) return console.error('用法: node src/cli.js detail <站点id> <vod_id>');
  const [d] = await st.detail(vid);
  if (!d) return console.error('未找到');
  console.log(JSON.stringify(d, null, 2));
}

async function cmdPlay() {
  const url = positional[0];
  if (!url) return console.error('用法: node src/cli.js play <m3u8>');
  const p = await resolvePlay(url);
  console.log(JSON.stringify({ ...p, duration: readableDuration(p.durationSec) }, null, 2));
}

async function cmdStations() {
  const list = await pickStations();
  const { raw } = await loadConfig();
  console.log(`\n已配置 ${list.length} 站（config/stations.json v${raw.version}）\n`);
  console.log(pad('id', 12), pad('名称', 12), pad('搜索', 8), pad('播放源flag', 16), '备注');
  console.log('-'.repeat(96));
  for (const s of list) {
    console.log(pad(s.id, 12), pad(s.name, 12), pad(s.s.search, 8), pad(s.s.playFrom || '—', 16), s.s.note || '');
  }
}

async function cmdChart() {
  const force = Boolean(flag('force'));
  const c = await getChart({ force, hours: Number(flag('hours') || 168), perStation: Number(flag('per') || 100) });
  console.log(`\n热榜 ${c.items.length} 部 · ${c.cached ? '来自缓存' : '刚生成'} · ${c.tookMs || 0}ms\n`);
  for (const [k, g] of Object.entries(c.groups)) {
    console.log(`【${g.label}】${g.items.length} 部`);
    g.items.slice(0, 8).forEach((it, i) =>
      console.log(`  ${String(i + 1).padStart(2, ' ')}. ${pad(it.name, 24)} ${pad(it.remarks || '', 12)} ${it.stations.length} 站`));
    console.log('');
  }
  if (flag('out')) {
    await writeFile(String(flag('out')), JSON.stringify(c, null, 2));
    console.log('已写入', flag('out'));
  }
}

const main = { health: cmdHealth, search: cmdSearch, detail: cmdDetail, play: cmdPlay, stations: cmdStations, chart: cmdChart }[cmd];
if (!main) {
  console.error('可用命令: chart | health | search | detail | play | stations');
  process.exit(1);
}
main().catch((e) => { console.error('执行失败:', e.message); process.exit(1); });
