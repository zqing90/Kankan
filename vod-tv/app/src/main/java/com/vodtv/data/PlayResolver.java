package com.vodtv.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 按片名到各片源站点检索，解析出可直接播放的 m3u8，并按稳定性排序。
 *
 * 每个源会走三步：
 *   1. ac=detail&wd=片名 搜索，挑同名且非「解说/预告/花絮」的条目
 *   2. 拆 vod_play_url（# 分集、$ 分名/址），分享页再二次解析出真实 m3u8
 *   3. 探测 m3u8 是否真能取到（决定这次算成功还是失败）
 *
 * 结果会写回 SourceStats，下一次打开时默认源的排序就会自动优化。
 */
public class PlayResolver {

    public static class PlayLine {
        public String sourceId;
        public String sourceName;
        public String url;      // 探针命中的那一集的原始地址
        public String media;    // 解析后的真实 m3u8
        public String label;    // 集名，如 HD / 第01集
        public boolean ok;
        public long ms;
        public double score;
        public String error;
        public List<Ep> eps = new ArrayList<Ep>();   // 该线路的全部剧集
    }

    /** 一集：名字 + 原始地址（地址可能是 m3u8，也可能是分享页，播放前才解析） */
    public static class Ep {
        public String label;
        public String url;
    }

    public interface Progress {
        void onLine(PlayLine line);
    }

    public static List<PlayLine> search(final String keyword, final Progress cb) {
        final List<SourceConfig> sources = SourceStore.enabled();
        final List<PlayLine> out = Collections.synchronizedList(new ArrayList<PlayLine>());
        ExecutorService pool = Executors.newFixedThreadPool(4);
        final CountDownLatch latch = new CountDownLatch(sources.size());

        for (final SourceConfig s : sources) {
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        PlayLine line = resolveOne(s, keyword);
                        if (line != null) {
                            out.add(line);
                            if (cb != null) cb.onLine(line);
                        }
                    } finally {
                        latch.countDown();
                    }
                }
            });
        }
        try {
            latch.await(35, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        pool.shutdownNow();

        Collections.sort(out, new Comparator<PlayLine>() {
            @Override
            public int compare(PlayLine a, PlayLine b) {
                if (a.ok != b.ok) return a.ok ? -1 : 1;
                if (Math.abs(b.score - a.score) > 0.001) return b.score > a.score ? 1 : -1;
                return (int) (a.ms - b.ms);
            }
        });

        // 成功/失败写回统计，用于下次默认源排序
        for (PlayLine l : out) {
            if (l.ok) SourceStats.recordOk(l.sourceId, l.ms);
            else SourceStats.recordFail(l.sourceId);
        }
        return out;
    }

    /** 单个源的完整解析流程 */
    private static PlayLine resolveOne(SourceConfig s, String keyword) {
        long t0 = System.currentTimeMillis();
        PlayLine line = new PlayLine();
        line.sourceId = s.id;
        line.sourceName = s.name;
        try {
            String url = s.endpoint() + "?ac=detail&at=json&wd=" + java.net.URLEncoder.encode(keyword, "UTF-8");
            String body = Http.getText(url, Http.headers(Http.UA_DESKTOP, null), 12000);
            JSONObject j = new JSONObject(body);
            JSONArray list = j.optJSONArray("list");
            if (list == null || list.length() == 0) {
                line.error = "未收录";
                line.ms = System.currentTimeMillis() - t0;
                return line;
            }

            JSONObject pick = pickBest(list, keyword);
            if (pick == null) {
                line.error = "无匹配条目";
                line.ms = System.currentTimeMillis() - t0;
                return line;
            }

            String playUrl = pick.optString("vod_play_url", "");
            line.eps = splitEpisodes(playUrl);
            if (line.eps.isEmpty()) {
                line.error = "无播放地址";
                line.ms = System.currentTimeMillis() - t0;
                return line;
            }

            // 该线路有多集时，抽第 1 集和最后一集各探一次，任一条能取到流就算这条线路可用
            List<Ep> tryList = new ArrayList<Ep>();
            if (line.eps.size() == 1) {
                tryList.add(line.eps.get(0));
            } else {
                tryList.add(line.eps.get(0));
                tryList.add(line.eps.get(line.eps.size() - 1));
            }

            for (Ep ep : tryList) {
                String media = toMedia(ep.url);
                if (media != null && media.length() > 0 && probe(media)) {
                    line.url = ep.url;
                    line.label = ep.label;
                    line.media = media;
                    line.ok = true;
                    break;
                }
            }
            if (!line.ok) line.error = "取流失败";
            line.ms = System.currentTimeMillis() - t0;
            line.score = SourceStats.score(s);
            return line;
        } catch (Exception e) {
            line.error = e.getMessage() == null ? "异常" : e.getMessage();
            line.ms = System.currentTimeMillis() - t0;
            return line;
        }
    }

    /**
     * 同名条目里挑最合适的：
     * 完全同名优先，其次是前缀匹配；「解说/预告/花絮/速看」直接降权。
     */
    private static JSONObject pickBest(JSONArray list, String keyword) {
        String want = clean(keyword);
        JSONObject best = null;
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.optJSONObject(i);
            if (o == null) continue;
            String name = clean(o.optString("vod_name", ""));
            int score = 0;
            if (name.equals(want)) score += 100;
            else if (name.startsWith(want) || want.startsWith(name)) score += 40;
            if (o.optString("vod_name", "").matches(".*(解说|预告|花絮|片段|速看|混剪|彩蛋).*")) score -= 200;
            if (i == 0) score += 5;
            String pu = o.optString("vod_play_url", "");
            if (pu.contains(".m3u8")) score += 5;
            if (score > bestScore) {
                bestScore = score;
                best = o;
            }
        }
        return bestScore >= 40 ? best : null;
    }

    private static String clean(String s) {
        if (s == null) return "";
        return s.replaceAll("[（(\\[【].*?[)）\\]】]", "").replaceAll("\\s+", "").trim();
    }

    /**
     * vod_play_url 是三级结构：`$$$` 分线路、`#` 分集、`$` 分隔集名和地址。
     *
     * 多数站点一条记录里塞两条线路：一条全是 /share/ 分享页，一条全是直链 m3u8。
     * 这里挑「直链占比最高」的那条，选集时点开就能播，不用再解析一次 HTML。
     */
    public static List<Ep> splitEpisodes(String playUrl) {
        List<Ep> out = new ArrayList<Ep>();
        if (playUrl == null || playUrl.length() == 0) return out;

        String[] lines = playUrl.split("\\$\\$\\$");
        String best = lines[0];
        int bestScore = -1;
        for (String ln : lines) {
            int score = 0;
            for (String p : ln.split("#")) {
                if (p == null || p.trim().length() == 0) continue;
                boolean direct = p.contains(".m3u8") && !p.contains("/share/");
                score += direct ? 2 : 1;
            }
            if (score > bestScore) {
                bestScore = score;
                best = ln;
            }
        }

        for (String p : best.split("#")) {
            if (p == null || p.trim().length() == 0) continue;
            int d = p.indexOf('$');
            Ep e = new Ep();
            e.label = d > 0 ? p.substring(0, d).trim() : "正片";
            e.url = (d > 0 ? p.substring(d + 1) : p).trim();
            if (e.url.length() > 0) out.add(e);
        }
        return out;
    }

    /** 原始地址 → 真实 m3u8（m3u8 直接用，分享页去 HTML 里抠） */
    public static String toMedia(String raw) {
        if (raw == null) return null;
        String media = raw;
        if (media.contains("/share/") || !media.contains(".m3u8")) {
            media = extractM3u8(media);
        }
        return media;
    }

    /**
     * 点某一集时才解析。返回可直接播放的 m3u8，失败返回 null。
     * 单独抽出来是因为批量预解析所有集会拖慢首屏（40 集就是 40 次请求）。
     */
    public static String resolveEp(Ep ep) {
        if (ep == null) return null;
        String media = toMedia(ep.url);
        if (media == null || media.length() == 0) return null;
        return probe(media) ? media : null;
    }

    /** 从分享页 HTML 里抠出真实 m3u8 */
    private static String extractM3u8(String pageUrl) {
        try {
            String html = Http.getText(pageUrl, Http.headers(Http.UA_DESKTOP, pageUrl), 12000);
            if (html.contains("#EXTM3U")) return pageUrl;

            Pattern abs = Pattern.compile("(https?://[^\"'\\s\\\\]+\\.m3u8[^\"'\\s\\\\]*)");
            Matcher m = abs.matcher(html);
            if (m.find()) return m.group(1);

            Pattern rel = Pattern.compile("[\"'](/[^\"']*\\.m3u8[^\"']*)[\"']");
            Matcher m2 = rel.matcher(html);
            if (m2.find()) {
                String path = m2.group(1);
                java.net.URL base = new java.net.URL(pageUrl);
                return new java.net.URL(base, path).toString();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 真取一次 m3u8，确认不是 404 / 空列表 */
    private static boolean probe(String m3u8) {
        try {
            String body = Http.getText(m3u8, Http.headers(Http.UA_DESKTOP, null), 10000);
            return body != null && body.contains("#EXTM3U");
        } catch (Exception e) {
            return false;
        }
    }

    /** 详情页展示用：这个源的历史表现 */
    public static String statLine(String sourceId) {
        return SourceStats.summary(sourceId);
    }
}
