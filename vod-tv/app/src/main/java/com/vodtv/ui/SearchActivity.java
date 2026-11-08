package com.vodtv.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.vodtv.R;
import com.vodtv.data.ImageLoader;
import com.vodtv.data.PlayResolver;
import com.vodtv.data.RankItem;
import com.vodtv.data.RankRepository;
import com.vodtv.data.SearchHistory;
import com.vodtv.widget.FocusKit;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 搜索页。
 *
 * 一次搜索 = 两条腿同时跑，结果合并成同一屏卡片：
 *   1. 本地榜单缓存里按片名 / 导演 / 演员过滤 —— 同步，立刻出；
 *   2. 各片源站按关键词搜 —— 后台并发，回来一部补一部。
 *
 * 两边的结果按片名归一后合并，同一部片只留一张卡，卡上标「N 个源」。
 * 点卡片才进详情页，那里才真正去解析线路、探 m3u8、选集播放。
 */
public class SearchActivity extends Activity {

    private EditText input;
    private GridView grid;
    private TextView status;
    private View empty;
    private TextView emptyTitle;
    /* 最近搜索标签组 */
    private LinearLayout historyPanel;
    private LinearLayout historyTags;
    private TextView btnHistoryClear;

    private ResultAdapter adapter;
    private final List<RankItem> results = new ArrayList<RankItem>();
    private int posterHeight = 0;

    private String keyword = "";
    /** 每次搜索自增，用来丢弃上一次搜索迟到的回调 */
    private int searchSeq = 0;
    /** 本地榜单命中数，只用于状态栏文案 */
    private int localCount = 0;
    /** 防止一次按键既走 onKey 又走 onClick 打开两个详情页 */
    private boolean opening = false;

    private static boolean isConfirmKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_search);
        ImageLoader.init(this);

        input = (EditText) findViewById(R.id.q);
        grid = (GridView) findViewById(R.id.sgrid);
        status = (TextView) findViewById(R.id.status);
        empty = findViewById(R.id.empty);
        emptyTitle = (TextView) findViewById(R.id.empty_title);
        historyPanel = (LinearLayout) findViewById(R.id.history_panel);
        historyTags = (LinearLayout) findViewById(R.id.history_tags);
        btnHistoryClear = (TextView) findViewById(R.id.btn_history_clear);

        btnHistoryClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SearchHistory.clear();
                renderHistoryTags();
            }
        });

        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        findViewById(R.id.btn_go).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doSearch();
            }
        });

        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER;
                if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE || enter) {
                    doSearch();
                    return true;
                }
                return false;
            }
        });

        // 一输入就收起「最近搜索」，清空后又露出来
        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
                renderHistoryTags();
            }
        });

        adapter = new ResultAdapter();
        grid.setAdapter(adapter);
        grid.post(new Runnable() {
            @Override
            public void run() {
                // 与首页同一套算法：列宽按屏幕算，海报 2:3
                int cols = grid.getNumColumns();
                int screenW = getResources().getDisplayMetrics().widthPixels;
                int gap = getResources().getDimensionPixelSize(R.dimen.card_gap_h);
                int usable = screenW - grid.getPaddingLeft() - grid.getPaddingRight()
                        - gap * (cols - 1);
                int colW = usable / cols;
                if (colW > 0) {
                    posterHeight = (int) ((colW - dp(4)) * 1.5f);
                    adapter.notifyDataSetChanged();
                }
            }
        });
        grid.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                openAt(position);
            }
        });

        // 结果网格同样要「焦点落在卡片上」：FocusFinder 从输入框/搜索图标往下走时，
        // 命中的是 GridView 容器，卡片不会亮、OK 也不进详情（和首页 1.4.10 同一个坑）。
        FocusKit.bindGrid(grid, null);
        FocusKit.iconFx(findViewById(R.id.btn_go));

        // 输入框是单行 EditText，↓ 必须先明确交给结果网格，
        // 否则焦点可能在输入框里左右移光标，看着像「卡住了」
        input.setNextFocusDownId(R.id.sgrid);
        findViewById(R.id.btn_go).setNextFocusDownId(R.id.sgrid);
    }

    /** 兜底：方向键在页面分发阶段再拦一次（实现见 FocusKit.handleGridKey，只在焦点位于结果卡片上时生效） */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (grid != null && FocusKit.handleGridKey(grid, event.getKeyCode(), event)) return true;
        return super.dispatchKeyEvent(event);
    }

    /* ---------- 最近搜索 ---------- */

    /** 输入框有内容、或历史为空时，整块隐藏 */
    private void renderHistoryTags() {
        if (historyPanel == null || historyTags == null) return;
        List<String> hist = SearchHistory.list();
        boolean typing = input.getText().toString().trim().length() > 0;
        if (typing || hist.isEmpty()) {
            // 面板一收，焦点若正停在某个历史标签上，会跟着 view 一起消失，
            // 遥控器随即变成「按了没反应」。先交回输入框再藏。
            if (historyPanel.hasFocus()) input.requestFocus();
            historyPanel.setVisibility(View.GONE);
            return;
        }
        historyPanel.setVisibility(View.VISIBLE);
        historyTags.removeAllViews();

        LinearLayout row = null;
        for (int i = 0; i < hist.size(); i++) {
            if (i % 6 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                if (i > 0) rlp.topMargin = dp(8);
                historyTags.addView(row, rlp);
            }
            row.addView(makeTag(hist.get(i)));
        }
    }

    /** 一个关键词标签：点即搜，长按 / 菜单键删掉 */
    private View makeTag(final String kw) {
        final TextView tv = new TextView(this);
        tv.setText(kw);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.text_caption));
        tv.setTextColor(getResources().getColor(R.color.text_secondary));
        tv.setBackgroundResource(R.drawable.bg_chip);
        tv.setPadding(dp(16), dp(8), dp(16), dp(8));
        tv.setSingleLine(true);
        tv.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        tv.setLayoutParams(lp);

        tv.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                tv.setTextColor(getResources().getColor(
                        hasFocus ? R.color.text_primary : R.color.text_secondary));
            }
        });
        tv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                input.setText(kw);
                input.setSelection(kw.length());
                doSearch();
            }
        });
        tv.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() != KeyEvent.ACTION_UP) return false;
                if (isConfirmKey(keyCode)) {
                    input.setText(kw);
                    doSearch();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_MENU) {
                    SearchHistory.remove(kw);
                    renderHistoryTags();
                    return true;
                }
                return false;
            }
        });
        // 触摸设备没有菜单键，长按等同删除
        tv.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                SearchHistory.remove(kw);
                renderHistoryTags();
                Toast.makeText(SearchActivity.this, "已删除这条搜索记录", Toast.LENGTH_SHORT).show();
                return true;
            }
        });
        return tv;
    }

    /* ---------- 搜索 ---------- */

    private void doSearch() {
        keyword = input.getText().toString().trim();
        if (keyword.length() == 0) {
            status.setText("输入片名后按搜索图标，也可以直接按遥控器确认键");
            return;
        }
        SearchHistory.add(keyword);
        renderHistoryTags();       // 已经有输入了，标签组收起来
        input.clearFocus();        // 收起键盘，把屏幕让给结果

        final int seq = ++searchSeq;

        // 先秒出本地榜单的命中，别让用户对着空屏等片源站
        results.clear();
        results.addAll(localMatch(keyword));
        localCount = results.size();
        adapter.notifyDataSetChanged();
        scrollGridToTop();

        empty.setVisibility(View.GONE);
        int n = com.vodtv.data.SourceStore.enabled().size();
        if (localCount > 0) {
            status.setText("本地榜单 " + localCount + " 部 · 同时搜 " + n + " 个片源站…");
        } else {
            status.setText("正在 " + n + " 个片源站搜索「" + keyword + "」…");
        }

        searchSources(seq);
    }

    /** 后台去片源站搜，回来一批补一批 */
    private void searchSources(final int seq) {
        final String kw = keyword;
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<PlayResolver.Candidate> got = PlayResolver.findCandidates(kw,
                        new PlayResolver.CandidateCb() {
                            @Override
                            public void onProgress(final List<PlayResolver.Candidate> snapshot) {
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (seq != searchSeq || isFinishing()) return;
                                        applyCandidates(snapshot, false);
                                    }
                                });
                            }
                        });
                final List<PlayResolver.Candidate> last = got;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (seq != searchSeq || isFinishing()) return;
                        applyCandidates(last, true);
                        scrollGridToTop();
                    }
                });
            }
        }, "search-sources").start();
    }

    /**
     * 把片源站候选并进结果。榜单里已有的只补「N 个源」，封面评分仍以榜单为准；
     * 榜单没有的才新建一张卡。
     */
    private void applyCandidates(List<PlayResolver.Candidate> list, boolean done) {
        boolean changed = false;
        for (int i = 0; i < list.size(); i++) {
            PlayResolver.Candidate c = list.get(i);
            if (c == null || c.title == null || c.title.length() == 0) continue;

            int idx = indexOfTitle(c.title);
            if (idx >= 0) {
                RankItem old = results.get(idx);
                int merged = Math.max(old.sourceCount, c.sources);
                if (merged != old.sourceCount) {
                    old.sourceCount = merged;
                    changed = true;
                }
                continue;
            }

            RankItem it = new RankItem();
            it.title = c.title;
            it.pic = c.pic;
            it.picLarge = c.pic;
            it.year = c.year;
            it.episodes = c.remarks;
            it.sourceCount = c.sources;
            results.add(it);
            changed = true;
        }
        if (changed) adapter.notifyDataSetChanged();

        if (results.isEmpty()) {
            if (done) {
                empty.setVisibility(View.VISIBLE);
                emptyTitle.setText("没找到「" + keyword + "」");
                status.setText("本地榜单和片源站都没有 · 换个说法，或者只输片名的一部分试试");
            }
            return;
        }

        empty.setVisibility(View.GONE);
        if (done) {
            status.setText("找到 " + results.size() + " 部 · 点海报看片源");
        } else {
            status.setText("本地 " + localCount + " 部 · 片源站已补充 "
                    + Math.max(0, results.size() - localCount) + " 部 · 还在搜…");
        }
    }

    /** 同一部片在榜单和片源站里的片名不完全一样，归一后再比对 */
    private int indexOfTitle(String title) {
        String t = normTitle(title);
        if (t.length() == 0) return -1;
        for (int i = 0; i < results.size(); i++) {
            if (normTitle(results.get(i).title).equals(t)) return i;
        }
        for (int i = 0; i < results.size(); i++) {
            if (isSameWithSuffix(normTitle(results.get(i).title), t)) return i;
        }
        return -1;
    }

    /** 去掉括注、空白与常见分隔符，转小写 */
    private static String normTitle(String s) {
        if (s == null) return "";
        return s.replaceAll("[（(\\[【].*?[)）\\]】]", "")
                .replaceAll("[\\s·:：\\-_—、,，.。]", "")
                .toLowerCase(Locale.US);
    }

    /**
     * 「狂飙」和「狂飙2023」「狂飙第二季」算同一部；
     * 「狂飙」和「狂飙之某某」不算——多出来的得是年份/季集/语言清晰度这类版本尾巴。
     */
    private static boolean isSameWithSuffix(String a, String b) {
        if (a.length() == 0 || b.length() == 0) return false;
        String shorter = a.length() <= b.length() ? a : b;
        String longer = a.length() <= b.length() ? b : a;
        if (shorter.length() < 2 || !longer.startsWith(shorter)) return false;
        String rest = longer.substring(shorter.length());
        return rest.matches("\\d{4}.*")
                || rest.matches("第[一二三四五六七八九十\\d]+[季部].*")
                || rest.matches("(全|更新至|连载)?\\d+集?.*")
                || rest.matches("(国语|粤语|英语|中字|高清|hd|蓝光|4k).*");
    }

    /** 在本地缓存的榜单里按片名 / 导演 / 演员过滤，同一部片只留一条 */
    private List<RankItem> localMatch(String kw) {
        List<RankItem> out = new ArrayList<RankItem>();
        RankRepository.RankData d = RankRepository.loadCache(this);
        if (d == null) return out;

        String k = kw.toLowerCase(Locale.US);
        Set<String> seen = new HashSet<String>();
        for (RankRepository.Board b : d.boards) {
            if (b == null || b.items == null) continue;
            for (RankItem it : b.items) {
                if (it == null || it.title == null) continue;
                if (seen.contains(it.title)) continue;
                if (matches(it, k)) {
                    seen.add(it.title);
                    out.add(it);
                }
            }
        }
        return out;
    }

    private boolean matches(RankItem it, String k) {
        if (it.title != null && it.title.toLowerCase(Locale.US).contains(k)) return true;
        if (it.director != null && it.director.toLowerCase(Locale.US).contains(k)) return true;
        if (it.actors != null) {
            for (String a : it.actors) {
                if (a != null && a.toLowerCase(Locale.US).contains(k)) return true;
            }
        }
        return false;
    }

    /* ---------- 结果列表 ---------- */

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

    private void openAt(int position) {
        if (opening) return;
        if (position < 0 || position >= results.size()) return;
        opening = true;

        RankItem it = results.get(position);
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
        if (l == null) return "";
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) sb.append(" / ");
            sb.append(l.get(i));
        }
        return sb.toString();
    }

    @Override
    protected void onResume() {
        super.onResume();
        opening = false;
        renderHistoryTags();
    }

    private class ResultAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return results.size();
        }

        @Override
        public RankItem getItem(int position) {
            return results.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = LayoutInflater.from(SearchActivity.this)
                        .inflate(R.layout.item_poster, parent, false);
            }
            final RankItem it = getItem(position);

            ImageView poster = (ImageView) v.findViewById(R.id.poster);
            TextView title = (TextView) v.findViewById(R.id.title);
            TextView sub = (TextView) v.findViewById(R.id.sub);
            TextView rank = (TextView) v.findViewById(R.id.rank);
            TextView score = (TextView) v.findViewById(R.id.score);

            // 搜索结果是按相关度排的，名次角标没有意义
            rank.setVisibility(View.GONE);

            title.setText(it.title);
            sub.setText(it.metaLine());

            if (it.rating > 0) {
                score.setVisibility(View.VISIBLE);
                score.setText(String.format(Locale.US, "%.1f", it.rating));
            } else {
                score.setVisibility(View.GONE);
            }

            ImageLoader.load(poster, it.pic, dp(260));

            if (posterHeight > 0) {
                ViewGroup.LayoutParams plp = poster.getLayoutParams();
                if (plp.height != posterHeight) {
                    plp.height = posterHeight;
                    poster.setLayoutParams(plp);
                }
            }

            // 焦点反馈统一走 FocusKit，别再各页面抄一份 1.06 的动画
            FocusKit.cardFx(v);

            // 条目自身 focusable，GridView 的 itemClick 收不到 OK 键，监听必须挂在这里
            final int pos = position;
            v.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    openAt(pos);
                }
            });
            // 方向键交给 FocusKit（否则屏幕上只剩两排、按 ↓ 不滚），OK 仍走页面自己
            FocusKit.bindCardKey(grid, v, new View.OnKeyListener() {
                @Override
                public boolean onKey(View view, int keyCode, KeyEvent event) {
                    if (event.getAction() == KeyEvent.ACTION_UP && isConfirmKey(keyCode)) {
                        openAt(pos);
                        return true;
                    }
                    return false;
                }
            });

            // 复用时的缩放复位已经由 FocusKit.cardFx 统一处理
            return v;
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
