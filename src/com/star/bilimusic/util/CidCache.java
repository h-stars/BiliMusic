package com.star.bilimusic.util;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * cid 缓存：按 (bvid, page) 记住 B 站分配的分P标识。
 *
 * cid 由 B 站分配后不再变，(bvid, page) → cid 是纯函数，可以安全复用。
 * 命中后省掉一次 /x/web-interface/view 往返（实测 77~196ms）。
 *
 * 为什么不用 SharedPreferences（2.1.0 之前就是这么做的）：
 *   - 旧实现每次 putCid 都要「读全文 → split 2000 行 → 重建字符串 → 整文件重写」。
 *     每切一首新歌就重写一次 bili.xml（装满 2000 条时约 49KB），
 *     既慢又让配置文件和这份缓存绑死在一起。
 *   - 现在改成独立文件 + 内存 Map：写盘只在累积够一定条数时做一次，
 *     切歌路径上只更新内存，几乎零开销。
 *
 * 存储：files/cidList.json
 *   {"version":1,"items":{"BV1xx#1":137649199, ...}}
 *
 * 线程安全：所有公开方法都是 synchronized。调用方多在后台线程
 * （取流发生在 Task.run 里），但 flush 也可能被主线程触发，必须防护。
 */
public final class CidCache {

    private static final String TAG = "BiliCidCache";
    private static final String FILE_NAME = "cidList.json";
    /** 结构版本。字段变化时递增，读到旧版本直接丢弃重建。 */
    private static final int VERSION = 1;

    /**
     * 条目上限。
     *
     * 达到后不再追加新条目（已有条目仍可更新）。2000 条约 49KB，
     * 这个量级对内存和文件都不构成压力，保留上限只是防止异常膨胀。
     */
    private static final int MAX_ENTRIES = 2000;

    /**
     * 攒够多少条改动才落盘。
     *
     * 取 10：切歌时仍然很少写盘，但万一进程被直接杀掉（用户从最近任务
     * 划掉、系统回收），最多只丢 9 条 cid。
     * 早期取 20 实测划掉 App 时会丢掉十几次播放攒下的记录，降到 10 更稳。
     *
     * 另外 MainActivity.onPause() 和 PlayService.onDestroy() 都会主动
     * flush，所以实际丢失窗口比这个阈值更小。
     */
    private static final int FLUSH_THRESHOLD = 10;

    /** 内存里的 cid 表。LinkedHashMap 保持插入顺序，便于按上限淘汰最早的。 */
    private static final LinkedHashMap<String, Long> MEM = new LinkedHashMap<String, Long>();

    private static File file;
    private static boolean loaded = false;
    /** 自上次落盘以来有多少条改动。 */
    private static int pending = 0;

    private CidCache() {}

    /**
     * 初始化。必须幂等。
     *
     * 为什么要幂等：Prefs.init() 会被 Bili / PlayService / Timing 三处
     * 各自的静态初始化分别调用一次。如果这里每次都清空内存并重置 loaded，
     * 那么第二次调用就会把已经攒下、还没落盘的 cid 全部丢掉，
     * 而且会让 loaded 回到 false 触发重复读文件。
     */
    public static void init(Context c) {
        synchronized (CidCache.class) {
            File f = new File(c.getApplicationContext().getFilesDir(), FILE_NAME);
            if (file != null && file.getAbsolutePath().equals(f.getAbsolutePath())) {
                return;         // 已经初始化过同一个路径，什么都不做
            }
            // 首次初始化，或路径变了（正常不会发生，防御性处理）
            if (file != null && pending > 0) flushLocked();   // 换路径前先落盘
            file = f;
            loaded = false;
            MEM.clear();
            pending = 0;
        }
    }

    /**
     * 取某视频的 cid。
     *
     * @return 大于 0 表示命中；0 表示没有记录
     */
    public static synchronized long get(String key) {
        if (key == null || key.isEmpty()) return 0;
        ensureLoaded();
        Long v = MEM.get(key);
        return v == null ? 0 : v;
    }

    /**
     * 记住某视频的 cid。
     *
     * 只更新内存；累积到阈值才落盘，避免每次切歌都写文件。
     */
    public static synchronized void put(String key, long cid) {
        if (key == null || key.isEmpty() || cid <= 0) return;
        ensureLoaded();

        boolean existed = MEM.containsKey(key);
        // 已达上限且是新条目：不再追加（已有条目仍可更新）
        if (!existed && MEM.size() >= MAX_ENTRIES) return;

        MEM.put(key, cid);
        pending++;
        if (pending >= FLUSH_THRESHOLD) {
            flushLocked();
        }
    }

    /**
     * 立即把内存改动写盘。
     *
     * 正常流程由 put() 自动触发；这里公开出来是为了在
     * 服务销毁、退出登录等时机主动落盘，避免丢最后几条。
     */
    public static synchronized void flush() {
        ensureLoaded();
        flushLocked();
    }

    /**
     * 当前内存里有多少条 cid 记录。
     *
     * 给「缓存管理」页展示用。走内存表而不是去解析文件：
     * 文件可能落后于内存（攒够 10 条才落盘），内存里的才是实际可用的量。
     */
    public static synchronized int size() {
        ensureLoaded();
        return MEM.size();
    }

    /** 清空缓存（退出登录、登录失效时调用）。 */
    public static synchronized void clearAll() {
        MEM.clear();
        pending = 0;
        loaded = true;
        try {
            if (file != null && file.exists()) file.delete();
        } catch (Exception e) {
            Log.w(TAG, "clearAll failed", e);
        }
    }

    // ------------------------------------------------------------ 内部实现

    /** 首次访问时把文件读进内存。只做一次。 */
    private static void ensureLoaded() {
        if (loaded) return;
        if (file == null) {
            // 还没 init（正常流程不会走到，防御一下）
            return;
        }
        if (!file.exists()) {
            loaded = true;      // 没有文件 = 没有缓存，不必再尝试
            return;
        }
        try {
            byte[] all = new byte[(int) file.length()];
            RandomAccessFile raf = new RandomAccessFile(file, "r");
            try {
                raf.readFully(all);
            } finally {
                raf.close();
            }
            JSONObject root = new JSONObject(new String(all, "UTF-8"));
            if (root.optInt("version", 0) != VERSION) {
                file.delete();      // 结构不兼容：丢掉重建
                loaded = true;
                return;
            }
            JSONObject items = root.optJSONObject("items");
            if (items != null) {
                Iterator<String> it = items.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    long v = items.optLong(k, 0);
                    if (v > 0) MEM.put(k, v);
                }
            }
            loaded = true;
        } catch (Exception e) {
            // 文件损坏：当作没有缓存，下次写入会重建
            Log.w(TAG, "load failed", e);
            MEM.clear();
            loaded = true;
        }
    }

    /** 把内存表写成 JSON 文件。调用方必须持有 CidCache.class 锁。 */
    private static void flushLocked() {
        if (file == null || pending <= 0) return;
        try {
            JSONObject items = new JSONObject();
            for (Map.Entry<String, Long> e : MEM.entrySet()) {
                items.put(e.getKey(), e.getValue().longValue());
            }
            JSONObject root = new JSONObject();
            root.put("version", VERSION);
            root.put("items", items);

            // 先写临时文件再改名：避免写到一半被杀死导致文件损坏
            File tmp = new File(file.getAbsolutePath() + ".tmp");
            FileOutputStream os = new FileOutputStream(tmp);
            try {
                os.write(root.toString().getBytes("UTF-8"));
            } finally {
                os.close();
            }
            if (!tmp.renameTo(file)) {
                file.delete();
                if (!tmp.renameTo(file)) {
                    Log.w(TAG, "rename failed");
                    return;         // 没写成功，保留 pending 下次再试
                }
            }
            pending = 0;
        } catch (Exception e) {
            Log.w(TAG, "flush failed", e);
        }
    }
}
