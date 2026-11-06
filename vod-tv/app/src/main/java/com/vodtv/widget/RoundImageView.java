package com.vodtv.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.widget.ImageView;

import com.vodtv.R;

/**
 * 圆角 ImageView。
 *
 * 为什么不用 {@code setClipToOutline}：那是 API 21 才有的，本项目 minSdk 19，直接调用会崩。
 * 为什么不在 ImageLoader 里把 Bitmap 裁成圆角：那会把内存翻倍（RGB_565 → ARGB_8888），
 * 6 列海报墙一次可见二十多张，很容易顶到 LruCache 上限然后反复重解码。
 * 这里走 Canvas.clipPath，只影响绘制、不占额外内存。
 *
 * 圆角半径默认取 {@code @dimen/radius_md}（全项目统一 8dp），需要别的值调 {@link #setRadiusPx(int)}。
 */
public class RoundImageView extends ImageView {

    private final Path clipPath = new Path();
    private final RectF rect = new RectF();
    private float radius;

    public RoundImageView(Context c) {
        super(c);
        init();
    }

    public RoundImageView(Context c, AttributeSet a) {
        super(c, a);
        init();
    }

    public RoundImageView(Context c, AttributeSet a, int defStyle) {
        super(c, a, defStyle);
        init();
    }

    private void init() {
        radius = getResources().getDimensionPixelSize(R.dimen.radius_md);
    }

    /** 直接给像素值；传 0 表示不要圆角 */
    public void setRadiusPx(int px) {
        if (radius == px) return;
        radius = px;
        invalidate();
    }

    public void setRadiusDp(int dp) {
        setRadiusPx((int) (dp * getResources().getDisplayMetrics().density + 0.5f));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (radius <= 0) {
            super.onDraw(canvas);
            return;
        }
        int save = canvas.save();
        rect.set(0, 0, getWidth(), getHeight());
        clipPath.reset();
        clipPath.addRoundRect(rect, radius, radius, Path.Direction.CW);
        canvas.clipPath(clipPath);
        super.onDraw(canvas);
        canvas.restoreToCount(save);
    }
}
