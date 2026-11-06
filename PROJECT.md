# 看看（Kankan）· 项目总说明

> 这份文档写给「接手这个项目的 AI」或未来的自己。目标是：只读这一个文件，就能知道项目是什么、代码在哪、为什么这么写、哪些坑已经踩过、下一步该做什么。
> 生成时间：2026-11-06 ｜ 版本：1.0.0 ｜ 全部内容为本地可运行代码，无外部私有依赖。

---

## 铁律 · APK 产出后必须分发（每次构建都做，不问）

> 用户 2026-11-06 明确要求，属**长期规则**，后续每次构建 APK 都要自动执行，不要逐次确认。

每生成一个新的 APK（`assembleDebug` / `assembleRelease` 成功之后），**必须**立即：

1. 把产物复制到 `vod-tv/dist/Kankan-<versionName>-debug.apk`（项目内留档）
2. 再把同一份 APK 复制到 **`E:\share\`**（对外分发目录，文件名保持一致）
3. 复制后校验两份文件 MD5 一致，并在回复中报出：文件名、大小、MD5、E 盘落点

一条命令搞定（Git Bash）：

```bash
cd /d/code/kankan/vod-tv
./gradlew assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk dist/Kankan-1.4.0-debug.apk
cp dist/Kankan-1.4.0-debug.apk /e/share/
md5sum dist/Kankan-1.4.0-debug.apk /e/share/Kankan-1.4.0-debug.apk
```

规则细则：

- **不要覆盖不同版本的同名文件而丢掉历史**：`E:\share\` 里若已存在**同名但内容不同**的 APK，先把旧的改名留档（追加构建日期，如 `Kankan-1.2.0-debug-20261106-2130.apk`）再放新的。
- 版本号没变但代码变了，属于「同名不同内容」，按上一条处理。
- `E:\share\` 不存在时先 `mkdir -p /e/share`，不要报错退出。
- 构建失败 / APK 没生成时，**不要**去复制上一次的旧包充数，如实说明失败。
- 复制完不要自作主张安装、上传、发链接，等用户指示。

---

## 铁律 · 先提交 dev，用户验收后才合 main

> 用户 2026-11-06 明确要求，属**长期规则**。仓库为纯本地 git（无远程）。

- **所有改动只提交到 `dev`**；`main` 是稳定基线，只放用户真机验收过的版本。
- 不许直接往 `main` 提交，不许 `push` / `reset --hard` / `push -f`。
- 交付话术固定：「已提交到 dev（commit `xxxxxxx`），等你验收」。用户说合并后才执行：
  `git checkout main && git merge --no-ff dev`，合完立刻 `git checkout dev` 切回。
- 用户说「有问题」就不合并，在 dev 上追加修复提交（不 amend 已交付的提交）。

## 铁律 · 按改动范围选测试，不跑无关测试

> 用户 2026-11-06 要求：「根据 git 的提交内容来测试，没涉及的改动就不跑对应测试」。

判定依据只看 `git status --short` / `git diff --name-only dev`，映射表见 **§14.2**。回复中必须写明「改了哪类文件 → 因此跑了哪些检查 → 结果」。

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
├── dist/Kankan-1.4.0-debug.apk  已构建产物（同时复制一份到 E:\share\）
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
        │   │   ├── RankRepository.java      ⭐ 榜单：豆瓣直连 / 服务端两套 + 磁盘缓存
        │   │   ├── RankItem.java            榜单条目模型 + 双结构归一化
        │   │   ├── PlayResolver.java        ⭐ 搜片 → 拆线路/分集 → 解析 m3u8 → 探测 → 排序
        │   │   ├── PlayCache.java           ⭐ 源检测结果按自然日缓存（每部片一个文件）
        │   │   ├── MovieInfo.java           影片信息模型（Intent ↔ JSON 双向转换）
        │   │   ├── HistoryStore.java        ⭐ 播放进度 + 影片信息快照（首页继续观看 / 记录页用）
        │   │   ├── SearchHistory.java       搜索关键词历史（最多 12 条，去重、最新的在前）
        │   │   ├── ImageLoader.java         海报加载（Referer + 内存/磁盘缓存）
        │   │   └── Http.java                极简 HTTP，只用 HttpURLConnection
        │   ├── ui/
        │   │   ├── HomeActivity.java        海报墙（手机 3 列 / 电视 6 列）、分组 tab、继续观看
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

### 4.1 豆瓣榜单（客户端直连模式）

```
GET https://m.douban.com/rexxar/api/v2/subject_collection/{cid}/items?start=0&count=60
Headers:
  User-Agent: Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 ... Safari/604.1
  Referer:    https://m.douban.com/
```

- `{cid}` 是榜单 id，共 17 个，见 `vod-collector/src/douban.js` 的 `BOARDS`
- **不能用**老接口 `/j/search_subjects`（403）
- 连续 35 次请求未封禁，但仍建议 30 分钟缓存

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
| 播放器 | ExoPlayer **2.19.1** | 升到 4.4 后不再受「API 14 最后版本」限制，取最后一个 2.x（HLS 修复最全） | 依赖 androidx（须 `useAndroidX=true`）；与 2.9.x API 差异大，见 §8.5 |
| 图片库 | 自己写 `ImageLoader` | Glide/Coil 都要求 API 14+ 但体积大、且需处理 Referer | 无 GIF、无复杂变换 |
| 依赖注入 | 无 | 保持 APK 体积与构建简单 | 全局静态初始化，见 `App.java` |
| 榜单源 | 豆瓣 rexxar 私有接口 | 第三方榜单全试过：猫眼/Trakt 403，云合 HTML，酷云连不通 | 接口变更需发版；可用服务端模式兜底 |
| 采集架构 | 配置化的 maccms 适配层 | 23 站接口参数完全同构（有实证） | 站点若改接口需改配置或适配层 |

---

## 8. 踩坑清单（现象 → 原因 → 解法）

### 8.1 Android 4.0~4.3 连不上 HTTPS

> 状态：**已不影响**（minSdk 提到 19 后不再有 4.0~4.3 设备）。`App.enableTls12()` 保留，用于个别 4.4 ROM 没默认开 TLS 1.2 的情况。

- 现象：所有网络请求 `SSLException`，豆瓣、CDN 全挂
- 原因：4.0~4.3 的 `SSLSocket` 默认只开 TLSv1，服务端只接受 TLS 1.2
- 解法：`App.enableTls12()` 把 `TLSv1.2` 塞进 `enabledProtocols`；同时装了 permissive TrustManager（老机器证书链缺失）
- 限制：**能否成功取决于 ROM 是否编入 TLS 1.2 的 OpenSSL**，4.4 起才是标配。所以「能装 ≠ 能用」

### 8.2 `GridView.getColumnWidth()` / `getHorizontalSpacing()` 是 API 16

> 状态：**已不影响**（minSdk 19）。代码里的手算列宽保留，行为正确且与 `stretchMode` 无关，不必改回。

- 现象：4.0/4.1 上打开首页直接 `NoSuchMethodError` 崩溃，编译期完全看不出来
- 原因：这两个方法 16 才有
- 解法：按屏幕宽度自己算，间距用布局里写死的 14dp
- **教训：必须跑 `./gradlew lintDebug` 看 `NewApi`，不能只在 5.0+ 设备上试**

### 8.3 `vod_play_url` 的 `$$$` 线路

- 现象：剧集列表出现两个「第01集」；点播报错或播成预告
- 原因：一条记录含两条线路（分享页 + 直链），只按 `#` 拆
- 解法：先按 `$$$` 选线路再拆集，见 §5.1

### 8.4 豆瓣电影榜整排破图

- 现象：电影分组的卡片全空，剧集正常
- 原因：电影用的是 `cover/info/directors` 结构，没有 `pic`
- 解法：`normalize()` 里判断 `!it.pic && it.cover` 走另一套字段

### 8.5 ExoPlayer 2.9.6 → 2.19.1 的迁移点（已升级，改代码前先看）

- **`ExoPlayerFactory.newSimpleInstance()` 已删除** → 改用 `new SimpleExoPlayer.Builder(ctx).setTrackSelector(...).setLoadControl(...).build()`
- **`DefaultBandwidthMeter` 没有公开构造函数了** → 不需要自己传，`DefaultTrackSelector` 内部会建；要自定义就用它的 `Builder`
- **`AdaptiveTrackSelection.Factory(meter)` 构造签名变了**（多一个 `Clock` 参数）→ 干脆不传，用 `new DefaultTrackSelector(this)` 走默认
- **`Player.EventListener` 已删除** → 改为 `implements Player.Listener`，回调名也变了：`onPlayerStateChanged(boolean,int)` → `onPlaybackStateChanged(int)`，`onPlayerError(ExoPlaybackException)` → `onPlayerError(PlaybackException)`
- **`player.prepare(source)` 拆成两步** → `setMediaSource(source)` + `prepare()`；`HlsMediaSource.Factory` 现在吃 `MediaItem` 而不是 `Uri`
- **`DefaultDataSourceFactory` 被 `DefaultDataSource.Factory` 取代**，且**没有** `setUserAgent` → 想带 UA 要嵌套一层：`new DefaultDataSource.Factory(ctx, new DefaultHttpDataSource.Factory().setUserAgent(ua))`
- **依赖 androidx** → `gradle.properties` 必须 `android.useAndroidX=true`，并且要删掉原来的 `support-annotations`，否则 support/androidx 混用直接冲突
- 教训（沿用）：2.x 的监听器都是 `default` 方法，**别加多余空实现**，只覆盖需要的

### 8.6 Gradle / SDK 下载慢或报错

- `/root/.gradle/init.gradle` 若有语法错误（如 `url` 没加引号、`mavelCentral()` 拼错），整个构建直接失败 → 移走备份成 `init.gradle.broken.bak`
- SDK 官方源 50KB/s → 用腾讯镜像 `https://mirrors.cloud.tencent.com/AndroidSDK/`（5MB/s）
- Gradle 发行包 → 腾讯镜像；JDK 17 → 华为镜像
- `gradle-wrapper.properties` 里的 `distributionUrl` 也要换

### 8.7 站点域名频繁更换

- 现象：昨天能用的源今天 404
- 原因：资源站被墙/换域名是常态
- 对策：`SourceStore` 支持改 host；`config/stations.json` 的 `hosts[]` 支持故障切换。**不要在代码里硬编码分片域名**（如 `yyv13.gghijk.com` 与接口返回的 `v13.gghijk.com` 就不是一回事）

### 8.8 「热播榜」排序参数是假的

- 现象：`order=vod_hits desc` 和不传完全一样
- 原因：多数资源站根本没实现按点击排序
- 结论：只有 `h=168`（最近 168 小时）有效；真正的榜单只能靠第三方（最终选了豆瓣），不要在这条路上继续投入

### 8.9 `getColor()` 里混用资源 ID 和颜色字面量 → 首页秒崩

- 现象：App 能正常打开，海报墙刚渲染就闪退；`CrashLog` 显示
  `android.content.res.Resources$NotFoundException: Resource ID #0xffc0c8d4`，栈顶是 `HomeActivity$PosterAdapter.getView`
- 原因：原来写的是
  `getResources().getColor(position == 0 ? R.color.gold : position == 1 ? 0xFFC0C8D4 : 0xFFCD7F32)`
  —— 三元表达式把**资源 ID** 和**颜色字面量**混在一个分支里。第 1 名拿到的是合法资源 ID 所以不报错，第 2 名把 `0xFFC0C8D4` 当资源 ID 去查表，必然抛 `NotFoundException`
- 解法：三个分支全部用资源 ID（`R.color.gold` / `silver` / `bronze`），或者干脆绕开 `getColor()`，直接 `setBackgroundColor(0xFFC0C8D4)`
- **规则：`getResources().getXxx()` 的参数只能是资源 ID；颜色值请直接传给 View 的 setter。三元表达式里出现 `R.` 与 `0xFF...` 混排，一律视为 bug**
- 已修：v1.0.2（versionCode 3），并在 `colors.xml` 补了 `silver` / `bronze`

### 8.10 遥控器点不动：条目 focusable 会吃掉 OK 键 / Tab 只认 click

- 现象一：海报墙里按 OK 没有任何反应，进不了详情页（`grid.setOnItemClickListener` 像失效）
- 原因：`item_poster.xml` 根布局是 `focusable="true"`，AbsListView 因此认为「条目自己会处理按键」（`mItemsCanFocus=true`），OK 键被焦点所在的条目吞掉，`OnItemClickListener` 永远等不到
- 解法：点击/按键监听要挂在**条目自身**上（`setOnClickListener` + `setOnKeyListener` 认 `DPAD_CENTER / ENTER / NUMPAD_ENTER`），同时要防重复打开，因为条目监听和 GridView 的 itemClick 可能都触发；**用布尔量 + `onResume` 复位，不要用时间窗**（600ms 时间窗会把「从详情页返回后立刻再点」一起吞掉，又变成要点两次）
- 现象二：顶部 Tab 切换后列表不刷新
- 原因：原来只挂了 `setOnClickListener`，而 `grid` 的 `nextFocusUp` 指向的 `tabs` 是**不可聚焦的容器**，焦点能不能落到某个 Tab 上取决于 ROM 的焦点搜索；而且只有按 OK 才切，遥控器左右移动时毫无反馈，看起来就是"点了没反应"
- 解法：给每个 Tab `setId(View.generateViewId())`，用 `setNextFocusLeftId/RightId/DownId` 显式串焦点链；**焦点一落到 Tab 就 `selectTab()`**（左右扫过去内容立即变），再用 `setOnKeyListener` + `setOnClickListener` 兜底；`selectTab` 里同步更新 `grid.setNextFocusUpId(当前Tab)`，这样从网格往上走回到当前分类而不是跳回第一个
- **规则：凡是动态生成的、要参与 D-pad 导航的控件，一律显式指定 id 和 nextFocus*，不要依赖 FocusFinder 猜**

### 8.11 手机上「每个按钮都要点两次才进」——`focusableInTouchMode`

- 现象：手机端点海报、点设置按钮、点片源行，第一下没反应，第二下才生效；TV 遥控器正常
- 原因：布局里给条目根节点写了 `android:focusableInTouchMode="true"`。`View.onTouchEvent` 的 ACTION_DOWN 分支是：

  ```java
  if (isFocusable() && isFocusableInTouchMode() && !isFocused()) focusTaken = requestFocus();
  ...
  if (!focusTaken) performClick();   // 第一次点击只抢焦点，click 被跳过
  ```

  所以第一次点击只是让这个 View 拿到焦点，第二次才真正触发 click
- 解法：**列表条目和按钮一律不要写 `focusableInTouchMode`**。TV 上（非触摸模式）靠 `focusable="true"` 就够；触摸模式下没有它，click 第一下就能触发
- 唯一例外：`EditText` 必须保留，否则点上去不弹键盘
- 已清：`item_poster` / `item_history` / `item_line` / `item_source` / `TopButton` 样式

### 8.12 切换分类后列表不回顶部——`setSelection` 在触摸模式是空操作

- 现象：手机上切「电影 / 剧集 / 动画」，下面的海报墙还停在原来那一屏
- 原因：`AbsListView.setSelection()` 里

  ```java
  if (!isInTouchMode()) setNextSelectedPositionInt(position);
  else mResurrectToPosition = position;   // 只记了个"待恢复位置"，不触发滚动
  ```

  触摸模式下它根本不滚，TV 上才有效
- 解法：`grid.post { if (isInTouchMode()) smoothScrollToPosition(0) else setSelection(0) }`。
  必须 `post` 到下一帧，让新数据先完成布局，否则会被 `notifyDataSetChanged` 的重排覆盖

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
# 分发：E:\share\Kankan-1.4.0-debug.apk   ← 见顶部「铁律 · APK 产出后必须分发」
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

> 小坑：增量构建偶尔会把 dex 以**未压缩**方式打进 APK（2.79MB → 5.74MB），dex 内容完全一致（都是 6,439,996 字节）。介意体积就 `clean` 一次。

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
| 长剧选集 | 一次性铺全部集数（竖排列表），几百集的长剧会偏重 |
| 缓存可能过期 | 当天缓存的源如果中途挂了不会自动重测，得手动点「重新检测」 |
| 无「下一集」自动连播 | 播完停在结束提示 |
| 片源质量 | 资源站镜像严重重合：14 站 46 条线路只有 40 个不同路径，6 个站是同一份片源。多站的价值是冗余而不是更多片源 |
| 合规 | 片源均为未授权内容，仅作技术验证 |

---

## 11. 如果要继续开发，建议顺序

1. **播放结束自动连播下一集** —— 现在结束只提示，需要用户自己进控制栏点「下一集」
2. **选集分组** —— 超过 50 集时按 10 集一段折叠，避免长列表（现在浮层里是平铺）
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

## 14. Git 工作流与测试策略

### 14.1 分支与提交

```
main   ●────────────●───────────────●   ← 只放用户真机验收过的版本（merge --no-ff）
        \          /               /
dev      ●──●──●──●───●──●──●─────●     ← 所有改动都落这里
```

| 项 | 约定 |
|---|---|
| 仓库位置 | `D:/code/kankan/.git`，**纯本地，无 remote** |
| 提交身份 | 仓库局部配置 `kankan-dev <kankan@localhost>`（未写全局，不影响其他项目） |
| 长期分支 | `main`（稳定基线）、`dev`（开发） |
| 当前工作分支 | `dev` |
| 提交粒度 | 一个可交付的改动集 = 一次提交；构建/测试通过后才提交 |
| 提交信息 | `<type>: <一句话>`，type ∈ `feat` / `fix` / `refactor` / `docs` / `chore` / `test`；正文写清「改了什么、为什么、验证了什么」 |
| 合并 | 用户验收通过 → `git checkout main && git merge --no-ff dev` → 立刻切回 `dev` |
| 禁止 | 直接提交 main、amend 已交付提交、`reset --hard`、`push -f`（无远程） |

新需求开工前先确认分支：

```bash
git -C D:/code/kankan branch --show-current   # 必须是 dev
git -C D:/code/kankan status --short          # 工作区有脏文件先弄清来源
```

### 14.2 改动 → 测试映射（核心：不跑无关测试）

| 改动的文件 | 必跑 | 明确不跑 |
|---|---|---|
| `PlayResolver.java`、`SourceStats.java`（纯逻辑，有单测） | `testDebugUnitTest` → `assembleDebug` → `lintDebug` | — |
| 其他 `*.java`、`res/**`、`AndroidManifest.xml` | `assembleDebug` → `lintDebug` | `testDebugUnitTest` |
| `*.gradle`、`settings.gradle`、`gradle.properties`、`lint.xml`、`gradle/wrapper/**` | `clean assembleDebug` → `testDebugUnitTest` → `lintDebug` | — |
| `app/src/test/**` | `testDebugUnitTest` | `assembleDebug`、`lintDebug` |
| 仅 `*.md`、`.gitignore`、`.gitattributes`、`.workbuddy/**` | **不跑任何构建** | 全部 |
| `vod-collector/**/*.js` | `node --check <改动文件>` | Gradle 全套 |
| `vod-collector/web/*.html`、`config/*.json` | 静态语法检查；必要时 `node src/cli.js health`（会联网） | Gradle 全套 |

跨类改动取并集。纯文档改动跑 Gradle 属于镀金，**明确禁止**。

### 14.3 交付话术（固定格式）

> 改动：`xxx.java`、`yyy.xml`（→ 命中「其他 java/res」行）
> 检查：`assembleDebug` 通过（37s）、`lintDebug` 0 error；未跑单测（本次未碰解析逻辑）
> 提交：`dev` @ `a1b2c3d` ｜ APK 已同步 `E:/share/`（MD5 校验一致）
> 待你验收后我再合到 `main`。

### 14.4 回滚与对比

```bash
git log --oneline --graph --all      # 看两条分支的合并历史
git show <commit> --stat             # 某次改了什么
git checkout main -- <file>          # 单独把 main 上某文件取回来（慎用）
git revert <commit>                  # 撤销一次提交（不用 reset）
```

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

强调色只有一个：`accent #FF4D6A`（焦点描边、进度条、选中态）。不要临时加新色值。

### 15.5 焦点

- 所有可聚焦控件必须显式串 `nextFocusLeft/Right/Up/Down`（FocusFinder 在 TV 上不可靠）。
- 焦点样式统一 `bg_tab`（2dp accent 描边 + 半透明底），卡片可叠加 1.07 放大。
- 列表条目自己 `focusable="true"` 时，OK 键会被条目吃掉 —— 必须同时挂 `setOnKeyListener` + `setOnClickListener`（见 §8.10）。
- 布局里**不要**给可点击控件写 `focusableInTouchMode="true"`（见 §8.11）。

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

### 19.5 按钮长什么样（1.4.5）

1.4.4 交付后用户反馈「播放页没有看到快进快退的按钮」。其实两个键一直在控制栏里，
但 **Material 的 `replay_10` / `forward_10` 在 24dp 尺寸下，圆里的「10」只有约 4dp 高**，
实机上就是两个「旋转箭头」，被当成刷新图标。同时 1.4.4 的图标还有系统性偏移
（见 §19.6），把「10」挤到左下角，更加看不见。

改法：

| 项 | 1.4.4 | 1.4.5 |
|---|---|---|
| 快退/快进 | 48dp 纯图标 | 58×56dp，**图标 26dp + 「快退/快进」标签** |
| 图标居中 | 偏 (-3dp, +3dp) | 居中 |
| 按钮行 | `layout_gravity=center_horizontal` | `fillViewport=true` + 子布局 `gravity=center` |

按钮行那一条是给窄屏兜底：`layout_gravity="center_horizontal"` 在内容比视口宽时
会得到**负的左边距**，两头对称截掉；换成 `fillViewport` 后，够宽就居中、不够宽就
从左排并可横向滚动，永远不会把两端的按钮切掉。

### 19.6 图标导出偏移（管线级 bug，别再犯）

`tools/fetch_material_icons.py` 的链路是 SVG → svglib 转 PDF → pdfium 光栅化。
PDF 页面尺寸和图形 bbox 不一致，光栅化后**字形落在画布偏左下**：
1.4.4 实测 96px 画布偏 (-12, +12)，缩到 72px 就是 (-9, +9)，即 1/8 画幅全错位。
所有图标（返回、搜索、记录、刷新、设置、播放系列）都受影响。

修法：渲染完先按 alpha bbox 裁出来、贴回画布正中再接缩放（`center_glyph()`），
只改位置不改尺寸——Material 各图标的相对大小是设计好的，按 bbox 缩放会让
`play_arrow` 这类形状失衡。

配套自检 `tools/check_icons.py`（见 AGENTS.md §5.3）：偏差 ≤2% 画幅、字形占 40%~92%。
