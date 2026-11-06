package com.vodtv.ui;

import android.app.Activity;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.vodtv.R;
import com.vodtv.data.Prefs;
import com.vodtv.data.RankRepository;
import com.vodtv.data.SourceConfig;
import com.vodtv.data.SourceStats;
import com.vodtv.data.SourceStore;

import java.util.List;

/** 设置页：数据源模式、强制更新、片源启停与稳定性统计。 */
public class SettingsActivity extends Activity {

    private LinearLayout sourceList;
    private TextView updateStatus;
    private EditText etServer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        sourceList = (LinearLayout) findViewById(R.id.source_list);
        updateStatus = (TextView) findViewById(R.id.tv_update_status);
        etServer = (EditText) findViewById(R.id.et_server);
        etServer.setText(Prefs.serverUrl());

        findViewById(R.id.btn_close).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        findViewById(R.id.btn_save_server).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Prefs.setServerUrl(etServer.getText().toString());
                Toast.makeText(SettingsActivity.this,
                        Prefs.useServer() ? "已切换到自建服务：" + Prefs.serverUrl() : "已切换为直连模式",
                        Toast.LENGTH_SHORT).show();
                updateStatus.setText("保存成功，下次更新生效");
            }
        });

        findViewById(R.id.btn_force_update).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                doUpdate();
            }
        });

        findViewById(R.id.btn_sync_sources).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!Prefs.useServer()) {
                    Toast.makeText(SettingsActivity.this, "先在上面填服务端地址并保存", Toast.LENGTH_SHORT).show();
                    return;
                }
                final String base = Prefs.serverUrl();
                updateStatus.setText("正在从服务端同步源…");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final boolean ok = SourceStore.syncFromServer();
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                renderSources();
                                updateStatus.setText(ok
                                        ? "已同步 " + SourceStore.all().size() + " 个源（本地启停状态已保留）"
                                        : "同步失败，检查服务端是否可达");
                                Toast.makeText(SettingsActivity.this, ok ? "同步完成" : "同步失败", Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                }).start();
            }
        });

        findViewById(R.id.btn_reset_sources).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SourceStore.resetToDefault();
                renderSources();
                Toast.makeText(SettingsActivity.this, "已恢复默认片源清单", Toast.LENGTH_SHORT).show();
            }
        });

        findViewById(R.id.btn_clear_stats).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SourceStats.clear();
                renderSources();
                Toast.makeText(SettingsActivity.this, "已清空稳定性统计", Toast.LENGTH_SHORT).show();
            }
        });

        renderSources();
        updateStatus.setText(Prefs.lastRankAt() > 0
                ? "上次更新：" + android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", Prefs.lastRankAt())
                : "尚未更新过");
    }

    /* ---------- 强制更新 ---------- */

    private void doUpdate() {
        updateStatus.setText("正在更新…");
        Prefs.setServerUrl(etServer.getText().toString());

        RankRepository.refreshAsync(this, new RankRepository.Listener() {
            @Override
            public void onProgress(String msg) {
            }

            @Override
            public void onDone(final RankRepository.RankData data, final String error) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (error != null) {
                            updateStatus.setText("更新失败：" + error);
                            Toast.makeText(SettingsActivity.this, "更新失败：" + error, Toast.LENGTH_LONG).show();
                        } else {
                            updateStatus.setText("更新成功 · " + data.boards.size() + " 个榜单 · "
                                    + data.total() + " 部 · "
                                    + android.text.format.DateFormat.format("HH:mm", System.currentTimeMillis()));
                            Toast.makeText(SettingsActivity.this, "已更新 " + data.total() + " 部", Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }
        });
    }

    /* ---------- 片源列表 ---------- */

    private void renderSources() {
        sourceList.removeAllViews();
        List<SourceConfig> list = SourceStore.all();
        List<SourceConfig> ranked = SourceStore.enabledByStability();
        String topId = ranked.isEmpty() ? "" : ranked.get(0).id;

        for (int i = 0; i < list.size(); i++) {
            final SourceConfig s = list.get(i);
            View row = LayoutInflater.from(this).inflate(R.layout.item_source, sourceList, false);

            ((TextView) row.findViewById(R.id.src_index)).setText(String.valueOf(i + 1));
            ((TextView) row.findViewById(R.id.src_name)).setText(
                    s.name + (s.id.equals(topId) ? "  ★ 默认" : ""));
            ((TextView) row.findViewById(R.id.src_host)).setText(s.host);
            ((TextView) row.findViewById(R.id.src_stat)).setText(
                    SourceStats.hasHistory(s.id) ? SourceStats.summary(s.id) : "暂无记录");

            TextView state = (TextView) row.findViewById(R.id.src_state);
            state.setText(s.enabled ? "已启用" : "已停用");
            state.setTextColor(getResources().getColor(s.enabled ? R.color.green : R.color.text_dim));
            state.setBackgroundColor(s.enabled ? 0x2233CC88 : 0x22FFFFFF);

            if (!s.enabled) row.setAlpha(0.55f);

            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    SourceStore.setEnabled(s.id, !s.enabled);
                    renderSources();
                }
            });
            // 长按设为锁定默认源
            row.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    if (s.id.equals(Prefs.pinnedSource())) {
                        Prefs.setPinnedSource("");
                        Toast.makeText(SettingsActivity.this, "已取消锁定，恢复自动选择", Toast.LENGTH_SHORT).show();
                    } else {
                        Prefs.setPinnedSource(s.id);
                        Toast.makeText(SettingsActivity.this, "已锁定默认源：" + s.name, Toast.LENGTH_SHORT).show();
                    }
                    return true;
                }
            });

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            sourceList.addView(row, lp);
        }
    }
}
