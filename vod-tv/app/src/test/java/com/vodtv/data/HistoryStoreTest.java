package com.vodtv.data;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 「看完」判定的回归用例。
 *
 * 这条判定决定记录是被保留成「已看完」还是从列表里消失，
 * 2026-11-07 反馈的「播完之后观看记录会消失」就是这里判错后直接删记录导致的。
 * 只测纯数值逻辑，不碰 SharedPreferences。
 */
public class HistoryStoreTest {

    private static final long MIN = 60 * 1000L;

    @Test
    public void 位置等于时长算看完() {
        assertTrue(HistoryStore.isFinished(45 * MIN, 45 * MIN));
    }

    @Test
    public void 停在片尾一分钟内算看完() {
        assertTrue(HistoryStore.isFinished(44 * MIN + 30 * 1000L, 45 * MIN));
    }

    @Test
    public void 差两分钟不算看完() {
        assertFalse(HistoryStore.isFinished(43 * MIN, 45 * MIN));
    }

    @Test
    public void 刚开始不算看完() {
        assertFalse(HistoryStore.isFinished(10 * 1000L, 45 * MIN));
    }

    @Test
    public void 时长未知时任何位置都不算看完() {
        // 直播 / 取不到时长的片：dur=0 时不能把任何位置判成看完，
        // 否则这些片的记录会全部显示「已看完」且永远从头播
        assertFalse(HistoryStore.isFinished(30 * MIN, 0));
        assertFalse(HistoryStore.isFinished(0, 0));
    }

    @Test
    public void 位置超过时长也算看完() {
        assertTrue(HistoryStore.isFinished(46 * MIN, 45 * MIN));
    }

    @Test
    public void 位置为负不算看完() {
        assertFalse(HistoryStore.isFinished(-1, 45 * MIN));
    }
}
