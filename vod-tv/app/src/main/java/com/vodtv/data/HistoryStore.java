package com.vodtv.data;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.Iterator;

/**
 * 播放进度。
 *
 * 一部片只留一条最新记录（看了第 2 集就覆盖第 1 集），够用且不会越滚越大。
 * 存的是「片名 → {集名, 源, 位置, 时长, 时间}」，片名就是详情页标题，稳定不重复。
 *
 * 续播判断放在 {@link #resumePos}：不足 30 秒、或离结尾不到 1 分钟，都从头开始，
 * 免得用户刚点开就被拖到片尾、或者被拖到片尾只剩字幕。
 */
public class HistoryStore {

    /**
     * 低于这个位置就不写记录。
     * 定 5 秒而不是 30 秒：用户点开看一眼就退出的情况很常见，
     * 30 秒门槛会让「观看记录」看起来像坏了（2026-11-06 反馈「看不到播放记录」的直接原因）。
     */
    private static final long MIN_POS = 5 * 1000L;
    /** 低于这个位置不续播：刚看 5 秒就跳回去没有意义，从头开始更符合直觉 */
    private static final long MIN_RESUME = 30 * 1000L;
    /** 距离结尾小于这个值就当作看完了 */
    private static final long TAIL = 60 * 1000L;
    /** 最多保留多少部片的记录 */
    public static final int MAX_ENTRIES = 80;

    public static class Entry {
        public String title;
        public String ep;        // 集名，如「第03集」「HD」
        public String sourceId;  // 上次用的是哪个源，续播时优先回到同一个
        public long pos;         // 毫秒
        public long dur;         // 毫秒，未知记 0
        public long at;          // 最后更新时间戳
        /** 影片信息快照。首页「继续观看」要用它重建详情页，不依赖榜单是否还在 */
        public MovieInfo info = new MovieInfo();

        public String posText() {
            return fmt(pos);
        }

        public String durText() {
            return dur > 0 ? fmt(dur) : "";
        }

        public int percent() {
            if (dur <= 0) return 0;
            return (int) Math.min(100, pos * 100 / dur);
        }
    }

    private static SharedPreferences sp;

    public static void init(Context c) {
        if (sp == null) {
            sp = c.getApplicationContext().getSharedPreferences("history", Context.MODE_PRIVATE);
        }
    }

    /* ---------- 写 ---------- */

    /**
     * 记一次进度。pos 太小或已经贴到结尾时，直接把这条记录删掉，
     * 下次进来就从头上播。
     */
    public static void save(String title, String ep, String sourceId,
                            long pos, long dur, MovieInfo info) {
        if (title == null || title.length() == 0) return;
        try {
            JSONObject all = all();

            boolean useless = pos < MIN_POS || (dur > 0 && pos > dur - TAIL);
            if (useless) {
                if (all.has(title)) {
                    all.remove(title);
                    commit(all);
                }
                return;
            }

            // 已有记录里的影片信息不要被空值盖掉：有的播放入口只带片名
            MovieInfo merged = info == null ? new MovieInfo() : info;
            JSONObject old = all.optJSONObject(title);
            if (old != null) {
                MovieInfo prev = MovieInfo.fromJson(old.optJSONObject("info"));
                if (merged.pic.length() == 0) merged.pic = prev.pic;
                if (merged.year.length() == 0) merged.year = prev.year;
                if (merged.genres.length() == 0) merged.genres = prev.genres;
                if (merged.comment.length() == 0) merged.comment = prev.comment;
                if (merged.director.length() == 0) merged.director = prev.director;
                if (merged.actors.length() == 0) merged.actors = prev.actors;
                if (merged.episodes.length() == 0) merged.episodes = prev.episodes;
                if (merged.rating == 0) merged.rating = prev.rating;
                if (merged.ratingCount == 0) merged.ratingCount = prev.ratingCount;
            }
            merged.title = title;

            JSONObject o = new JSONObject();
            o.put("ep", ep == null ? "" : ep);
            o.put("src", sourceId == null ? "" : sourceId);
            o.put("pos", pos);
            o.put("dur", dur);
            o.put("at", System.currentTimeMillis());
            o.put("info", merged.toJson());
            all.put(title, o);

            prune(all);
            commit(all);
        } catch (Exception ignored) {
        }
    }

    /* ---------- 读 ---------- */

    /** 这部片看到哪了；没有记录返回 null */
    public static Entry get(String title) {
        if (title == null || title.length() == 0) return null;
        try {
            JSONObject o = all().optJSONObject(title);
            return o == null ? null : read(title, o);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 最近看过的片，按时间倒序。首页「继续观看」横条用。
     * 只返回 pos 有效（能续播）的记录——刚看几秒的片放上去，点进去却从头播，反而奇怪。
     * 完整的「看过」清单走 {@link #all(int)}。
     */
    public static java.util.List<Entry> recent(int limit) {
        return query(limit, MIN_RESUME);
    }

    /**
     * 全部观看记录（含只看了几秒的），按时间倒序。观看记录页用。
     */
    public static java.util.List<Entry> all(int limit) {
        return query(limit, MIN_POS);
    }

    private static java.util.List<Entry> query(int limit, long minPos) {
        java.util.List<Entry> out = new java.util.ArrayList<Entry>();
        try {
            JSONObject all = all();
            java.util.List<Entry> tmp = new java.util.ArrayList<Entry>();
            Iterator<String> it = all.keys();
            while (it.hasNext()) {
                String k = it.next();
                JSONObject o = all.optJSONObject(k);
                if (o == null) continue;
                Entry e = read(k, o);
                if (e.pos >= minPos) tmp.add(e);
            }
            java.util.Collections.sort(tmp, new java.util.Comparator<Entry>() {
                @Override
                public int compare(Entry a, Entry b) {
                    return a.at == b.at ? 0 : (a.at > b.at ? -1 : 1);
                }
            });
            for (int i = 0; i < tmp.size() && i < limit; i++) out.add(tmp.get(i));
        } catch (Exception ignored) {
        }
        return out;
    }

    private static Entry read(String title, JSONObject o) {
        Entry e = new Entry();
        e.title = title;
        e.ep = o.optString("ep", "");
        e.sourceId = o.optString("src", "");
        e.pos = o.optLong("pos", 0);
        e.dur = o.optLong("dur", 0);
        e.at = o.optLong("at", 0);
        e.info = MovieInfo.fromJson(o.optJSONObject("info"));
        if (e.info == null) e.info = new MovieInfo();
        e.info.title = title;
        return e;
    }

    /**
     * 该不该续播、从哪开始。返回 0 表示从头播。
     * 集名对不上（用户手动换了别的集）时也从头播，避免张冠李戴。
     */
    public static long resumePos(Entry e, String ep) {
        if (e == null) return 0;
        if (ep != null && ep.length() > 0 && e.ep != null && e.ep.length() > 0 && !ep.equals(e.ep)) {
            return 0;
        }
        if (e.pos < MIN_RESUME) return 0;
        if (e.dur > 0 && e.pos > e.dur - TAIL) return 0;
        return e.pos;
    }

    public static void clear(String title) {
        try {
            JSONObject all = all();
            if (all.has(title)) {
                all.remove(title);
                commit(all);
            }
        } catch (Exception ignored) {
        }
    }

    /** 清空全部记录（观看记录页的「清空」） */
    public static void clearAll() {
        try {
            commit(new JSONObject());
        } catch (Exception ignored) {
        }
    }

    /* ---------- 内部 ---------- */

    private static JSONObject all() {
        String s = sp.getString("data", "{}");
        try {
            return new JSONObject(s);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static void commit(JSONObject all) {
        sp.edit().putString("data", all.toString()).apply();
    }

    /** 超出上限就按时间戳淘汰最旧的 */
    private static void prune(JSONObject all) {
        try {
            if (all.length() <= MAX_ENTRIES) return;
            String oldestKey = null;
            long oldest = Long.MAX_VALUE;
            Iterator<String> it = all.keys();
            while (it.hasNext()) {
                String k = it.next();
                JSONObject o = all.optJSONObject(k);
                long at = o == null ? 0 : o.optLong("at", 0);
                if (at < oldest) {
                    oldest = at;
                    oldestKey = k;
                }
            }
            if (oldestKey != null) all.remove(oldestKey);
        } catch (Exception ignored) {
        }
    }

    public static String fmt(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000;
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        if (h > 0) return String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, sec);
        return String.format(java.util.Locale.US, "%02d:%02d", m, sec);
    }
}
