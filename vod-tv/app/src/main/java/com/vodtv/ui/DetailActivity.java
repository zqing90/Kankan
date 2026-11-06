package com.vodtv.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.vodtv.R;
import com.vodtv.data.HistoryStore;
import com.vodtv.data.ImageLoader;
import com.vodtv.data.MovieInfo;
import com.vodtv.data.PlayCache;
import com.vodtv.data.PlayResolver;
import com.vodtv.data.SourceConfig;
import com.vodtv.data.SourceStats;
import com.vodtv.data.SourceStore;
import com.vodtv.player.PlayerActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 详情页：上半是影片信息，下半是片源列表。
 * 片源按稳定性排序，第一条即「默认播放源」，进页面后焦点直接落在它上面。
 */
public class DetailActivity extends Activity {

    private LinearLayout linesBox;
    private ScrollView scroll;
    private TextView linesTitle;
    private TextView btnRetry;
    private String title;
    private MovieInfo info = new MovieInfo();
    /** 本次结果来自缓存的时间戳，0 表示刚检测完 */
    private long cacheAt = 0;

    private List<PlayResolver.PlayLine> currentLines = new java.util.ArrayList<PlayResolver.PlayLine>();
    private boolean inEpisodes = false;

    private TextView btnResume;
    private View btnBack;
    private TextView btnBackLabel;
    private HistoryStore.Entry resumeEntry;
    private PlayResolver.PlayLine resumeLine;
    private PlayResolver.Ep resumeEp;
    /**
     * 从观看记录 / 首页「继续看」进来时置 true：检索完成后直接续播，不用再按一次 OK。
     * 只消费一次——之后用户手动重测或换源，不该又被自动跳进播放页。
     */
    private boolean autoResumePending;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);

        ImageLoader.init(this);

        title = getIntent().getStringExtra("title");
        info = MovieInfo.fromIntent(getIntent());
        if (info.title.length() == 0) info.title = title;
        autoResumePending = getIntent().getBooleanExtra("autoResume", false);
        linesBox = (LinearLayout) findViewById(R.id.lines);
        scroll = (ScrollView) findViewById(R.id.scroll);
        linesTitle = (TextView) findViewById(R.id.lines_title);
        btnRetry = (TextView) findViewById(R.id.btn_retry);

        bindInfo();

        btnBack = findViewById(R.id.btn_back);
        btnBackLabel = (TextView) findViewById(R.id.back_label);
        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 和物理返回键保持一致：选集态先退回片源列表，再按才退出详情页。
                // 按钮文字会跟着变，用户不会以为它失灵
                if (inEpisodes) {
                    renderLines(currentLines);
                } else {
                    finish();
                }
            }
        });

        findViewById(R.id.btn_retry).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 手动重测：丢掉当天缓存，强制重新跑一遍所有源
                loadSources(true);
            }
        });

        btnResume = (TextView) findViewById(R.id.btn_resume);
        btnResume.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                continuePlay();
            }
        });

        loadSources();
    }

    private void bindInfo() {
        ((TextView) findViewById(R.id.title)).setText(title);

        TextView rating = (TextView) findViewById(R.id.rating);
        if (info.rating > 0) {
            rating.setVisibility(View.VISIBLE);
            rating.setText(String.format(Locale.US, "%.1f", info.rating));
        }

        StringBuilder meta = new StringBuilder();
        if (info.year.length() > 0) meta.append(info.year);
        if (info.episodes.length() > 0) {
            if (meta.length() > 0) meta.append(" · ");
            meta.append(info.episodes);
        }
        if (info.ratingCount > 0) {
            if (meta.length() > 0) meta.append("  ");
            meta.append(info.ratingCount).append(" 人评价");
        }
        ((TextView) findViewById(R.id.meta)).setText(meta.toString());

        TextView g = (TextView) findViewById(R.id.genres);
        if (info.genres.length() > 0) {
            g.setText(info.genres.replace(" / ", "  ·  "));
        } else {
            g.setVisibility(View.GONE);
        }

        TextView desc = (TextView) findViewById(R.id.desc);
        desc.setText(info.comment.length() == 0 ? "暂无简介" : info.comment);

        StringBuilder cr = new StringBuilder();
        if (info.director.length() > 0) cr.append("导演 ").append(info.director);
        if (info.actors.length() > 0) {
            if (cr.length() > 0) cr.append("   ");
            cr.append("主演 ").append(info.actors);
        }
        ((TextView) findViewById(R.id.credits)).setText(cr.toString());

        ImageLoader.load((ImageView) findViewById(R.id.poster), info.pic, 400);
    }

    /* ---------- 片源检索 ---------- */

    /** 当天已经检测过就直接用缓存，省掉 14 个站点的重复请求 */
    private void loadSources() {
        loadSources(false);
    }

    private void loadSources(boolean force) {
        if (!force) {
            PlayCache.Cached c = PlayCache.load(title);
            if (c != null && c.fresh()) {
                cacheAt = c.at;
                renderLines(c.lines);
                return;
            }
        }

        cacheAt = 0;
        linesBox.removeAllViews();
        linesTitle.setText("片源（按稳定性排序）");
        btnRetry.setEnabled(false);
        TextView loading = new TextView(this);
        loading.setText("正在检索 " + SourceStore.enabled().size() + " 个片源站点…");
        loading.setTextColor(getResources().getColor(R.color.text_secondary));
        loading.setTextSize(13);
        loading.setPadding(0, dp(10), 0, 0);
        linesBox.addView(loading);

        final String kw = title;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<PlayResolver.PlayLine> result = PlayResolver.search(kw, null);
                // 一个可播的都没有就别写缓存，免得把昨天还能用的结果盖掉
                boolean any = false;
                for (PlayResolver.PlayLine l : result) {
                    if (l.ok) {
                        any = true;
                        break;
                    }
                }
                final boolean anyOk = any;
                if (anyOk) PlayCache.save(kw, result);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        btnRetry.setEnabled(true);
                        cacheAt = anyOk ? System.currentTimeMillis() : 0;
                        renderLines(result);
                    }
                });
            }
        }, "resolve").start();
    }

    private void renderLines(List<PlayResolver.PlayLine> list) {
        inEpisodes = false;
        if (btnBackLabel != null) btnBackLabel.setText(R.string.back);
        currentLines = list;
        linesBox.removeAllViews();
        scroll.scrollTo(0, 0);
        HistoryStore.Entry h = HistoryStore.get(title);
        int okCount = 0;
        for (PlayResolver.PlayLine l : list) if (l.ok) okCount++;
        StringBuilder head = new StringBuilder("片源（")
                .append(okCount).append(" 个可播 / 共 ").append(list.size()).append(" 个源）");
        if (cacheAt > 0) head.append("  ·  ").append(cacheTimeText(cacheAt)).append("检测，今日不再重复检测");
        linesTitle.setText(head.toString());
        // 用缓存时按钮明说「重新检测」，用户才知道源挂了可以自己救
        btnRetry.setText(cacheAt > 0 ? "重新检测" : "重新检索");

        if (list.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("没有检索到片源。可能是片名不匹配，或这些站点暂未收录。");
            t.setTextColor(getResources().getColor(R.color.text_secondary));
            t.setTextSize(13);
            linesBox.addView(t);
            return;
        }

        View firstPlayable = null;
        for (int i = 0; i < list.size(); i++) {
            final PlayResolver.PlayLine l = list.get(i);
            View row = LayoutInflater.from(this).inflate(R.layout.item_line, linesBox, false);

            TextView name = (TextView) row.findViewById(R.id.line_name);
            TextView spec = (TextView) row.findViewById(R.id.line_spec);
            TextView badge = (TextView) row.findViewById(R.id.line_badge);
            TextView stat = (TextView) row.findViewById(R.id.line_stat);

            String epInfo = (l.eps != null && l.eps.size() > 1)
                    ? "  ·  共 " + l.eps.size() + " 集" : "";
            name.setText(l.sourceName + "  ·  " + (l.label == null ? "正片" : l.label) + epInfo);

            if (l.ok) {
                spec.setText(l.ms + "ms");
                if (h != null && h.sourceId != null && h.sourceId.equals(l.sourceId)) {
                    stat.setText("上次看到 " + (h.ep == null ? "" : h.ep) + " " + h.posText());
                } else {
                    stat.setText(SourceStats.hasHistory(l.sourceId)
                            ? SourceStats.summary(l.sourceId) : "首次使用");
                }
                if (firstPlayable == null) {
                    badge.setVisibility(View.VISIBLE);
                    badge.setText("推荐");
                    firstPlayable = row;
                }
            } else {
                name.setTextColor(getResources().getColor(R.color.text_tertiary));
                spec.setText(l.error == null ? "不可用" : l.error);
                stat.setText("");
                row.setFocusable(false);
                row.setAlpha(0.45f);
            }

            // 当前锁定的源额外标一下
            SourceConfig cfg = SourceStore.byId(l.sourceId);
            if (cfg != null && cfg.id.equals(com.vodtv.data.Prefs.pinnedSource()) && l.ok) {
                badge.setVisibility(View.VISIBLE);
                badge.setText("已锁定");
            }

            if (l.ok) {
                row.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openLine(l);
                    }
                });
            }

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            linesBox.addView(row, lp);
        }

        updateResume(h);

        // 从记录页 / 首页「继续看」点进来：检索一完成就直接续播。
        // 以前这里只是把焦点放在「继续看」上，用户还得再按一次 OK
        //（加上要等检索十几秒）—— 感受就是「点了没反应、没法继续播放」。
        boolean wantResume = autoResumePending && btnResume.getVisibility() == View.VISIBLE;
        if (wantResume) {
            autoResumePending = false;
            btnResume.post(new Runnable() {
                @Override
                public void run() {
                    continuePlay();
                }
            });
            return;
        }
        autoResumePending = false;

        final View first = firstPlayable;
        if (first != null) {
            first.post(new Runnable() {
                @Override
                public void run() {
                    first.requestFocus();
                }
            });
        }
    }

    /* ---------- 续播 ---------- */

    /**
     * 有历史记录、并且当前检索到的线路里能找到那一集，才亮出「继续观看」。
     * 优先回到上次用的那个源；那个源挂了就退而求其次，找任何一个含同集名的线路。
     */
    private void updateResume(HistoryStore.Entry h) {
        resumeEntry = h;
        resumeLine = null;
        resumeEp = null;
        btnResume.setVisibility(View.GONE);
        if (h == null || h.pos <= 0) return;

        for (PlayResolver.PlayLine l : currentLines) {
            if (!l.ok) continue;
            if (h.sourceId != null && h.sourceId.length() > 0 && h.sourceId.equals(l.sourceId)) {
                resumeLine = l;
                resumeEp = findEp(l, h.ep);
                break;
            }
        }
        if (resumeLine == null) {
            for (PlayResolver.PlayLine l : currentLines) {
                if (!l.ok) continue;
                PlayResolver.Ep e = findEp(l, h.ep);
                if (e != null) {
                    resumeLine = l;
                    resumeEp = e;
                    break;
                }
            }
        }
        if (resumeLine == null) return;

        StringBuilder txt = new StringBuilder("继续看 ");
        if (h.ep != null && h.ep.length() > 0) txt.append(h.ep).append("  ");
        txt.append(h.posText());
        if (h.dur > 0) txt.append(" / ").append(h.durText());
        btnResume.setText(txt.toString());
        btnResume.setVisibility(View.VISIBLE);
    }

    private PlayResolver.Ep findEp(PlayResolver.PlayLine l, String label) {
        if (l.eps == null || l.eps.isEmpty()) return null;
        if (label != null && label.length() > 0) {
            for (PlayResolver.Ep e : l.eps) {
                if (label.equals(e.label)) return e;
            }
        }
        // 单集（电影）集名对不上也认，多集则要求集名一致，避免播错
        return l.eps.size() == 1 ? l.eps.get(0) : null;
    }

    /** 点「继续观看」：解析上次那一集，直接进播放页，不用再选集 */
    private void continuePlay() {
        if (resumeLine == null) return;
        final PlayResolver.PlayLine line = resumeLine;
        final PlayResolver.Ep ep = resumeEp != null ? resumeEp
                : (line.eps.isEmpty() ? null : line.eps.get(0));
        if (ep == null) {
            play(line, null, line.media);
            return;
        }
        btnResume.setEnabled(false);
        btnResume.setText("正在解析 " + ep.label + "…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String media = PlayResolver.resolveEp(ep);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        btnResume.setEnabled(true);
                        if (media == null) {
                            btnResume.setText("这一集取流失败，请换源");
                        } else {
                            updateResume(HistoryStore.get(title));
                            play(line, ep, media);
                        }
                    }
                });
            }
        }, "resume").start();
    }

    /* ---------- 选集 ---------- */

    /** 点线路：只有一条就直连播放，多集先进选集页 */
    private void openLine(PlayResolver.PlayLine l) {
        if (l.eps != null && l.eps.size() > 1) {
            showEpisodes(l);
        } else {
            PlayResolver.Ep only = (l.eps != null && l.eps.size() == 1) ? l.eps.get(0) : null;
            play(l, only, l.media);
        }
    }

    private void showEpisodes(final PlayResolver.PlayLine l) {
        inEpisodes = true;
        if (btnBackLabel != null) btnBackLabel.setText("片源列表");
        linesBox.removeAllViews();
        linesTitle.setText("选集 · " + l.sourceName + "（共 " + l.eps.size() + " 集）");
        scroll.scrollTo(0, 0);

        HistoryStore.Entry h = HistoryStore.get(title);
        String lastEp = (h != null && l.sourceId != null && l.sourceId.equals(h.sourceId)) ? h.ep : null;

        View back = LayoutInflater.from(this).inflate(R.layout.item_line, linesBox, false);
        ((TextView) back.findViewById(R.id.line_name)).setText("← 返回片源列表");
        ((TextView) back.findViewById(R.id.line_spec)).setText("");
        ((TextView) back.findViewById(R.id.line_stat)).setText("");
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                renderLines(currentLines);
            }
        });
        linesBox.addView(back, rowParams());

        View firstEp = null;
        View lastWatch = null;
        final String lastPosText = (h != null && lastEp != null) ? h.posText() : "";
        for (int i = 0; i < l.eps.size(); i++) {
            final PlayResolver.Ep ep = l.eps.get(i);
            final View row = LayoutInflater.from(this).inflate(R.layout.item_line, linesBox, false);
            final TextView name = (TextView) row.findViewById(R.id.line_name);
            final TextView spec = (TextView) row.findViewById(R.id.line_spec);
            final TextView badge = (TextView) row.findViewById(R.id.line_badge);

            name.setText(ep.label);
            // 探针已解析过的那一集打个标，说明这条是确认能播的
            if (ep.label != null && ep.label.equals(l.label)) {
                badge.setVisibility(View.VISIBLE);
                badge.setText("已验证");
            }
            boolean isLast = lastEp != null && lastEp.equals(ep.label);
            spec.setText(isLast ? "上次看到 " + lastPosText : "");
            if (isLast) lastWatch = row;

            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    spec.setText("解析中…");
                    resolveAndPlay(l, ep, spec);
                }
            });

            linesBox.addView(row, rowParams());
            if (firstEp == null) firstEp = row;
        }

        // 有上次记录就直接把焦点放在那一集上，按 OK 就接着看
        final View target = lastWatch != null ? lastWatch : firstEp;
        if (target != null) {
            final View f = target;
            f.post(new Runnable() {
                @Override
                public void run() {
                    f.requestFocus();
                }
            });
        }
    }

    private void resolveAndPlay(final PlayResolver.PlayLine l,
                                final PlayResolver.Ep ep,
                                final TextView spec) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String media = PlayResolver.resolveEp(ep);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (media == null) {
                            spec.setText("这一集取流失败");
                        } else {
                            spec.setText("");
                            play(l, ep, media);
                        }
                    }
                });
            }
        }, "ep-resolve").start();
    }

    /**
     * 进播放页。
     *
     * 除了当前这一集的地址，还把**整条线路的全部集数**带过去 —— 播放页要靠它做
     * 「上一集 / 下一集 / 选集」，否则用户在播放页里想换集只能退出来重选。
     * 集数只传 label 和原始 url，真正的 m3u8 解析放到播放页切集时再做（慢，得在子线程）。
     */
    private void play(PlayResolver.PlayLine l, PlayResolver.Ep ep, String media) {
        launchPlayer(this, l, ep, media, info);
    }

    /**
     * 跳播放页。观看记录页的「继续播放」也走这里，避免两处各打一份 Intent 参数。
     *
     * 除了当前这一集的地址，还把**整条线路的全部集数**带过去 —— 播放页要靠它做
     * 「上一集 / 下一集 / 选集」，否则用户在播放页里想换集只能退出来重选。
     * 集数只传 label 和原始 url，真正的 m3u8 解析放到播放页切集时再做（慢，得在子线程）。
     */
    public static void launchPlayer(Activity from, PlayResolver.PlayLine l, PlayResolver.Ep ep,
                                    String media, MovieInfo info) {
        if (from == null || l == null || media == null || media.length() == 0) return;

        Intent i = new Intent(from, PlayerActivity.class);
        i.putExtra("url", media);
        i.putExtra("source", l.sourceName);
        i.putExtra("sourceId", l.sourceId);
        i.putExtra("ep", ep != null ? ep.label : l.label);
        i.putExtra("spec", l.ms + "ms");

        if (l.eps != null && l.eps.size() > 1) {
            ArrayList<String> labels = new ArrayList<String>();
            ArrayList<String> urls = new ArrayList<String>();
            for (PlayResolver.Ep e : l.eps) {
                labels.add(e.label == null ? "" : e.label);
                urls.add(e.url == null ? "" : e.url);
            }
            i.putStringArrayListExtra("epLabels", labels);
            i.putStringArrayListExtra("epUrls", urls);
            i.putExtra("epIndex", indexOf(l.eps, ep));
        }

        // 整份影片信息一起带过去，播放页退出时写进历史，首页才能显示海报
        MovieInfo m = info != null ? info : new MovieInfo();
        if (m.title == null || m.title.length() == 0) m.title = l.label;
        m.toIntent(i);
        from.startActivity(i);
    }

    /** 先按引用找（正常路径都是同一个对象），找不到再按集名兜底 */
    private static int indexOf(List<PlayResolver.Ep> eps, PlayResolver.Ep ep) {
        if (ep == null) return 0;
        for (int i = 0; i < eps.size(); i++) {
            if (eps.get(i) == ep) return i;
        }
        for (int i = 0; i < eps.size(); i++) {
            if (ep.label != null && ep.label.equals(eps.get(i).label)) return i;
        }
        return 0;
    }

    /** 缓存时间的人话描述：「今日 20:15 检测」「11-05 20:15 检测」 */
    private String cacheTimeText(long at) {
        java.util.Date d = new java.util.Date(at);
        java.text.SimpleDateFormat day = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US);
        java.text.SimpleDateFormat hm = new java.text.SimpleDateFormat("HH:mm", Locale.US);
        if (day.format(d).equals(com.vodtv.data.Prefs.today())) return "今日 " + hm.format(d) + " ";
        return new java.text.SimpleDateFormat("MM-dd HH:mm", Locale.US).format(d) + " ";
    }

    private LinearLayout.LayoutParams rowParams() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从播放页退回来时进度已经变了，刷新一下「继续观看」上的时间
        if (btnResume != null && !currentLines.isEmpty()) {
            updateResume(HistoryStore.get(title));
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        // 选集页按返回，退回片源列表，而不是直接退出详情页
        if (keyCode == KeyEvent.KEYCODE_BACK && inEpisodes) {
            renderLines(currentLines);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
