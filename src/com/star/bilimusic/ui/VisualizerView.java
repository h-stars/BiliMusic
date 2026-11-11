package com.star.bilimusic.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * 「正在播放」封面上叠的三根竖条，模拟音频可视化。
 *
 * 说明：这里不接 Android 的 Visualizer API（它需要 RECORD_AUDIO 权限，
 * 而本项目刻意不做权限申请），而是用一组错相的计时曲线画出起伏，
 * 视觉上等价，且零成本。
 *
 * 三种状态：
 *   - 隐藏（不是当前播放的歌）
 *   - 静止（当前歌但已暂停）：三根条固定高度
 *   - 跳动（正在播放）：按时间推进相位
 */
class VisualizerView extends View {

    private static final int BARS = 3;
    /** 一帧的时长（约 30fps，省电且肉眼足够顺滑）。 */
    private static final long FRAME_MS = 33;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    /** 三根条当前的高度比例（0~1）。 */
    private final float[] level = new float[]{0.45f, 0.75f, 0.55f};
    /** 动画起点，用 SystemClock 累加，避免依赖 Animator 的生命周期。 */
    private long phaseMs = 0;
    private long lastTick = 0;

    private boolean running = false;

    public VisualizerView(Context c) {
        super(c);
        // 用主题强调色（和顶栏 Tab 高亮、按钮文字一致），比纯白更协调
        paint.setColor(Colors.ACCENT);
        // 一点阴影让白条在浅色封面上也看得见
        paint.setShadowLayer(2f, 0f, 1f, 0x66000000);
        setLayerType(LAYER_TYPE_SOFTWARE, paint);
        setVisibility(GONE);
    }

    /** 开始跳动（正在播放）。 */
    void start() {
        if (running) return;
        running = true;
        setVisibility(VISIBLE);
        lastTick = System.currentTimeMillis();
        postInvalidateOnAnimation();
        removeCallbacks(ticker);
        postDelayed(ticker, FRAME_MS);
    }

    /**
     * 停止跳动但保持可见（已暂停）。
     * 三根条会停在当前位置，看起来像"定格的频谱"。
     */
    void freeze() {
        running = false;
        removeCallbacks(ticker);
        setVisibility(VISIBLE);
        invalidate();
    }

    /** 完全隐藏（不是当前播放的歌）。 */
    void hideBars() {
        running = false;
        removeCallbacks(ticker);
        setVisibility(GONE);
    }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!running) return;
            long now = System.currentTimeMillis();
            phaseMs += Math.max(0, now - lastTick);
            lastTick = now;
            invalidate();
            postDelayed(this, FRAME_MS);
        }
    };

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(ticker);
        running = false;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        // 三条整体的视觉平衡：
        //   宽度和间距都收窄一点，看起来更「聚拢」而不是散开；
        //   最大高度降低，避免柱条顶到容器边缘显得压抑。
        float barW = w * 0.11f;            // 单根宽度
        float gap = w * 0.09f;             // 间距
        float totalW = barW * BARS + gap * (BARS - 1);
        float left = (w - totalW) / 2f;    // 整体水平居中
        float maxH = h * 0.60f;            // 最高到容器高度的 60%
        float minH = h * 0.10f;            // 最低也留一点，不至于消失
        // 底部对齐：三条的底边固定在这条线上，只让顶部上下动。
        //
        // 基线取 0.80 是为了让「最高状态」的柱条正好在容器里垂直居中：
        //   顶端 = 0.80h - 0.60h = 0.20h
        //   底端 = 0.80h
        //   中心 = 0.50h  ← 正好是容器中心
        // 之前取 0.90，最高柱的中心落在 0.60h，整体看起来偏下。
        float baseY = h * 0.80f;
        float radius = Math.min(barW / 2f, 3f);   // 轻微圆角，保留"柱状"的直边感

        for (int i = 0; i < BARS; i++) {
            float v;
            if (running) {
                // 三根条用不同频率和相位，避免同步得整齐像跳动的心电图
                double t = (phaseMs / 1000.0) * (2.2 + i * 0.6);
                double ph = i * 1.9;
                v = (float) (0.5 + 0.5 * Math.sin(t + ph));
                // 再叠一个更快的分量，让起伏不规则一点
                v = (v + (float) (0.5 + 0.5 * Math.sin(t * 2.3 + ph * 1.4))) / 2f;
                level[i] = 0.2f + v * 0.8f;    // 保证不会缩到 0
            }
            float barH = minH + (maxH - minH) * level[i];
            float x = left + i * (barW + gap);
            // 底边固定，顶边随高度变化
            rect.set(x, baseY - barH, x + barW, baseY);
            canvas.drawRoundRect(rect, radius, radius, paint);
        }
    }
}
