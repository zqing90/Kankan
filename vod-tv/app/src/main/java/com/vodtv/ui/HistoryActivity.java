package com.vodtv.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.vodtv.R;
import com.vodtv.data.HistoryStore;
import com.vodtv.data.ImageLoader;
import com.vodtv.data.PlayResolver;
import com.vodtv.widget.FocusKit;

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
    private View empty;
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
        empty = findViewById(R.id.empty);
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
        // 兜底点击通道：条目自身是可聚焦的，正常情况下触摸/OK 都由条目自己接住；
        // 但在部分机型上 GridView 会把焦点收上去、事件不下发，条目就"点了没反应"。
        // OnItemClickListener 走的是 AdapterView.performItemClick，能把这两种情况一起覆盖。
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < items.size()) {
                    resume(items.get(position));
                }
            }
        });

        // 遥控器焦点必须落到记录卡片上，不能停在 GridView 容器上（和首页、搜索结果同一个坑）
        FocusKit.bindGrid(grid, null);
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

        ResumeHelper.resume(this, e, new ResumeHelper.Cb() {
            @Override
            public void onBusy(String text) {
                if (busyText != null) busyText.setText(text);
                if (busyPanel != null) busyPanel.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPlay(PlayResolver.PlayLine line, PlayResolver.Ep ep, String media,
                               HistoryStore.Entry entry) {
                opening = false;
                hideBusy();
                DetailActivity.launchPlayer(HistoryActivity.this, line, ep, media, entry.info);
            }

            @Override
            public void onGiveUp(HistoryStore.Entry entry, int reason) {
                opening = false;
                hideBusy();
                Toast.makeText(HistoryActivity.this, failText(reason), Toast.LENGTH_LONG).show();
                // 拿不到流就交给人挑源：详情页带 autoResume，检索完会自己续播
                Intent i = new Intent(HistoryActivity.this, DetailActivity.class);
                entry.info.toIntent(i);
                i.putExtra("title", entry.title);
                i.putExtra("autoResume", true);
                startActivity(i);
            }
        });
    }

    private int failText(int reason) {
        if (reason == ResumeHelper.FAIL_NO_EP) return R.string.resume_fail_ep;
        if (reason == ResumeHelper.FAIL_NO_MEDIA) return R.string.resume_fail_media;
        return R.string.resume_fail_source;
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
            if (e.isFinished()) {
                // 看完的记录保留在列表里，不再显示「45:00 / 45:00」这种无信息量的文字
                sub.append(getString(R.string.history_finished));
            } else {
                sub.append(e.posText());
                if (e.dur > 0) sub.append(" / ").append(e.durText());
            }
            TextView subView = (TextView) v.findViewById(R.id.h_sub);
            subView.setText(sub.toString());
            subView.setTextColor(getResources().getColor(
                    e.isFinished() ? R.color.text_tertiary : R.color.text_secondary));

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

            // 焦点反馈：与首页/搜索页同一份实现（FocusKit.cardFx，1.06 放大）；
            // 只靠 bg_card 的 2dp 描边在 3 米外太弱，用户会说「看不出选中了」
            FocusKit.cardFx(v);

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
            // 第三道兜底：条目是 focusable 的，某些 ROM 上 GridView 会把 UP 事件收走
            //（AbsListView 对「child 自身可聚焦」的条目不做 performItemClick），
            // 条目的 onClick 就收不到。这里直接监听触摸，不消费事件，滚动照常。
            v.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View view, MotionEvent ev) {
                    if (ev.getAction() == MotionEvent.ACTION_UP) {
                        resume(e);
                    }
                    return false;
                }
            });

            return v;
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
