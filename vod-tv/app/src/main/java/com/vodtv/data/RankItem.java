package com.vodtv.data;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 榜单里的一条影视条目（已归一化，剧集结构 / 电影结构都在这里抹平） */
public class RankItem {

    public String id;
    public String board;
    public String boardLabel;
    public String group;
    public String title;
    public String year;
    public String region;
    public String episodes;   // "更新至26集" / "08.19 上映"
    public String subtitle;
    public String comment;
    public String pic;        // 卡片用，s_ratio
    public String picLarge;   // 详情用，m_ratio
    public String url;
    public String director;
    public List<String> actors = new ArrayList<String>();
    public List<String> genres = new ArrayList<String>();
    public double rating;
    public int ratingCount;

    public String metaLine() {
        StringBuilder sb = new StringBuilder();
        if (year != null && year.length() > 0) sb.append(year);
        if (episodes != null && episodes.length() > 0) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(episodes);
        }
        if (sb.length() == 0 && region != null) sb.append(region);
        return sb.toString();
    }

    /**
     * 豆瓣榜单混着两套结构，这里统一：
     *   A 剧集/综艺/动画/纪录：pic{normal,large} + card_subtitle + comment + episodes_info
     *   B 电影：cover{url} + info + directors[] + actors[] + release_date
     */
    public static RankItem parse(JSONObject it, String board, String boardLabel, String group) {
        RankItem r = new RankItem();
        r.board = board;
        r.boardLabel = boardLabel;
        r.group = group;
        r.title = it.optString("title", "");
        r.year = it.optString("year", "");

        JSONObject picObj = it.optJSONObject("pic");
        if (picObj != null) {
            r.pic = picObj.optString("normal", "");
            r.picLarge = picObj.optString("large", "");
        }
        JSONObject cover = it.optJSONObject("cover");
        if ((r.pic == null || r.pic.length() == 0) && cover != null) {
            String cu = cover.optString("url", "");
            r.picLarge = cu;
            r.pic = cu.replace("/m_ratio_poster/", "/s_ratio_poster/");
        }

        r.id = it.optString("id", "");
        if (r.id.length() == 0) {
            r.id = extractId(it.optString("uri", ""));
        }
        if (r.id.length() == 0) {
            r.id = extractId(it.optString("url", ""));
        }

        r.subtitle = it.optString("card_subtitle", "");
        if (r.subtitle.length() == 0) r.subtitle = it.optString("info", "");
        r.comment = it.optString("comment", "");
        if (r.comment.length() == 0) r.comment = it.optString("description", "");
        r.episodes = it.optString("episodes_info", "");
        if (r.episodes.length() == 0) {
            String rd = it.optString("release_date", "");
            if (rd.length() > 0) r.episodes = rd + " 上映";
        }

        JSONObject rating = it.optJSONObject("rating");
        if (rating != null) {
            r.rating = rating.optDouble("value", 0);
            r.ratingCount = rating.optInt("count", 0);
        }

        // 地区 / 类型：两套结构都是 "地区 / 类型 / 导演 / 演员"
        String info = r.subtitle;
        String[] parts = info.split("/");
        if (parts.length >= 1) r.region = parts[0].trim();
        if (parts.length >= 2) {
            for (String g : parts[1].trim().split("\\s+")) {
                if (g.length() > 0) r.genres.add(g);
            }
        }
        if (parts.length >= 3) r.director = parts[2].trim();

        org.json.JSONArray dirs = it.optJSONArray("directors");
        if (dirs != null && dirs.length() > 0) r.director = dirs.optString(0, r.director);

        org.json.JSONArray acts = it.optJSONArray("actors");
        if (acts != null && acts.length() > 0) {
            for (int i = 0; i < acts.length(); i++) r.actors.add(acts.optString(i, ""));
        } else if (parts.length >= 4) {
            for (String a : parts[3].trim().split("\\s+")) {
                if (a.length() > 0) r.actors.add(a);
            }
        }

        r.url = r.id.length() > 0
                ? "https://movie.douban.com/subject/" + r.id + "/"
                : it.optString("url", "");
        return r;
    }

    private static String extractId(String s) {
        if (s == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{5,})").matcher(s);
        return m.find() ? m.group(1) : "";
    }
}
