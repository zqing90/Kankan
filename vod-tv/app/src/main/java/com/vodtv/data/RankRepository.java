package com.vodtv.data;

import android.content.Context;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 榜单仓库。
 *
 * 两条取数路径：
 *   1. 直连：直接打豆瓣 rexxar 的 17 个榜单（4 路并发，约 4 秒）
 *   2. 服务端：GET {server}/api/rank（一次请求，字段已归一化）
 *
 * 缓存落盘到 files/rank.json，App 启动先读缓存秒开，再按需后台刷新。
 */
public class RankRepository {

    public static class Board {
        public String id;
        public String label;
        public String group;
        public List<RankItem> items = new ArrayList<RankItem>();
    }

    public static class RankData {
        public long fetchedAt;
        public List<Board> boards = new ArrayList<Board>();

        public Board board(String id) {
            for (Board b : boards) if (b.id.equals(id)) return b;
            return boards.isEmpty() ? null : boards.get(0);
        }

        public List<String> groups() {
            List<String> g = new ArrayList<String>();
            for (Board b : boards) if (!g.contains(b.group)) g.add(b.group);
            return g;
        }

        public List<Board> boardsOf(String group) {
            List<Board> l = new ArrayList<Board>();
            for (Board b : boards) if (b.group.equals(group)) l.add(b);
            return l;
        }

        public int total() {
            int n = 0;
            for (Board b : boards) n += b.items.size();
            return n;
        }
    }

    public static final String GROUP_TV = "tv";
    public static final String GROUP_MOVIE = "movie";
    public static final String GROUP_SHOW = "show";
    public static final String GROUP_ANIME = "anime";
    public static final String GROUP_DOC = "doc";

    /** 与 Node 端 BOARDS 保持一致 */
    private static final String[][] BOARDS = {
            {"tv_hot", "近期热门剧集", GROUP_TV},
            {"tv_domestic", "热门国产剧", GROUP_TV},
            {"tv_american", "热门美剧", GROUP_TV},
            {"tv_korean", "热门韩剧", GROUP_TV},
            {"tv_japanese", "热门日剧", GROUP_TV},
            {"tv_chinese_best_weekly", "华语口碑剧集榜", GROUP_TV},
            {"tv_global_best_weekly", "全球口碑剧集榜", GROUP_TV},

            {"movie_hot_gaia", "豆瓣热门电影", GROUP_MOVIE},
            {"movie_showing", "影院热映", GROUP_MOVIE},
            {"movie_hot", "新片榜", GROUP_MOVIE},
            {"movie_weekly_best", "一周口碑电影榜", GROUP_MOVIE},
            {"movie_top250", "豆瓣电影 Top250", GROUP_MOVIE},

            {"show_hot", "近期热门综艺", GROUP_SHOW},
            {"show_domestic", "热门国内综艺", GROUP_SHOW},
            {"show_chinese_best_weekly", "国内口碑综艺榜", GROUP_SHOW},

            {"tv_animation", "近期热门动画", GROUP_ANIME},
            {"tv_documentary", "近期热门纪录片", GROUP_DOC},
    };

    public static String groupLabel(String g) {
        if (GROUP_TV.equals(g)) return "剧集";
        if (GROUP_MOVIE.equals(g)) return "电影";
        if (GROUP_SHOW.equals(g)) return "综艺";
        if (GROUP_ANIME.equals(g)) return "动画";
        if (GROUP_DOC.equals(g)) return "纪录片";
        return g;
    }

    private static File cacheFile(Context c) {
        return new File(c.getFilesDir(), "rank.json");
    }

    /* ---------- 读缓存 ---------- */

    public static RankData loadCache(Context c) {
        File f = cacheFile(c);
        if (!f.exists()) return null;
        try {
            String s = readText(f);
            return parseCache(new JSONObject(s));
        } catch (Exception e) {
            return null;
        }
    }

    /* ---------- 刷新 ---------- */

    public interface Listener {
        void onProgress(String msg);

        void onDone(RankData data, String error);
    }

    public static void refreshAsync(final Context ctx, final Listener l) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    RankData d = refresh(ctx, l);
                    Prefs.markRankUpdated();
                    if (l != null) l.onDone(d, null);
                } catch (Exception e) {
                    if (l != null) l.onDone(null, e.getMessage());
                }
            }
        }, "rank-refresh").start();
    }

    public static RankData refresh(Context ctx, Listener l) throws Exception {
        RankData d = Prefs.useServer() ? fetchFromServer(ctx, l) : fetchFromDouban(l);
        d.fetchedAt = System.currentTimeMillis();
        save(ctx, d);
        return d;
    }

    /** 直连豆瓣：17 个榜，4 路并发 */
    private static RankData fetchFromDouban(Listener l) throws Exception {
        final RankData data = new RankData();
        final List<Board> results = new ArrayList<Board>();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        final CountDownLatch latch = new CountDownLatch(BOARDS.length);
        final Object lock = new Object();

        for (final String[] b : BOARDS) {
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        Board board = fetchBoard(b[0], b[1], b[2]);
                        synchronized (lock) {
                            results.add(board);
                        }
                    } catch (Exception ignored) {
                    } finally {
                        latch.countDown();
                    }
                }
            });
        }
        latch.await(45, TimeUnit.SECONDS);
        pool.shutdownNow();

        if (results.size() < 3) throw new Exception("榜单拉取失败（成功 " + results.size() + " 个）");

        // 按 BOARDS 顺序排回来
        for (String[] def : BOARDS) {
            for (Board b : results) {
                if (b.id.equals(def[0])) {
                    data.boards.add(b);
                    break;
                }
            }
        }
        if (l != null) l.onProgress("已获取 " + data.total() + " 部");
        return data;
    }

    private static Board fetchBoard(String id, String label, String group) throws Exception {
        String url = "https://m.douban.com/rexxar/api/v2/subject_collection/" + id + "/items?start=0&count=60";
        String body = Http.getText(url, Http.headers(Http.UA_MOBILE, "https://m.douban.com/"), 15000);
        JSONObject j = new JSONObject(body);
        JSONArray arr = j.optJSONArray("subject_collection_items");
        Board b = new Board();
        b.id = id;
        b.label = label;
        b.group = group;
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject it = arr.optJSONObject(i);
                if (it == null) continue;
                RankItem r = RankItem.parse(it, id, label, group);
                if (r.title != null && r.title.length() > 0) b.items.add(r);
            }
        }
        return b;
    }

    /** 走自建服务端 */
    private static RankData fetchFromServer(Context ctx, Listener l) throws Exception {
        String base = Prefs.serverUrl();
        String body = Http.getText(base + "/api/rank", Http.headers(Http.UA_DESKTOP, null), 20000);
        JSONObject j = new JSONObject(body);
        JSONArray groups = j.optJSONArray("groups");
        if (groups == null) throw new Exception("服务端返回格式不正确");

        RankData d = new RankData();
        for (int i = 0; i < groups.length(); i++) {
            JSONObject g = groups.optJSONObject(i);
            String gk = g.optString("key", "");
            JSONArray boards = g.optJSONArray("boards");
            if (boards == null) continue;
            for (int k = 0; k < boards.length(); k++) {
                JSONObject bo = boards.optJSONObject(k);
                Board b = new Board();
                b.id = bo.optString("id", "");
                b.label = bo.optString("label", "");
                b.group = gk;
                JSONArray items = bo.optJSONArray("items");
                if (items != null) {
                    for (int m = 0; m < items.length(); m++) {
                        JSONObject it = items.optJSONObject(m);
                        if (it == null) continue;
                        RankItem r = new RankItem();
                        r.board = b.id;
                        r.boardLabel = b.label;
                        r.group = gk;
                        r.id = it.optString("id", "");
                        r.title = it.optString("title", "");
                        r.year = it.optString("year", "");
                        r.region = it.optString("region", "");
                        r.episodes = it.optString("episodes", "");
                        r.subtitle = it.optString("subtitle", "");
                        r.comment = it.optString("comment", "");
                        // 服务端给的是豆瓣原始地址，海报仍需经它的代理
                        String pic = it.optString("pic", "");
                        String picLarge = it.optString("picLarge", "");
                        r.pic = pic;
                        r.picLarge = picLarge;
                        r.url = it.optString("url", "");
                        r.director = it.optString("director", "");
                        r.rating = it.optDouble("rating", 0);
                        r.ratingCount = it.optInt("ratingCount", 0);
                        JSONArray ac = it.optJSONArray("actors");
                        if (ac != null) for (int x = 0; x < ac.length(); x++) r.actors.add(ac.optString(x, ""));
                        JSONArray ge = it.optJSONArray("genres");
                        if (ge != null) for (int x = 0; x < ge.length(); x++) r.genres.add(ge.optString(x, ""));
                        if (r.title.length() > 0) b.items.add(r);
                    }
                }
                if (!b.items.isEmpty()) d.boards.add(b);
            }
        }
        if (l != null) l.onProgress("已获取 " + d.total() + " 部");
        return d;
    }

    /* ---------- 缓存读写 ---------- */

    private static void save(Context c, RankData d) {
        try {
            JSONObject root = new JSONObject();
            root.put("fetchedAt", d.fetchedAt);
            JSONArray boards = new JSONArray();
            for (Board b : d.boards) {
                JSONObject bo = new JSONObject();
                bo.put("id", b.id);
                bo.put("label", b.label);
                bo.put("group", b.group);
                JSONArray items = new JSONArray();
                for (RankItem r : b.items) items.put(toJson(r));
                bo.put("items", items);
                boards.put(bo);
            }
            root.put("boards", boards);
            writeText(cacheFile(c), root.toString());
        } catch (Exception ignored) {
        }
    }

    private static JSONObject toJson(RankItem r) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", r.id);
            o.put("title", r.title);
            o.put("year", r.year);
            o.put("region", r.region);
            o.put("episodes", r.episodes);
            o.put("subtitle", r.subtitle);
            o.put("comment", r.comment);
            o.put("pic", r.pic);
            o.put("picLarge", r.picLarge);
            o.put("url", r.url);
            o.put("director", r.director);
            o.put("rating", r.rating);
            o.put("ratingCount", r.ratingCount);
            o.put("actors", TextUtils.join("\u0001", r.actors));
            o.put("genres", TextUtils.join("\u0001", r.genres));
        } catch (Exception ignored) {
        }
        return o;
    }

    private static RankData parseCache(JSONObject root) throws Exception {
        RankData d = new RankData();
        d.fetchedAt = root.optLong("fetchedAt", 0);
        JSONArray boards = root.optJSONArray("boards");
        if (boards == null) return d;
        for (int i = 0; i < boards.length(); i++) {
            JSONObject bo = boards.getJSONObject(i);
            Board b = new Board();
            b.id = bo.optString("id", "");
            b.label = bo.optString("label", "");
            b.group = bo.optString("group", "");
            JSONArray items = bo.optJSONArray("items");
            if (items != null) {
                for (int k = 0; k < items.length(); k++) {
                    JSONObject o = items.getJSONObject(k);
                    RankItem r = new RankItem();
                    r.board = b.id;
                    r.boardLabel = b.label;
                    r.group = b.group;
                    r.id = o.optString("id", "");
                    r.title = o.optString("title", "");
                    r.year = o.optString("year", "");
                    r.region = o.optString("region", "");
                    r.episodes = o.optString("episodes", "");
                    r.subtitle = o.optString("subtitle", "");
                    r.comment = o.optString("comment", "");
                    r.pic = o.optString("pic", "");
                    r.picLarge = o.optString("picLarge", "");
                    r.url = o.optString("url", "");
                    r.director = o.optString("director", "");
                    r.rating = o.optDouble("rating", 0);
                    r.ratingCount = o.optInt("ratingCount", 0);
                    String ac = o.optString("actors", "");
                    if (ac.length() > 0) for (String s : ac.split("\u0001")) if (s.length() > 0) r.actors.add(s);
                    String ge = o.optString("genres", "");
                    if (ge.length() > 0) for (String s : ge.split("\u0001")) if (s.length() > 0) r.genres.add(s);
                    b.items.add(r);
                }
            }
            d.boards.add(b);
        }
        return d;
    }

    private static String readText(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[(int) f.length()];
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) break;
                off += n;
            }
            return new String(buf, 0, off, "UTF-8");
        } finally {
            in.close();
        }
    }

    private static void writeText(File f, String s) throws Exception {
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(s.getBytes("UTF-8"));
        } finally {
            out.close();
        }
    }
}
