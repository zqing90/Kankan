package com.vodtv.data;

import android.content.Intent;

import org.json.JSONObject;

/**
 * 一部影片的展示信息。
 *
 * 抽出来的原因：这些字段原本靠 Intent 一个个 putExtra 在首页→详情页→播放页之间传，
 * 而历史记录要能「不依赖榜单」地重建详情页，就必须把整份信息存下来。
 * 有了这个类，Intent 和 JSON 两个方向都只有一处转换代码。
 */
public class MovieInfo {

    public String title = "";
    public String pic = "";
    public String year = "";
    public double rating;
    public int ratingCount;
    public String genres = "";
    public String comment = "";
    public String director = "";
    public String actors = "";
    public String episodes = "";     // 「全 24 集」这类文字

    public static MovieInfo fromIntent(Intent i) {
        MovieInfo m = new MovieInfo();
        if (i == null) return m;
        m.title = nz(i.getStringExtra("title"));
        m.pic = nz(i.getStringExtra("pic"));
        m.year = nz(i.getStringExtra("year"));
        m.rating = i.getDoubleExtra("rating", 0);
        m.ratingCount = i.getIntExtra("ratingCount", 0);
        m.genres = nz(i.getStringExtra("genres"));
        m.comment = nz(i.getStringExtra("comment"));
        m.director = nz(i.getStringExtra("director"));
        m.actors = nz(i.getStringExtra("actors"));
        m.episodes = nz(i.getStringExtra("episodes"));
        return m;
    }

    public void toIntent(Intent i) {
        i.putExtra("title", title);
        i.putExtra("pic", pic);
        i.putExtra("year", year);
        i.putExtra("rating", rating);
        i.putExtra("ratingCount", ratingCount);
        i.putExtra("genres", genres);
        i.putExtra("comment", comment);
        i.putExtra("director", director);
        i.putExtra("actors", actors);
        i.putExtra("episodes", episodes);
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("title", title);
            o.put("pic", pic);
            o.put("year", year);
            o.put("rating", rating);
            o.put("rc", ratingCount);
            o.put("genres", genres);
            o.put("comment", comment);
            o.put("director", director);
            o.put("actors", actors);
            o.put("episodes", episodes);
        } catch (Exception ignored) {
        }
        return o;
    }

    public static MovieInfo fromJson(JSONObject o) {
        MovieInfo m = new MovieInfo();
        if (o == null) return m;
        m.title = nz(o.optString("title", ""));
        m.pic = nz(o.optString("pic", ""));
        m.year = nz(o.optString("year", ""));
        m.rating = o.optDouble("rating", 0);
        m.ratingCount = o.optInt("rc", 0);
        m.genres = nz(o.optString("genres", ""));
        m.comment = nz(o.optString("comment", ""));
        m.director = nz(o.optString("director", ""));
        m.actors = nz(o.optString("actors", ""));
        m.episodes = nz(o.optString("episodes", ""));
        return m;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
