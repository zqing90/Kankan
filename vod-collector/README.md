# vod-collector

一套适配层接入全部苹果CMS（maccms V10）资源站。**新增一个资源站 = 在 `config/stations.json` 里加一段 JSON，不改一行代码。**

```bash
node src/server.js 8765                     # 起服务，首页即「热播榜」http://localhost:8765
node src/cli.js health                      # 站点健康检查（存活 / 条数 / 是否开放搜索）
node src/cli.js search 空枪 --resolve       # 多站并采 + 合并去重 + 逐条验证视频能否播放
node src/cli.js chart                       # 备用榜单（多站共现自算热度，豆瓣挂了的兜底）
node src/cli.js detail bfzy 169057          # 单站详情
node src/cli.js play <m3u8地址>              # 解析任意播放地址（含分享页/加密/多级）
```

零依赖，只要 Node ≥ 18（用原生 `fetch`）。

---

## 一、为什么能"一套代码通吃"

23 个存活站实测，协议层完全同构：

| 维度 | 实测结果 |
|---|---|
| 接口路径 | 3 种：`/api.php/provide/vod/`（21 站）、`/xinlangapi.php/provide/vod/`（新浪）、`/provide/vod/`（金鹰） |
| 请求参数 | 完全一致：`ac=list` / `ac=detail` / `wd=` / `ids=` / `pg` `t` `h` / `at=json` |
| 默认响应格式 | **23/23 都是 JSON**（不是文档里写的 XML），统一加 `at=json` 最稳 |
| 详情字段 | 83～85 个，**公共字段 82 个**，差异只有 3 个扩展图片字段（`vod_pic_original` 等） |
| 播放源结构 | `vod_play_from` 用 `$$$` 分线路；`vod_play_url` 用 `#` 分集、`$` 分「集名/地址」、`$$$` 分线路 |

差异只有 4 类，全部收敛进配置：

1. **播放地址形态**（4 种，由 `src/hls.js` 自动识别，配置无需关心）
   - `media` 直接媒体列表（暴风，相对 `.ts`）
   - `master` 多级清晰度列表（牛牛/最大，`index.m3u8` → `2000k_1080/hls/index.m3u8`）
   - `share-page` 分享页 HTML（量子/非凡，页面里内嵌带 `sign` 的 m3u8，需二次解析）
   - `AES-128` 加密（光速/红牛/极速/新浪/金鹰/魔都/飘零/虎牙/360 共 9 站）
2. **搜索开放度**：16 站开放 `wd`，7 站返回「暂不支持搜索」（快车/天涯/樱花/索尼/闪电/鸭鸭/牛牛），只能 `ac=list` 全量增量采
3. **播放源 flag 命名**：`lzm3u8` / `bfzym3u8` / `gsm3u8` … 只作标识，不参与逻辑
4. **域名漂移**：站点经常换域名，配置里用 `hosts` 数组做故障切换

---

## 二、目录

```
config/stations.json   站点清单（全部可配）
src/http.js            传输层：超时 / 重试 / 多域名切换 / 每域名限速
src/maccms.js          协议层：list / detail / search + 播放地址拆分 + 字段归一化
src/hls.js             解析层：4 种播放地址形态统一成可播信息（清晰度/时长/段数/加密）
src/collector.js       编排层：并发拉多站 → 按片名聚合 → 去重 → 选最佳源
src/douban.js          热播榜数据源：豆瓣 17 个官方榜单 + 海报代理 + 30 分钟缓存
src/chart.js           备用榜单：多站共现打分（豆瓣不可用时的兜底）
web/rank.html          热播榜首页（默认首页，daisyUI）
web/index.html         资源站海报墙（旧版，走 /index.html）
src/cli.js             命令行
src/server.js          HTTP 接口（带 CORS，前端可直接调）
out/                   采集结果 / 榜单缓存 / 海报缓存
```

---

## 三、配置字段

```jsonc
{
  "defaults": {
    "adapter": "maccms",
    "format": "json",              // 统一加 at=json
    "timeoutMs": 15000,
    "retry": 1,                    // 每个域名失败重试次数
    "concurrencyPerHost": 2,       // 单域名并发上限（别打人家）
    "headers": { "User-Agent": "..." }
  },
  "stations": [
    {
      "id": "bfzy",                          // 内部唯一 id
      "name": "暴风资源",
      "enabled": true,                       // false = 直接跳过
      "hosts": ["https://bfzyapi.com"],      // 多域名容灾，按顺序试
      "path": "/api.php/provide/vod/",       // 接口路径（3 种变体之一）
      "priority": 1,                         // 1 主力 / 2 可用 / 3 备选（选源时参考）
      "search": "wd",                        // wd | none
      "searchVia": null,                     // 搜索关闭时可选 suggest（站内联想接口）
      "playFrom": "bfzym3u8",                // 仅记录，便于排查
      "note": "直接给 m3u8，无加密，最省事"
    }
  ]
}
```

**加一个新站（30 秒）**：复制一段，改 `id` / `name` / `hosts` / `path`，然后 `node src/cli.js health --json` 看它是否活着、搜索是否开放。就这样，播放地址是什么形态由解析层自动判断。

---

## 四、扩展点

| 需求 | 改哪里 |
|---|---|
| 新的接口路径变体 | `stations[].path` |
| 新站点挂了换域名 | `stations[].hosts` 加一个，自动故障切换 |
| 搜索关闭的站也想搜 | 实现 `searchVia: "suggest"` 的适配（走 `index.php/ajax/suggest` 拿 id 再 detail） |
| 新播放形态（如 kuyun） | `src/hls.js` 加一个 `resolveXxx()`，在 `resolvePlay` 里按特征分流 |
| 分类对齐（整站采集） | `MaccmsStation.classes()` + `stations[].typeMap` 映射本地栏目 |
| 增量采集 | `st.list({ pg, t, h })`，`h` = 最近 N 小时更新 |

---

## 五、本次实测（2026-11-06）

```bash
$ node src/cli.js search 空枪 --resolve
「空枪」  14/23 站命中 · 合并 3 条 · 耗时 19736ms

■ 空枪 (2026)  剧情片  HD
  14 站收录  24/24 条线路可播  最佳: 最大资源 1920x800 2h41m
■ 空枪（粤语） (2026)  剧情片  更新至HD（粤语）
  12 站收录  20/20 条线路可播  别名: 空枪（粤语） / 空枪粤语
■ 空枪[电影解说] (2026)  电影解说  已完结
  1 站收录  2/2 条线路可播
```

- 46 条播放线路全部可播（首片 200/206），其中 40 条片源路径互不相同
- 端到端 19.7 秒（含 46 条线路逐个取流验证）；只搜索不验证约 8.6 秒
- 详情见 `out/kongqiang.json`

## 六、热播榜首页

`node src/server.js 8765` 后打开 `http://localhost:8765`：分类 tab + 海报墙 + 详情弹窗 + 播放。

榜单数据来自**豆瓣 rexxar 接口**（17 个官方榜单，528 部，30 分钟缓存），不是自算热度：

```
GET /api/rank                   # 全部榜单（约 700KB，7ms 命中缓存）
GET /api/rank?force=1           # 强制重抓（约 4s）
GET /api/poster?u=<encoded>     # 豆瓣海报代理（必须走这层，直连 418）
GET /api/stations               # 片源清单（含 path / priority，供 TV 端远程同步）
```

为什么选豆瓣、实测了哪些源不可用、四个必踩的坑，见 **`热播榜数据源.md`**。

播放链路与榜单源解耦：点开详情时才按片名去资源站检索，命中后带打分筛选（避开同名短剧/解说版）。

`src/chart.js` 保留为备用算法，走 `/api/chart`。

### 给 Android TV 用

同一套接口可以直接喂电视端（`/workspace/vod-tv`）：

- `/api/rank` → TV 端填了服务端地址后走这个，一次请求拿全量
- `/api/poster` → TV 端不再自己带 Referer，全部走代理
- `/api/stations` → TV 设置页「从服务端同步源」拉这个，加源不用发新版

TV 端默认是直连模式（不依赖服务器），填了地址才切服务端。详见 `vod-tv/README.md`。

## 七、注意

- 这些资源站提供的是**未授权的影视片源**，接口只适合做技术验证/自建测试，对外提供播放有侵权风险。
- 站点域名更换频繁（本次 9/32 已失效），建议把 `health` 挂到定时任务，把 `hosts` 当可热更配置维护。
- 采集频率别太高，`concurrencyPerHost` 保持 2 以内，避免被 ban。
