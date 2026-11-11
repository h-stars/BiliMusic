package com.star.bilimusic.util;

import android.util.Log;

import com.star.bilimusic.api.Bili;
import com.star.bilimusic.api.Track;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 收藏夹 / 合集的列表加载器：缓存优先 + 分页拉取 + 风控规避。
 *
 * 为什么需要这个类：合集最多可达 900+ 首，接口每页只给 30 条，
 * 一次拉完要 30 次请求。B 站对短时间内的密集请求会返回 -352（风控），
 * 而且风控状态会持续一段时间，重登也没用。所以必须：
 *   1) 有缓存时直接返回，完全不联网；
 *   2) 需要联网时逐页拉取，每页之间随机延时，把请求摊开；
 *   3) 拉取过程通过回调报进度，让用户知道在干活。
 *
 * 加载策略约定（与产品确认过）：
 *   - 必须全部加载完才算成功，中途失败不写缓存；
 *   - 只有用户点进去过的列表才会被缓存（由调用时机决定）。
 */
public final class TrackListLoader {

    private static final String TAG = "BiliListLoader";

    /** 每页之间的随机延时区间（毫秒）。实测这个区间能稳定避开风控。 */
    private static final int DELAY_MIN_MS = 500;
    private static final int DELAY_MAX_MS = 1000;

    /** 翻页硬上限，防止接口异常导致死循环（30×100 = 3000 首，足够）。 */
    private static final int MAX_PAGES = 100;

    private static final Random RAND = new Random();

    /** 进度回调。 */
    public interface Progress {
        /**
         * @param loaded 已加载条数
         * @param page   已完成页数
         */
        void onProgress(int loaded, int page);
    }

    /** 结果。 */
    public static class Result {
        /** 成功时的列表；失败为 null。 */
        public List<Track> items;
        /** 是否来自缓存（true 时没有联网）。 */
        public boolean fromCache;
        /** 失败信息；成功为 null。 */
        public String error;
    }

    private TrackListLoader() {}

    /**
     * 加载一个列表。
     *
     * 流程：
     *   1. 查缓存，命中且允许用缓存 → 直接返回（不联网）
     *   2. 否则逐页拉取，每页之间随机延时
     *   3. 全部成功才写缓存
     *
     * @param srcType     Bili.SRC_FOLDER / Bili.SRC_SEASON
     * @param id          收藏夹 media_id 或合集 season_id
     * @param cacheKey    ListCache 的 key
     * @param forceRefresh true 表示忽略缓存，强制重新拉取（手动刷新）
     * @param progress    进度回调，可为 null
     */
    public static Result load(Bili bili, int srcType, long id, String cacheKey,
                              boolean forceRefresh, Progress progress) {
        Result r = new Result();

        // ---------- 1. 缓存优先 ----------
        if (!forceRefresh) {
            List<Track> cached = ListCache.load(cacheKey);
            if (cached != null && !cached.isEmpty()) {
                r.items = cached;
                r.fromCache = true;
                return r;
            }
        }

        // ---------- 2. 逐页拉取 ----------
        List<Track> out = new ArrayList<Track>();
        try {
            for (int page = 1; page <= MAX_PAGES; page++) {
                // 除第一页外，每页前随机等待，避免连续请求触发风控。
                // 第一页不等：让用户尽快看到反应，也减少无效等待。
                if (page > 1) {
                    sleepRandom();
                }
                Bili.TrackPage p = bili.pageOf(srcType, id, page);
                if (p.items.isEmpty()) break;
                out.addAll(p.items);
                if (progress != null) progress.onProgress(out.size(), page);
                if (!p.hasMore) break;
            }
        } catch (Exception e) {
            Log.w(TAG, "load failed: " + cacheKey, e);
            // 失败不写缓存：宁可让用户重试，也不要留一份残缺的列表
            r.error = e.getMessage() == null ? "加载失败" : e.getMessage();
            return r;
        }

        if (out.isEmpty()) {
            r.error = "列表为空";
            return r;
        }

        // 统一分配序号：日志里用它标识是第几首。
        // 缓存重建时也会重新赋号，所以两边行为一致。
        for (int i = 0; i < out.size(); i++) out.get(i).seq = i + 1;

        r.items = out;
        r.fromCache = false;

        // ---------- 3. 写缓存 ----------
        ListCache.save(cacheKey, out);
        return r;
    }

    /** 随机休眠，模拟人类翻页节奏。被中断时直接返回，让上层尽快感知退出。 */
    private static void sleepRandom() {
        int ms = DELAY_MIN_MS + RAND.nextInt(DELAY_MAX_MS - DELAY_MIN_MS + 1);
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
