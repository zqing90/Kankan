package com.vodtv.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.vodtv.R;
import com.vodtv.data.HistoryStore;
import com.vodtv.data.ImageLoader;
import com.vodtv.data.PlayCache;
import com.vodtv.data.PlayResolver;

import java.util.ArrayList;
import java.util.List;

/**
 * 观看记录页。
 *
 * 以前记录只藏在首页顶部一行小横条里（最多 10 条、不能删），
 * 只有播放超过 30 秒才出现，所以用户根本找不到。现在给它一个正式入口：
 * 顶栏图标 / 横条末尾的「全部记录」，都能进来。
 *
 * 交互：
 *   OK / 点击  = 进详情页继续看
 *   MENU / 长按 = 删掉这一条
 *   右上「清空」 = 全删（要确认一次，清空不可撤销）
 */
public class HistoryActivity extends Activity {

    private GridView grid;
    private TextView count;
    private TextView empty;
    /** 续播准备遮罩 */
    private LinearLayout busyPanel;
    private TextView busyText;

    private RecordAdapter adapter;
    private final List<HistoryStore.Entry> items = new ArrayList<HistoryStore.Entry>();

    private int colWidth = 0;
    /** 防连点：一次按键会同时触发 onKey 和 onClick；解析没结束时也不允许再点第二条 */
    private boolean opening = false;

    private static boolean isConfirmKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);
        ImageLoader.init(this);

        grid = (GridView) findViewById(R.id.rgrid);
        count = (TextView) findViewById(R.id.tv_count);
        empty = (TextView) findViewById(R.id.empty);
        busyPanel = (LinearLayout) findViewById(R.id.busy_panel);
        busyText = (TextView) findViewById(R.id.busy_text);

        findViewById(R.id.btn_back).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        findViewById(R.id.btn_clear).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmClear();
            }
        });

        adapter = new RecordAdapter();
        grid.setAdapter(adapter);
        grid.post(new Runnable() {
            @Override
            public void run() {
                // 列数按屏宽算：1080p 电视 3 列，手机 2 列，别再写死 6 列挤成一条缝
                int screenW = getResources().getDisplayMetrics().widthPixels;
                int widthDp = screenW * 160 / getResources().getDisplayMetrics().densityDpi;
                int cols = Math.max(2, (widthDp - 96) / 220);
                if (cols != grid.getNumColumns()) {
                    grid.setNumColumns(cols);
                }
                int usable = screenW - grid.getPaddingLeft() - grid.getPaddingRight() - dp(14) * (cols - 1);
                colWidth = usable / cols;
                adapter.notifyDataSetChanged();
            }
        });

        reload();
    }

    @Override
    protected void onResume() {
        super.onResume();
        opening = false;
        hideBusy();
        reload();   // 从详情/播放页回来，进度已经变了
    }

    /* ---------- 数据 ---------- */

    private void reload() {
        items.clear();
        items.addAll(HistoryStore.all(HistoryStore.MAX_ENTRIES));
        adapter.notifyDataSetChanged();

        if (items.isEmpty()) {
            empty.setVisibility(View.VISIBLE);
            grid.setVisibility(View.INVISIBLE);
            count.setText("");
        } else {
            empty.setVisibility(View.GONE);
            grid.setVisibility(View.VISIBLE);
            count.setText(getString(R.string.history_count, items.size()));
        }
    }

    private void deleteOne(HistoryStore.Entry e) {
        HistoryStore.clear(e.title);
        Toast.makeText(this, R.string.history_deleted, Toast.LENGTH_SHORT).show();
        reload();
    }

    private void confirmClear() {
        if (items.isEmpty()) {
            Toast.makeText(this, R.string.history_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.history)
                .setMessage(R.string.history_clear_confirm)
                .setNegativeButton(R.string.history_cancel, null)
                .setPositiveButton(R.string.history_clear, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        HistoryStore.clearAll();
                        Toast.makeText(HistoryActivity.this, R.string.history_cleared, Toast.LENGTH_SHORT).show();
                        reload();
                    }
                })
                .show();
    }

    /* ---------- 续播 ---------- */

    /**
     * 点一条记录 = **直接继续播放**，不再绕详情页。
     *
     * 播放页要的是「真实 m3u8 + 整条线路的集数」，记录里只有「片名 / 集名 / 源」，中间必须补一次解析：
     *   1. 今天已经检测过（PlayCache 当天有效）→ 直接用缓存里的线路，只解析这一集的流，1~2 秒；
     *   2. 今天没检测过 → 重新检索各站（十几秒，必须给进度反馈），找上次那个源，找不到就用第一条可播的；
     *   3. 还是拿不到流 → 退回详情页（autoResume），那边会展示全部片源让人自己挑。
     *
     * 以前这里是直接跳详情页、只把焦点放到「继续看」上，用户还得再按一次 OK，
     * 加上详情页要重新检索十几秒，感受就是「点了没法继续播放」。见 PROJECT.md §17。
     */
    private void resume(final HistoryStore.Entry e) {
        if (opening || e == null) return;
        opening = true;
        showBusy("正在准备续播…");

        new Thread(new Runnable() {
            @Override
            public void run() {
                PlayCache.Cached cache = PlayCache.load(e.title);
                boolean checkedToday = cache != null && cache.fresh();

                PlayResolver.PlayLine line = null;
                PlayResolver.Ep ep = null;
                if (checkedToday) {
                    line = pickLine(cache.lines, e.sourceId);
                    ep = pickEp(line, e.ep);
                }

                // 今天没检测过才值得再跑一遍；检测过却对不上，重跑也是一样的结果
                if (!checkedToday) {
                    final int n = com.vodtv.data.SourceStore.enabled().size();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            showBusy("上次的片源没缓存，正在重新检索 " + n + " 个站…");
                        }
                    });
                    List<PlayResolver.PlayLine> list = PlayResolver.search(e.title, null);
                    line = pickLine(list, e.sourceId);
                    ep = pickEp(line, e.ep);
                }

                String media = null;
                if (line != null && ep != null) {
                    // 检索时探的就是这一集，直接复用结果，不再多打一次请求
                    if (line.media != null && line.media.length() > 0
                            && ep.label != null && ep.label.equals(line.label)) {
                        media = line.media;
                    } else {
                        media = PlayResolver.resolveEp(ep);
                    }
                }

                final PlayResolver.PlayLine fLine = line;
                final PlayResolver.Ep fEp = ep;
                final String fMedia = media;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        opening = false;
                        hideBusy();
                        if (fMedia != null && fMedia.length() > 0) {
                            DetailActivity.launchPlayer(HistoryActivity.this, fLine, fEp, fMedia, e.info);
                            return;
                        }
                        // 拿不到流就交给人挑源：详情页带 autoResume，检索完会自己续播
                        Intent i = new Intent(HistoryActivity.this, DetailActivity.class);
                        e.info.toIntent(i);
                        i.putExtra("title", e.title);
                        i.putExtra("autoResume", true);
                        startActivity(i);
                    }
                });
            }
        }, "history-resume").start();
    }

    /** 优先上次那个源；那个源挂了就用第一条可播的 */
    private PlayResolver.PlayLine pickLine(List<PlayResolver.PlayLine> list, String sourceId) {
        if (list == null) return null;
        PlayResolver.PlayLine fallback = null;
        for (PlayResolver.PlayLine l : list) {
            if (l == null || !l.ok) continue;
            if (sourceId != null && sourceId.length() > 0 && sourceId.equals(l.sourceId)) return l;
            if (fallback == null) fallback = l;
        }
        return fallback;
    }

    /** 集名对得上才算续播；单集（电影）集名不符也认，多集对不上就交给详情人挑 */
    private PlayResolver.Ep pickEp(PlayResolver.PlayLine l, String label) {
        if (l == null || l.eps == null || l.eps.isEmpty()) return null;
        if (label != null && label.length() > 0) {
            for (PlayResolver.Ep ep : l.eps) {
                if (label.equals(ep.label)) return ep;
            }
        }
        return l.eps.size() == 1 ? l.eps.get(0) : null;
    }

    private void showBusy(final String text) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (busyPanel == null) return;
                busyText.setText(text);
                busyPanel.setVisibility(View.VISIBLE);
            }
        });
    }

    private void hideBusy() {
        if (busyPanel != null) busyPanel.setVisibility(View.GONE);
    }

    /* ---------- 列表 ---------- */

    private class RecordAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public HistoryStore.Entry getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView;
            if (v == null) {
                v = LayoutInflater.from(HistoryActivity.this)
                        .inflate(R.layout.item_record, parent, false);
            }
            final HistoryStore.Entry e = getItem(position);

            // 宽度必须由代码给：GridView 对 match_parent 的子项量不出列宽
            if (colWidth > 0) {
                v.setLayoutParams(new AbsListView.LayoutParams(colWidth, dp(96)));
            }

            ImageLoader.load((ImageView) v.findViewById(R.id.poster), e.info.pic, 200);
            ((TextView) v.findViewById(R.id.h_title)).setText(e.title);

            StringBuilder sub = new StringBuilder();
            if (e.ep != null && e.ep.length() > 0) sub.append(e.ep).append("  ·  ");
            sub.append(e.posText());
            if (e.dur > 0) sub.append(" / ").append(e.durText());
            ((TextView) v.findViewById(R.id.h_sub)).setText(sub.toString());

            int pct = Math.max(0, Math.min(100, e.percent()));
            View done = v.findViewById(R.id.h_bar_done);
            View rest = v.findViewById(R.id.h_bar_rest);
            LinearLayout.LayoutParams a = (LinearLayout.LayoutParams) done.getLayoutParams();
            LinearLayout.LayoutParams b = (LinearLayout.LayoutParams) rest.getLayoutParams();
            a.weight = Math.max(1, pct);
            b.weight = Math.max(1, 100 - pct);
            done.setLayoutParams(a);
            rest.setLayoutParams(b);
            done.setVisibility(e.dur > 0 ? View.VISIBLE : View.GONE);

            // 条目自身 focusable，GridView 的 itemClick 收不到 OK，必须挂到这里
            v.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    resume(e);
                }
            });
            v.setOnKeyListener(new View.OnKeyListener() {
                @Override
                public boolean onKey(View view, int keyCode, KeyEvent event) {
                    if (event.getAction() != KeyEvent.ACTION_UP) return false;
                    if (isConfirmKey(keyCode)) {
                        resume(e);
                        return true;
                    }
                    // 遥控器「菜单」键：删掉当前焦点这一条
                    if (keyCode == KeyEvent.KEYCODE_MENU) {
                        deleteOne(e);
                        return true;
                    }
                    return false;
                }
            });
            // 触摸设备没有菜单键，长按等同删除
            v.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View view) {
                    deleteOne(e);
                    return true;
                }
            });

            return v;
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
