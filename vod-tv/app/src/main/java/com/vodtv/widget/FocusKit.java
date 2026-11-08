package com.vodtv.widget;

import android.view.KeyEvent;
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
 * 解决思路统一成四步：允许子项持焦 → 容器拿到焦点就转交 → 选中回调里再兜一次
 * → 方向键自己算目标格并自己滚动（1.4.13 补：子项持焦时系统不会替网格滚，见 onGridKey）。
 */
public final class FocusKit {

    /** 全站唯一的焦点放大倍率：2dp 描边 + 1.06 放大，见 AGENTS.md §4.1 视觉铁律 */
    private static final float SCALE_FOCUS = 1.06f;
    private static final long FX_MS = 120;

    /** 翻行时的一次滚动动画时长（AbsListView.smoothScrollBy 的 duration 是 int，别写成 long） */
    private static final int ROW_SCROLL_MS = 140;
    /** 卡片被遮挡时补一次「露出来」的滚动 */
    private static final int REVEAL_MS = 120;
    /** 滚动是动画，目标行要滚进屏幕才会被 layout 出来 → 分几次试着转交焦点 */
    private static final int FOCUS_TRIES = 6;
    private static final long FOCUS_RETRY_MS = 60;

    /** 记住「上次焦点停在第几张」，从顶栏按 ↓ 回网格时还能回到原地，不弹回第一张 */
    public interface Position {
        int get();
    }

    private FocusKit() {
    }

    /** 给海报网格装上「焦点一定落在卡片上」的三道保险 + 方向键自己接管 */
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

        // 4. 方向键：卡片自己持焦时系统不会替网格滚动，必须自己算目标格（见 onGridKey）
        grid.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                return onGridKey(grid, keyCode, event);
            }
        });
    }

    /* ---------- 方向键：自己算目标格 + 自己滚 ---------- */

    private static final int DIR_UP = 0;
    private static final int DIR_DOWN = 1;
    private static final int DIR_LEFT = 2;
    private static final int DIR_RIGHT = 3;

    /**
     * 遥控器方向键：算出「下一格是哪一格」，转交焦点，并保证它真的露在屏幕上。
     *
     * 为什么要自己接（1.4.13，2026-11-08 用户上报「海报墙只能显示第一屏，按 ↓ 不往下滚」）：
     * 卡片是 focusable 的，方向键落到卡片的 View.onKeyDown 上没人处理，于是交给系统的
     * 焦点搜索（FocusFinder）。而 FocusFinder 只在**已经 layout 出来的子视图**里找候选，
     * GridView 又只给可见区域建视图 —— 焦点走到屏幕最下面那一行后，下一行还不存在，
     * 找不到候选 → 焦点原地不动，网格自然也不会滚。这是「子项持焦 + AbsListView」的
     * 固有行为，不是本项目写错，4.4 上尤其明显（1.4.9~1.4.12 只修了「焦点能不能落到卡片上」，
     * 没管「落到卡片上之后还能不能继续往下走」）。
     *
     * 只接管四个方向键，越界一律返回 false 交回系统：
     * ↓ 到最后一格、↑ 到第一行（回顶栏靠布局里写好的 nextFocusUp）、←→ 撞行首行尾，
     * 都保持原有链路，顶栏/筛选条不受影响。
     */
    private static boolean onGridKey(final GridView grid, int keyCode, KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;

        final int dir;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_DOWN:
                dir = DIR_DOWN;
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                dir = DIR_UP;
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                dir = DIR_LEFT;
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                dir = DIR_RIGHT;
                break;
            default:
                return false;
        }

        if (grid.getAdapter() == null) return false;
        int pos = focusedPosition(grid);
        if (pos < 0) return false;

        int count = grid.getAdapter().getCount();
        int cols = Math.max(1, grid.getNumColumns());
        int target;
        switch (dir) {
            case DIR_DOWN:
                target = pos + cols;
                break;
            case DIR_UP:
                target = pos - cols;
                break;
            case DIR_RIGHT:
                // 行尾右移交给系统（可能是顶栏，也可能什么都不做）
                if (pos % cols == cols - 1) return false;
                target = pos + 1;
                break;
            default:
                if (pos % cols == 0) return false;
                target = pos - 1;
                break;
        }
        if (target < 0 || target >= count) return false;

        return moveFocusTo(grid, target);
    }

    /** 焦点在哪一格：以真正持焦的子视图为准，不依赖容器的 mSelectedPosition */
    private static int focusedPosition(GridView grid) {
        View f = grid.getFocusedChild();
        if (f == null) return -1;
        int idx = grid.indexOfChild(f);
        return idx >= 0 ? grid.getFirstVisiblePosition() + idx : -1;
    }

    /** 把焦点转到第 target 格；目标还没建视图就先滚一行，滚完再转交 */
    private static boolean moveFocusTo(final GridView grid, final int target) {
        View child = childAt(grid, target);
        if (child != null) {
            if (!child.requestFocus()) return false;
            reveal(grid, child);
            return true;
        }

        int step = rowStep(grid);
        if (step <= 0) return false;
        boolean back = target < grid.getFirstVisiblePosition();
        grid.smoothScrollBy(back ? -step : step, ROW_SCROLL_MS);
        tryFocusLater(grid, target, 1);
        return true;
    }

    /**
     * 滚动是动画（ROW_SCROLL_MS），目标行滚进屏幕后才被 layout 出来，
     * 所以按帧试几次；全试完还接不住就放弃（用户再按一次 ↓ 会从新位置继续，不会卡死）。
     */
    private static void tryFocusLater(final GridView grid, final int target, final int attempt) {
        grid.postDelayed(new Runnable() {
            @Override
            public void run() {
                View child = childAt(grid, target);
                if (child != null && child.requestFocus()) {
                    reveal(grid, child);
                    return;
                }
                if (attempt < FOCUS_TRIES) tryFocusLater(grid, target, attempt + 1);
            }
        }, attempt == 1 ? ROW_SCROLL_MS : FOCUS_RETRY_MS);
    }

    private static View childAt(GridView grid, int position) {
        int idx = position - grid.getFirstVisiblePosition();
        if (idx < 0 || idx >= grid.getChildCount()) return null;
        return grid.getChildAt(idx);
    }

    /** 卡片被屏幕边缘切掉一截时补一次滚动，让它整张露出来（焦点放大 1.06 也算在内） */
    private static void reveal(GridView grid, View child) {
        int limitTop = grid.getPaddingTop();
        int limitBottom = grid.getHeight() - grid.getPaddingBottom();
        int dy = 0;
        if (child.getBottom() > limitBottom) dy = child.getBottom() - limitBottom;
        else if (child.getTop() < limitTop) dy = child.getTop() - limitTop;
        if (dy != 0) grid.smoothScrollBy(dy, REVEAL_MS);
    }

    /**
     * 一行有多高（含行间距）。屏幕上还有第二行时按两行的 top 差实测——
     * 三个网格的行间距各不相同（16dp / space_md），实测比读资源稳。
     */
    private static int rowStep(GridView grid) {
        if (grid.getChildCount() == 0) return 0;
        View first = grid.getChildAt(0);
        int cols = Math.max(1, grid.getNumColumns());
        if (grid.getChildCount() > cols) {
            int step = grid.getChildAt(cols).getTop() - first.getTop();
            if (step > 0) return step;
        }
        // 只有一行可见：退回卡片高度，少滚的那点行间距不影响目标行露出来
        return Math.max(first.getHeight(), 0);
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
