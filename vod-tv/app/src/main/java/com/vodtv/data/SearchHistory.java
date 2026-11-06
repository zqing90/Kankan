package com.vodtv.data;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/**
 * 搜索历史：只记关键词，不记结果。
 *
 * 规矩：最多 {@link #MAX} 条，重复搜索把旧词提到最前（不分大小写），
 * 全部存在一个 SharedPreferences 的 JSON 数组里。
 * 记结果快照没意义——片源站的结果隔天就变，点回去反而更容易失效。
 */
public class SearchHistory {

    private static final int MAX = 12;
    private static final String KEY = "list";

    private static SharedPreferences sp;

    public static void init(Context c) {
        if (sp == null) {
            sp = c.getApplicationContext().getSharedPreferences("search_history", Context.MODE_PRIVATE);
        }
    }

    /** 最近的在前 */
    public static List<String> list() {
        List<String> out = new ArrayList<String>();
        if (sp == null) return out;
        try {
            JSONArray arr = new JSONArray(sp.getString(KEY, "[]"));
            for (int i = 0; i < arr.length() && i < MAX; i++) {
                String s = arr.optString(i, "");
                if (s != null && s.length() > 0) out.add(s);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static void add(String keyword) {
        if (sp == null || keyword == null) return;
        String kw = keyword.trim();
        if (kw.length() == 0) return;
        try {
            JSONArray old = new JSONArray(sp.getString(KEY, "[]"));
            JSONArray arr = new JSONArray();
            arr.put(kw);
            for (int i = 0; i < old.length() && arr.length() < MAX; i++) {
                String s = old.optString(i, "");
                if (s == null || s.length() == 0) continue;
                if (s.equalsIgnoreCase(kw)) continue;   // 同一个词只留最新的那条
                arr.put(s);
            }
            sp.edit().putString(KEY, arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    public static void remove(String keyword) {
        if (sp == null || keyword == null) return;
        try {
            JSONArray old = new JSONArray(sp.getString(KEY, "[]"));
            JSONArray arr = new JSONArray();
            for (int i = 0; i < old.length(); i++) {
                String s = old.optString(i, "");
                if (s != null && s.length() > 0 && !s.equalsIgnoreCase(keyword)) arr.put(s);
            }
            sp.edit().putString(KEY, arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    public static void clear() {
        if (sp == null) return;
        sp.edit().remove(KEY).apply();
    }
}
