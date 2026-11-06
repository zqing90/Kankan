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

    private RecordAdapter adapter;
    private final List<HistoryStore.Entry> items = new ArrayList<HistoryStore.Entry>();

    private int colWidth = 0;
    /** 防连点：一次按键会同时触发 onKey 和 onClick */
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

    /** 带着整份影片信息进详情页，并把焦点停在「继续看」 */
    private void open(HistoryStore.Entry e) {
        if (opening) return;
        opening = true;
        Intent i = new Intent(this, DetailActivity.class);
        e.info.toIntent(i);
        i.putExtra("title", e.title);
        i.putExtra("autoResume", true);
        startActivity(i);
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
                    open(e);
                }
            });
            v.setOnKeyListener(new View.OnKeyListener() {
                @Override
                public boolean onKey(View view, int keyCode, KeyEvent event) {
                    if (event.getAction() != KeyEvent.ACTION_UP) return false;
                    if (isConfirmKey(keyCode)) {
                        open(e);
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
