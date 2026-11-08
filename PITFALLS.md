# 看看（Kankan）· 踩坑笔记

> 2026-11-07 从 PROJECT.md 拆出：PROJECT.md 只讲项目本身（是什么 / 结构 / 技术 / 原理），
> 所有「现象 → 原因 → 解法」类的坑集中在本文件，**新坑追加到这里**，不要写回 PROJECT.md。
> 「原 §8.x」是拆分前 PROJECT.md 的编号，便于对照旧提交。改代码前建议先通读一遍。

---

## 一、构建与工具链

### P-01 zipflinger 增量打包污染（原 §9.1，2026-11-07 实锤，当日复发两次）

- 现象：增量 `assembleDebug` 出的 APK 体积异常翻倍（3.2MB → 6.07MB），dex 内容没变，能正常安装运行
- 原因：zipflinger 打包时把陈旧条目数据留在 zip 里——本地条目区混入孤儿数据（实测 2.84MB、44 个僵尸 zip 本地头，其中一个还是全零空头），中央目录不引用它们
- 识别：文件里 `PK\x03\x04` 本地头数远大于中央目录条目数（污染时 196/195 vs 149；正常包恒差 1，如 150 vs 149，多出的 1 个是 dex 内数据的假阳性，历史包同样）
- 解法：**`clean assembleDebug` 重打**
- **复发规律（2026-11-07 两次验证）**：只要**上一轮构建产物还在**就裸跑 `assembleDebug`，
  哪怕只改了 2 个 java 文件也会中招（第一次 08:57、第二次 09:50）。
  两次都是 clean 重打立刻恢复 3.2MB / 150 vs 149。
  → **结论：交付包固定走 `clean assembleDebug`，不做增量赌运气。**
- **机械核对（已做成脚本，别再靠肉眼）**：

  ```bash
  cd vod-tv && python tools/check_apk.py            # 查构建产物
  cd vod-tv && python tools/check_apk.py --all      # 连带查 dist/ 与 E:/share 留档
  ```

  退出码 0 = 干净；1 = 脏包（会列出孤儿条目数与路径）。**这是发版前的硬闸门，退出码非 0 不得分发。**
  **构建后体积比上一版翻倍，先怀疑这个**，跑一遍脚本即可确诊。

### P-02 Gradle / SDK 下载慢或报错（原 §8.6）

- `/root/.gradle/init.gradle` 若有语法错误（如 `url` 没加引号、`mavelCentral()` 拼错），整个构建直接失败 → 移走备份成 `init.gradle.broken.bak`
- SDK 官方源 50KB/s → 用腾讯镜像 `https://mirrors.cloud.tencent.com/AndroidSDK/`（5MB/s）
- Gradle 发行包 → 腾讯镜像；JDK 17 → 华为镜像
- `gradle-wrapper.properties` 里的 `distributionUrl` 也要换

---

## 二、Android 4.4 兼容

### P-03 Android 4.0~4.3 连不上 HTTPS（原 §8.1，已不影响）

> 状态：minSdk 提到 19 后不再有 4.0~4.3 设备。`App.enableTls12()` 保留，用于个别 4.4 ROM 没默认开 TLS 1.2 的情况。

- 现象：所有网络请求 `SSLException`，豆瓣、CDN 全挂
- 原因：4.0~4.3 的 `SSLSocket` 默认只开 TLSv1，服务端只接受 TLS 1.2
- 解法：`App.enableTls12()` 把 `TLSv1.2` 塞进 `enabledProtocols`；同时装了 permissive TrustManager（老机器证书链缺失）
- 限制：**能否成功取决于 ROM 是否编入 TLS 1.2 的 OpenSSL**，4.4 起才是标配。所以「能装 ≠ 能用」

### P-04 `GridView.getColumnWidth()` / `getHorizontalSpacing()` 是 API 16（原 §8.2，已不影响）

> 状态：minSdk 19。代码里的手算列宽保留，行为正确且与 `stretchMode` 无关，不必改回。

- 现象：4.0/4.1 上打开首页直接 `NoSuchMethodError` 崩溃，编译期完全看不出来
- 原因：这两个方法 16 才有
- 解法：按屏幕宽度自己算，间距用布局里写死的 14dp
- **教训：必须跑 `./gradlew lintDebug` 看 `NewApi`，不能只在 5.0+ 设备上试**

---

## 三、数据与接口

### P-05 `vod_play_url` 的 `$$$` 线路（原 §8.3）

- 现象：剧集列表出现两个「第01集」；点播报错或播成预告
- 原因：一条记录含两条线路（分享页 + 直链），只按 `#` 拆
- 解法：先按 `$$$` 选线路再拆集，结构见 PROJECT.md §5.1

### P-06 豆瓣电影榜整排破图（原 §8.4）

- 现象：电影分组的卡片全空，剧集正常
- 原因：电影用的是 `cover/info/directors` 结构，没有 `pic`
- 解法：`normalize()` 里判断 `!it.pic && it.cover` 走另一套字段

### P-07 ExoPlayer 2.9.6 → 2.19.1 的迁移点（原 §8.5，已升级，改代码前先看）

- **`ExoPlayerFactory.newSimpleInstance()` 已删除** → 改用 `new SimpleExoPlayer.Builder(ctx).setTrackSelector(...).setLoadControl(...).build()`
- **`DefaultBandwidthMeter` 没有公开构造函数了** → 不需要自己传，`DefaultTrackSelector` 内部会建；要自定义就用它的 `Builder`
- **`AdaptiveTrackSelection.Factory(meter)` 构造签名变了**（多一个 `Clock` 参数）→ 干脆不传，用 `new DefaultTrackSelector(this)` 走默认
- **`Player.EventListener` 已删除** → 改为 `implements Player.Listener`，回调名也变了：`onPlayerStateChanged(boolean,int)` → `onPlaybackStateChanged(int)`，`onPlayerError(ExoPlaybackException)` → `onPlayerError(PlaybackException)`
- **`player.prepare(source)` 拆成两步** → `setMediaSource(source)` + `prepare()`；`HlsMediaSource.Factory` 现在吃 `MediaItem` 而不是 `Uri`
- **`DefaultDataSourceFactory` 被 `DefaultDataSource.Factory` 取代**，且**没有** `setUserAgent` → 想带 UA 要嵌套一层：`new DefaultDataSource.Factory(ctx, new DefaultHttpDataSource.Factory().setUserAgent(ua))`
- **依赖 androidx** → `gradle.properties` 必须 `android.useAndroidX=true`，并且要删掉原来的 `support-annotations`，否则 support/androidx 混用直接冲突
- 教训（沿用）：2.x 的监听器都是 `default` 方法，**别加多余空实现**，只覆盖需要的

### P-08 站点域名频繁更换（原 §8.7）

- 现象：昨天能用的源今天 404
- 原因：资源站被墙/换域名是常态
- 对策：`SourceStore` 支持改 host；`config/stations.json` 的 `hosts[]` 支持故障切换。**不要在代码里硬编码分片域名**（如 `yyv13.gghijk.com` 与接口返回的 `v13.gghijk.com` 就不是一回事）

### P-09 「热播榜」排序参数是假的（原 §8.8）

- 现象：`order=vod_hits desc` 和不传完全一样
- 原因：多数资源站根本没实现按点击排序
- 结论：只有 `h=168`（最近 168 小时）有效；真正的榜单只能靠第三方（最终选了豆瓣），不要在这条路上继续投入

---

## 四、UI / 焦点 / 触摸

### P-10 `getColor()` 里混用资源 ID 和颜色字面量 → 首页秒崩（原 §8.9）

- 现象：App 能正常打开，海报墙刚渲染就闪退；`CrashLog` 显示
  `android.content.res.Resources$NotFoundException: Resource ID #0xffc0c8d4`，栈顶是 `HomeActivity$PosterAdapter.getView`
- 原因：原来写的是
  `getResources().getColor(position == 0 ? R.color.gold : position == 1 ? 0xFFC0C8D4 : 0xFFCD7F32)`
  —— 三元表达式把**资源 ID** 和**颜色字面量**混在一个分支里。第 1 名拿到的是合法资源 ID 所以不报错，第 2 名把 `0xFFC0C8D4` 当资源 ID 去查表，必然抛 `NotFoundException`
- 解法：三个分支全部用资源 ID（`R.color.gold` / `silver` / `bronze`），或者干脆绕开 `getColor()`，直接 `setBackgroundColor(0xFFC0C8D4)`
- **规则：`getResources().getXxx()` 的参数只能是资源 ID；颜色值请直接传给 View 的 setter。三元表达式里出现 `R.` 与 `0xFF...` 混排，一律视为 bug**
- 已修：v1.0.2（versionCode 3），并在 `colors.xml` 补了 `silver` / `bronze`

### P-11 遥控器点不动：条目 focusable 会吃掉 OK 键 / Tab 只认 click（原 §8.10）

- 现象一：海报墙里按 OK 没有任何反应，进不了详情页（`grid.setOnItemClickListener` 像失效）
- 原因：`item_poster.xml` 根布局是 `focusable="true"`，AbsListView 因此认为「条目自己会处理按键」（`mItemsCanFocus=true`），OK 键被焦点所在的条目吞掉，`OnItemClickListener` 永远等不到
- 解法：点击/按键监听要挂在**条目自身**上（`setOnClickListener` + `setOnKeyListener` 认 `DPAD_CENTER / ENTER / NUMPAD_ENTER`），同时要防重复打开，因为条目监听和 GridView 的 itemClick 可能都触发；**用布尔量 + `onResume` 复位，不要用时间窗**（600ms 时间窗会把「从详情页返回后立刻再点」一起吞掉，又变成要点两次）
- 现象二：顶部 Tab 切换后列表不刷新
- 原因：原来只挂了 `setOnClickListener`，而 `grid` 的 `nextFocusUp` 指向的 `tabs` 是**不可聚焦的容器**，焦点能不能落到某个 Tab 上取决于 ROM 的焦点搜索；而且只有按 OK 才切，遥控器左右移动时毫无反馈，看起来就是"点了没反应"
- 解法：给每个 Tab `setId(View.generateViewId())`，用 `setNextFocusLeftId/RightId/DownId` 显式串焦点链；**焦点一落到 Tab 就 `selectTab()`**（左右扫过去内容立即变），再用 `setOnKeyListener` + `setOnClickListener` 兜底；`selectTab` 里同步更新 `grid.setNextFocusUpId(当前Tab)`，这样从网格往上走回到当前分类而不是跳回第一个
- **规则：凡是动态生成的、要参与 D-pad 导航的控件，一律显式指定 id 和 nextFocus*，不要依赖 FocusFinder 猜**

### P-12 手机上「每个按钮都要点两次才进」——`focusableInTouchMode`（原 §8.11）

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

### P-13 切换分类后列表不回顶部——`setSelection` 在触摸模式是空操作（原 §8.12）

- 现象：手机上切「电影 / 剧集 / 动画」，下面的海报墙还停在原来那一屏
- 原因：`AbsListView.setSelection()` 里

  ```java
  if (!isInTouchMode()) setNextSelectedPositionInt(position);
  else mResurrectToPosition = position;   // 只记了个"待恢复位置"，不触发滚动
  ```

  触摸模式下它根本不滚，TV 上才有效
- 解法：`grid.post { if (isInTouchMode()) smoothScrollToPosition(0) else setSelection(0) }`。
  必须 `post` 到下一帧，让新数据先完成布局，否则会被 `notifyDataSetChanged` 的重排覆盖

### P-14 图标导出偏移（原 PROJECT.md §19.6，管线级 bug，别再犯）

- 原因：`tools/fetch_material_icons.py` 的链路是 SVG → svglib 转 PDF → pdfium 光栅化。PDF 页面尺寸和图形 bbox 不一致，光栅化后**字形落在画布偏左下**：1.4.4 实测 96px 画布偏 (-12, +12)，缩到 72px 就是 (-9, +9)，即 1/8 画幅全错位。所有图标（返回、搜索、记录、刷新、设置、播放系列）都受影响
- 修法：渲染完先按 alpha bbox 裁出来、贴回画布正中再接缩放（`center_glyph()`），只改位置不改尺寸——Material 各图标的相对大小是设计好的，按 bbox 缩放会让 `play_arrow` 这类形状失衡
- 配套自检 `tools/check_icons.py`：偏差 ≤2% 画幅、字形占 40%~92%

### P-15 子项持焦后网格不滚动——「海报墙只能看第一屏」（2026-11-08 海信 4.4.2 实锤）

- 现象：首页 / 搜索页海报墙，焦点能在第一屏里上下左右走，但走到屏幕上最下面那一行后再按 ↓ 毫无反应，
  网格不往下滚，第二屏内容永远看不到（遥控器左右键正常、OK 能进详情）
- 原因：卡片是 `focusable` 的，方向键落在卡片的 `View.onKeyDown` 上没人处理 → 交给系统 FocusFinder。
  FocusFinder 只在**已经 layout 出来的子视图**里找候选，而 `GridView` 只给可见区域建视图
  （`fillDown` 的循环条件是 `nextTop < end`）。焦点在最后一整行时，下一行的 top 已经 ≥ end、
  视图压根不存在 → 找不到任何候选 → 焦点原地不动、容器自然也不会滚
- **不是写法错**，是「子项持焦 + AbsListView」的固有行为，4.4 上尤其明显；`listSelector` 又是透明的，
  容器不持焦也没有兜底高亮，所以现象就是「按了没反应」
- **注意返工教训**：1.4.9~1.4.12 修的是「焦点能不能落到卡片上」（`FocusKit` 前三步），
  完全没覆盖「落到卡片上之后还能不能继续往下走」——同一个页面被反馈两次，第二次才补上
- 解法（`FocusKit.handleGridKey`，1.4.13 起草、1.4.14 修正落点）：方向键自己接管
  1. 算目标格（↓ +列数 / ↑ -列数 / ←→ ±1，撞行首行尾返回 `false`）；
  2. `child.requestFocus()`，再用 `reveal()` 把它整张滚进可见区；
  3. 目标格还没建视图 → 先按「一行高度」滚一次，滚完再转交焦点（`postDelayed` 分次重试，
     滚动是动画，视图要滚进去才会被 layout）；
  4. 越界（↓ 到底 / ↑ 到第一行）返回 `false` 交回系统，`nextFocusUp` 回顶栏的链路不变
- **监听器必须挂在「卡片」上，挂在 GridView 上完全无效**（1.4.13 就是这么翻车的，用户当晚反馈"还是不能下翻"）：
  `ViewGroup.dispatchKeyEvent` 在子视图持焦时只做 `mFocused.dispatchKeyEvent(event)`，
  拿到结果直接 return，**再也不会调用容器自己的 `OnKeyListener` / `onKeyDown`**。
  正确落点是每张卡（`FocusKit.bindCardKey(grid, card, 页面自己的监听器)`），
  再在三个页面的 `dispatchKeyEvent` 里加一层同样的兜底（个别 ROM 派发路径不同）
- 配套细节：行高按「屏幕上两行的 top 差」实测，**不要读 dimen**（首页 16dp、记录页 space_md 各不同）；
  `smoothScrollBy(int distance, int duration)` 的 duration 是 `int`，写成 long 直接编译失败
