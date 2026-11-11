package com.star.bilimusic.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * 简洁的旋转加载圈。
 *
 * 用 Canvas 画弧线，由外部按帧调用 setAngle 推进角度，
 * 不依赖 ObjectAnimator（避免 animator_duration_scale=0 的设备不动）。
 */
public class LoadingSpinner extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private float angle = 0f;
    private float stroke;

    public LoadingSpinner(Context c) {
        super(c);
        stroke = getResources().getDisplayMetrics().density * 2.5f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(Colors.ACCENT);
    }

    public void setColor(int color) {
        paint.setColor(color);
        invalidate();
    }

    public void setAngle(float a) {
        angle = a;
        invalidate();
    }

    public float angle() {
        return angle;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        float inset = stroke / 2f + 1;
        oval.set(inset, inset, w - inset, h - inset);
        // 画一个 270 度的弧，随 angle 旋转
        canvas.drawArc(oval, angle, 270f, false, paint);
    }
}
