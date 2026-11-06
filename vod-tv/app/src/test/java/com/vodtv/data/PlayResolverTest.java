package com.vodtv.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/**
 * 只测「纯字符串解析」这一层 —— 它不碰 Android API，能在 JVM 上秒跑。
 *
 * 为什么是这块：vod_play_url 的 `$$$` / `#` / `$` 三级结构是全项目最容易写错的地方，
 * 一旦拆错就会出现「第01集重复 40 次」「选集点了没反应」这类问题，
 * 而它在真机上很难一眼看出来。UI 和网络部分仍以真机验收为准。
 */
public class PlayResolverTest {

    /** 电影：单集，只有一个 `$`，前面是集名 */
    @Test
    public void 单集解析出正片() {
        List<PlayResolver.Ep> eps = PlayResolver.splitEpisodes("正片$https://cdn.x/a.m3u8");
        assertEquals(1, eps.size());
        assertEquals("正片", eps.get(0).label);
        assertEquals("https://cdn.x/a.m3u8", eps.get(0).url);
    }

    /** 剧集：`#` 分集，集名和地址用 `$` 隔开 */
    @Test
    public void 多集按井号拆分() {
        List<PlayResolver.Ep> eps = PlayResolver.splitEpisodes(
                "第01集$https://cdn.x/1.m3u8#第02集$https://cdn.x/2.m3u8#第03集$https://cdn.x/3.m3u8");
        assertEquals(3, eps.size());
        assertEquals("第01集", eps.get(0).label);
        assertEquals("第03集", eps.get(2).label);
        assertEquals("https://cdn.x/3.m3u8", eps.get(2).url);
    }

    /**
     * 三级结构：`$$$` 分线路。优先挑「直链占比最高」的那条，
     * 否则选集后要再解析一次 HTML，点开才有得播。
     */
    @Test
    public void 多线路时优先直链() {
        String share = "第01集$https://cdn.x/share/1#第02集$https://cdn.x/share/2";
        String direct = "第01集$https://cdn.x/1.m3u8#第02集$https://cdn.x/2.m3u8";
        List<PlayResolver.Ep> eps = PlayResolver.splitEpisodes(share + "$$$" + direct);
        assertEquals(2, eps.size());
        assertTrue(eps.get(0).url.endsWith(".m3u8"));
        assertTrue(!eps.get(0).url.contains("/share/"));
    }

    /** 裸地址（没有 `$`）也要能播，不能被当成异常丢掉 */
    @Test
    public void 没有集名时按正片处理() {
        List<PlayResolver.Ep> eps = PlayResolver.splitEpisodes("https://cdn.x/only.m3u8");
        assertEquals(1, eps.size());
        assertEquals("正片", eps.get(0).label);
        assertEquals("https://cdn.x/only.m3u8", eps.get(0).url);
    }

    /** 空数据是常态（站点没收录），必须返回空表而不是抛异常或塞个空条目 */
    @Test
    public void 空输入返回空表() {
        assertTrue(PlayResolver.splitEpisodes(null).isEmpty());
        assertTrue(PlayResolver.splitEpisodes("").isEmpty());
        assertTrue(PlayResolver.splitEpisodes("#").isEmpty());
    }

    /** 已经是 m3u8 的地址原样返回，不能再去请求一次 */
    @Test
    public void 直链不做二次解析() {
        assertEquals("https://cdn.x/a.m3u8", PlayResolver.toMedia("https://cdn.x/a.m3u8"));
        assertNull(PlayResolver.toMedia(null));
    }
}
