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
import android.widget.TextView;

import com.vodtv.R;
import com.vodtv.data.ImageLoader;
import com.vodtv.data.PlayCache;
import com.vodtv.data.PlayResolver;
import com.vodtv.data.RankItem;
import com.vodtv.data.RankRepository;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 搜索页。
 *
 * 两层：先在已经缓存到本地的榜单里过滤（秒出，不联网）；
 * 本地一条都没有时，才提供「联网搜索片源」，去 14 个站跑一遍。
 *
 * 联网结果会按关键词写进当天的片源缓存，跳进详情页就能秒开，
 * 不至于为了一个搜索结果再等第二遍。
 */
public class SearchActivity extends Activity {

    private EditText input;
    private GridView grid;
    private TextView status;
    private TextView empty;
    private TextView btnOnline;

    private ResultAdapter adapter;
    private final List<RankItem> results = new ArrayList<RankItem>();
    private int posterHeight = 0;

    private String keyword = "";
    private boolean searching = false;
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
        empty = (TextView) findViewById(R.id.empty);
        btnOnline = (TextView) findViewById(R.id.btn_online);

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

        btnOnline.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onlineSearch();
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
                int usable = screenW - grid.getPaddingLeft() - grid.getPaddingRight()
                        - dp(14) * (cols - 1);
                int colW = usable / cols;
                if (colW > 0) {
                    posterHeight = (int) (colW * 1.5f);
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
    }

    /* ---------- 搜索 ---------- */

    private void doSearch() {
        keyword = input.getText().toString().trim();
        if (keyword.length() == 0) {
            status.setText("先输入片名，再按搜索");
            btnOnline.setVisibility(View.GONE);
            return;
        }
        // 收起键盘，把屏幕让给结果
        input.clearFocus();

        results.clear();
        results.addAll(localMatch(keyword));
        adapter.notifyDataSetChanged();
        scrollGridToTop();

        if (results.isEmpty()) {
            empty.setVisibility(View.VISIBLE);
            empty.setText("本地榜单里没有「" + keyword + "」");
            status.setText("本地榜单没有匹配 · 可以点右侧按钮去片源站找");
            btnOnline.setVisibility(View.VISIBLE);
        } else {
            empty.setVisibility(View.GONE);
            status.setText("本地榜单 · 找到 " + results.size() + " 部");
            btnOnline.setVisibility(View.GONE);
        }
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

    /** 兜底：本地没有就去片源站搜，搜到了直接进详情页看片源 */
    private void onlineSearch() {
        if (searching || keyword.length() == 0) return;
        searching = true;
        btnOnline.setEnabled(false);
        btnOnline.setAlpha(0.4f);
        status.setText("正在 " + com.vodtv.data.SourceStore.enabled().size()
                + " 个片源站搜索「" + keyword + "」，可能要十几秒…");

        final String kw = keyword;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<PlayResolver.PlayLine> list = PlayResolver.search(kw, null);
                boolean any = false;
                for (PlayResolver.PlayLine l : list) {
                    if (l.ok) {
                        any = true;
                        break;
                    }
                }
                final boolean anyOk = any;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        searching = false;
                        btnOnline.setEnabled(true);
                        btnOnline.setAlpha(1f);
                        if (!anyOk) {
                            status.setText("片源站也没找到「" + kw + "」，换个说法或只输片名的一部分试试");
                            return;
                        }
                        // 按关键词写进当天缓存：详情页进去就是现成的片源列表，不用再搜一遍
                        PlayCache.save(kw, list);
                        Intent i = new Intent(SearchActivity.this, DetailActivity.class);
                        i.putExtra("title", kw);
                        startActivity(i);
                    }
                });
            }
        }, "search-online").start();
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

            v.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View view, boolean has) {
                    float s = has ? 1.07f : 1.0f;
                    view.animate().scaleX(s).scaleY(s).setDuration(120).start();
                }
            });

            // 条目自身 focusable，GridView 的 itemClick 收不到 OK 键，监听必须挂在这里
            final int pos = position;
            v.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    openAt(pos);
                }
            });
            v.setOnKeyListener(new View.OnKeyListener() {
                @Override
                public boolean onKey(View view, int keyCode, KeyEvent event) {
                    if (event.getAction() == KeyEvent.ACTION_UP && isConfirmKey(keyCode)) {
                        openAt(pos);
                        return true;
                    }
                    return false;
                }
            });

            v.setScaleX(v.isFocused() ? 1.07f : 1.0f);
            v.setScaleY(v.isFocused() ? 1.07f : 1.0f);
            return v;
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
