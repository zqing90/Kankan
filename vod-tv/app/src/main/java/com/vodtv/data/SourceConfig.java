package com.vodtv.data;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 一个可配置的片源站点。
 * 和 Node 服务端 config/stations.json 的字段一一对应，方便互相同步。
 */
public class SourceConfig {

    public String id;       // 唯一标识，如 bfzy
    public String name;     // 显示名，如 暴风资源
    public String host;     // https://bfzyapi.com
    public String path;     // /api.php/provide/vod/
    public boolean enabled = true;
    public int priority = 2;   // 1 主力 / 2 可用 / 3 备选
    public boolean search = true; // 是否支持 wd 搜索

    public SourceConfig() {
    }

    public SourceConfig(String id, String name, String host, String path, int priority, boolean search) {
        this.id = id;
        this.name = name;
        this.host = host;
        this.path = path;
        this.priority = priority;
        this.search = search;
    }

    public String endpoint() {
        return host + path;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("name", name);
            o.put("host", host);
            o.put("path", path);
            o.put("enabled", enabled);
            o.put("priority", priority);
            o.put("search", search);
        } catch (JSONException ignored) {
        }
        return o;
    }

    public static SourceConfig fromJson(JSONObject o) {
        SourceConfig s = new SourceConfig();
        s.id = o.optString("id", "");
        s.name = o.optString("name", s.id);
        s.host = o.optString("host", "");
        s.path = o.optString("path", "/api.php/provide/vod/");
        s.enabled = o.optBoolean("enabled", true);
        s.priority = o.optInt("priority", 2);
        s.search = o.optBoolean("search", true);
        return s;
    }

    public SourceConfig copy() {
        SourceConfig s = new SourceConfig(id, name, host, path, priority, search);
        s.enabled = enabled;
        return s;
    }
}
