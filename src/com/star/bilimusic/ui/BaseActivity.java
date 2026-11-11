package com.star.bilimusic.ui;

import android.app.Activity;

/**
 * Activity 公共基类。
 *
 * 目前只收敛两处真正重复的东西：密度换算、广播注册的 SDK 兼容分支。
 * 不做更多抽象——两个界面差异较大，强行统一反而更难读。
 */
public abstract class BaseActivity extends Activity {

    /** 密度系数在 Activity 生命周期内不变，缓存一次避免每次 getResources()。 */
    private float density = -1f;

    /** dp 转像素。 */
    protected int dp(int v) {
        if (density < 0) {
            density = getResources().getDisplayMetrics().density;
        }
        return (int) (v * density + 0.5f);
    }

    /**
     * 注册仅应用内可见的广播接收器。
     * Android 13+ 必须显式指定 RECEIVER_NOT_EXPORTED，这里统一处理。
     */
    protected void registerLocal(android.content.BroadcastReceiver rx,
                                 android.content.IntentFilter... filters) {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            for (android.content.IntentFilter f : filters) {
                registerReceiver(rx, f, android.content.Context.RECEIVER_NOT_EXPORTED);
            }
        } else {
            for (android.content.IntentFilter f : filters) {
                registerReceiver(rx, f);
            }
        }
    }
}
