package com.vodtv.data;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 片源稳定性统计，用来决定「默认播哪个源」。
 *
 * 评分 = 成功率 × 0.75 + 响应速度 × 0.25
 * 成功率用拉普拉斯平滑（+1/+2），避免只成功过一次的源被顶到第一。
 * 没有任何历史数据时，score 会退化成 0.625 左右，此时由 priority 决定顺序。
 */
public class SourceStats {

    private static SharedPreferences sp;

    public static void init(Context c) {
        sp = c.getApplicationContext().getSharedPreferences("vodtv_stats", Context.MODE_PRIVATE);
    }

    private static String key(String id) {
        return "s_" + id;
    }

    public static void recordOk(String id, long ms) {
        Stat s = get(id);
        s.success++;
        s.totalMs += ms;
        s.lastOk = System.currentTimeMillis();
        put(id, s);
    }

    public static void recordFail(String id) {
        Stat s = get(id);
        s.fail++;
        put(id, s);
    }

    public static double score(SourceConfig cfg) {
        Stat s = get(cfg.id);
        int total = s.success + s.fail;
        double rate = (s.success + 1.0) / (total + 2.0);
        double avg = s.success > 0 ? (double) s.totalMs / s.success : 3000.0;
        double speed = 1.0 - Math.min(avg, 6000.0) / 6000.0;
        double base = rate * 0.75 + speed * 0.25;
        // priority 只做很小的加权，不能盖过真实成功率
        double bonus = cfg.priority == 1 ? 0.03 : cfg.priority == 2 ? 0.015 : 0.0;
        return base + bonus;
    }

    public static String summary(String id) {
        Stat s = get(id);
        int total = s.success + s.fail;
        if (total == 0) return "暂无播放记录";
        long avg = s.success > 0 ? s.totalMs / s.success : 0;
        int pct = (int) Math.round(s.success * 100.0 / total);
        return s.success + "/" + total + " 成功 · " + pct + "% · 平均 " + avg + "ms";
    }

    public static boolean hasHistory(String id) {
        Stat s = get(id);
        return s.success + s.fail > 0;
    }

    public static void clear() {
        sp.edit().clear().apply();
    }

    /* ---------- 内部 ---------- */

    private static class Stat {
        int success;
        int fail;
        long totalMs;
        long lastOk;
    }

    private static Stat get(String id) {
        Stat s = new Stat();
        String raw = sp.getString(key(id), null);
        if (raw == null) return s;
        String[] p = raw.split(",");
        try {
            if (p.length >= 4) {
                s.success = Integer.parseInt(p[0]);
                s.fail = Integer.parseInt(p[1]);
                s.totalMs = Long.parseLong(p[2]);
                s.lastOk = Long.parseLong(p[3]);
            }
        } catch (Exception ignored) {
        }
        return s;
    }

    private static void put(String id, Stat s) {
        sp.edit().putString(key(id), s.success + "," + s.fail + "," + s.totalMs + "," + s.lastOk).apply();
    }
}
