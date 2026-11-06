package com.vodtv.data;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 轻量配置存储。API 14 没有 Jetpack DataStore，SharedPreferences 足够。 */
public class Prefs {

    private static SharedPreferences sp;

    public static void init(Context c) {
        if (sp == null) {
            sp = c.getApplicationContext().getSharedPreferences("vodtv", Context.MODE_PRIVATE);
        }
    }

    /* ---------- 服务端（可选） ---------- */

    /** 为空表示直连模式；填了就走自建 Node 服务 */
    public static String serverUrl() {
        return sp.getString("server_url", "");
    }

    public static void setServerUrl(String url) {
        String v = url == null ? "" : url.trim();
        while (v.endsWith("/")) v = v.substring(0, v.length() - 1);
        sp.edit().putString("server_url", v).apply();
    }

    public static boolean useServer() {
        return serverUrl().length() > 0;
    }

    /* ---------- 榜单更新 ---------- */

    public static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    /** 上次成功更新榜单的日期，用于「每天只更新一次」的判断 */
    public static String lastRankDate() {
        return sp.getString("rank_date", "");
    }

    public static long lastRankAt() {
        return sp.getLong("rank_at", 0L);
    }

    public static void markRankUpdated() {
        sp.edit()
                .putString("rank_date", today())
                .putLong("rank_at", System.currentTimeMillis())
                .apply();
    }

    /** 当天还没更新过 → 启动时静默刷新 */
    public static boolean needDailyUpdate() {
        return !today().equals(lastRankDate());
    }

    /* ---------- 播放偏好 ---------- */

    /** 用户手动锁定过的源 id；为空表示按稳定性自动选 */
    public static String pinnedSource() {
        return sp.getString("pinned_source", "");
    }

    public static void setPinnedSource(String id) {
        sp.edit().putString("pinned_source", id).apply();
    }

    /** 直播/点播音量各自记忆，TV 上很实用 */
    public static int volume() {
        return sp.getInt("volume", -1);
    }

    public static void setVolume(int v) {
        sp.edit().putInt("volume", v).apply();
    }
}
