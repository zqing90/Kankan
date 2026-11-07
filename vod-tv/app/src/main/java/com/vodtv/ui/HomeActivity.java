package com.vodtv.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.vodtv.CrashLog;import com.vodtv.R;
import com.vodtv.data.ImageLoader;
import com.vodtv.data.Prefs;
import com.vodtv.data.RankItem;
import com.vodtv.data.RankRepository;
import com.vodtv.widget.FocusKit;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 首页：海报墙。
 *
 * 遥控器交互：
 *   左右 = 切换分类 / 在网格里移动
 *   OK   = 进详情
 *   MENU = 直接进设置
 *   顶部「更新」= 强制刷新榜单
 */
public class HomeActivity extends Activity {

    private GridView grid;
    private LinearLayout tabs;
    private TextView status;
    private View empty;
    private View btnRefresh;
    private View btnSearch;
    private View btnHistory;
    private View btnSettings;

    private RankRepository.RankData data;
    private PosterAdapter adapter;
    private boolean updating = false;
    private int posterHeight = 0;         // 按列宽动态算，避免不同分辨率下高度失控
    /** 上一次真正持有焦点的卡片位置：从 tab 按 ↓ 回到海报墙时还停在原地，不弹回第一张 */
    private int lastFocusedPos = 0;
    /** 给 FocusKit 读「上次停在哪张」用，写成字段避免每次按键都 new 一个匿名类 */
    private final FocusKit.Position lastPosRef = new FocusKit.Position() {
        @Override
        public int get() {
            return lastFocusedPos;
        }
    };

    /** tab 0：全部榜单合并去重后的列表（data 一变就重建） */
    private List<RankItem> hotAll = new ArrayList<RankItem>();

    /* ---------- 找一找（tab 2） ---------- */
    private View findBar;
    private LinearLayout findRow;
    private String findTags = "";         // ""=全部，或 电影/电视剧/综艺/动画/纪录片
    private String findYear = "all";      // all/cur/prev/older
    private String findSort = "U";        // U=最热 S=高分 R=最新
    private String findApplied = null;    // 当前结果对应的筛选组合 key
    private boolean findLoading = false;
    private boolean findHasMore = false;
    /** 筛选组合 → 已加载的结果（含已翻的页），切回来不用重查 */
    private final java.util.HashMap<String, FindResult> findCache = new java.util.HashMap<String, FindResult>();

    /** 一组筛选条件下的已加载结果 */
    private static class FindResult {
        final List<RankItem> items = new ArrayList<RankItem>();
        int nextStart = 0;
        boolean hasMore = true;
    }

    private static final String[][] FIND_TYPES = {
            {"", "全部"}, {"电影", "电影"}, {"电视剧", "电视剧"},
            {"综艺", "综艺"}, {"动画", "动画"}, {"纪录片", "纪录片"},
    };
    private static final String[][] FIND_YEARS = {
            {"all", "全部"}, {"cur", "今年"}, {"prev", "去年"}, {"older", "更早"},
    };
    private static final String[][] FIND_SORTS = {
            {"U", "最热"}, {"S", "高分"}, {"R", "最新"},
    };

    /**
     * 列数按**物理像素**算，不读 density、也不读 values 里的 grid_cols。
     *
     * 原因：电视面板的 densityDpi 各家乱报，同一个 1920x1080 面板有报 160 的、
     * 也有报 320 的。报 320 时屏幕只有 540dp 宽，连 values-w520dp 的 7 列也会
     * 被算成「每张 150dp」，而报 160 时又变成每张 274dp —— 看着完全不是同一个 App。
     * 物理像素是唯一稳定的量：1920 宽就摆 7 张（每张约 250px），3 米外一行 7 个正好。
     *
     * 手机竖屏（1080x2340 这类）宽高比不满足下面任何一档，直接走 values/integers.xml。
     */
    private int resolveColumns(int widthPx, int heightPx) {
        // 只有「横向大屏」才按像素加列数；竖屏手机哪怕 1440px 宽，宽 < 高，不受影响
        boolean wide = widthPx > heightPx;
        if (wide) {
            if (widthPx >= 3200) return 11;   // 4K
            if (widthPx >= 2560) return 9;    // 2K
            if (widthPx >= 1920) return 7;    // 1080p
            if (widthPx >= 1600) return 6;
            if (widthPx >= 1280) return 5;    // 720p
        }
        return getResources().getInteger(R.integer.grid_cols);
    }

    private final List<String[]> tabDefs = new ArrayList<String[]>();
    /** 和 tabDefs 一一对应的 TextView，用来控制焦点链和选中态 */
    private final List<TextView> tabViews = new ArrayList<TextView>();
    private int currentTab = -1;

    /** 遥控器 OK / 确认键：一口气认全，不同遥控器报的键值不一样 */
    private static boolean isConfirmKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    /**
     * 防重复打开：一次按键会同时走条目的 onKey 和 onClick 两条路。
     * 用布尔量而不是时间窗——时间窗会连「从详情页返回后马上再点」一起吞掉，
     * 表现就变成"要按两次"。onResume 里复位即可。
     */
    private boolean opening = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        ImageLoader.init(this);

        grid = (GridView) findViewById(R.id.grid);
        tabs = (LinearLayout) findViewById(R.id.tabs);
        findBar = findViewById(R.id.find_bar);
        findRow = (LinearLayout) findViewById(R.id.find_row);
        status = (TextView) findViewById(R.id.status);
        empty = findViewById(R.id.empty);
        btnRefresh = findViewById(R.id.btn_refresh);
        btnSearch = findViewById(R.id.btn_search);
        btnHistory = findViewById(R.id.btn_history);
        btnSettings = findViewById(R.id.btn_settings);

        tabDefs.add(new String[]{"hot", "豆瓣热榜"});
        tabDefs.add(new String[]{"new", "新品速递"});
        tabDefs.add(new String[]{"find", "找一找"});

        buildTabs();
        buildFindBar();
        // 四个顶栏图标都要有「看得见」的焦点态，否则遥控器选过去了也没反馈
        FocusKit.iconFx(btnSearch);
        FocusKit.iconFx(btnHistory);
        FocusKit.iconFx(btnRefresh);
        FocusKit.iconFx(btnSettings);

        adapter = new PosterAdapter();
        // 列数必须在 setAdapter 之前定：GridView 在首次布局时才按 numColumns 摊格子，
        // 之后改列数要重建适配器才生效。
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        grid.setNumColumns(resolveColumns(dm.widthPixels, dm.heightPixels));
        grid.setAdapter(adapter);
        grid.post(new Runnable() {
            @Override
            public void run() {
                // getColumnWidth / getHorizontalSpacing 都是 API 16 的方法，4.0/4.1 上会 NoSuchMethodError，
                // 所以列宽和间距按布局里的 @dimen/card_gap_h 自己算
                int cols = grid.getNumColumns();
                int screenW = getResources().getDisplayMetrics().widthPixels;
                int gap = getResources().getDimensionPixelSize(R.dimen.card_gap_h);
                int usable = screenW - grid.getPaddingLeft() - grid.getPaddingRight()
                        - gap * (cols - 1);
                int colW = usable / cols;
                if (colW > 0) {
                    // 卡片根布局左右各 2dp padding（给焦点描边留位置），海报宽要减掉这 4dp
                    posterHeight = (int) ((colW - dp(4)) * 1.5f); // 2:3 海报
                    adapter.notifyDataSetChanged();
                }
            }
        });
        grid.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                openDetailAt(position);
            }
        });

        // 焦点必须落在「卡片」上，不能停在 GridView 容器上 —— 三道保险的实现在 FocusKit，
        // 搜索页、观看记录页共用同一份（2026-11-07 三个页面在电视上症状完全一样）。
        FocusKit.bindGrid(grid, lastPosRef);

        btnRefresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                forceUpdate();
            }
        });
        btnSearch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(HomeActivity.this, SearchActivity.class));
            }
        });
        // 顶栏「记录」图标 → 观看记录页（首页不再放「继续观看」横条）
        btnHistory.setOnClickListener(new View.OnClickListener() {            @Override
            public void onClick(View v) {
                startActivity(new Intent(HomeActivity.this, HistoryActivity.class));
            }
        });
        btnSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(HomeActivity.this, SettingsActivity.class));
            }
        });

        loadCacheThenMaybeRefresh();
        selectTab(0);
        showLastCrashIfAny();
    }

    /** 上一次崩溃了就把堆栈弹出来，方便在电视上直接看/截图 */
    private void showLastCrashIfAny() {
        final String trace = CrashLog.read(this);
        if (trace == null) return;

        TextView tv = new TextView(this);
        tv.setText(trace);
        tv.setTextSize(11);
        tv.setTextColor(0xFFF2F4F8);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(16), dp(12), dp(16), dp(12));

        ScrollView sv = new ScrollView(this);
        sv.addView(tv);

        new AlertDialog.Builder(this)
                .setTitle("上次崩溃了，这是现场")
                .setView(sv)
                .setPositiveButton("清除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        CrashLog.clear(HomeActivity.this);
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    /* ---------- 数据 ---------- */

    private void loadCacheThenMaybeRefresh() {
        data = RankRepository.loadCache(this);
        rebuildHotAll();
        if (data != null && data.boards.size() > 0) {
            renderStatus();
            adapter.notifyDataSetChanged();
            empty.setVisibility(View.GONE);
        } else {
            status.setText("首次使用，正在拉取榜单…");
        }

        if (data == null || Prefs.needDailyUpdate()) {
            doRefresh(false);
        } else {
            renderStatus();
        }
    }

    private void forceUpdate() {
        if (updating) return;
        doRefresh(true);
    }

    private void doRefresh(final boolean manual) {
        if (updating) return;
        updating = true;
        status.setText(manual ? "正在强制更新榜单…" : "正在更新今日榜单…");
        if (manual) Toast.makeText(this, "开始更新", Toast.LENGTH_SHORT).show();

        RankRepository.refreshAsync(this, new RankRepository.Listener() {
            @Override
            public void onProgress(String msg) {
            }

            @Override
            public void onDone(final RankRepository.RankData d, final String error) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        updating = false;
                        if (error != null) {
                            status.setText("更新失败：" + error + "（显示的是上次数据）");
                            if (manual) Toast.makeText(HomeActivity.this, "更新失败：" + error, Toast.LENGTH_LONG).show();
                        } else {
                            data = d;
                            rebuildHotAll();
                            renderStatus();
                            adapter.notifyDataSetChanged();
                            empty.setVisibility(currentItems().isEmpty() ? View.VISIBLE : View.GONE);
                            if (manual) Toast.makeText(HomeActivity.this, "已更新 " + d.total() + " 部", Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }
        });
    }

    private void renderStatus() {
        if (currentTab == 2) {
            // 「找一找」不依赖主榜数据，data 为 null 也要能显示
            status.setText("找一找 · " + describeFind() + " · " + currentItems().size()
                    + " 部" + (findLoading ? " · 正在加载…" : ""));
            return;
        }
        if (data == null) return;
        String t = data.fetchedAt > 0
                ? new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date(data.fetchedAt))
                : "未知";
        String mode = Prefs.useServer() ? "服务端" : "豆瓣直连";
        String tab = (currentTab >= 0 && currentTab < tabDefs.size()) ? tabDefs.get(currentTab)[1] : "全部";
        status.setText(tab + " · " + currentItems().size() + " 部 · " + data.boards.size()
                + " 个榜单 · " + mode + " · 更新于 " + t);
    }

    /* ---------- 分类 ---------- */

    /** data 一变就重建「豆瓣热榜(全部)」：17 个榜按序拼接、同片去重（保留第一次出现的榜位） */
    private void rebuildHotAll() {
        hotAll = new ArrayList<RankItem>();
        if (data == null) return;
        java.util.HashSet<String> seen = new java.util.HashSet<String>();
        for (RankRepository.Board b : data.boards) {
            if (RankRepository.GROUP_NEW.equals(b.group)) continue;   // 新品速递不混进热榜
            for (RankItem it : b.items) {
                if (it == null || it.title == null || it.title.length() == 0) continue;
                String key = it.title.trim();
                if (seen.add(key)) hotAll.add(it);
            }
        }
    }

    /* ---------- 找一找 ---------- */

    private String findKey() {
        return findTags + "|" + findYear + "|" + findSort;
    }

    private FindResult findState() {
        FindResult st = findCache.get(findKey());
        if (st == null) {
            st = new FindResult();
            findCache.put(findKey(), st);
        }
        return st;
    }

    /** 组装筛选条：类型 / 年份 / 排序各占一行，行间留 4dp 间距 */
    private void buildFindBar() {
        findRow.removeAllViews();
        addChipGroup("类型", FIND_TYPES, findTags);
        addChipGroup("年份", FIND_YEARS, findYear);
        addChipGroup("排序", FIND_SORTS, findSort);
    }

    private void addChipGroup(String label, String[][] defs, String current) {
        // 一个条件一行：行本身横向，标签 + 该组 chips 排在行内
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        if (findRow.getChildCount() > 0) rowLp.topMargin = dp(4);
        findRow.addView(row, rowLp);

        TextView lb = new TextView(this);
        lb.setText(label);
        lb.setTextSize(12);
        lb.setTextColor(getResources().getColor(R.color.text_tertiary));
        lb.setPadding(dp(10), 0, dp(4), 0);
        row.addView(lb);

        for (final String[] def : defs) {
            final boolean on = def[0].equals(current);
            TextView c = new TextView(this);
            c.setId(View.generateViewId());
            c.setText(def[1]);
            c.setTextSize(13);
            c.setPadding(dp(14), dp(5), dp(14), dp(5));
            c.setBackgroundResource(R.drawable.bg_tab);
            c.setTextColor(getResources().getColor(on ? R.color.text_primary : R.color.text_secondary));
            c.setSelected(on);
            c.setFocusable(true);
            c.setFocusableInTouchMode(true);
            c.setTag("chip_" + label + "_" + def[0]);
            // 与 tab 一致：焦点扫过去就生效（带防抖 + 结果缓存，扫一排不会连环打接口）
            c.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean has) {
                    if (!has) return;
                    setChipState(def[0], label);
                    scheduleFindApply();
                }
            });
            c.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    setChipState(def[0], label);
                    applyFind(false);
                }
            });
            c.setOnKeyListener(new View.OnKeyListener() {
                @Override
                public boolean onKey(View v, int keyCode, KeyEvent event) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        // 末行（排序）的 ↓ 才进网格；上面的行走默认焦点搜索，落到下一行的 chip
                        View row = (View) v.getParent();
                        if (findRow.getChildAt(findRow.getChildCount() - 1) == row) {
                            FocusKit.focusGrid(grid, lastPosRef);
                            return true;
                        }
                        return false;
                    }
                    return false;
                }
            });
            row.addView(c);
        }
    }

    /** 视觉选中态：原地更新同组 chips，不重建视图——重建会把焦点一起销毁，遥控器就断链了 */
    private void setChipState(String value, String groupLabel) {
        if ("类型".equals(groupLabel)) findTags = value;
        else if ("年份".equals(groupLabel)) findYear = value;
        else if ("排序".equals(groupLabel)) findSort = value;

        // find_row 下是三行 LinearLayout，chip 都在行里，两级遍历
        for (int r = 0; r < findRow.getChildCount(); r++) {
            View row = findRow.getChildAt(r);
            if (!(row instanceof ViewGroup)) continue;
            ViewGroup g = (ViewGroup) row;
            for (int i = 0; i < g.getChildCount(); i++) {
                View v = g.getChildAt(i);
                Object tag = v.getTag();
                if (!(v instanceof TextView) || tag == null) continue;
                String t = String.valueOf(tag);
                if (!t.startsWith("chip_" + groupLabel + "_")) continue;
                String chipValue = t.substring(("chip_" + groupLabel + "_").length());
                boolean on = chipValue.equals(value);
                v.setSelected(on);
                ((TextView) v).setTextColor(getResources().getColor(
                        on ? R.color.text_primary : R.color.text_secondary));
            }
        }
    }

    /** 焦点扫过 chips 时防抖：停 300ms 才真正发请求 */
    private final Runnable findPending = new Runnable() {
        @Override
        public void run() {
            applyFind(false);
        }
    };

    private void scheduleFindApply() {
        grid.postDelayed(findPending, 300);
    }

    /** 按当前筛选查第一页。silent=true 时失败不弹 Toast（切 tab 自动触发的那次） */
    private void applyFind(final boolean silent) {
        grid.removeCallbacks(findPending);
        final FindResult target = findState();   // 钉住发起时的筛选组：请求回来前用户可能又换了条件
        findLoading = true;
        renderStatus();

        new Thread(new Runnable() {
            @Override
            public void run() {
                List<RankItem> result;
                try {
                    result = RankRepository.find(findTags, findYear, findSort, 0);
                } catch (Exception e) {
                    result = null;
                }
                final List<RankItem> fResult = result;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        findLoading = false;
                        if (fResult == null) {
                            if (!silent) Toast.makeText(HomeActivity.this, "搜索失败，请稍后再试", Toast.LENGTH_SHORT).show();
                            renderStatus();
                            return;
                        }
                        target.items.clear();
                        target.items.addAll(fResult);
                        target.nextStart = fResult.size();
                        target.hasMore = fResult.size() >= 20;
                        adapter.notifyDataSetChanged();
                        grid.invalidateViews();
                        empty.setVisibility(currentItems().isEmpty() ? View.VISIBLE : View.GONE);
                        renderStatus();
                    }
                });
            }
        }, "find-search").start();
    }

    /** 滚到最后几行时追加下一页 */
    private void loadMoreFind() {
        final FindResult st = findState();
        if (findLoading || !st.hasMore || st.nextStart == 0) return;
        findLoading = true;
        renderStatus();
        final int start = st.nextStart;

        new Thread(new Runnable() {
            @Override
            public void run() {
                List<RankItem> page;
                try {
                    // 用户可能停在旧筛选上，用发起时的 key 存回对应那组
                    page = RankRepository.find(findTags, findYear, findSort, start);
                } catch (Exception e) {
                    page = null;
                }
                final List<RankItem> fPage = page;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        findLoading = false;
                        if (fPage != null && !fPage.isEmpty()) {
                            java.util.HashSet<String> seen = new java.util.HashSet<String>();
                            for (RankItem it : st.items) seen.add(it.title.trim());
                            for (RankItem it : fPage) {
                                if (seen.add(it.title.trim())) st.items.add(it);
                            }
                            st.nextStart = start + fPage.size();
                            st.hasMore = fPage.size() >= 20;
                        } else {
                            st.hasMore = false;   // 没有更多了 / 失败就不再自动重试
                        }
                        adapter.notifyDataSetChanged();
                        renderStatus();
                    }
                });
            }
        }, "find-more").start();
    }

    private String describeFind() {
        String ty = "全部", yr = "全部", so = "最热";
        for (String[] d : FIND_TYPES) if (d[0].equals(findTags)) ty = d[1];
        for (String[] d : FIND_YEARS) if (d[0].equals(findYear)) yr = d[1];
        for (String[] d : FIND_SORTS) if (d[0].equals(findSort)) so = d[1];
        return ty + " · " + yr + " · " + so;
    }


    private void buildTabs() {
        tabs.removeAllViews();
        tabViews.clear();

        for (int i = 0; i < tabDefs.size(); i++) {
            final int idx = i;
            TextView tv = new TextView(this);
            tv.setId(View.generateViewId());
            tv.setText(tabDefs.get(i)[1]);
            tv.setTextColor(getResources().getColor(R.color.text_secondary));
            tv.setTextSize(15);
            tv.setPadding(dp(18), dp(7), dp(18), dp(7));
            tv.setBackgroundResource(R.drawable.bg_tab);
            tv.setFocusable(true);
            tv.setFocusableInTouchMode(true);
            tv.setTag("tab_" + i);

            // 焦点一落到某个 tab 就立刻切分类。遥控器左右扫过去内容就跟着变，
            // 不用再按 OK —— 很多 ROM 上 D-pad 的确认键根本传不到这里。
            tv.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    if (hasFocus) selectTab(idx);
                }
            });
            // 触摸屏 / 鼠标
            tv.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectTab(idx);
                }
            });
            // 遥控器确认键兜底：直接吃按键，不依赖 clickable 语义
            tv.setOnKeyListener(new View.OnKeyListener() {
                @Override
                public boolean onKey(View v, int keyCode, KeyEvent event) {
                    if (event.getAction() == KeyEvent.ACTION_UP && isConfirmKey(keyCode)) {
                        selectTab(idx);
                        return true;
                    }
                    return false;
                }
            });

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(6);
            tabs.addView(tv, lp);
            tabViews.add(tv);
        }

        // 显式把焦点链串起来，别指望 FocusFinder 自己猜（容器本身不可聚焦）
        bindTopbarFocus();
        grid.setNextFocusUpId(tabViews.get(0).getId());
    }

    /**
     * 顶栏一行的焦点闭环：
     * <pre>
     *   tab0 ←→ tab1 ←→ tab2（找一找） → 搜索 → 记录 → 更新 → 设置 → 回到 tab0
     *   每个节点的 ↑ 都钉在自己身上（已经是最上面一行了）；↓ 一律回海报墙
     * </pre>
     *
     * ★ 踩过的坑（2026-11-07 用户实测）：旧版为了让左右能在 tab 间环绕，
     *   把最后一个 tab 的 nextFocusRight 设成了 tab0。结果整行被 tab 封成环，
     *   遥控器按 → 只会在「豆瓣热榜 / 新品速递 / 找一找」里转圈，
     *   右边那排搜索、记录、更新、设置**永远选不中**（它们只设了 nextFocusLeft，
     *   而焦点移动只认「当前控件」的 nextFocusX，不认目标控件的）。
     *   现在环绕改挂在两端：tab0 的 ← 去设置、设置的 → 回 tab0，
     *   中间是单向直链，遥控器才能一路走到头。
     */
    private void bindTopbarFocus() {
        final int n = tabViews.size();
        if (n == 0) return;

        final View[] icons = {btnSearch, btnHistory, btnRefresh, btnSettings};

        for (int i = 0; i < n; i++) {
            TextView cur = tabViews.get(i);
            cur.setNextFocusLeftId(i == 0 ? btnSettings.getId() : tabViews.get(i - 1).getId());
            cur.setNextFocusRightId(i == n - 1 ? btnSearch.getId() : tabViews.get(i + 1).getId());
            cur.setNextFocusDownId(R.id.grid);
            cur.setNextFocusUpId(cur.getId());       // 顶行，再往上没有了：钉住自己，别让焦点乱跳
        }

        for (int i = 0; i < icons.length; i++) {
            View v = icons[i];
            v.setNextFocusLeftId(i == 0 ? tabViews.get(n - 1).getId() : icons[i - 1].getId());
            v.setNextFocusRightId(i == icons.length - 1 ? tabViews.get(0).getId() : icons[i + 1].getId());
            v.setNextFocusUpId(v.getId());
            v.setNextFocusDownId(R.id.grid);
        }
    }

    private void selectTab(int idx) {
        if (idx < 0 || idx >= tabDefs.size()) return;

        for (int i = 0; i < tabViews.size(); i++) {
            boolean on = i == idx;
            tabViews.get(i).setSelected(on);
            tabViews.get(i).setTextColor(getResources().getColor(
                    on ? R.color.text_primary : R.color.text_secondary));
        }

        if (currentTab == idx) {
            // 同一个分类再按一次也重绑一遍，避免界面偶发没跟上；
            // 顺手回到顶部，「再点一次当前 Tab 回顶」是通用交互
            adapter.notifyDataSetChanged();
            grid.invalidateViews();
            scrollGridToTop();
            return;
        }
        currentTab = idx;

        findBar.setVisibility(idx == 2 ? View.VISIBLE : View.GONE);
        // 首次进「找一找」且这组条件还没查过，先按默认条件查一轮
        if (idx == 2) {
            FindResult st = findState();
            if (st.items.isEmpty() && st.nextStart == 0 && !findLoading) applyFind(false);
        }

        // 从网格往上走时焦点回到当前 tab
        updateFocusChain();

        adapter.notifyDataSetChanged();
        grid.invalidateViews();
        scrollGridToTop();

        empty.setVisibility(currentItems().isEmpty() ? View.VISIBLE : View.GONE);
        renderStatus();
    }

    /**
     * 切分类后回到第一行。
     *
     * 三个坑叠在一起，所以写法不能省：
     * 1. {@code setSelection(0)} 在触摸模式只记住位置、不滚动（AbsListView 的既定行为）；
     * 2. 数据刚 notify 过，layoutChildren 会走 handleDataChanged() 把上一个分类的
     *    可见位置「恢复」回来——这就是「切了 Tab 还停在原来那一屏」的直接原因；
     * 3. {@code setSelectionFromTop} 是 API 21 才有的（lint 会拦，minSdk 19 用不了）。
     *
     * 所以先用 setSelection(0) 把待恢复的位置清掉，再在下一帧用
     * smoothScrollToPositionFromTop（API 11，内部 PositionScroller 会等布局完成再滚）
     * 真正把第 0 项顶到顶部。
     */
    private void scrollGridToTop() {
        grid.setSelection(0);
        grid.post(new Runnable() {
            @Override
            public void run() {
                grid.setSelection(0);
                grid.smoothScrollToPositionFromTop(0, 0);
            }
        });
    }

    /* ---------- 列表 ---------- */

    private List<RankItem> currentItems() {
        if (currentTab == 2) return findState().items;
        if (currentTab == 1) {
            // 新品速递独立于主榜，可能还没拉到
            RankRepository.Board b = data == null ? null : data.board(RankRepository.BOARD_NEW);
            return b == null ? java.util.Collections.<RankItem>emptyList() : b.items;
        }
        return hotAll;
    }

    /** 带防重复的入口：条目自身的按键监听和 onClick 可能都会触发 */
    private void openDetailAt(int position) {
        if (opening) return;
        opening = true;
        openDetail(adapter.getItem(position));
    }

    /**
     * 焦点链：tab ↓ 海报墙，海报墙 ↑ 当前 tab。
     * 全部显式指定，不依赖 FocusFinder 猜。
     */
    private void updateFocusChain() {
        int tabId = (currentTab >= 0 && currentTab < tabViews.size())
                ? tabViews.get(currentTab).getId() : View.NO_ID;

        if (tabId != View.NO_ID) {
            grid.setNextFocusUpId(tabId);
        }

        // 找一找 tab：↓ 先落筛选条第一个 chip（chips 末行的 ↓ 在 onKey 里交给 grid）
        int chipId = firstChipId();
        for (int i = 0; i < tabViews.size(); i++) {
            tabViews.get(i).setNextFocusDownId(
                    (i == 2 && chipId != View.NO_ID) ? chipId : R.id.grid);
        }
        if (btnSearch != null) btnSearch.setNextFocusDownId(R.id.grid);
    }

    private int firstChipId() {
        for (int r = 0; r < findRow.getChildCount(); r++) {
            View rowV = findRow.getChildAt(r);
            if (!(rowV instanceof ViewGroup)) continue;
            ViewGroup g = (ViewGroup) rowV;
            for (int i = 0; i < g.getChildCount(); i++) {
                View v = g.getChildAt(i);
                Object tag = v.getTag();
                if (v.isFocusable() && tag != null && String.valueOf(tag).startsWith("chip_")) {
                    return v.getId();
                }
            }
        }
        return View.NO_ID;
    }

    private void openDetail(RankItem it) {
        if (it == null) return;
        Intent i = new Intent(this, DetailActivity.class);
        i.putExtra("title", it.title);
        i.putExtra("year", it.year);
        i.putExtra("pic", it.picLarge != null ? it.picLarge : it.pic);
        i.putExtra("picSmall", it.pic);
        i.putExtra("comment", it.comment);
        i.putExtra("subtitle", it.subtitle);
        i.putExtra("episodes", it.episodes);
        i.putExtra("rating", it.rating);
        i.putExtra("ratingCount", it.ratingCount);
        i.putExtra("director", it.director);
        i.putExtra("actors", join(it.actors));
        i.putExtra("genres", join(it.genres));
        i.putExtra("url", it.url);
        startActivity(i);
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) sb.append(" / ");
            sb.append(l.get(i));
        }
        return sb.toString();
    }

    /* ---------- 适配器 ---------- */

    private class PosterAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return currentItems().size();
        }

        @Override
        public RankItem getItem(int position) {
            List<RankItem> l = currentItems();
            return position < l.size() ? l.get(position) : null;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            // 「找一找」滚到最后几行自动追加下一页（loadMoreFind 自己防重）
            if (currentTab == 2 && position >= currentItems().size() - 4) loadMoreFind();

            View v = convertView;
            if (v == null) {
                v = LayoutInflater.from(HomeActivity.this).inflate(R.layout.item_poster, parent, false);
            }
            final RankItem it = getItem(position);
            ImageView poster = (ImageView) v.findViewById(R.id.poster);
            TextView title = (TextView) v.findViewById(R.id.title);
            TextView sub = (TextView) v.findViewById(R.id.sub);
            TextView rank = (TextView) v.findViewById(R.id.rank);
            TextView score = (TextView) v.findViewById(R.id.score);

            if (it == null) return v;

            title.setText(it.title);
            // 热榜是多个榜拼的，标注来源榜；新品/找一找本来就是单一列表
            sub.setText(currentTab == 0
                    ? it.boardLabel + " · " + it.metaLine()
                    : it.metaLine());

            if (it.rating > 0) {
                score.setVisibility(View.VISIBLE);
                score.setText(String.format(Locale.US, "%.1f", it.rating));
            } else {
                score.setVisibility(View.GONE);
            }

            // TOP 角标只给有排序意义的两个 tab；找一找是查询结果，没有名次
            if (currentTab != 2 && position < 3) {
                rank.setVisibility(View.VISIBLE);
                rank.setText("TOP " + (position + 1));
                // 三个分支必须都是资源 ID —— 不能把 R.color.gold 和 0xFFC0C8D4 这种
                // 颜色字面量混在一个三元表达式里，否则 getColor() 会当成资源 ID 去查表，
                // 抛 Resources$NotFoundException（第 2、3 名会直接崩溃）。
                int badgeRes = position == 0 ? R.color.gold
                        : position == 1 ? R.color.silver : R.color.bronze;
                // 底统一是 bg_pill 的半透明黑胶囊，只换字色，不再整块铺色
                rank.setTextColor(getResources().getColor(badgeRes));
            } else {
                rank.setVisibility(View.GONE);
            }

            ImageLoader.load(poster, it.pic, dp(260));

            if (posterHeight > 0) {
                ViewGroup.LayoutParams plp = poster.getLayoutParams();
                if (plp.height != posterHeight) {
                    plp.height = posterHeight;
                    poster.setLayoutParams(plp);
                }
            }

            // 焦点反馈全站只有 FocusKit 一份实现（以前三个页面各抄一份，改一处忘两处）；
            // 这里只是额外记下「焦点停在第几张」，从 tab 按 ↓ 回来不再弹回第一张。
            FocusKit.cardFx(v, new Runnable() {
                @Override
                public void run() {
                    lastFocusedPos = position;
                }
            });

            // 第一行的卡片 ↑ 要回「当前分类 tab」。不显式绑的话 FocusFinder 会挑
            // 位置最近的那个 tab（在「找一找」里按 ↑ 可能落回「豆瓣热榜」，内容被顺手切走）。
            // 第二行以后必须放开，否则按 ↑ 会跳过上一行直接飞回顶栏。
            v.setNextFocusUpId(position < grid.getNumColumns() ? grid.getNextFocusUpId() : View.NO_ID);

            // 关键：item_poster 的根布局是 focusable 的，这种情况下 AbsListView 认为
            // 「条目自己会处理按键」，grid.setOnItemClickListener 永远等不到 OK 键，
            // 表现就是「点剧集毫无反应」。所以点击要挂在条目本身上。
            final int pos = position;
            v.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    openDetailAt(pos);
                }
            });
            v.setOnKeyListener(new View.OnKeyListener() {
                @Override
                public boolean onKey(View view, int keyCode, KeyEvent event) {
                    if (event.getAction() == KeyEvent.ACTION_UP && isConfirmKey(keyCode)) {
                        openDetailAt(pos);
                        return true;
                    }
                    return false;
                }
            });

            // 复用时的缩放复位已经由 FocusKit.cardFx 统一处理，不要再在这里抄一份

            return v;
        }
    }

    /* ---------- 通用 ---------- */

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从详情页/设置页回来，允许再次打开
        opening = false;
        // 从设置页回来时源配置可能变了，刷新一下状态栏文案即可
        renderStatus();
    }
}
