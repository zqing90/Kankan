package com.vodtv.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * 搜索结果合并这一层。
 *
 * 为什么值得测：14 个站会各返回一遍同一部片，合并逻辑一旦漏了去重，
 * 搜索页就是「狂飙」刷满整屏；反过来若把不同剧误判成同一部，
 * 用户点进去会发现片名、集数全不对。这两种都是真机上很难复现、但一眼就崩体验的问题。
 *
 * 只测纯字符串逻辑（addCandidate / relevant / sortCandidates），网络与 JSON 不在单测范围。
 */
public class PlayResolverCandidateTest {

    private static List<PlayResolver.Candidate> pool() {
        return new ArrayList<PlayResolver.Candidate>();
    }

    /** 同一部片出现在多个站 → 合并成一条，sources 累加 */
    @Test
    public void 同名条目合并成一个候选() {
        List<PlayResolver.Candidate> list = pool();
        PlayResolver.addCandidate(list, "狂飙", "a.jpg", "2023", "全39集", "站A");
        PlayResolver.addCandidate(list, "狂飙", "b.jpg", "2023", "", "站B");
        PlayResolver.addCandidate(list, "狂飙", "", "", "", "站C");

        assertEquals(1, list.size());
        PlayResolver.Candidate c = list.get(0);
        assertEquals(3, c.sources);
        assertEquals(3, c.sourceNames.size());
        // 空字段被后面的站补齐，已有字段不被覆盖
        assertEquals("a.jpg", c.pic);
        assertEquals("全39集", c.remarks);
    }

    /** 同一个站给出两条同名，只能算一个源，否则「N 个源」会虚高 */
    @Test
    public void 同一个站重复出现只算一个源() {
        List<PlayResolver.Candidate> list = pool();
        PlayResolver.addCandidate(list, "狂飙", "", "", "", "站A");
        PlayResolver.addCandidate(list, "狂飙", "", "", "", "站A");

        assertEquals(1, list.size());
        assertEquals(1, list.get(0).sources);
    }

    /** 大小写、全角括号、空格差异要归一，否则「The Last of Us」会散成好几张卡 */
    @Test
    public void 片名归一后大小写与括号不产生重复() {
        List<PlayResolver.Candidate> list = pool();
        PlayResolver.addCandidate(list, "The Last of Us", "", "2023", "", "站A");
        PlayResolver.addCandidate(list, "the last of us", "", "", "", "站B");
        PlayResolver.addCandidate(list, "The Last of Us（美剧）", "", "", "", "站C");

        assertEquals(1, list.size());
        assertEquals(3, list.get(0).sources);
    }

    /** 名字为空 / 纯空白 / null 一律丢弃，不能生成空卡片 */
    @Test
    public void 空片名不入池() {
        List<PlayResolver.Candidate> list = pool();
        PlayResolver.addCandidate(list, null, "", "", "", "站A");
        PlayResolver.addCandidate(list, "", "", "", "", "站A");
        PlayResolver.addCandidate(list, "   ", "", "", "", "站A");

        assertTrue(list.isEmpty());
    }

    /** 源多的排前面；都是一路时短的在前（「狂飙」不该排在「狂飙 全39集」后面） */
    @Test
    public void 排序按源数量再按标题长度() {
        List<PlayResolver.Candidate> list = pool();
        PlayResolver.addCandidate(list, "狂飙 全39集", "", "", "", "站A");
        PlayResolver.addCandidate(list, "狂飙", "", "", "", "站A");
        PlayResolver.addCandidate(list, "狂飙", "", "", "", "站B");
        PlayResolver.sortCandidates(list);

        assertEquals(2, list.size());
        assertEquals("狂飙", list.get(0).title);
        assertEquals(2, list.get(0).sources);
        assertEquals("狂飙 全39集", list.get(1).title);
    }

    /** 解说 / 预告 / 花絮不是正片，除非用户本来就在搜它 */
    @Test
    public void 排除解说与预告类条目() {
        assertTrue(PlayResolver.relevant("狂飙", "狂飙"));
        assertFalse(PlayResolver.relevant("狂飙 全39集 解说", "狂飙"));
        assertFalse(PlayResolver.relevant("狂飙 预告片", "狂飙"));
        // 用户明确搜「解说」时不该被过滤掉
        assertTrue(PlayResolver.relevant("狂飙 解说", "狂飙解说"));
    }

    /** 完全不沾边的条目不能进结果，防止站点把整个列表都吐回来 */
    @Test
    public void 不相关条目被过滤() {
        assertFalse(PlayResolver.relevant("三体", "狂飙"));
        assertFalse(PlayResolver.relevant("漫长的季节", "狂飙"));
        assertTrue(PlayResolver.relevant("狂飙 2023", "狂飙"));
    }
}
