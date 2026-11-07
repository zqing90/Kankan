# 看看（Kankan）· 项目总说明

> 这份文档写给「接手这个项目的 AI」或未来的自己。目标是：只读这一个文件，就能知道项目是什么、代码在哪、为什么这么写、哪些坑已经踩过、下一步该做什么。
> 生成时间：2026-11-06 ｜ 最后更新：2026-11-07（1.4.8） ｜ 全部内容为本地可运行代码，无外部私有依赖。

---

## 0. 一句话概括

一个 Android TV 客户端（应用名「看看」），首页是豆瓣热播榜海报墙，点开影片自动到 14 个盗版资源站检索片源、解析 m3u8、按稳定性排序、可用遥控器播放，并记住每部片看到第几集第几秒。

配套一个 Node.js 采集器（可选）：把 23 个资源站的差异配置化、把豆瓣榜单归一化，同时提供电脑端海报墙网页。

---

## 1. 项目由三部分组成

| 目录 | 语言/技术 | 作用 | 是否必需 |
|---|---|---|---|
| `vod-tv/` | Java 8 + Android 原生 View | TV 客户端，产出 APK | 必需（最终交付物） |
| `vod-collector/` | Node.js 18+，零第三方依赖 | 采集器 + 榜单服务 + 网页 | 可选（客户端能直连） |
| `kongqiang-test/` | 原生 HTML + hls.js | 最早期的验证页，保留作参考 | 否 |

三者关系：

```
            ┌─────────────────────────────────────────┐
            │  vod-tv（Android TV）                    │
            │  默认直连：豆瓣 + 14 个资源站             │
            │  可选：填了服务端地址就全走 vod-collector  │
            └───────────────┬─────────────────────────┘
                            │ 可选 HTTP
            ┌───────────────▼─────────────────────────┐
            │  vod-collector（Node，端口 8765）         │
            │  /api/rank  /api/poster  /api/search ... │
            └─────────────────────────────────────────┘
```

---

## 2. 目录与文件职责

### 2.1 vod-tv（Android）

```
vod-tv/
├── build.gradle                 根构建脚本
├── settings.gradle
├── gradle.properties
├── local.properties             ⚠️ 不入库，需自己写 sdk.dir
├── gradlew / gradlew.bat
├── dist/Kankan-1.4.8-debug.apk  已构建产物（历史版本依次留档；同时复制一份到 E:\share\）
├── README.md                    使用与构建说明（面向人）
├── PROJECT.md                   本文件（面向 AI）
└── app/
    ├── build.gradle             compileSdk 33 / minSdk 19 / targetSdk 28
    └── src/main/
        ├── AndroidManifest.xml  Launcher + Leanback 双入口
        ├── java/com/vodtv/
        │   ├── App.java                     ⭐ 入口：给 4.x 强开 TLS 1.2 + 初始化存储
        │   ├── data/
        │   │   ├── Prefs.java               配置：服务端地址、更新日期、锁定源、音量
        │   │   ├── SourceConfig.java        单个源模型（id/name/host/path/priority/enabled）
        │   │   ├── SourceStore.java         14 个内置源 + 启停/锁定/恢复默认/远程同步
        │   │   ├── SourceStats.java         ⭐ 成功率统计与打分（拉普拉斯平滑）
        │   │   ├── RankRepository.java      ⭐ 榜单：豆瓣双接口直连 / 服务端两套 + 磁盘缓存
        │   │   │                            + 新品速递 / 找一找（1.4.8，见 §20）
        │   │   ├── RankItem.java            榜单条目模型 + 双结构归一化
        │   │   ├── PlayResolver.java        ⭐ 搜片 → 拆线路/分集 → 解析 m3u8 → 探测 → 排序
        │   │   ├── PlayCache.java           ⭐ 源检测结果按自然日缓存（每部片一个文件）
        │   │   ├── ResumeHelper.java        ⭐ 续播三级降级（PlayCache→重新检索→详情页），
        │   │   │                            记录页/历史入口共用（1.4.1，见 §17.2）
        │   │   ├── MovieInfo.java           影片信息模型（Intent ↔ JSON 双向转换）
        │   │   ├── HistoryStore.java        ⭐ 播放进度 + 影片信息快照 + 已看完推导（1.4.7，见 §17.5）
        │   │   ├── SearchHistory.java       搜索关键词历史（最多 12 条，去重、最新的在前）
        │   │   ├── ImageLoader.java         海报加载（Referer + 内存/磁盘缓存）
        │   │   └── Http.java                极简 HTTP，只用 HttpURLConnection
        │   ├── ui/
        │   │   ├── HomeActivity.java        海报墙三 tab：豆瓣热榜/新品速递/找一找（1.4.8，见 §20）
        │   │   ├── DetailActivity.java      影片信息 + 片源列表 + 选集 + 继续观看
        │   │   ├── SearchActivity.java      搜索页：本地榜单 + 片源站候选合并、最近搜索标签
        │   │   ├── HistoryActivity.java     观看记录页（网格 / 单条删除 / 清空）
        │   │   └── SettingsActivity.java    源配置、服务端地址、清缓存、强制更新
        │   ├── widget/RoundImageView.java   圆角图片（API 19 无 setClipToOutline，走 clipPath）
        │   └── player/PlayerActivity.java   ExoPlayer 播放页 + 控制栏 + 选集浮层 + 进度落盘
        └── res/
            ├── layout/                      12 个布局，全部手写原生 View
            │                                （含 part_back.xml：所有二级页共用的返回按钮）
            ├── values/ + values-sw600dp/    colors / strings / styles / dimens / integers（两档）
            ├── mipmap-*/                    ic_launcher（5 档密度）+ ic_launcher_foreground
            ├── mipmap-anydpi-v26/           自适应图标（API 26+）
            └── drawable-{m,h,xh,xxh,xxxh}dpi/
```

图标分两类，**都不要手改 PNG**：

| 类型 | 来源 | 脚本 |
|---|---|---|
| App 图标 / TV banner | 自己画（纯色底 + 白色播放三角，参照夸克/UU 远程） | `tools/make_icons.py` |
| 顶栏 / 播放控制图标 | **Google Material Rounded（Apache-2.0），不自己画** | `tools/fetch_material_icons.py` |

Material 图标的 SVG 缓存在 `tools/icons_src/<变体>/`，改图标只改脚本里的映射表再重跑：
`MATERIAL_VARIANT=materialiconsround python tools/fetch_material_icons.py`（默认就是 Rounded）。

交付前跑 `python tools/check_ids.py`（见 §18.7）。

### 2.2 vod-collector（Node）

```
vod-collector/
├── package.json          "type": "module"，无 dependencies
├── config/stations.json  ⭐ 23 个资源站的配置（唯一需要改的地方）
├── src/
│   ├── http.js           fetch 封装：超时、重试、UA、并发限流
│   ├── maccms.js         ⭐ 苹果CMS 适配层：list / detail / search
│   ├── hls.js            ⭐ 播放地址解析：master 递归、AES-128、分享页二次解析
│   ├── collector.js      ⭐ 多站聚合：按 name|year 合并、去重、并发
│   ├── douban.js         ⭐ 豆瓣 17 榜拉取 + 双结构归一化
│   ├── chart.js          自算热度榜（备用，已被豆瓣榜取代）
│   ├── cli.js            命令行：health / search / detail / play / stations / chart
│   └── server.js         HTTP 服务，端口 8765
├── web/
│   ├── index.html        服务端驱动的海报墙
│   ├── rank.html         豆瓣榜单海报墙（带播放浮层）
│   └── 海报墙-离线预览.html  内联 base64 海报，双击可看
├── out/                  运行产物（缓存、日志）
├── 技术分析.md           23 站同构性的实证分析
├── 热播榜数据源.md       选源过程与被排除的方案
└── 热播榜与海报墙.md     页面设计说明
```

---

## 3. 数据流

### 3.1 首页榜单

```
启动 App
  → RankRepository.load()
      ├─ 读 files/rank.json（命中则立即渲染，秒开）
      └─ Prefs.needDailyUpdate()（今天 != 上次日期）
            ├─ 直连模式：GET m.douban.com/rexxar/... × 17 个榜单
            └─ 服务端模式：GET {server}/api/rank（一次拿全）
          → 合并去重 → 写 rank.json + 记日期 → 刷新 UI
```

首页现在是三个 tab（1.4.8）：**豆瓣热榜**（17 榜全量合并去重）/ **新品速递**（豆瓣 B 接口）/ **找一找**（B 接口按条件查片），详见 §20。

### 3.2 点开一部片

```
HomeActivity（点击海报）
  → DetailActivity（传 title/year/pic/rating/...）
      → PlayResolver.search(title)
          对 14 个源并发：
            1. GET {host}{path}?ac=detail&at=json&wd={片名}
            2. pickBest() 挑同名且非「解说/预告/花絮」的条目
            3. splitEpisodes(vod_play_url) → 按 $$$ 选线路、按 # 拆集
            4. 首集 + 末集各探一次（GET m3u8 看有没有 #EXTM3U）
            5. 写 SourceStats（成功/失败）
          → 按 score 排序 → 渲染片源列表（第一条标「推荐」并自动获焦）
```

### 3.3 播放

```
DetailActivity
  ├─ 单集：直接 PlayerActivity
  └─ 多集：选集页 → 点某集才 resolveEp() 解析该集 → PlayerActivity
PlayerActivity
  ├─ 读 HistoryStore → 有记录且集名匹配 → READY 后 seekTo
  ├─ 每 5 秒 saveProgress()，onPause / onDestroy 各补一次
  └─ ExoPlayer(HlsMediaSource) 播放
```

---

## 4. 外部接口（全部需要联网的地方）

### 4.1 豆瓣双接口（1.4.8 实测对比后双轨使用）

**A 接口 · rexxar 榜单**（豆瓣热榜 17 榜在用）

```
GET https://m.douban.com/rexxar/api/v2/subject_collection/{cid}/items?start=0&count=60
Headers:
  User-Agent: Mozilla/5.0 (iPhone; ...) Safari/604.1
  Referer:    https://m.douban.com/        ← 缺了直接 400
```

- `{cid}` 见 `vod-collector/src/douban.js` 的 `BOARDS`；count 上限 50
- 字段全：`pic/cover.url`、`rating.value`、`card_subtitle`、`description`

**B 接口 · new_search_subjects**（新品速递 / 找一找在用）

```
GET https://m.douban.com/rexxar/api/v2/new_search_subjects?start=0&count=20&sort=U&tags=电影&year_range=2020,2026
Headers:
  User-Agent: 必须有，缺了 418；不需要 Referer
```

- 每页**固定 20 条**，`start` 可翻到 980 以后（适合自动翻页）
- 字段窄：`title/rate/cover/url/casts/directors`，**没有简介和年份**（年份由查询条件带入）
- 参数：`sort=U(最热)/S(高分)/T(最新)`；`tags` 逗号组合可用、**空格不行**；
  `year_range=a,b` 支持区间；`range=8,10` 评分下限
- 封装：`RankRepository.fetchNewReleases()`（当年可播按热度 3 页 60 条）、`RankRepository.find(tags, yearKey, sort, start)`

老接口 `/j/search_subjects` 确认 403，不要用。两接口 30 分钟内建议缓存。

### 4.2 苹果 CMS（maccms V10）资源站

```
GET {host}{path}?ac=detail&at=json&wd={关键词}   搜索
GET {host}{path}?ac=list&t={分类}&pg={页码}      列表
GET {host}{path}?ac=detail&ids={id}             详情
```

- 23 个存活站参数完全一致（`ac`/`wd`/`ids`/`pg`/`t`/`h`/`at`），详见 `技术分析.md`
- 差异只有三类，已全部配置化：路径变体、搜索是否开放、域名漂移

### 4.3 海报（客户端直连时）

豆瓣图片直连会 418，必须带 Referer。`ImageLoader` 里对 `doubanio.com` 单独加了头；服务端模式下改为 `/api/poster?u=` 代理（带磁盘缓存 + SSRF 白名单）。

---

## 5. 关键数据结构

### 5.1 `vod_play_url` —— 三级结构（最容易搞错的地方）

```
第01集$https://a.com/share/xxx#第02集$https://a.com/share/yyy   ← 线路1：分享页
$$$                                                              ← 线路分隔符
第01集$https://a.com/2025/xx/index.m3u8#第02集$...               ← 线路2：直链
```

- `$$$` 分线路，`#` 分集，`$` 分隔集名与地址
- **一条记录通常是两条线路**（一条分享页、一条直链），只按 `#` 拆会把 91 条当 91 集、集号重复
- `PlayResolver.splitEpisodes()` 的策略：选**直链占比最高**的那条线路

实测《庆余年》第一季：原始 91 条 → 拆出 46 集、集名无重复、全直链。

### 5.2 豆瓣榜单：两套结构混在一个列表里

| 类型 | 海报字段 | 简介字段 | 导演/演员 |
|---|---|---|---|
| 剧集/综艺/动画/纪录 | `pic.normal` / `pic.large` | `card_subtitle` | 需从 subtitle 正则拆 |
| 电影（购票/票房榜） | `cover.url` | `info` | `directors[]` / `actors[]` |

只按第一套取，电影整排破图。归一化入口：`RankItem.fromDouban()` / `douban.js#normalize()`。

海报尺寸替换：卡片用 `/s_ratio_poster/`（≈30KB），详情用 `/m_ratio_poster/`（≈100KB）。

### 5.3 播放进度（SharedPreferences `history`）

```json
{
  "庆余年第一季": {
    "ep": "第03集",
    "src": "liangzi",
    "pos": 734000,
    "dur": 2760000,
    "at": 1767331200000
  }
}
```

一部片只留最新一条，最多 80 部，超了按 `at` 淘汰。

### 5.4 源配置（SharedPreferences `vodtv`，key = `sources`）

```json
[{"id":"liangzi","name":"量子资源","host":"https://cj.lziapi.com",
  "path":"/api.php/provide/vod/","priority":1,"enabled":true}]
```

---

## 6. 核心算法

### 6.1 源稳定性打分（`SourceStats`）

```
成功率 = (成功次数 + 1) / (总次数 + 2)      // 拉普拉斯平滑
速度分 = 1 - min(平均耗时, 6000) / 6000
得分   = 成功率 × 0.75 + 速度分 × 0.25 + priority × 0.01
```

平滑的作用：只成功过一次的源不会因为 1/1 = 100% 被顶到第一位。**这是「稳定性优先」的实现核心，不要改成纯成功率排序。**

### 6.2 片名匹配打分（`PlayResolver.pickBest`）

```
完全同名        +100
前缀互相包含    +40
含「解说/预告/花絮/片段/速看/混剪/彩蛋」  -200
列表第一条      +5
vod_play_url 含 .m3u8  +5

最终 < 40 视为不匹配（宁可没有，也不播错片）
```

注意：`clean()` 会去掉括号内容，否则「庆余年（2024）」匹配不上「庆余年」。

### 6.3 续播判断（`HistoryStore.resumePos`）

| 条件 | 行为 |
|---|---|
| `pos < 30s` | 从头 |
| `pos > dur - 60s` | 从头（当看完了） |
| 集名不一致 | 从头（防止把第 1 集进度套到第 8 集） |

首页的「继续观看」不要自己去猜哪部片，直接 `HistoryStore.recent(10)` 拿按 `at` 倒序的记录；
每条记录里带一份 `MovieInfo` 快照，所以榜单里已经没有这部片了也能正常显示和进入详情页。

### 6.4 源检测缓存（`PlayCache`）

同一部片**一天只检测一次**，跨天自动失效。

| 项 | 做法 |
|---|---|
| 粒度 | 整份结果：每个源可用与否 + 全部集数 + 已验证的那一集 |
| 存储 | `filesDir/playcache/<MD5(片名)>.json`，**不放 SharedPreferences**——一部剧几十 KB，60 部会把每次读配置都拖慢 |
| 过期 | `Prefs.today().equals(cache.day)`，跨天失效 |
| 手动重测 | 详情页「重新检测」按钮强制跳过缓存（源挂了用户能自救） |
| 不写缓存 | 一个可播源都没有时不写，避免把昨天还能用的结果盖掉 |
| 上限 | 60 个文件，超出按修改时间淘汰 |

详情页会显示「今日 20:15 检测，今日不再重复检测」，让用户知道看到的是缓存而不是刚查的。

### 6.5 每日更新

`Prefs.lastRankDate()` 存 `yyyy-MM-dd`；启动时 `today().equals(lastRankDate())` 为 false 才拉。一天内反复启动不重复请求。

---

## 7. 技术选型与理由（改动前先读）

| 决策 | 选择 | 理由 | 代价 |
|---|---|---|---|
| 最低版本 | **minSdk 19（Android 4.4）** | 4.4 起 TLS 1.2 与 GridView 尺寸方法原生可用，兼容绕路全部可以不要 | 4.0~4.3 不再支持 |
| TV 框架 | **不用 Leanback**，framework View + GridView | 当初为兼容 4.0 排除（Leanback 要 16）；现在门槛已过，但手写焦点已稳定，换框架无收益 | 焦点系统全手写（焦点放大、白边） |
| 播放器 | ExoPlayer **2.19.1** | 升到 4.4 后不再受「API 14 最后版本」限制，取最后一个 2.x（HLS 修复最全） | 依赖 androidx（须 `useAndroidX=true`）；与 2.9.x API 差异大，见 `PITFALLS.md` P-07 |
| 图片库 | 自己写 `ImageLoader` | Glide/Coil 都要求 API 14+ 但体积大、且需处理 Referer | 无 GIF、无复杂变换 |
| 依赖注入 | 无 | 保持 APK 体积与构建简单 | 全局静态初始化，见 `App.java` |
| 榜单源 | 豆瓣 rexxar 私有接口 | 第三方榜单全试过：猫眼/Trakt 403，云合 HTML，酷云连不通 | 接口变更需发版；可用服务端模式兜底 |
| 采集架构 | 配置化的 maccms 适配层 | 23 站接口参数完全同构（有实证） | 站点若改接口需改配置或适配层 |

---

## 8. 踩坑笔记

踩坑笔记（现象 → 原因 → 解法，P-01~P-14，覆盖构建工具链 / 4.4 兼容 / 数据接口 / UI 焦点 / 图标管线）单独放在 **`PITFALLS.md`**。

---

## 9. 构建与运行

### 9.1 构建 APK

```bash
cd vod-tv
# 首次：写 SDK 路径（这个文件不入库）
echo "sdk.dir=D:\\Android\\Sdk" > local.properties

./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
# 留档：dist/Kankan-1.4.0-debug.apk
# 分发：复制一份到对外分发目录（本机约定 E:\share\）
```

环境（本机已装好，可直接构建）：

| 项 | 路径 / 版本 |
|---|---|
| JDK 17 | `D:\tools\jdk17`（openjdk 17.0.2，华为镜像） |
| Android SDK | `D:\Android\Sdk`（platform 33 + build-tools 33.0.2 + platform-tools） |
| Gradle | 7.6.4，wrapper 自动拉（腾讯镜像），落在 `C:\Users\<user>\.gradle` |
| 依赖仓库 | 阿里云镜像（`settings.gradle` 已配），实测 20MB/s |

> AGP 7.4.2 会额外自动装 build-tools **30.0.3**（它自己的默认值），这是正常现象，不用管。

构建耗时参考（i3-4130T / 2C4T / 16G，2026-11-06 实测）：

| 场景 | 耗时 |
|---|---|
| 冷构建（含 Gradle 发行包 + 全部依赖下载） | 3 分 58 秒 |
| `clean assembleDebug`（缓存已热） | 53 秒 |
| 改 1 个 .java 后 `assembleDebug` | 37 秒 |
| `lintDebug` | 1 分 25 秒 |

> ⚠️ 增量构建可能产出被污染的 APK（体积翻倍、孤儿 zip 条目）——识别与解法见 `PITFALLS.md` P-01。

装机：

```bash
adb connect 192.168.x.x:5555
adb install -r Kankan-1.4.0-debug.apk
```

### 9.2 跑采集器

```bash
cd vod-collector
npm start                      # = node src/server.js 8765

node src/cli.js health         # 23 站存活检查
node src/cli.js search 空枪    # 多站搜索
node src/cli.js search 空枪 --resolve   # 连播放地址一起解析（慢，约 20s）
node src/cli.js play <站id> <片名>
```

浏览器开 `http://localhost:8765` 即海报墙。

### 9.3 客户端切到服务端模式

设置页填 `http://<电脑内网IP>:8765` → 保存 → 强制更新榜单。电视与电脑必须同局域网，**不能填 localhost**。

---

## 10. 已知限制

| 项 | 说明 |
|---|---|
| 未实机验证 | 本机无 KVM、CPU 也太弱（i3-4130T），跑不了模拟器；布局按 1080p / 6 列推算，装到盒子可能要微调间距 |
| 最低版本 | 只支持 **Android 4.4（API 19）** 及以上，4.0~4.3 已放弃 |
| 榜单接口 | 豆瓣私有接口，无公开承诺；变了要发版，或用服务端模式 |
| 长剧选集 | 已按 10 集一组折叠（≥50 集才启用，见 §11.2 已完成项）；但一次性解析全部集数标签的开销仍在 |
| 缓存可能过期 | 当天缓存的源如果中途挂了不会自动重测，得手动点「重新检测」 |
| 无「下一集」自动连播 | 播完停在结束提示 |
| 片源质量 | 资源站镜像严重重合：14 站 46 条线路只有 40 个不同路径，6 个站是同一份片源。多站的价值是冗余而不是更多片源 |
| 合规 | 片源均为未授权内容，仅作技术验证 |

---

## 11. 如果要继续开发，建议顺序

1. **播放结束自动连播下一集** —— 现在结束只提示，需要用户自己进控制栏点「下一集」
2. ~~**选集分组**~~（已完成）—— 详情页选集列表和播放页浮层在 ≥50 集时按 10 集一组加组头，
   组头可点开合（`▾ 第 1 - 10 集` / `▸ 第 1 - 10 集`），默认只展开当前组
   （浮层 = 正在播的组，详情页 = 上次看到的组）；当前集 / 上次看到的集文字前打 `✓`，
   浮层里保留品牌蓝实底。实现：`DetailActivity.renderEpisodes()` / `PlayerActivity.rebuildEpisodes()`
3. **播放失败自动换源** —— `onPlayerError` 时回退到同片的下一条线路（需要把 lines 传进播放页）
4. **真机兼容性实测** —— 接电视盒子/手机跑一遍，重点验控制栏焦点、选集浮层、触摸点击

---

## 12. 术语

| 术语 | 含义 |
|---|---|
| 采集站 / 资源站 | 提供 `vod_play_url` 的第三方站，多为苹果CMS（maccms V10） |
| 线路 / from | `vod_play_url` 里用 `$$$` 分隔的一组播放地址，通常对应不同 CDN |
| 集 / episode | 线路内用 `#` 分隔的一条播放地址 |
| 分享页 | 形如 `/share/xxxx` 的中间页，需再请求 HTML 才能拿到真实 m3u8 |
| 探针 / probe | 真取一次 m3u8，看响应里有没有 `#EXTM3U`，用来判断这条线路是否可用 |
| 拉普拉斯平滑 | `(成功+1)/(总次数+2)`，避免小样本把成功率顶到 100% |
| rexxar | 豆瓣移动端使用的 API 前缀，非公开文档 |
| Leanback | Android TV 官方 UI 库，本项目未使用（当初因 minSdk 16 被排除，现门槛已满足但未迁移） |

---

## 13. 给 AI 的额外提示

- 改 UI 前先看 `res/values/styles.xml` 和 `colors.xml`，颜色/间距是统一的，不要写死新值
- 所有网络请求都走 `Http.java`，它已经处理了超时、UA、错误吞掉；不要直接用 `HttpURLConnection`
- 新增存储一律走 `Prefs` 或独立 SharedPreferences 类，在 `App.onCreate` 里 `init`，因为所有类是静态单例
- 改完**必须**跑 `./gradlew lintDebug` 并确认 `NewApi` 为 0，这是 4.4 兼容的唯一自动化保障
- **排查真机闪退**：App 里已内置 `CrashLog`（`App.onCreate` 第一个安装）。崩溃后重新打开 App，首页会弹窗显示上一次的堆栈；同时写两份文件：
  - `files/last_crash.txt`（内部）
  - `/sdcard/Android/data/com.vodtv/files/last_crash.txt`（外部，盒子上用文件管理器能直接拷出来）
  有 adb 时更省事：`adb logcat -b crash -d` 或 `adb logcat -d | grep -E "AndroidRuntime|Kankan"`

- 资源站的接口随时可能变，遇到「全部源失败」先跑 `node src/cli.js health` 确认是站挂了还是代码挂了

---

## 14. 分支模型

- 两条长期分支：`main`（稳定基线，只放验证过的版本）/ `dev`（开发分支，所有改动先落这里）。
- `dev` 验证通过后 `git merge --no-ff dev` 进 `main`，保留「一次验收 = 一个合并节点」，方便整批回滚。
- 提交粒度：一个可交付的改动集 = 一次提交，构建与检查通过后才提交。

---

## 15. UI 规范（TV 优先，手机兼容）

用户 2026-11-06 反馈：「返回按钮一会儿在左、一会儿在右」「搜索是个文字按钮」。这一节是之后所有页面必须遵守的硬规范。

### 15.1 依据

- Android 官方 TV 导航指南（[training/tv/start/navigation](https://developer.android.google.cn/training/tv/start/navigation)）：
  **电视上不应该在屏幕上放虚拟「返回」按钮**，遥控器返回键就是电视的返回导航。
- Android 官方 TV 布局指南：导航控件放左右两侧；布局四周留 overscan 安全区，
  **左右 48dp、上下 27dp**。
- 本项目同时要在手机上跑（没有实体返回键），所以保留屏幕返回按钮 —— 但**只允许出现在左上角一处**，且全项目共用同一份布局。

### 15.2 顶栏结构（唯一模板）

```
[← 返回]   页面标题                 ……        [图标] [图标]
 └ 二级页固定第一项                └ 右对齐，页面级动作
```

| 页面 | 第一项 | 中间 | 右侧动作 |
|---|---|---|---|
| 首页 | `看看` | 分类 tab | 搜索 / 记录 / 更新 / 设置（4 个图标） |
| 详情 | `← 返回` | 片名 | — |
| 播放 | `← 返回`（浮层内） | 片名 + 当前源 | 控制按钮图标行 |
| 搜索 | `← 返回` | 输入框 | 搜索图标 |
| 记录 | `← 返回` | `观看记录` + 条数 | 清空 |
| 设置 | `← 返回` | `设置` | — |

- 返回按钮**只能**用 `layout/part_back.xml`（`<include android:id="@+id/btn_back">`），不许再手写第二份。
- 顶栏左右 padding 统一 **48dp**；内容区（海报墙）**44dp**；文字左对齐线 50dp。
- 顶栏垂直居中，返回按钮 8dp 内边距、图标 18dp、文字 14sp。

### 15.3 图标

**先找现成的，不许上来就自己画。** 顺序：

> 1.4.0 起图标统一用 **Material Rounded** 变体（圆头收笔与 8dp 圆角界面同一种语言），
> 详见 §18.5。下表仍有效，只是图形换成了 Rounded。

1. 优先 **Google Material Icons**（Apache-2.0），本项目顶栏/播放控制全部用它，
   由 `tools/fetch_material_icons.py` 抓 SVG → 光栅化成 5 档密度 PNG。
2. Material 没有合适的图标，再换同类开源库（Lucide / Tabler / Feather，均 MIT）。
3. 以上都没有，才允许自己画（`tools/make_icons.py` 那只用于 App 图标和 TV banner）。

已固定的映射（改图标只改脚本映射表）：

| 用途 | 图标 | Material 名 |
|---|---|---|
| 返回 | `ic_ui_back` | navigation/arrow_back |
| 搜索 | `ic_ui_search` | action/search |
| 观看记录 | `ic_ui_history` | action/history |
| 更新榜单 | `ic_ui_refresh` | navigation/refresh |
| 设置 | `ic_ui_settings` | action/settings |
| 删除 / 清空 | `ic_ui_delete` | action/delete |
| 后退 10 秒 | `ic_ui_replay10` | av/replay_10 |
| 前进 10 秒 | `ic_ui_forward10` | av/forward_10 |
| 播放 / 暂停 | `ic_ui_play` / `ic_ui_pause` | av/play_arrow / av/pause |
| 上一集 / 下一集 | `ic_ui_prev` / `ic_ui_next` | av/skip_previous / skip_next |
| 选集 | `ic_ui_episodes` | av/playlist_play |

规矩：

- **纯文字动作按钮不许再出现**（「搜索」「更新」「设置」原来都是文字，已全部换图标）。
  ±10 秒这类动作有现成图标（replay_10 / forward_10），不许写「快进 10 秒」四个字。
- 图标按钮一律 44dp（顶栏）/ 48dp（播放页）触达区，`@style/IconButton` / `@style/PlayerIconBtn`。
- 图标本身是白色 PNG，非聚焦态压暗（alpha 0.85~0.9），聚焦靠 `bg_tab` 的强调色描边，不换图标颜色。
- 每个图标必须有 `android:contentDescription`（无障碍 + 语音遥控）。

### 15.4 字号与颜色

| 用途 | 字号 | 颜色 |
|---|---|---|
| App 名 | 26sp bold | `text_primary` |
| 页面标题 | 20~22sp bold | `text_primary` |
| 分类 tab | 15sp | 选中 `text_primary` / 未选 `text_secondary` |
| 卡片标题 | 14~15sp | `text_primary` |
| 卡片副标题 | 11~12sp | `text_secondary` |
| 说明 / 提示 | 11~12sp | `text_dim` |

强调色只有一个：**品牌蓝 `#2B5CFF`**（`brand`，焦点/选中态用亮一档的 `focus #5B82FF`），与 §18.2 一致。不要临时加新色值。（本节旧版写的粉红 `accent` 已废弃。）

### 15.5 焦点

- 所有可聚焦控件必须显式串 `nextFocusLeft/Right/Up/Down`（FocusFinder 在 TV 上不可靠）。
- 焦点样式统一 `bg_tab`（2dp accent 描边 + 半透明底），卡片可叠加 1.07 放大。
- 列表条目自己 `focusable="true"` 时，OK 键会被条目吃掉 —— 必须同时挂 `setOnKeyListener` + `setOnClickListener`（见 `PITFALLS.md` P-11）。
- 布局里**不要**给可点击控件写 `focusableInTouchMode="true"`（见 `PITFALLS.md` P-12）。

---

## 16. 搜索链路（1.3.1 定型）

### 16.1 一句话

> 搜索 = 一次搜索同时问两边，出一屏**剧集卡片**；**点某一张才进详情页**。
> 详情页才是解析线路、探 m3u8、选集播放的地方。

之前（1.3.0 及更早）是两条互不相干的路：本地榜单命中就出卡片，没命中就给一个「联网搜索片源」文字按钮，
点了之后——**搜到直接跳详情页，片名就是关键词**。用户没法确认「这是不是我要的那部剧」，也没法在多条候选里挑。
1.3.1 把这条错误路径整个删掉了。

### 16.2 两条腿

| 来源 | 时机 | 做什么 | 速度 |
|---|---|---|---|
| 本地榜单缓存 | 同步，搜索瞬间 | `RankRepository.loadCache` 按片名 / 导演 / 演员过滤 | 立即 |
| 各片源站 | 异步并发（4 线程，35s 兜底超时） | `PlayResolver.findCandidates` 只读 JSON 元信息 | 2~10 秒，回来一批补一批 |

两边结果按片名归一后合并成同一屏；同一部片只留一张卡，卡上标「N 个源」。

### 16.3 数据层的两件事，别混

| 方法 | 用途 | 特征 |
|---|---|---|
| `PlayResolver.search(keyword, cb)` | 已知片名，要拿到**能播的线路** | 每站取一条最佳，逐条探 m3u8，慢（十几秒） |
| `PlayResolver.findCandidates(keyword, cb)` | 只知道关键词，要先列出**搜到了哪几部片** | 每站最多收 10 条候选，**完全不探流**，快 |

搜索页只调后者；详情页只调前者。列表页去探流会把 14 个站乘上几十次请求，手机上等到超时。

### 16.4 合并规则（`PlayResolver.addCandidate`）

- 归一键：`clean(片名)` 去掉 `（）() [] 【】` 里的括注 + 去空白 + 转小写。
  `The Last of Us` / `the last of us` / `The Last of Us（美剧）` 合并成一条。
- 同一个站重复给出同名条目**只算一个源**（`sourceNames` 去重），否则「N 个源」会虚高。
- 合并时只补空字段，不覆盖已有值（先到的站留下的封面/集数优先）。
- 排序：源多的在前 → 都是单源时标题短的在前（「狂飙」不该排在「狂飙 全39集」后面）。
- 过滤：`relevant()` 要求名字与关键词互相包含，并排除「解说 / 预告 / 花絮 / 片段 / 速看 / 混剪 / 彩蛋」；
  用户关键词本身含这些词时不排除。

### 16.5 跨来源去重（`SearchActivity.indexOfTitle`）

榜单叫「狂飙」，片源站叫「狂飙 2023」「狂飙 第二季」，要认成同一部。判定顺序：

1. `normTitle` 归一后**完全相等** → 同一部；
2. 否则一方以另一方开头，且**多出来的部分必须是版本尾巴**（年份 / 第 N 季第 N 部 / 全 N 集 / 国语粤语高清蓝光 4K）→ 同一部。

「狂飙」与「狂飙之某某」**不算**同一部——多出来的是副标题，不是版本尾巴。
归一后的对比函数是纯字符串逻辑，`PlayResolverCandidateTest` 覆盖。

### 16.6 搜索历史

- `data/SearchHistory`：最多 12 条，去重（不分大小写），最新在前，存 SharedPreferences。
- 搜索页空白态显示「最近搜索」标签组，点标签直接重搜；菜单键 / 长按删单条，右上角一键清空。
- 一旦输入框有内容，标签组自动收起；清空后又露出来。

> ⚠️ **1.3.0 的坑**：`SearchHistory.init()` 从来没被调用过，`sp` 一直是 null，
> `add()` 第一行就 `return`、`list()` 永远返回空 → 标签组整块永久 `GONE`。
> 表现就是「搜索记录没有」，而且不报错、不崩溃，只能靠肉眼发现。
> **新增任何依赖 `init(Context)` 的数据类，必须同时改 `App.onCreate()`，并在此处登记。**

---

## 17. 续播链路：点记录就是播，不是「进详情页再按一下」

### 17.1 1.3.1 及更早的问题

观看记录页 `open()` 做的事是：`startActivity(DetailActivity)` + `putExtra("autoResume", true)`，
而详情页对 `autoResume` 的处理只是**把焦点放到「继续看」按钮上**——用户还得再按一次 OK。

再叠加一层：详情页要重新检索 14 个片源站（当天没缓存时 10~30 秒），期间只有一行「正在检索…」，
按钮根本还不存在。用户点一下记录，看到的是一个要等的详情页，**感受就是「点了没法继续播放」**。

### 17.2 现在的链路（1.3.2 起）

点记录卡片 → 立刻出现遮罩「正在准备续播…」→ 后台按三级降级拿流：

| 级 | 条件 | 动作 | 耗时 |
|---|---|---|---|
| 1 | `PlayCache` 有**当天**缓存 | 取缓存线路 → 找上次那个源 → 找同名集 → `resolveEp` | 1~2 秒 |
| 2 | 今天没检测过 | `PlayResolver.search(title)` 重新检索 → 同样规则挑源挑集 → `resolveEp` | 10~30 秒 |
| 3 | 上面都拿不到流 | 进详情页（`autoResume`），**详情页检索完会自己续播**，不用再按 OK | — |

要点：

- **第 1 级命中就不要跑第 2 级**。「今天检测过但对不上」重跑一遍也是同样结果，白等 30 秒。
- 若 `ep.label` 与线路探测时命中的 `line.label` 相同，直接用 `line.media`，不再多打一次探针。
- 第 2 级必须更新遮罩文案（「重新检索 N 个站…」），否则用户以为卡死。
- 全程 `opening` 标志拦住重复点击；`onResume` 复位并收起遮罩。
- 遮罩层 `busy_panel` 显式 `clickable="true"`，否则触摸会穿透到下面的网格。

**1.4.1~1.4.2 的演进**：

- 三级降级抽成 `data/ResumeHelper.java`，**观看记录页与一切「继续观看」入口共用**，
  回调 `onBusy / onPlay / onGiveUp`，带 busy 遮罩 + Toast——不要再往 `HistoryActivity` 里手写一份。
- 1.4.1 曾把续播横条放在首页顶部，但点击没反应（详情页对不上集时静默停住）；
  1.4.2 **首页历史横条整体删除**（用户明确要求），续播入口只剩观看记录页。

### 17.3 播放页参数打包只有一份

`DetailActivity.launchPlayer(from, line, ep, media, info)` 是**唯一**跳播放页的入口，
详情页 `play()` 和记录页续播都调它。参数包括当前集 url、整条线路的 `epLabels`/`epUrls`/`epIndex`、
以及整份 `MovieInfo`（播放页退出时靠它写历史，首页/记录页才能显示海报）。

以前详情页里有一份私有 `play()`，记录页要直接播就得再抄一遍——抄漏一个 extra 就是
「播放页没有选集」「历史里没海报」这类难查的问题。

### 17.4 仍然成立的约束

- 记录里的 `sourceId` 只是**优先项**，那个源挂了要能退到任意可播线路，不能因此判失败。
- 多集时集名对不上就**不猜**，返回 null 交给详情页让人挑（单集电影例外）。
- 详情页 `autoResumePending` 只消费一次：用户手动「重新检测」或被降级进来后，不该再次自动跳播放页。

### 17.5 看完不删记录（1.4.7）

**症状**：播完之后观看记录消失。

**根因**：`HistoryStore.save()` 里有这么一段——「位置太小 **或** 已经贴到结尾」就把记录
`remove` 掉。而播放到结尾时 `pos ≈ dur`，正好命中后半条，于是整条记录被删。

**现在的规则**（`HistoryStore` 顶部注释同步）：

| 情况 | 行为 |
|---|---|
| `pos < 5s` | 不新建记录；**已有记录原样保留**（以前会连旧记录一起删） |
| `pos` 在片尾 60s 内 | 保留记录，`pos` 写成 `dur`，读出来 `Entry.isFinished()` 为 true |
| 其他 | 正常更新位置 |

配套：

- 判定抽成 `HistoryStore.isFinished(pos, dur)` 静态方法 —— 见 `HistoryStoreTest`（7 条用例），
  这条逻辑写错就是「记录消失」或「全部显示已看完」，必须有回归测试。
- **不新增布尔字段**，用 `pos/dur` 推导：旧数据不用迁移，`dur == 0`（直播 / 取不到时长）
  时任何位置都不算看完。
- `resumePos()` 对已看完的记录返回 0 → 点进去从头播。
- 记录页副标题显示「已看完」（`history_finished`），颜色降到 `text_tertiary`；进度条满格。
- 详情页续播按钮改文案「重看 第03集」，不再显示「继续看 45:00 / 45:00」。
- 播放页 `STATE_ENDED` 里**立刻** `saveProgress()`，不等 `onPause`——
  看完直接按电源/回桌面时 `onPause` 可能来不及。
- `recent()`（「继续观看」类入口）过滤掉已看完的；`all()`（观看记录页）保留。

记录只有两种消失方式：**用户手动删**、**超过 80 条按时间淘汰**。

---

## 18. 视觉系统（1.4.0 定型）

### 18.1 三条原则

1. **品牌连贯**：桌面图标是 `#2B5CFF` 蓝底白三角，界面强调色必须同值。1.3.x 界面用粉红 `#FF4D6A`，和图标完全对不上 —— 用户点进来像换了个 App。
2. **一种焦点语言**：1.3.x 有两套 —— 海报卡 3dp **白边**、按钮 2dp **粉边**。现在全应用只有一种：**2dp 焦点蓝 `#5B82FF`**。
3. **海报即卡片**：1.3.x 的卡片是「5dp 深灰框 + 海报 + 3dp 白边」三层套着，海报亮度被吃掉一圈。现在去掉框，海报本体就是卡片。

### 18.2 颜色 token（`res/values/colors.xml`）

| Token | 值 | 用途 |
|---|---|---|
| `bg` | `#08090D` | 页面底（比旧版更深，让海报跳出来） |
| `surface` | `#12151C` | 卡片 / 列表行 / 面板 |
| `surface_2` | `#1B1F28` | 聚焦态、浮层、二级面 |
| `stroke` | `#1FFFFFFF` | 1dp 分隔线 |
| `stroke_strong` | `#33FFFFFF` | 面板外框 |
| `brand` | `#2B5CFF` | **与 ic_launcher_bg 同值**，唯一强调色 |
| `brand_dim` | `#1F47D6` | 按下态 |
| `focus` | `#5B82FF` | 焦点描边（比 brand 亮一档，深底上更清楚） |
| `text_primary` | `#F5F7FA` | 18.4:1 |
| `text_secondary` | `#9BA4B4` | 7.6:1 |
| `text_tertiary` | `#76808F` | 5.0:1（旧值 `#5F6878` 只有 3.4:1，11sp 在电视上看不清） |
| `gold` / `green` / `red` / `amber` | — | 只给语义：评分 / 可播 / 失败 / 警告 |
| `scrim` | `#B3000000` | 压在图片上的角标底 |

三档文本全部过 WCAG AA（4.5:1）。

### 18.3 字号与尺寸（`dimens.xml` + `values-sw600dp/dimens.xml`）

字号只有五档，布局里**不许再出现 `15sp` 这种字面量**：

| Token | 手机 | 电视 | 用途 |
|---|---|---|---|
| `text_hero` | 26sp | 30sp | App 名、详情页片名 |
| `text_title` | 20sp | 22sp | 页面标题、区块标题 |
| `text_body` | 15sp | 16sp | 卡片标题、列表主行 |
| `text_caption` | 13sp | 14sp | 副标题、次要信息 |
| `text_micro` | 11sp | 12sp | 角标、时间码、提示 |

| Token | 手机 | 电视 | 说明 |
|---|---|---|---|
| `topbar_pad_h` | 20dp | 48dp | 顶栏左右（电视是 overscan 安全区） |
| `content_pad_h` | 16dp | 44dp | 内容区左右 |
| `icon_btn_size` | 40dp | 48dp | 顶栏图标命中区 |
| `grid_cols` | 3 | 6 | 海报列数（手机 6 列时每张只 55dp，标题全被省略号吃掉） |
| `card_gap_h` / `_v` | 10/16dp | 14/20dp | 网格间距 |
| `radius_sm/md/lg/xl` | 4/8/12/16dp | 同 | 旧版是 0/6/8/14 四个没规律的值 |
| `space_xs…xxl` | 4/8/12/16/24/32dp | 同 | 4 的倍数 |

### 18.4 组件规范

| 组件 | Drawable | 规则 |
|---|---|---|
| 卡片 / 列表行 | `bg_card` | 默认 surface + 1dp 描边；焦点 = surface_2 + 2dp 焦点蓝 |
| 海报卡 | `bg_poster` | 底永远透明，只画描边；根布局留 **2dp padding** 让描边不压画面 |
| 分类 tab | `bg_tab` | 未选中**完全透明**；选中 = brand 实底白字；选中+焦点 = 白描边（蓝底蓝边会糊） |
| 顶栏图标按钮 | `bg_icon` | 默认 6% 白；焦点 = 2dp 焦点蓝 |
| 主行动按钮 | `bg_primary` | brand 实底白字，焦点白描边（一屏只允许一个） |
| 输入框 | `bg_input` | surface + 1dp 描边，焦点换 2dp 蓝 |
| 胶囊 | `bg_chip` | 标签/集数格；`selected` = brand 实底，用来标「当前这一集」 |
| 图片角标 | `bg_pill` | 半透明黑 + 99dp 圆角 |
| 播放控制栏 | `bg_player_controls` | 上透明→下近黑的渐变，不用整块黑条压画面 |
| 播放键 | `bg_play` | 品牌蓝圆形，整条控制栏里唯一有实底的按钮 |

**焦点统一参数**：`1.06` 倍放大 + `@dimen/focus_stroke`(2dp) 描边。以前是 1.07，和 2dp/3dp 混用，看不出差别但代码不一致。

### 18.5 图标

- 全部 **Material Rounded**（`materialiconsround`），圆头收笔和 8dp 圆角界面同一种语言。
  `tools/fetch_material_icons.py` 用环境变量 `MATERIAL_VARIANT` 切换变体，缓存按变体分目录。
- 品牌标记 `ic_brand_mark.png`（蓝底白三角，与 ic_launcher 同款）由 tools 脚本生成，5 档密度。
- 角标：排名 `TOP 1/2/3` 用金/银/铜字色 + 半透明黑胶囊；评分金色数字 + 同款胶囊。
  **不再用整块红底包住海报左上角**（旧版 30dp 红色直角方块，压住海报最关键的视觉区）。

### 18.6 圆角在 API 19 上怎么实现

`setClipToOutline` 是 API 21 的，minSdk 19 直接调用会崩。方案：`widget/RoundImageView`
用 `Canvas.clipPath` 裁剪，只影响绘制、不占额外内存。

没有选「在 ImageLoader 里把 Bitmap 裁成圆角」，是因为那会把 RGB_565 转成 ARGB_8888，
内存翻倍；6 列海报墙一次可见二十多张，很容易顶到 LruCache 上限后反复重解码。

### 18.7 交付前机械自检

```bash
cd vod-tv
python tools/check_ids.py     # java 强转类型 vs 布局标签，防止改结构后运行时 ClassCastException
```

**为什么需要它**：把空态的 `TextView` 换成 `LinearLayout` 后，`(TextView) findViewById(...)`
依然能编译通过，强转在编译期不校验，装上机器才崩。1.4.0 改动中就靠它抓到了
`HomeActivity` 的这一处（其余 88 个 id 全部兼容）。

### 18.8 1.3.2 → 1.4.0 改了什么

| 项 | 1.3.2 | 1.4.0 |
|---|---|---|
| 强调色 | 粉红 `#FF4D6A` | 品牌蓝 `#2B5CFF`（图标同值） |
| 焦点 | 白边 3dp / 粉边 2dp 两套 | 焦点蓝 2dp 一套 |
| 海报卡 | 深灰框 + 白边 + 双角标 | 海报即卡 + 蓝描边 + 单胶囊 |
| 图标 | Material Filled | Material **Rounded** |
| 字号 | 11/12/14/15/17/20/26 散装 | 五档 token |
| 圆角 | 0/6/8/14 无规律 | 4/8/12/16 |
| 手机列数 | 6（每张 55dp） | 3 |
| 首页动作 | 文字按钮 | 图标按钮 |
| 顶栏 | 各页 padding 不统一 | 统一 48/44dp + 品牌标记 |
| 播放控制栏 | 整块 `#D9000000` 黑条 | 渐变 + 居中图标组 + 蓝色圆形播放键 |
| 时间码 | 默认字体（跳秒会左右晃） | 等宽字体 |
| 全部记录入口 | 横条末尾一张卡 | 标题行右侧「全部 ›」 |
| 版本号 | 布局里硬编码 1.0.0 | 从 PackageInfo 读 |

---

## 19. 播放页长按快进/快退（1.4.4）

### 19.1 交互

| 入口 | 单击 | 按住 |
|---|---|---|
| 遥控器 ← → | 快退/快进 10 秒 | 连续跳，松手即停 |
| 遥控器 MEDIA_REWIND / FF | 同上 | 同上 |
| 手机画面左半屏 | 切换控制栏 | 连续快退，松手即停 |
| 手机画面右半屏 | 切换控制栏 | 连续快进，松手即停 |
| 控制栏 ⏪ ⏩ 按钮 | 10 秒 | —（按钮不做长按） |

节奏：按下立刻跳一次（保持原单击手感），480ms 后开始连跳，之后每 350ms 一跳，
约 28 倍速。连续跳期间屏幕中央浮层常亮，显示「快进 37 秒 / 12:34 / 45:00」。

### 19.2 三条不能改的实现约束

1. **目标位置自己累加，绝不回读 `getCurrentPosition()`。**
   `seekTo()` 之后短时间内 `getCurrentPosition()` 仍可能是旧值，用「当前 + 10s」
   去累加，第二跳起就会重复落到同一位置，表现为越按越慢甚至倒退。
   正确做法：`seekTargetMs` 自增，`player.seekTo(seekTargetMs)`。

2. **只在 `repeatCount == 0` 的 DOWN 起步，不用系统 repeat 驱动连跳。**
   系统重复键的节奏是「先 500ms、之后约 50ms」，50ms 跳 10 秒等于 200 倍速，
   完全没法用。系统 repeat 只当心跳（刷新 `seekKeyBeat`），不影响节奏。

3. **必须有 UP 丢失的兜底。**
   某些遥控器 / 输入设备不发 UP，按住时会一直快进。按住期间系统会持续发 repeat 的
   DOWN，因此用「最后心跳超过 3 秒就 `stopSeek()`」兜底。同理 `onPause()`
   里也要停，否则按着时来电/回桌面，回来还在冲。

### 19.3 与触摸层的分工

`touch_layer` 是全屏透明层，轻点切控制栏、长按左右半屏快进快退，两种手势在同一层上，
所以整层自己吃下全部事件（`onTouch` 返回 true），只在**没触发长按**的 `ACTION_UP`
里调 `performClick()` 走点击逻辑（不是直接调方法，无障碍服务要的是这次点击本身）。
长按松手不能再切一次控制栏，这是最容易写错的地方。

连跳期间每次 seek 都会短暂进入 `STATE_BUFFERING`，`onPlaybackStateChanged` 里
`if (seekDir != 0) return;` 跳过「缓冲中…」，否则它和浮层叠字。

### 19.4 相关文件

- `player/PlayerActivity.java`：`startSeek / stepSeek / stopSeek / showSeekHud`、`onKeyUp`
- `res/layout/activity_player.xml`：`seek_hud / seek_hud_action / seek_hud_time`
- `res/values/strings.xml`：`seek_forward_n / seek_backward_n / seek_at_start / seek_at_end`

### 19.5 按钮长什么样（1.4.6 起：六键同尺寸）

演进：1.4.4 是 48dp 纯图标 →「看不出是快进快退」，1.4.5 临时加了中文标签 →
标签让按钮 58×56dp 与其余 48dp 不齐，1.4.6 又回到纯图标，但**整行统一尺寸并把图标放大**。

当前形态（唯一标准，别再往标签方向改）：

| 项 | 值 |
|---|---|
| 六个键 | 全部 56×56dp，圆角 `radius_md`，图形 30dp（播放键 26dp） |
| 间距 | 8dp，由容器 `p_buttons` 的 `showDividers="middle"` + `@drawable/divider_gap` 给 |
| 播放键 | 尺寸形状与其余完全一致，只靠 **品牌蓝实底** 区分主次 |
| 文字 | **无**。快退/快进只靠 `replay_10` / `forward_10` 里的数字 |
| 焦点 | 全部走 `bg_icon` / `bg_play` 的 `state_focused` 2dp 描边 |

两个容易踩的点：

1. **间距不要用子项 `layout_marginRight`。** 子项 margin 会计入 `wrap_content` 的总宽，
   而末尾那个 margin 后面没有内容，整行居中时会往左偏半个间距（4dp）。
   用 LinearLayout 的 `showDividers="middle"` 配一个只带 `size` 的透明 shape 当 divider，
   宽度干净、首尾无多余留白。`showDividers` 是 API 11，minSdk 19 可用。
2. **图标必须够大。** `replay_10` / `forward_10` 里那个「10」只占整张图的约 1/5 高，
   24dp 时只有 4~5dp，等于看不见；30dp 时约 6dp，勉强可读。再小就必须考虑
   自绘更粗的字形（届时另开图标，不要靠加文字解决，用户已明确不要文字）。

### 19.6 图标导出偏移

管线级 bug（SVG→PDF→光栅化后字形偏左下）与修法 `center_glyph()` 见 **`PITFALLS.md` P-14**；
交付前跑 `tools/check_icons.py` 自检：偏差 ≤2% 画幅、字形占 40%~92%。

---

## 20. 首页三 tab（1.4.8）

### 20.1 为什么改

用户反馈豆瓣源影视太少。调研影视仓（takagen99/Box）后确认其首页三选一（豆瓣热播 / 站点推荐 / 历史），
其「豆瓣热播」走的就是 B 接口；再对比豆瓣 A/B 两个接口（见 §4.1）后拍板：
**首页留 3 个 tab**——豆瓣热榜（全部，17 榜全量合并去重）、新品速递、找一找。

### 20.2 三个 tab

| tab | 数据 | 实现 |
|---|---|---|
| 豆瓣热榜 | 17 榜**全量合并去重**（保留首现榜位），不再按 group/boardId 分组 | `rebuildHotAll()`；无独立接口，复用 rank.json |
| 新品速递 | 当年可播片按热度，3 页 60 条 | `RankRepository.fetchNewReleases()`，Board id=`new_releases` |
| 找一找 | B 接口按条件查片，支持自动翻页 | `RankRepository.find(tags, yearKey, sort, start)` |

### 20.3 找一找的交互与状态

- 三组筛选：**类型**（全部/电影/电视剧/综艺/动画/纪录片）、**年份**（all/cur/prev/older，older=1900~前年）、
  **排序**（U 最热 / S 高分 / R 最新）。`findTags/findYear/findSort` 三个状态变量，组合 key=`findKey()`。
- **每个条件独占一行**（2026-11-07 用户明确要求）：`find_bar` 是纵向 LinearLayout，
  `addChipGroup` 每组包一层横向行，行距 4dp；行间竖线分隔已删。
- chips 焦点行为：**焦点扫过 300ms 防抖后才发请求**（`scheduleFindApply`），结果按 findKey 组合缓存；
  选中态**原地更新**（遍历改颜色），**绝不重建视图**——重建会销毁焦点、遥控器断链。
- 焦点链：tab ↓ 第一个 chip；chip ↓ 在**末行（排序）**进 grid，非末行走默认焦点搜索落下一行；
  grid 顶 ↑ 回当前 tab。
- 自动翻页：滚到最后 4 行触发 `loadMoreFind`，按 title 去重；`FindResult` 记 `nextStart/hasMore`。
- 切 tab 回顶：`setSelection(0)` + post `smoothScrollToPositionFromTop(0,0)`
  （`setSelectionFromTop` 是 API 21，minSdk 19 lint 会拦；handleDataChanged 会恢复旧位置，所以必须双保险）。

### 20.4 1.4.8 遗留

- **待真机验收**：热榜合并去重量级、新品速递 60 条、找一找三组筛选 + 自动翻页、chips 焦点链。
- **片太少的真正瓶颈在 CMS 源**（豆瓣侧 1.4.8 只扩了入口）：CMS 采集源补充调研停在候选源验证
  （ffzy/bdzy 等证书或代理问题待复测），见调研存档 `tools/research/`。
- dev 分支 `96d2368`（三 tab）+ `4e5ad6c`（找一找分行），**未合 main**，等用户验收。
