package com.star.bilimusic.util;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 后台任务统一入口。
 *
 * 之前散落着 7 处裸 new Thread()：没有并发上限，异常会静默消失，
 * 界面销毁后任务仍在跑。这里统一成：
 *   - 固定大小线程池（避免请求风暴）
 *   - 异常统一记录，不会让线程静默死掉
 *   - 提供 runUi 回到主线程
 */
public final class Task {

    /**
     * 4 路并发。任务分布：
     *   1) 取音频流（audioUrl + 探测）
     *   2) 列表加载（收藏夹 / 分页）
     *   3) 播放时自动加载下一页
     *   4) 随机模式的批量补全
     * 封面下载走 CoverLoader 自己的单线程池，不占这里。
     * 留 4 路是为了避免「正在加载分页时点歌，取流被排队」。
     */
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4);
    private static final Handler UI = new Handler(Looper.getMainLooper());

    private Task() {}

    /** 在后台线程执行。异常会被捕获并记录，不会中断线程池。 */
    public static void run(final Runnable r) {
        POOL.execute(new Runnable() {
            @Override public void run() {
                try {
                    r.run();
                } catch (Throwable t) {
                    android.util.Log.e("Task", "后台任务异常", t);
                }
            }
        });
    }

    /** 回到主线程执行。 */
    public static void runUi(Runnable r) {
        UI.post(r);
    }

    /** 延迟回主线程。 */


}
