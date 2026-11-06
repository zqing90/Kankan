package com.vodtv.data;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 片源检索结果缓存。
 *
 * 检索一部片要并发打 14 个站点，每个站搜一次、再探两条 m3u8，慢的时候三十多秒。
 * 同一部片一天里反复进详情页，重复做这件事没有意义，所以按「自然日」缓存：
 * 当天已经检测过就直接复用结果，跨天自动失效，另外随时可以手动重测。
 *
 * 为什么不放 SharedPreferences：一部剧是「14 个源 × 几十集」，JSON 有几十 KB，
 * 塞进去会让每次读配置（音量、上次榜单日期等）都变慢。改成每部片一个文件。
 *
 * 只缓存成功的那部分信息，失败源也存——「这个源今天没有这部片」同样是有价值的信息，
 * 不然每次都要重新去确认一遍。
 */
public class PlayCache {

    private static final String TAG = "PlayCache";
    private static final String DIR = "playcache";
    /** 最多保留多少部片的缓存文件，超出按检测时间淘汰 */
    private static final int MAX_FILES = 60;

    private static File dir;

    public static void init(Context c) {
        if (dir == null) {
            dir = new File(c.getApplicationContext().getFilesDir(), DIR);
            if (!dir.exists()) dir.mkdirs();
        }
    }

    /** 一份缓存 */
    public static class Cached {
        public String keyword;
        public long at;
        public String day;
        public List<PlayResolver.PlayLine> lines = new ArrayList<PlayResolver.PlayLine>();

        /** 今天是否已经检测过 */
        public boolean fresh() {
            return day != null && day.equals(Prefs.today());
        }
    }

    /* ---------- 读 ---------- */

    /** 有缓存就返回，没有返回 null。是否新鲜由调用方判断 */
    public static Cached load(String keyword) {
        if (dir == null || keyword == null || keyword.length() == 0) return null;
        File f = file(keyword);
        if (!f.exists()) return null;

        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            JSONObject o = new JSONObject(new String(bos.toByteArray(), "UTF-8"));

            Cached c = new Cached();
            c.keyword = o.optString("kw", keyword);
            c.at = o.optLong("at", 0);
            c.day = o.optString("day", "");

            JSONArray arr = o.optJSONArray("lines");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject l = arr.optJSONObject(i);
                    if (l != null) c.lines.add(readLine(l));
                }
            }
            return c;
        } catch (Exception e) {
            Log.w(TAG, "读缓存失败 " + keyword + " : " + e);
            return null;
        } finally {
            close(in);
        }
    }

    private static PlayResolver.PlayLine readLine(JSONObject l) {
        PlayResolver.PlayLine p = new PlayResolver.PlayLine();
        p.sourceId = l.optString("sid", "");
        p.sourceName = l.optString("sn", "");
        p.url = l.optString("url", "");
        p.media = l.optString("media", "");
        p.label = l.optString("label", "");
        p.ok = l.optBoolean("ok", false);
        p.ms = l.optLong("ms", 0);
        p.score = l.optDouble("score", 0);
        p.error = l.optString("err", "");

        JSONArray eps = l.optJSONArray("eps");
        if (eps != null) {
            for (int i = 0; i < eps.length(); i++) {
                JSONObject e = eps.optJSONObject(i);
                if (e == null) continue;
                PlayResolver.Ep ep = new PlayResolver.Ep();
                ep.label = e.optString("l", "");
                ep.url = e.optString("u", "");
                if (ep.url.length() > 0) p.eps.add(ep);
            }
        }
        return p;
    }

    /* ---------- 写 ---------- */

    public static void save(String keyword, List<PlayResolver.PlayLine> lines) {
        if (dir == null || keyword == null || keyword.length() == 0 || lines == null) return;
        FileOutputStream out = null;
        try {
            JSONArray arr = new JSONArray();
            for (PlayResolver.PlayLine p : lines) {
                JSONObject l = new JSONObject();
                l.put("sid", p.sourceId == null ? "" : p.sourceId);
                l.put("sn", p.sourceName == null ? "" : p.sourceName);
                l.put("url", p.url == null ? "" : p.url);
                l.put("media", p.media == null ? "" : p.media);
                l.put("label", p.label == null ? "" : p.label);
                l.put("ok", p.ok);
                l.put("ms", p.ms);
                l.put("score", p.score);
                l.put("err", p.error == null ? "" : p.error);

                JSONArray eps = new JSONArray();
                if (p.eps != null) {
                    for (PlayResolver.Ep e : p.eps) {
                        JSONObject o = new JSONObject();
                        o.put("l", e.label == null ? "" : e.label);
                        o.put("u", e.url == null ? "" : e.url);
                        eps.put(o);
                    }
                }
                l.put("eps", eps);
                arr.put(l);
            }

            JSONObject root = new JSONObject();
            root.put("kw", keyword);
            root.put("at", System.currentTimeMillis());
            root.put("day", Prefs.today());
            root.put("lines", arr);

            out = new FileOutputStream(file(keyword));
            out.write(root.toString().getBytes("UTF-8"));
            out.flush();

            pruneFiles();
        } catch (Exception e) {
            Log.w(TAG, "写缓存失败 " + keyword + " : " + e);
        } finally {
            close(out);
        }
    }

    /** 手动重测或换片名时清掉这条 */
    public static void clear(String keyword) {
        if (dir == null || keyword == null) return;
        try {
            File f = file(keyword);
            if (f.exists()) f.delete();
        } catch (Exception ignored) {
        }
    }

    /* ---------- 内部 ---------- */

    private static File file(String keyword) {
        return new File(dir, hash(keyword) + ".json");
    }

    private static String hash(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                String h = Integer.toHexString(b & 0xff);
                if (h.length() < 2) sb.append('0');
                sb.append(h);
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }

    /** 缓存文件太多时按修改时间淘汰最旧的 */
    private static void pruneFiles() {
        try {
            File[] fs = dir.listFiles();
            if (fs == null || fs.length <= MAX_FILES) return;
            Arrays.sort(fs, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    long x = a.lastModified(), y = b.lastModified();
                    return x < y ? -1 : (x > y ? 1 : 0);
                }
            });
            for (int i = 0; i < fs.length - MAX_FILES; i++) fs[i].delete();
        } catch (Exception ignored) {
        }
    }

    private static void close(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
            }
        }
    }
}
