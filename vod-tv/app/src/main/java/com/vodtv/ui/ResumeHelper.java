package com.vodtv.ui;

import android.app.Activity;

import com.vodtv.data.HistoryStore;
import com.vodtv.data.PlayCache;
import com.vodtv.data.PlayResolver;
import com.vodtv.data.SourceStore;

import java.util.List;

/**
 * 「点一条观看记录 → 继续播放」的唯一实现。
 *
 * 首页顶部横条和观看记录页都调这里，避免两份逻辑各自跑偏
 * （1.4.0 之前首页只跳详情页，详情页检索完对不上集就静默停住，
 *  用户看到的就是「点了没反应」）。
 *
 * 三级降级：
 *   1. 当天缓存命中 → 直接拿已验证的线路，只解析这一集的流，1~2 秒；
 *   2. 没缓存 → 重新检索各站（十几秒，必须上报进度）；
 *   3. 拿不到流 → 交给详情页（autoResume），那里有全部片源可以手挑。
 *
 * 回调一律在主线程。
 */
public final class ResumeHelper {

    /** 失败原因，用于给用户一句人话 */
    public static final int FAIL_NO_SOURCE = 0;   // 一个源都没搜到
    public static final int FAIL_NO_EP = 1;       // 搜到了，但对不上上次看的那一集
    public static final int FAIL_NO_MEDIA = 2;    // 集找到了，取流失败

    public interface Cb {
        /** 进度提示，文字会直接显示在遮罩上 */
        void onBusy(String text);

        /** 拿到了可播地址，直接进播放页 */
        void onPlay(PlayResolver.PlayLine line, PlayResolver.Ep ep, String media, HistoryStore.Entry e);

        /** 拿不到流，调用方自己决定是跳详情页还是提示 */
        void onGiveUp(HistoryStore.Entry e, int reason);
    }

    private ResumeHelper() {
    }

    public static void resume(Activity from, HistoryStore.Entry e, Cb cb) {
        if (from == null || e == null || cb == null) return;
        final Activity act = from;
        final HistoryStore.Entry entry = e;
        final Cb callback = cb;

        cb.onBusy(act.getString(com.vodtv.R.string.resume_preparing));

        new Thread(new Runnable() {
            @Override
            public void run() {
                int reason = FAIL_NO_SOURCE;
                String media = null;
                PlayResolver.PlayLine line = null;
                PlayResolver.Ep ep = null;

                try {
                    PlayCache.Cached cache = PlayCache.load(entry.title);
                    boolean checkedToday = cache != null && cache.fresh();
                    if (checkedToday) {
                        line = pickLine(cache.lines, entry.sourceId);
                        ep = pickEp(line, entry.ep);
                    }
                    if (!checkedToday) {
                        final int n = SourceStore.enabled().size();
                        post(act, callback, act.getString(com.vodtv.R.string.resume_searching, n));
                        List<PlayResolver.PlayLine> list = PlayResolver.search(entry.title, null);
                        line = pickLine(list, entry.sourceId);
                        ep = pickEp(line, entry.ep);
                    }

                    if (line == null) {
                        reason = FAIL_NO_SOURCE;
                    } else if (ep == null) {
                        reason = FAIL_NO_EP;
                    } else {
                        // 检索时探的就是这一集，直接复用，不再多打一次请求
                        if (line.media != null && line.media.length() > 0
                                && ep.label != null && ep.label.equals(line.label)) {
                            media = line.media;
                        } else {
                            media = PlayResolver.resolveEp(ep);
                        }
                        reason = (media != null && media.length() > 0) ? -1 : FAIL_NO_MEDIA;
                    }
                } catch (Throwable t) {
                    // 线程里抛异常会带走整个进程，用户看到的就是「点了没反应」
                    media = null;
                    reason = FAIL_NO_SOURCE;
                }

                final PlayResolver.PlayLine fLine = line;
                final PlayResolver.Ep fEp = ep;
                final String fMedia = media;
                final int fReason = reason;
                act.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (act.isFinishing()) return;
                        if (fReason < 0 && fMedia != null && fMedia.length() > 0) {
                            callback.onPlay(fLine, fEp, fMedia, entry);
                        } else {
                            callback.onGiveUp(entry, fReason);
                        }
                    }
                });
            }
        }, "resume-play").start();
    }

    private static void post(final Activity act, final Cb cb, final String text) {
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (!act.isFinishing()) cb.onBusy(text);
            }
        });
    }

    /** 优先上次那个源；那个源挂了就用第一条可播的 */
    static PlayResolver.PlayLine pickLine(List<PlayResolver.PlayLine> list, String sourceId) {
        if (list == null) return null;
        PlayResolver.PlayLine fallback = null;
        for (PlayResolver.PlayLine l : list) {
            if (l == null || !l.ok) continue;
            if (sourceId != null && sourceId.length() > 0 && sourceId.equals(l.sourceId)) return l;
            if (fallback == null) fallback = l;
        }
        return fallback;
    }

    /**
     * 找上次那一集。顺序：集名精确匹配 → 该线路已验证可播的那一集 → 单集片直接认。
     * 都对不上返回 null，交给详情页让人自己挑，别瞎播一集。
     */
    static PlayResolver.Ep pickEp(PlayResolver.PlayLine l, String label) {
        if (l == null || l.eps == null || l.eps.isEmpty()) return null;
        if (label != null && label.length() > 0) {
            for (PlayResolver.Ep ep : l.eps) {
                if (label.equals(ep.label)) return ep;
            }
        }
        if (l.label != null && l.label.length() > 0) {
            for (PlayResolver.Ep ep : l.eps) {
                if (l.label.equals(ep.label)) return ep;
            }
        }
        return l.eps.size() == 1 ? l.eps.get(0) : null;
    }
}
