package com.vodtv.widget;

import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.GridView;

/**
 * 遥控器焦点工具。
 *
 * 为什么要有这个类（1.4.11 收口，三个页面都踩过同一个坑）：
 * 本项目的海报网格全是「GridView + 卡片根布局 focusable + listSelector 透明」的组合。
 * 一旦焦点被 GridView **容器**接住，电视上的表现就是「按方向键没反应、选不中任何一张」：
 * <ul>
 *   <li>卡片自己的 focused 态（bg_poster 描边 + 1.06 放大）不会触发；</li>
 *   <li>listSelector 设成了透明（描边画在卡片自己身上），容器选中态也没有兜底高亮；</li>
 *   <li>容器 mSelectedPosition 还是 -1，OK 键不进详情。</li>
 * </ul>
 * 首页的 tab 用 nextFocusDown 直接指向 grid、搜索页/记录页靠 FocusFinder 搜索，
 * 两条路最后都落在容器上，所以三个页面症状一模一样。
 *
 * 解决思路统一成三步：允许子项持焦 → 容器拿到焦点就转交 → 选中回调里再兜一次。
 */
public final class FocusKit {

    /** 全站唯一的焦点放大倍率：2dp 描边 + 1.06 放大，见 AGENTS.md §4.1 视觉铁律 */
    private static final float SCALE_FOCUS = 1.06f;
    private static final long FX_MS = 120;

    /** 记住「上次焦点停在第几张」，从顶栏按 ↓ 回网格时还能回到原地，不弹回第一张 */
    public interface Position {
        int get();
    }

    private FocusKit() {
    }

    /** 给海报网格装上「焦点一定落在卡片上」的三道保险 */
    public static void bindGrid(final GridView grid, final Position lastPos) {
        // 1. 子项优先于容器（GridView 没有 ListView 的 setItemsCanFocus，
        //    只有 descendantFocusability 这一个开关可用）
        grid.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);

        // 2. 容器刚拿到焦点 → 立刻转交给卡片
        grid.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (hasFocus) handOff(grid, lastPos);
            }
        });

        // 3. 兜底：AbsListView 拿到焦点后会自己 setSelection，onItemSelected 正是转交时机
        grid.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (view != null && !view.isFocused()) view.requestFocus();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    /** 把焦点送进网格：给「筛选条末行 ↓」这类从外部进网格的场景用 */
    public static void focusGrid(GridView grid, Position lastPos) {
        View child = pick(grid, lastPos);
        if (child != null && child.requestFocus()) return;
        grid.requestFocus();
        handOff(grid, lastPos);
    }

    /**
     * 海报卡 / 记录卡 / 结果卡的焦点反馈：聚焦 1.06 放大，失焦还原。
     *
     * 这段以前在首页、搜索页、记录页各抄了一份（<b>三份一模一样的 lambda</b>），
     * 改一处忘两处的风险太高，2026-11-07 收口到这里，全站卡片只许调这个方法。
     */
    public static void cardFx(View v) {
        cardFx(v, null);
    }

    /**
     * 带回调的版本：卡片拿到焦点时还要做别的事（例如记住自己第几个）就走这里。
     *
     * 进来先把缩放对齐到「当前是否聚焦」：GridView 会回收条目视图，
     * 复用到的那张可能还留着上一次的 1.06，不对齐就会看到莫名放大的卡片。
     */
    public static void cardFx(View v, final Runnable onFocus) {
        float init = v.isFocused() ? SCALE_FOCUS : 1f;
        v.setScaleX(init);
        v.setScaleY(init);
        v.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean has) {
                float s = has ? SCALE_FOCUS : 1f;
                view.animate().scaleX(s).scaleY(s).setDuration(FX_MS).start();
                if (has && onFocus != null) onFocus.run();
            }
        });
    }

    /**
     * 顶栏图标按钮的焦点反馈。
     *
     * IconButton 样式的 alpha 固定 0.72（让图标不抢标题重心），selector 里 focused
     * 只是把底色从 6% 白提到 12% 白 —— 深色背景 + 72% 透明，3 米外等于「看不出选中了没有」。
     * 这里统一成 1.06 放大 + 提亮到不透明，和海报卡、tab 的焦点视觉一致。
     */
    public static void iconFx(View v) {
        final float idle = 0.72f;
        v.setAlpha(idle);
        v.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View view, boolean has) {
                float s = has ? SCALE_FOCUS : 1f;
                view.animate().scaleX(s).scaleY(s).setDuration(FX_MS).start();
                view.setAlpha(has ? 1f : idle);
            }
        });
    }

    private static void handOff(final GridView grid, final Position lastPos) {
        View child = pick(grid, lastPos);
        if (child != null && child.requestFocus()) return;

        // 首帧可能还没布局完（child 为 null），下一帧再试一次
        grid.post(new Runnable() {
            @Override
            public void run() {
                View c = pick(grid, lastPos);
                if (c != null && !c.isFocused()) c.requestFocus();
            }
        });
    }

    /** 上次停留的那张（若仍可见），否则退回画面第一张 */
    private static View pick(GridView grid, Position lastPos) {
        int pos = lastPos == null ? 0 : lastPos.get();
        int idx = pos - grid.getFirstVisiblePosition();
        View c = (idx >= 0 && idx < grid.getChildCount()) ? grid.getChildAt(idx) : null;
        return c != null ? c : grid.getChildAt(0);
    }
}
