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

import com.vodtv.CrashLog;
import com.vodtv.R;
import com.vodtv.data.HistoryStore;
import com.vodtv.data.ImageLoader;
import com.vodtv.data.Prefs;
import com.vodtv.data.RankItem;
import com.vodtv.data.RankRepository;

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

    /* 继续观看横条 */
    private LinearLayout historyBlock;
    private LinearLayout historyRow;
    private final List<View> historyCards = new ArrayList<View>();
    /** 横条末尾的「全部记录」入口卡 */
    private TextView btnAllHistory;
    private TextView historyCount;

    private RankRepository.RankData data;
    private String group = null;          // null = 全部
    private String boardId = null;        // 当前分类下的子榜
    private PosterAdapter adapter;
    private boolean updating = false;
    private int posterHeight = 0;         // 按列宽动态算，避免不同分辨率下高度失控

    private static final int GRID_COLS = 6;

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
        status = (TextView) findViewById(R.id.status);
        empty = findViewById(R.id.empty);
        btnRefresh = findViewById(R.id.btn_refresh);
        btnSearch = findViewById(R.id.btn_search);
        btnHistory = findViewById(R.id.btn_history);
        btnSettings = findViewById(R.id.btn_settings);
        historyBlock = (LinearLayout) findViewById(R.id.history_block);
        historyRow = (LinearLayout) findViewById(R.id.history_row);

        tabDefs.add(new String[]{"all", "全部"});
        tabDefs.add(new String[]{RankRepository.GROUP_TV, "剧集"});
        tabDefs.add(new String[]{RankRepository.GROUP_MOVIE, "电影"});
        tabDefs.add(new String[]{RankRepository.GROUP_SHOW, "综艺"});
        tabDefs.add(new String[]{RankRepository.GROUP_ANIME, "动画"});
        tabDefs.add(new String[]{RankRepository.GROUP_DOC, "纪录片"});

        buildTabs();

        adapter = new PosterAdapter();
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
        btnAllHistory = (TextView) findViewById(R.id.btn_all_history);
        historyCount = (TextView) findViewById(R.id.history_count);
        btnAllHistory.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(HomeActivity.this, HistoryActivity.class));
            }
        });
        btnHistory.setOnClickListener(new View.OnClickListener() {
            @Override
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
        renderHistory();
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
                            renderStatus();
                            adapter.notifyDataSetChanged();
                            empty.setVisibility(d.total() > 0 ? View.GONE : View.VISIBLE);
                            if (manual) Toast.makeText(HomeActivity.this, "已更新 " + d.total() + " 部", Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }
        });
    }

    private void renderStatus() {
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
        for (int i = 0; i < tabViews.size(); i++) {
            TextView cur = tabViews.get(i);
            int left = (i == 0) ? tabViews.size() - 1 : i - 1;
            int right = (i == tabViews.size() - 1) ? 0 : i + 1;
            cur.setNextFocusLeftId(tabViews.get(left).getId());
            cur.setNextFocusRightId(tabViews.get(right).getId());
            cur.setNextFocusDownId(R.id.grid);
        }
        if (!tabViews.isEmpty()) {
            grid.setNextFocusUpId(tabViews.get(0).getId());
            // 顶栏右侧是一排图标：分类最右 → 搜索 → 记录 → 更新 → 设置 → 回到分类最左
            View lastTab = tabViews.get(tabViews.size() - 1);
            btnSearch.setNextFocusLeftId(lastTab.getId());
            btnSearch.setNextFocusRightId(btnHistory.getId());
            btnHistory.setNextFocusLeftId(btnSearch.getId());
            btnHistory.setNextFocusRightId(btnRefresh.getId());
            btnRefresh.setNextFocusLeftId(btnHistory.getId());
            btnRefresh.setNextFocusRightId(btnSettings.getId());
            btnSettings.setNextFocusLeftId(btnRefresh.getId());
            btnSettings.setNextFocusRightId(tabViews.get(0).getId());
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
            // 同一个分类再按一次也重绑一遍，避免界面偶发没跟上
            adapter.notifyDataSetChanged();
            grid.invalidateViews();
            return;
        }
        currentTab = idx;

        String g = tabDefs.get(idx)[0];
        group = "all".equals(g) ? null : g;
        boardId = null;

        // 从网格往上走时焦点回到当前 tab（有历史横条时先经过横条）
        updateFocusChain();

        adapter.notifyDataSetChanged();
        grid.invalidateViews();
        scrollGridToTop();

        empty.setVisibility(data != null && currentItems().isEmpty() ? View.VISIBLE : View.GONE);
        renderStatus();
    }

    /**
     * 切分类后回到第一行。
     *
     * 不能直接用 grid.setSelection(0)：AbsListView 的实现里，
     * 触摸模式下它只把位置记进 mResurrectToPosition，不触发滚动——
     * 手机上表现为「切了 Tab，列表还停在原来那一屏」。
     * 所以触摸模式必须走 smoothScrollToPosition，且要 post 到下一帧让新数据先完成布局。
     */
    private void scrollGridToTop() {
        grid.post(new Runnable() {
            @Override
            public void run() {
                if (grid.isInTouchMode()) {
                    grid.smoothScrollToPosition(0);
                } else {
                    grid.setSelection(0);
                }
            }
        });
    }

    /* ---------- 列表 ---------- */

    private List<RankItem> currentItems() {
        List<RankItem> out = new ArrayList<RankItem>();
        if (data == null) return out;

        if (group == null) {
            // 全部：每个分组取主榜前 8，拼成一面墙
            for (String g : data.groups()) {
                List<RankRepository.Board> bs = data.boardsOf(g);
                if (bs.isEmpty()) continue;
                List<RankItem> items = bs.get(0).items;
                out.addAll(items.subList(0, Math.min(8, items.size())));
            }
            return out;
        }

        if (boardId != null) {
            RankRepository.Board b = data.board(boardId);
            if (b != null) return b.items;
        }
        List<RankRepository.Board> bs = data.boardsOf(group);
        if (bs.isEmpty()) return out;
        return bs.get(0).items;
    }

    /** 带防重复的入口：条目自身的按键监听和 onClick 可能都会触发 */
    private void openDetailAt(int position) {
        if (opening) return;
        opening = true;
        openDetail(adapter.getItem(position));
    }

    /* ---------- 继续观看 ---------- */

    /**
     * 横条数据完全独立于榜单：榜单每天在变，历史片可能早就掉出去了，
     * 所以每条记录里存了一份影片信息快照（见 HistoryStore.Entry.info）。
     */
    private void renderHistory() {
        if (historyRow == null) return;
        historyRow.removeAllViews();
        historyCards.clear();

        List<HistoryStore.Entry> list = HistoryStore.recent(10);
        if (list.isEmpty()) {
            historyBlock.setVisibility(View.GONE);
            updateFocusChain();
            return;
        }

        historyBlock.setVisibility(View.VISIBLE);
        for (final HistoryStore.Entry e : list) {
            View card = LayoutInflater.from(this).inflate(R.layout.item_history, historyRow, false);
            bindHistoryCard(card, e);
            // 先分配 id，下面串焦点链要用
            card.setId(View.generateViewId());
            card.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openFromHistory(e);
                }
            });
            // 卡片自身 focusable，OK 键会被它吃掉，所以按键也得在这里兜住
            card.setOnKeyListener(new View.OnKeyListener() {
                @Override
                public boolean onKey(View v, int keyCode, KeyEvent event) {
                    if (event.getAction() == KeyEvent.ACTION_UP && isConfirmKey(keyCode)) {
                        openFromHistory(e);
                        return true;
                    }
                    return false;
                }
            });

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(12);
            historyRow.addView(card, lp);
            historyCards.add(card);
        }

        // 「全部记录」入口改到标题行右侧（尾卡方案已移除，横条只留海报）
        if (historyCount != null) {
            historyCount.setText(getString(R.string.history_count,
                    HistoryStore.all(HistoryStore.MAX_ENTRIES).size()));
        }

        updateFocusChain();
    }

    private void bindHistoryCard(View card, HistoryStore.Entry e) {
        ImageLoader.load((ImageView) card.findViewById(R.id.poster), e.info.pic, 200);
        ((TextView) card.findViewById(R.id.h_title)).setText(e.title);

        StringBuilder sub = new StringBuilder();
        if (e.ep != null && e.ep.length() > 0) sub.append(e.ep).append("  ·  ");
        sub.append(e.posText());
        if (e.dur > 0) sub.append(" / ").append(e.durText());
        ((TextView) card.findViewById(R.id.h_sub)).setText(sub.toString());

        int pct = Math.max(0, Math.min(100, e.percent()));
        View done = card.findViewById(R.id.h_bar_done);
        View rest = card.findViewById(R.id.h_bar_rest);
        LinearLayout.LayoutParams a = (LinearLayout.LayoutParams) done.getLayoutParams();
        LinearLayout.LayoutParams b = (LinearLayout.LayoutParams) rest.getLayoutParams();
        a.weight = Math.max(1, pct);
        b.weight = Math.max(1, 100 - pct);
        done.setLayoutParams(a);
        rest.setLayoutParams(b);
        // 时长未知时没有百分比可算，与其画一条全灰的条，不如不画
        done.setVisibility(e.dur > 0 ? View.VISIBLE : View.GONE);
    }

    /** 从历史卡进详情：带整份影片信息，并让详情页把焦点直接停在「继续看」上 */
    private void openFromHistory(HistoryStore.Entry e) {
        if (opening) return;
        opening = true;
        Intent i = new Intent(this, DetailActivity.class);
        e.info.toIntent(i);
        i.putExtra("title", e.title);
        i.putExtra("autoResume", true);
        startActivity(i);
    }

    /**
     * 焦点链：tab ↓ 历史首卡 ↓ 海报墙，海报墙 ↑ 历史首卡 ↑ 当前 tab。
     * 全部显式指定，不依赖 FocusFinder 猜。
     */
    private void updateFocusChain() {
        int tabId = (currentTab >= 0 && currentTab < tabViews.size())
                ? tabViews.get(currentTab).getId() : View.NO_ID;
        boolean hasHistory = !historyCards.isEmpty();

        if (hasHistory) {
            for (int i = 0; i < historyCards.size(); i++) {
                View c = historyCards.get(i);
                if (tabId != View.NO_ID) c.setNextFocusUpId(tabId);
                c.setNextFocusDownId(R.id.grid);
                if (i > 0) c.setNextFocusLeftId(historyCards.get(i - 1).getId());
                if (i < historyCards.size() - 1) c.setNextFocusRightId(historyCards.get(i + 1).getId());
            }
            // 横条最后一张按 ↑ 直达标题行右侧的「全部 ›」，再 ↓ 回到第一张
            View last = historyCards.get(historyCards.size() - 1);
            if (btnAllHistory != null) {
                last.setNextFocusUpId(btnAllHistory.getId());
                btnAllHistory.setNextFocusDownId(historyCards.get(0).getId());
            }
            grid.setNextFocusUpId(historyCards.get(0).getId());
        } else if (tabId != View.NO_ID) {
            grid.setNextFocusUpId(tabId);
        }

        int downId = hasHistory ? historyCards.get(0).getId() : R.id.grid;
        for (TextView t : tabViews) t.setNextFocusDownId(downId);
        if (btnSearch != null) btnSearch.setNextFocusDownId(downId);
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
            sub.setText(group == null
                    ? RankRepository.groupLabel(it.group) + " · " + it.metaLine()
                    : it.metaLine());

            if (it.rating > 0) {
                score.setVisibility(View.VISIBLE);
                score.setText(String.format(Locale.US, "%.1f", it.rating));
            } else {
                score.setVisibility(View.GONE);
            }

            if (position < 3) {
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

            v.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View view, boolean has) {
                    float s = has ? 1.06f : 1.0f;
                    view.animate().scaleX(s).scaleY(s).setDuration(120).start();
                }
            });

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

            // 复用时要复位，否则滑出屏幕的卡片会保持放大状态
            v.setScaleX(v.isFocused() ? 1.06f : 1.0f);
            v.setScaleY(v.isFocused() ? 1.06f : 1.0f);

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
        // 从播放页回来进度变了、或刚在记录页删了记录，横条要跟着变
        renderHistory();
        // 从设置页回来时源配置可能变了，刷新一下状态栏文案即可
        renderStatus();
    }
}
