package com.vodtv.data;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 片源清单的存储与编辑。
 *
 * 设计要点：
 *   1. 内置一份默认清单（init 时写入），用户不必自己配；
 *   2. 全部操作都落到 SharedPreferences，改完即时生效；
 *   3. 支持从服务端 /api/stations 覆盖（远程配置优先），改源不用发版。
 */
public class SourceStore {

    private static final String KEY = "sources_v1";
    private static SharedPreferences sp;
    private static List<SourceConfig> cache = new ArrayList<SourceConfig>();

    public static void init(Context c) {
        sp = c.getApplicationContext().getSharedPreferences("vodtv", Context.MODE_PRIVATE);
        load();
    }

    private static void load() {
        String raw = sp.getString(KEY, null);
        cache = new ArrayList<SourceConfig>();
        if (raw == null) {
            cache.addAll(defaults());
            persist();
            return;
        }
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                SourceConfig s = SourceConfig.fromJson(arr.getJSONObject(i));
                if (s.id != null && s.id.length() > 0 && s.host != null && s.host.length() > 0) {
                    cache.add(s);
                }
            }
        } catch (Exception e) {
            cache = new ArrayList<SourceConfig>(defaults());
            persist();
        }
    }

    private static void persist() {
        JSONArray arr = new JSONArray();
        for (SourceConfig s : cache) arr.put(s.toJson());
        sp.edit().putString(KEY, arr.toString()).apply();
    }

    /** 默认清单：按实测可用性 + 是否直出 m3u8 排的优先级 */
    public static List<SourceConfig> defaults() {
        List<SourceConfig> l = new ArrayList<SourceConfig>();
        l.add(new SourceConfig("bfzy", "暴风资源", "https://bfzyapi.com", "/api.php/provide/vod/", 1, true));
        l.add(new SourceConfig("liangzi", "量子资源", "https://cj.lziapi.com", "/api.php/provide/vod/", 1, true));
        l.add(new SourceConfig("feifan", "非凡资源", "https://cj.ffzyapi.com", "/api.php/provide/vod/", 1, true));
        l.add(new SourceConfig("guangsu", "光速资源", "https://api.guangsuapi.com", "/api.php/provide/vod/", 2, true));
        l.add(new SourceConfig("wujin", "无尽资源", "https://api.wujinapi.com", "/api.php/provide/vod/", 2, true));
        l.add(new SourceConfig("modu", "魔都资源", "https://caiji.moduapi.cc", "/api.php/provide/vod/", 2, true));
        l.add(new SourceConfig("piaoling", "飘零资源", "https://p2100.net", "/api.php/provide/vod/", 2, true));
        l.add(new SourceConfig("360zy", "360资源", "https://360zy5.com", "/api.php/provide/vod/", 2, true));
        l.add(new SourceConfig("jisu", "极速资源", "https://jszyapi.com", "/api.php/provide/vod/", 2, true));
        l.add(new SourceConfig("hongniu", "红牛资源", "https://www.hongniuzy2.com", "/api.php/provide/vod/", 2, true));
        l.add(new SourceConfig("xinlang", "新浪资源", "https://api.xinlangapi.com", "/xinlangapi.php/provide/vod/", 3, true));
        l.add(new SourceConfig("huya", "虎牙资源", "https://www.huyaapi.com", "/api.php/provide/vod/", 3, true));
        l.add(new SourceConfig("jinying", "金鹰资源", "https://jyzyapi.com", "/provide/vod/", 3, true));
        l.add(new SourceConfig("zuida", "最大资源", "https://api.zuidapi.com", "/api.php/provide/vod/", 3, true));
        return l;
    }

    /* ---------- 查询 ---------- */

    public static List<SourceConfig> all() {
        return new ArrayList<SourceConfig>(cache);
    }

    public static List<SourceConfig> enabled() {
        List<SourceConfig> l = new ArrayList<SourceConfig>();
        for (SourceConfig s : cache) if (s.enabled) l.add(s);
        return l;
    }

    public static SourceConfig byId(String id) {
        for (SourceConfig s : cache) if (s.id.equals(id)) return s;
        return null;
    }

    /** 按稳定性分数降序（稳定性相同时看 priority） */
    public static List<SourceConfig> enabledByStability() {
        List<SourceConfig> l = enabled();
        Collections.sort(l, new Comparator<SourceConfig>() {
            @Override
            public int compare(SourceConfig a, SourceConfig b) {
                double sa = SourceStats.score(a);
                double sb = SourceStats.score(b);
                if (Math.abs(sa - sb) > 0.001) return sa > sb ? -1 : 1;
                return a.priority - b.priority;
            }
        });
        return l;
    }

    /* ---------- 编辑 ---------- */

    public static void save(SourceConfig s) {
        for (int i = 0; i < cache.size(); i++) {
            if (cache.get(i).id.equals(s.id)) {
                cache.set(i, s);
                persist();
                return;
            }
        }
        cache.add(s);
        persist();
    }

    public static void remove(String id) {
        for (int i = 0; i < cache.size(); i++) {
            if (cache.get(i).id.equals(id)) {
                cache.remove(i);
                persist();
                return;
            }
        }
    }

    public static void setEnabled(String id, boolean on) {
        SourceConfig s = byId(id);
        if (s != null) {
            s.enabled = on;
            persist();
        }
    }

    public static void move(int from, int to) {
        if (from < 0 || to < 0 || from >= cache.size() || to >= cache.size()) return;
        SourceConfig s = cache.remove(from);
        cache.add(to, s);
        persist();
    }

    public static void replaceAll(List<SourceConfig> list) {
        if (list == null || list.isEmpty()) return;
        cache = new ArrayList<SourceConfig>(list);
        persist();
    }

    public static void resetToDefault() {
        cache = new ArrayList<SourceConfig>(defaults());
        persist();
        SourceStats.clear();
    }

    /**
     * 从服务端 /api/stations 拉取源清单覆盖本地。
     * 保留用户已有的启用状态：服务端删掉的站会被清掉，新增的站默认启用。
     */
    public static boolean syncFromServer() {
        if (!Prefs.useServer()) return false;
        try {
            String body = Http.getText(Prefs.serverUrl() + "/api/stations",
                    Http.headers(Http.UA_DESKTOP, null), 15000);
            JSONArray arr = new JSONArray(body);
            if (arr.length() == 0) return false;

            java.util.Map<String, Boolean> oldEnabled = new java.util.HashMap<String, Boolean>();
            for (SourceConfig s : cache) oldEnabled.put(s.id, s.enabled);

            List<SourceConfig> list = new ArrayList<SourceConfig>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String id = o.optString("id", "");
                JSONArray hosts = o.optJSONArray("hosts");
                if (id.length() == 0 || hosts == null || hosts.length() == 0) continue;

                SourceConfig s = new SourceConfig();
                s.id = id;
                s.name = o.optString("name", id);
                s.host = hosts.optString(0, "");
                s.path = o.optString("path", "/api.php/provide/vod/");
                s.priority = o.optInt("priority", 2);
                s.search = !"none".equals(o.optString("search", "wd"));
                Boolean was = oldEnabled.get(id);
                s.enabled = was == null ? true : was;
                if (s.host.length() > 0) list.add(s);
            }
            if (list.isEmpty()) return false;
            replaceAll(list);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
