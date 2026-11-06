# 看看 · Android TV 版

应用名「看看」。一个能装在电视盒子上的影视热播榜客户端：海报墙首页、遥控器操作、点开即播、看到哪记到哪。

**Android 4.4（API 19）及以上**可安装（早期是 4.0，2026-11 起统一抬到 4.4，原因见第九节）。

---

## 一、功能

| 能力 | 说明 |
|---|---|
| 海报墙首页 | 五个分区（剧集 / 电影 / 综艺 / 动画 / 纪录片），前三名金银铜角标 |
| 榜单数据 | 豆瓣 17 个官方榜单，约 530 部，带评分、集数、简介、高清海报 |
| 每日更新 | 启动时比对日期，跨天自动拉取；不跨天直接用本地缓存秒开 |
| 强制更新 | 首页「更新」按钮、设置页「强制更新榜单」，随时手动刷新 |
| 片源可配置 | 内置 14 个站点，可逐条启用/停用，可恢复默认，支持远程覆盖 |
| 稳定性排序 | 按「成功率 × 响应速度」给每个源打分，默认聚焦得分最高的一路 |
| 选集 | 多集线路点进去先选集（已探针那集标「已验证」），点某集才实时解析地址 |
| 断点续播 | 每 5 秒记一次进度，退出再进直接回到上次那一集那一秒，详情页有「继续看 第03集 12:34」入口 |
| 遥控器 | 方向键 / OK / 返回 / MENU 全程可操作，焦点放大 + 白边 |
| 播放器 | ExoPlayer + HLS，支持 AES-128 加密流，缓冲/错误都有明确提示 |
| 双模式 | 默认直连豆瓣；填了服务端地址就走自建 Node 服务 |

## 二、构建

需要 JDK 17 + Android SDK（platform 33、build-tools 33.0.2）。

```bash
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk

cd vod-tv
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

> 本机（Windows）已装好：JDK 在 `D:\tools\jdk17`，SDK 在 `D:\Android\Sdk`，`local.properties` 已写好，直接
> `gradlew.bat assembleDebug` 即可。

装机：

```bash
adb connect 192.168.1.x:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> release 包默认用 debug 签名，方便直接装盒子。要正式发布请换成自己的 keystore（`app/build.gradle` 里的 `signingConfig`）。

### 本次构建实测

| 项 | 结果 |
|---|---|
| 构建 | `BUILD SUCCESSFUL`，Gradle 7.6.4 + AGP 7.4.2 + JDK 17；冷构建 3m58s，`clean` 重编 53s，改 1 个文件增量 37s |
| 产物 | `app/build/outputs/apk/debug/app-debug.apk`，**3.12MB**（副本 `dist/Kankan-1.2.0-debug.apk`，versionCode 6 / 1.2.0） |
| minSdk / targetSdk | **19** / 28 |
| APK 校验 | `aapt2 dump badging` 读到 `sdkVersion:'19'`，标签「看看」，Launcher 与 Leanback 双入口都在 |
| dex 校验 | ExoPlayer 2.19.1（4567 类，HLS 204 类）、Guava 5917、androidx.core 1700、com.vodtv 165 |
| Lint | 1m 25s 跑完，**NewApi 0**；仅 1 个 error 是 `ExpiredTargetSdkVersion`（只影响上架 Google Play，侧载无碍） |

## 三、两套取数模式

```
┌──────────────┐   直连（默认）    ┌─────────┐
│  Android TV  │ ────────────────▶ │  豆瓣   │
│              │                   └─────────┘
│              │   服务端模式       ┌─────────┐
│              │ ────────────────▶ │ Node 服务│ ──▶ 豆瓣 / 片源站
└──────────────┘                   └─────────┘
```

- **直连**：App 自己拉 17 个榜单（4 路并发，约 4 秒），海报也自己带 Referer 取。不需要任何服务器。
- **服务端**：设置里填 `http://192.168.1.10:8765`，App 只请求 `/api/rank` 和 `/api/poster`。逻辑改在服务端，不用发新版。

## 四、片源配置

### 配置在哪

`SourceStore.defaults()` 里内置了 14 个站点，字段与 Node 端 `config/stations.json` 一一对应：

```java
new SourceConfig("bfzy", "暴风资源", "https://bfzyapi.com", "/api.php/provide/vod/", 1, true)
//                  id      显示名        host                    path                      优先级 支持搜索
```

`priority`：1 主力 / 2 可用 / 3 备选。它只在**没有历史数据**时参与排序，一旦有播放记录就让位给真实成功率。

### 怎么改

电视上：设置页 → 片源 → 按 OK 启用/停用，长按锁定为默认源。

代码里：改 `SourceStore.defaults()`，或在设置页点「恢复默认源清单」重置。

远程：填了服务端地址后，设置页「从服务端同步源」会拉 `/api/stations` 覆盖本地清单——**服务端加一个站，所有盒子点一下同步就有了，不用发版**。同步时会保留每台设备自己的启停状态。

### 稳定性怎么算

每次检索一个源都会记录成功/失败和耗时：

```
成功率 = (成功 + 1) / (总次数 + 2)        // 拉普拉斯平滑
速度分 = 1 - min(平均耗时, 6000ms) / 6000
得分   = 成功率 × 0.75 + 速度分 × 0.25 + priority 微调(0~0.03)
```

平滑的作用：只成功过一次的源不会因为 `1/1 = 100%` 被顶到第一位。

详情页的源列表就按这个得分降序排列，第一条打「推荐」标并自动获得焦点——所以"默认播哪个源"不需要用户选，用几次之后自己就准了。

### 播放地址的三级结构

`vod_play_url` 一层套一层，拆错就会播成预告或者干脆播不出来：

```
第01集$https://a.com/share/xxx#第02集$https://a.com/share/yyy   ← 线路 1（分享页，要二次解析）
$$$                                                              ← 线路分隔
第01集$https://a.com/2025/xx/index.m3u8#第02集$...               ← 线路 2（直链）
```

`PlayResolver.splitEpisodes()` 先按 `$$$` 挑一条线路——**优先直链占比高的那条**，点开就能播，省一次 HTML 解析；再按 `#` 拆集、按 `$` 拆集名和地址。拿《庆余年》实测：原始 91 条（46 + 46 两条线路），拆完是 46 集、集名无重复、全部为直链。

## 五、更新策略

```
启动
 ├─ 读 files/rank.json ──▶ 有缓存则立刻渲染（秒开）
 └─ Prefs.needDailyUpdate() ?
       ├─ 是 → 后台刷新，完成后写入缓存 + 记录日期
       └─ 否 → 什么都不做
```

日期存的是 `yyyy-MM-dd`，所以一天内反复启动不会重复拉取。用户也可以随时按「更新」强制刷新。

服务端模式下，服务端自己有 30 分钟缓存，App 的每日判断独立生效。

### 播放进度怎么记

播放页每 5 秒往 `SharedPreferences("history")` 写一次，`onPause` / `onDestroy` 再补一次。一条记录长这样：

```json
{"庆余年第一季": {"ep": "第03集", "src": "liangzi", "pos": 734000, "dur": 2760000, "at": 1767331200000}}
```

一部片只留最新一条（看了第 4 集就覆盖第 3 集），最多存 80 部，超了按时间淘汰。

`resumePos()` 决定要不要续播，三条规则：

| 情况 | 行为 |
|---|---|
| 进度 < 30 秒 | 从头播（刚点开就退出，没必要留） |
| 距结尾 < 60 秒 | 从头播（当看完了，避免一进来就是字幕） |
| 集名对不上 | 从头播（防止把第 1 集的进度套到第 8 集上） |

详情页的「继续观看」不是死链：它先找回上次那个源，那个源挂了就找任何一条含同集名的线路；点下去直接解析该集并进播放页，不用再过一遍选集。片源列表里也会给上次用的那条标「上次看到 第03集 12:34」。

## 六、遥控器键位

| 页面 | 按键 | 行为 |
|---|---|---|
| 首页 | ← → | 切换分类 / 网格内移动 |
| 首页 | ↑ | 回到顶部按钮 |
| 首页 | OK | 进详情 |
| 首页 | MENU | 直接进设置 |
| 详情 | OK | 单集直连播放；多集进选集页 |
| 选集 | OK | 解析并播放该集，失败行内提示 |
| 选集 | 返回 | 退回片源列表（不退出详情页） |
| 播放 | OK / 播放暂停 | 播放 / 暂停 |
| 播放 | ← → | 快退 / 快进 10 秒 |
| 播放 | ↑ ↓ | 呼出 / 收起控制栏 |
| 全局 | 返回 | 逐级退出 |

## 七、工程结构

```
app/src/main/java/com/vodtv/
├── App.java                    入口：给 4.x 强制开 TLS 1.2，初始化各存储
├── data/
│   ├── Prefs.java              服务端地址、更新日期、锁定源
│   ├── SourceConfig.java       单个源的模型
│   ├── SourceStore.java        源清单：默认值、增删改、远程覆盖
│   ├── SourceStats.java        稳定性统计与打分
│   ├── RankRepository.java     榜单拉取 / 解析 / 缓存（豆瓣 + 服务端两套）
│   ├── RankItem.java           条目归一化（剧集结构 / 电影结构）
│   ├── PlayResolver.java       搜片 → 拆线路/分集 → 解析 m3u8 → 探测 → 排序
│   ├── HistoryStore.java       播放进度（片名 → 集/秒/源），续播判断也在这
│   ├── ImageLoader.java        海报加载（Referer + 内存/磁盘缓存）
│   └── Http.java               极简 HTTP，只依赖 HttpURLConnection
├── ui/
│   ├── HomeActivity.java       海报墙
│   ├── DetailActivity.java     详情 + 片源列表
│   └── SettingsActivity.java   源配置 + 强制更新
└── player/
    └── PlayerActivity.java     ExoPlayer 播放页
```

## 八、依赖为什么这么少

| 常选项 | 最低 API | 结论 |
|---|---|---|
| Leanback（TV 官方库） | 17 | 现在 19 够用了，但手写焦点已经跑通，迁移无收益 |
| ExoPlayer 2.19.1 | 16 | 取最后一个 2.x；再往上（media3）要 API 21，够不上 |
| Glide / Coil | 21 | 仍用自写 `ImageLoader`：要单独带 Referer，且体积更小 |
| AndroidX | 14 | **已启用**（ExoPlayer 2.19 依赖 androidx.core） |

所以整套是「framework View + ExoPlayer 2.19.1 + 自写图片加载」，依赖只有两个：

```gradle
com.google.android.exoplayer:exoplayer-core:2.19.1
com.google.android.exoplayer:exoplayer-hls:2.19.1
```

注意：2.19 依赖 androidx，`gradle.properties` 必须 `android.useAndroidX=true`，且不能同时引 `support-annotations`。代价是 UI 要自己搭，好处是 4.4 的老盒子也能跑。

## 九、已知限制（重要）

### 为什么从 4.0 抬到 4.4

4.0~4.3 的 `SSLSocket` 默认协议是 TLSv1，而豆瓣和片源 CDN 基本只接受 TLS 1.2。`App.java` 里虽然把 TLSv1.2 塞进了 `enabledProtocols`，但**能不能成取决于系统有没有编入 TLS 1.2 的 OpenSSL**——4.0 有一部分没有，装得上却连不上网；同时 `GridView.getColumnWidth()` 这类 API 16 方法在 4.0/4.1 上会直接 `NoSuchMethodError`。

与其为一小撮 4.0 设备写绕路代码，不如把 minSdk 直接定在 19（4.4）：TLS 1.2 是标配、API 16 的方法随便用，还能顺手把 ExoPlayer 从 2.9.6 升到 2.19.1。


### 已经踩掉的兼容坑

`GridView.getColumnWidth()` 和 `getHorizontalSpacing()` 看着人畜无害，实际都是 **API 16** 的方法，在 4.0/4.1 上直接 `NoSuchMethodError` 崩溃。海报高度现在改成自己按屏幕宽算，间距直接用布局里写死的 14dp：

```java
int usable = screenW - paddingLeft - paddingRight - dp(14) * (cols - 1);
posterHeight = (int) (usable / cols * 1.5f);
```

Lint 的 `NewApi` 检查就是为了抓这类问题，构建时务必看它的结果，不要只在 5.0+ 的机器上试。

### 其它

- **没有实机验证**：本机 CPU 太弱（i3-4130T 2C4T）跑不动模拟器，UI 布局是按 1080p / 6 列推的，装到盒子上可能需要微调间距。
- **闪退自查**：App 内置了崩溃捕获。崩了之后重新打开 App，首页会直接弹窗显示上一次的堆栈；同一份内容也会写到 `/sdcard/Android/data/com.vodtv/files/last_crash.txt`，可以用电视上的文件管理器拷出来。有 adb 时直接 `adb logcat -b crash -d`。
- **图标**：由 `tools/make_icons.py` 生成（胶片框 + 播放键），包含 5 档 mipmap、API 26 自适应图标、3 档 TV 横幅。想换配色改脚本里的常量重跑即可。
- 选集页是一次性把全部集数铺成竖排列表（和片源列表同一套样式），40 集滚动没问题，几百集的长剧列表会偏重。
- 榜单依赖豆瓣的私有接口，没有公开承诺。接口变了要发新版（用服务端模式可以避免）。
- 海报直连需要带 Referer，所以 `ImageLoader` 里对 `doubanio.com` 单独处理；走服务端时改由服务端代理。

## 十、合规

榜单条目来自豆瓣公开页面，片源来自第三方采集站，均为未授权内容。本工程仅作技术验证，对外提供服务有侵权风险。
