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
cp app/build/outputs/apk/debug/app-debug.apk dist/Kankan-1.2.0-debug.apk
cp dist/Kankan-1.2.0-debug.apk /e/share/
md5sum dist/Kankan-1.2.0-debug.apk /e/share/Kankan-1.2.0-debug.apk
```

规则细则：

- **不要覆盖不同版本的同名文件而丢掉历史**：`E:\share\` 里若已存在**同名但内容不同**的 APK，先把旧的改名留档（追加构建日期，如 `Kankan-1.2.0-debug-20261106-2130.apk`）再放新的。
- 版本号没变但代码变了，属于「同名不同内容」，按上一条处理。
- `E:\share\` 不存在时先 `mkdir -p /e/share`，不要报错退出。
- 构建失败 / APK 没生成时，**不要**去复制上一次的旧包充数，如实说明失败。
- 复制完不要自作主张安装、上传、发链接，等用户指示。

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
├── dist/Kankan-1.2.0-debug.apk  已构建产物（同时复制一份到 E:\share\）
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
        │   │   ├── HistoryStore.java        ⭐ 播放进度 + 影片信息快照（首页继续观看用）
        │   │   ├── ImageLoader.java         海报加载（Referer + 内存/磁盘缓存）
        │   │   └── Http.java                极简 HTTP，只用 HttpURLConnection
        │   ├── ui/
        │   │   ├── HomeActivity.java        6 列海报墙、5 个分组 tab、继续观看、搜索入口
        │   │   ├── DetailActivity.java      影片信息 + 片源列表 + 选集 + 继续观看
        │   │   ├── SearchActivity.java      本地榜单过滤 + 片源站联网兜底
        │   │   └── SettingsActivity.java    源配置、服务端地址、清缓存、强制更新
        │   └── player/PlayerActivity.java   ExoPlayer 播放页 + 控制栏 + 选集浮层 + 进度落盘
        └── res/
            ├── layout/                      9 个布局，全部手写原生 View
            ├── values/                      colors / strings / styles
            ├── mipmap-*/                    ic_launcher（5 档密度）+ ic_launcher_foreground
            ├── mipmap-anydpi-v26/           自适应图标（API 26+）
            └── drawable-{m,xh,xxh}dpi/      tv_banner（320×180 / 640×360 / 960×540）
```

图标与横幅由 `tools/make_icons.py` 生成（纯几何绘制 + 4 倍超采样），改样式就改脚本重跑，不要手改 PNG。

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
# 留档：dist/Kankan-1.2.0-debug.apk
# 分发：E:\share\Kankan-1.2.0-debug.apk   ← 见顶部「铁律 · APK 产出后必须分发」
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
adb install -r Kankan-1.2.0-debug.apk
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
