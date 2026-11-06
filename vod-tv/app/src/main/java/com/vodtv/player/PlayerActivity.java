package com.vodtv.player;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultDataSource;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer2.util.Util;
import com.vodtv.R;
import com.vodtv.data.HistoryStore;
import com.vodtv.data.MovieInfo;
import com.vodtv.data.PlayResolver;

import java.util.ArrayList;
import java.util.Locale;

/**
 * 播放页。
 *
 * 遥控器键位（观看态）：
 *   OK / 播放暂停键  播放/暂停
 *   ← →            快退/快进 10 秒
 *   ↑              呼出控制栏并把焦点交给按钮
 *   ↓              收起控制栏
 *   返回           退出
 *
 * 焦点在控制栏按钮上时，方向键由系统做焦点移动，按返回或 ↓ 退回观看态。
 * 手机：点画面切换控制栏，控制栏按钮本身可触摸点击。
 */
public class PlayerActivity extends Activity implements Player.Listener {

    private SimpleExoPlayer player;
    private SurfaceView surface;
    private LinearLayout controls;
    private LinearLayout epPanel;
    private LinearLayout epBox;
    private ProgressBar progress;
    private TextView msg;
    private TextView timeText;
    private TextView pTitle;
    private TextView pSource;
    private View btnBack;
    private View btnPrev;
    private View btnNext;
    private ImageView btnPlay;
    private View btnEps;
    private TextView epTitle;

    private boolean controlsShowing = false;
    /** 焦点是否落在控制栏的按钮上；决定方向键是「快进快退」还是「移动焦点」 */
    private boolean inControls = false;

    private DataSource.Factory dsFactory;

    private final Handler handler = new Handler();
    private final Runnable hideControls = new Runnable() {
        @Override
        public void run() {
            // 焦点还在按钮上时不自动收起，否则按着按着按钮就消失了
            if (!inControls) showControls(false);
        }
    };
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            if (++tickCount % 10 == 0) saveProgress();  // 每 5 秒落一次盘
            handler.postDelayed(this, 500);
        }
    };
    private final Runnable hideMsg = new Runnable() {
        @Override
        public void run() {
            if (!ended) msg.setVisibility(View.GONE);
        }
    };

    private String url;
    private boolean ended = false;

    private String title;
    private String ep;
    private String sourceId;
    private String sourceName;
    private String spec;
    /** 影片信息快照，退出时一并写进历史记录 */
    private MovieInfo movie = new MovieInfo();
    /** 待恢复的播放位置，进 READY 后只执行一次 */
    private long pendingSeekMs = 0;
    private boolean seeked = false;
    private int tickCount = 0;

    /* 整条线路的集数：只有多集线路才会从详情页传过来 */
    private ArrayList<String> epLabels;
    private ArrayList<String> epUrls;
    private int epIndex = -1;
    private boolean switching = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_player);

        surface = (SurfaceView) findViewById(R.id.surface);
        controls = (LinearLayout) findViewById(R.id.controls);
        progress = (ProgressBar) findViewById(R.id.progress);
        msg = (TextView) findViewById(R.id.msg);
        timeText = (TextView) findViewById(R.id.p_time);
        pTitle = (TextView) findViewById(R.id.p_title);
        pSource = (TextView) findViewById(R.id.p_source);
        btnBack = findViewById(R.id.p_back);
        btnPrev = findViewById(R.id.p_prev);
        btnNext = findViewById(R.id.p_next);
        btnPlay = findViewById(R.id.p_play);
        btnEps = findViewById(R.id.p_eps);
        epPanel = (LinearLayout) findViewById(R.id.ep_panel);
        epBox = (LinearLayout) findViewById(R.id.ep_box);
        epTitle = (TextView) findViewById(R.id.ep_title);

        url = getIntent().getStringExtra("url");
        title = getIntent().getStringExtra("title");
        ep = getIntent().getStringExtra("ep");
        sourceId = getIntent().getStringExtra("sourceId");
        sourceName = getIntent().getStringExtra("source");
        spec = getIntent().getStringExtra("spec");
        // 详情页会把整份影片信息带过来，存进历史记录，首页「继续观看」才能显示海报
        movie = MovieInfo.fromIntent(getIntent());

        epLabels = getIntent().getStringArrayListExtra("epLabels");
        epUrls = getIntent().getStringArrayListExtra("epUrls");
        epIndex = getIntent().getIntExtra("epIndex", -1);

        pTitle.setText(title);
        updateHeader();
        bindButtons();

        // 上一回看到哪了
        HistoryStore.Entry h = HistoryStore.get(title);
        pendingSeekMs = HistoryStore.resumePos(h, ep);

        if (url == null || url.length() == 0) {
            showError("没有可用的播放地址");
            return;
        }

        initPlayer();
    }

    private void bindButtons() {
        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        btnPlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePlay();
            }
        });
        btnPrev.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchEpisode(epIndex - 1);
            }
        });
        btnNext.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchEpisode(epIndex + 1);
            }
        });
        findViewById(R.id.p_rew).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                seekBy(-10000);
            }
        });
        findViewById(R.id.p_ff).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                seekBy(10000);
            }
        });
        btnEps.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openEpisodes();
            }
        });
        findViewById(R.id.ep_close).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeEpisodes();
            }
        });

        // 手机上点画面切换控制栏
        findViewById(R.id.touch_layer).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (epPanel.getVisibility() == View.VISIBLE) return;
                if (controlsShowing && !inControls) {
                    showControls(false);
                } else {
                    showControls(true);
                }
            }
        });
    }

    private void updateHeader() {
        StringBuilder sub = new StringBuilder(sourceName == null ? "" : sourceName);
        if (ep != null && ep.length() > 0) sub.append("  ·  ").append(ep);
        if (spec != null && spec.length() > 0) sub.append("  ·  ").append(spec);
        pSource.setText(sub.toString());
    }

    private void initPlayer() {
        try {
            DefaultTrackSelector trackSelector = new DefaultTrackSelector(this);
            DefaultLoadControl loadControl = new DefaultLoadControl();

            player = new SimpleExoPlayer.Builder(this)
                    .setTrackSelector(trackSelector)
                    .setLoadControl(loadControl)
                    .build();
            player.addListener(this);
            player.setVideoSurfaceView(surface);

            // 部分资源站会拦 ExoPlayer 的默认 UA，这里显式带上
            DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                    .setUserAgent(Util.getUserAgent(this, "VodTV"));
            dsFactory = new DefaultDataSource.Factory(this, httpFactory);

            player.setMediaSource(buildSource(url));
            player.prepare();
            player.setPlayWhenReady(true);

            msg.setVisibility(View.VISIBLE);
            msg.setText("正在加载…");
            handler.post(tick);
            showControls(true);
        } catch (Throwable e) {
            showError("播放器初始化失败：" + e.getMessage());
        }
    }

    private HlsMediaSource buildSource(String media) {
        return new HlsMediaSource.Factory(dsFactory)
                .createMediaSource(MediaItem.fromUri(media));
    }

    /* ---------- 播放控制 ---------- */

    private void togglePlay() {
        if (player == null) return;
        if (ended) {
            player.seekTo(0);
            player.setPlayWhenReady(true);
            ended = false;
            updatePlayButton();
            return;
        }
        boolean playing = player.getPlayWhenReady();
        player.setPlayWhenReady(!playing);
        updatePlayButton();
        showControls(true);
    }

    private void updatePlayButton() {
        if (btnPlay == null) return;
        boolean playing = player != null && player.getPlayWhenReady();
        btnPlay.setImageResource(playing ? R.drawable.ic_ui_pause : R.drawable.ic_ui_play);
    }

    private void seekBy(long deltaMs) {
        if (player == null) return;
        long pos = player.getCurrentPosition() + deltaMs;
        long dur = player.getDuration();
        if (dur > 0 && pos > dur) pos = dur;
        if (pos < 0) pos = 0;
        player.seekTo(pos);
        showControls(true);
    }

    /* ---------- 换集 ---------- */

    /** 播放页里直接切集，不用退回详情页 */
    private void switchEpisode(final int idx) {
        if (epLabels == null || epUrls == null) return;
        if (idx < 0 || idx >= epUrls.size()) return;
        if (switching) return;

        switching = true;
        final String label = epLabels.get(idx);
        final String rawUrl = epUrls.get(idx);
        msg.setVisibility(View.VISIBLE);
        msg.setText("正在解析 " + label + "…");
        closeEpisodes();

        new Thread(new Runnable() {
            @Override
            public void run() {
                PlayResolver.Ep e = new PlayResolver.Ep();
                e.label = label;
                e.url = rawUrl;
                final String media = PlayResolver.resolveEp(e);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        switching = false;
                        if (media == null) {
                            msg.setVisibility(View.VISIBLE);
                            msg.setText("《" + label + "》取流失败，请换一条线路");
                            handler.removeCallbacks(hideMsg);
                            handler.postDelayed(hideMsg, 3200);
                            return;
                        }
                        applyEpisode(idx, label, media);
                    }
                });
            }
        }, "switch-ep").start();
    }

    private void applyEpisode(int idx, String label, String media) {
        if (player == null) return;
        // 先把上一集的进度落盘，否则切走的那一集进度会丢
        saveProgress();

        epIndex = idx;
        ep = label;
        url = media;
        seeked = false;
        pendingSeekMs = 0;
        ended = false;

        updateHeader();
        msg.setVisibility(View.VISIBLE);
        msg.setText("正在加载 " + label + "…");
        player.setMediaSource(buildSource(media));
        player.prepare();
        player.setPlayWhenReady(true);
        showControls(true);
        updateNavButtons();
    }

    /* ---------- 选集浮层 ---------- */

    private void openEpisodes() {
        if (epLabels == null || epLabels.size() < 2) return;

        epBox.removeAllViews();
        epTitle.setText("选集 · " + (sourceName == null ? "" : sourceName)
                + "（共 " + epLabels.size() + " 集）");

        int screenW = getResources().getDisplayMetrics().widthPixels;
        final int cellW = dp(150);
        final int cols = Math.max(3, (screenW - dp(90)) / cellW);

        LinearLayout row = null;
        for (int i = 0; i < epLabels.size(); i++) {
            if (i % cols == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                rlp.bottomMargin = dp(6);
                epBox.addView(row, rlp);
            }

            final int idx = i;
            TextView b = new TextView(this);
            b.setText(epLabels.get(i));
            b.setTextSize(13);
            b.setTextColor(getResources().getColor(R.color.text_primary));
            b.setPadding(dp(12), dp(9), dp(12), dp(9));
            b.setBackgroundResource(R.drawable.bg_tab);
            b.setFocusable(true);
            b.setSingleLine(true);
            b.setEllipsize(TextUtils.TruncateAt.END);
            b.setGravity(android.view.Gravity.CENTER);
            // 当前正在播的那一集标红，一眼能找到自己在哪
            b.setSelected(i == epIndex);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (idx == epIndex) {
                        closeEpisodes();
                    } else {
                        switchEpisode(idx);
                    }
                }
            });

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cellW, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(6);
            row.addView(b, lp);
        }

        epPanel.setVisibility(View.VISIBLE);
        inControls = false;
        View f = getCurrentFocus();
        if (f != null) f.clearFocus();
        handler.removeCallbacks(hideControls);
        // 焦点落在当前集上，省得用户在几十集里找
        epPanel.post(new Runnable() {
            @Override
            public void run() {
                int r = epIndex >= 0 ? epIndex / cols : 0;
                int c = epIndex >= 0 ? epIndex % cols : 0;
                if (r >= epBox.getChildCount()) return;
                View rowView = epBox.getChildAt(r);
                if (!(rowView instanceof LinearLayout)) return;
                LinearLayout rl = (LinearLayout) rowView;
                if (c < rl.getChildCount()) rl.getChildAt(c).requestFocus();
            }
        });
    }

    private void closeEpisodes() {
        if (epPanel.getVisibility() != View.VISIBLE) return;
        epPanel.setVisibility(View.GONE);
        inControls = false;
        View f = getCurrentFocus();
        if (f != null) f.clearFocus();
        showControls(false);
    }

    /* ---------- 控制栏 ---------- */

    private void showControls(boolean show) {
        controlsShowing = show;
        controls.setVisibility(show ? View.VISIBLE : View.GONE);
        handler.removeCallbacks(hideControls);
        if (show) {
            updatePlayButton();
            updateNavButtons();
            if (!inControls) scheduleHide();
        } else if (inControls) {
            inControls = false;
            View f = getCurrentFocus();
            if (f != null) f.clearFocus();
        }
    }

    private void scheduleHide() {
        handler.removeCallbacks(hideControls);
        handler.postDelayed(hideControls, 4500);
    }

    /** ↑ 呼出控制栏并把焦点交给按钮，之后方向键用来选按钮 */
    private void enterControls() {
        showControls(true);
        inControls = true;
        handler.removeCallbacks(hideControls);
        View target = btnPlay.isEnabled() ? btnPlay : btnBack;
        if (target != null) target.requestFocus();
    }

    private void leaveControls() {
        inControls = false;
        View f = getCurrentFocus();
        if (f != null) f.clearFocus();
        scheduleHide();
    }

    private void updateNavButtons() {
        boolean multi = epUrls != null && epUrls.size() > 1;
        boolean hasPrev = multi && epIndex > 0;
        boolean hasNext = multi && epIndex >= 0 && epIndex < epUrls.size() - 1;
        setEnabled(btnPrev, hasPrev);
        setEnabled(btnNext, hasNext);
        btnEps.setVisibility(multi ? View.VISIBLE : View.GONE);
        // 单集（电影）没有「上一集/下一集」可讲，藏起来别占位置
        btnPrev.setVisibility(multi ? View.VISIBLE : View.GONE);
        btnNext.setVisibility(multi ? View.VISIBLE : View.GONE);
    }

    private void setEnabled(View v, boolean on) {
        v.setEnabled(on);
        v.setAlpha(on ? 1f : 0.32f);
        // 不可用的按钮不能再接焦点，否则遥控器会停在死按钮上
        v.setFocusable(on);
    }

    private void updateProgress() {
        if (player == null) return;
        long pos = player.getCurrentPosition();
        long dur = player.getDuration();
        if (dur > 0) {
            progress.setProgress((int) (pos * 1000 / dur));
            timeText.setText(fmt(pos) + " / " + fmt(dur));
        } else {
            timeText.setText(fmt(pos));
        }
    }

    private static String fmt(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000;
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, sec);
        return String.format(Locale.US, "%02d:%02d", m, sec);
    }

    private void showError(String text) {
        msg.setVisibility(View.VISIBLE);
        msg.setText(text + "\n\n按返回键退出");
    }

    /* ---------- 按键 ---------- */

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // 选集浮层开着：返回关浮层，其余交给浮层里的按钮自己处理
        if (epPanel.getVisibility() == View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                closeEpisodes();
                return true;
            }
            return super.onKeyDown(keyCode, event);
        }

        // 焦点在控制栏按钮上：返回 / ↓ 退回观看态，方向键交给系统做焦点移动
        if (inControls) {
            if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                leaveControls();
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) showControls(false);
                return true;
            }
            return super.onKeyDown(keyCode, event);
        }

        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                togglePlay();
                return true;

            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                seekBy(-10000);
                return true;

            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                seekBy(10000);
                return true;

            case KeyEvent.KEYCODE_DPAD_UP:
                enterControls();
                return true;

            case KeyEvent.KEYCODE_DPAD_DOWN:
                showControls(!controlsShowing);
                return true;

            case KeyEvent.KEYCODE_BACK:
                finish();
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /* ---------- 进度记忆 ---------- */

    private void saveProgress() {
        if (player == null || title == null || title.length() == 0) return;
        try {
            long pos = player.getCurrentPosition();
            long dur = player.getDuration();
            if (dur < 0) dur = 0;
            if (pos <= 0) return;
            HistoryStore.save(title, ep, sourceId, pos, dur, movie);
        } catch (Throwable ignored) {
        }
    }

    /* ---------- ExoPlayer 回调 ---------- */

    @Override
    public void onPlaybackStateChanged(int playbackState) {
        if (playbackState == Player.STATE_BUFFERING) {
            msg.setVisibility(View.VISIBLE);
            msg.setText("缓冲中…");
        } else if (playbackState == Player.STATE_READY) {
            msg.setVisibility(View.GONE);
            ended = false;
            updatePlayButton();

            // 续播只做一次：等真正 READY 了再 seek，prepare 之前 seek 会被丢掉
            if (pendingSeekMs > 0 && !seeked) {
                seeked = true;
                player.seekTo(pendingSeekMs);
                msg.setVisibility(View.VISIBLE);
                msg.setText("已从 " + HistoryStore.fmt(pendingSeekMs) + " 继续播放");
                handler.removeCallbacks(hideMsg);
                handler.postDelayed(hideMsg, 2600);
                showControls(true);
            }
        } else if (playbackState == Player.STATE_ENDED) {
            ended = true;
            boolean hasNext = epUrls != null && epIndex >= 0 && epIndex < epUrls.size() - 1;
            msg.setVisibility(View.VISIBLE);
            msg.setText(hasNext
                    ? "播放结束\n\n按 OK 重播，↑ 控制栏里可以看下一集"
                    : "播放结束\n\n按 OK 重播，按返回键退出");
            showControls(true);
            updatePlayButton();
        }
    }

    @Override
    public void onPlayerError(PlaybackException error) {
        showError("播放失败：" + (error.getMessage() == null ? "未知错误" : error.getMessage()));
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveProgress();
        if (player != null) player.setPlayWhenReady(false);
        updatePlayButton();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (player != null) player.setPlayWhenReady(true);
        updatePlayButton();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        saveProgress();
        if (player != null) {
            player.removeListener(this);
            player.release();
            player = null;
        }
        super.onDestroy();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
