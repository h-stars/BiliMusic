package com.star.bilimusic.player;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import com.star.bilimusic.util.Prefs;

/**
 * 定时暂停。
 *
 * 从 PlayService 抽出来：它只需要「到点后干什么」一个回调，
 * 与播放器本身没有耦合。到点动作由服务传入（通常是 pause + 提示）。
 */
public final class SleepTimer {

    /** 到点时回调，用于暂停播放并提示。 */
    public interface OnFire {
        void onFire();
    }

    private final Context ctx;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private OnFire onFire;

    /** 触发时刻，0 表示未设置。 */
    private long sleepAt = 0;
    private Runnable tick;

    public SleepTimer(Context c, OnFire fire) {
        this.ctx = c.getApplicationContext();
        this.onFire = fire;
    }


    /** 服务重建时恢复到期的定时器。 */
    public void restore() {
        clearTick();
        long saved = Prefs.getSleepAt();
        if (saved <= System.currentTimeMillis()) {
            Prefs.setSleepAt(0);
            return;
        }
        sleepAt = saved;
        schedule(saved);
    }

    /** 设置定时。minutes <= 0 表示取消，最短 1 分钟。 */
    public void set(int minutes) {
        cancel();
        if (minutes <= 0) {
            broadcast();
            return;
        }
        if (minutes < 1) minutes = 1;
        sleepAt = System.currentTimeMillis() + minutes * 60L * 1000L;
        Prefs.setSleepAt(sleepAt);
        schedule(sleepAt);
        broadcast();
    }

    public void cancel() {
        sleepAt = 0;
        Prefs.setSleepAt(0);
        clearTick();
    }

    /** 剩余秒数，未设置返回 -1。 */
    public int remainSeconds() {
        if (sleepAt <= 0) return -1;
        long d = (sleepAt - System.currentTimeMillis()) / 1000;
        return d < 0 ? 0 : (int) d;
    }


    // ---------------------------------------------------------------- 内部

    private void schedule(final long target) {
        tick = new Runnable() {
            @Override public void run() {
                // 已被取消或替换
                if (sleepAt == 0 || sleepAt != target) return;

                if (System.currentTimeMillis() >= sleepAt) {
                    sleepAt = 0;
                    tick = null;
                    Prefs.setSleepAt(0);
                    if (onFire != null) onFire.onFire();
                    broadcast();
                    return;
                }
                broadcast();
                ui.postDelayed(this, 1000);
            }
        };
        ui.postDelayed(tick, 1000);
    }

    private void clearTick() {
        if (tick != null) {
            ui.removeCallbacks(tick);
            tick = null;
        }
    }

    /** 广播剩余时间变化，供界面刷新倒计时。 */
    private void broadcast() {
        Intent i = new Intent(PlayService.ACT_SLEEP);
        i.setPackage(ctx.getPackageName());
        i.putExtra("remain", remainSeconds());
        ctx.sendBroadcast(i);
    }
}
