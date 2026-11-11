package com.star.bilimusic.media;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import com.star.bilimusic.net.Http;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 封面加载器：内存 LRU + 磁盘缓存 + 异步下载。
 *
 * 内存缓存按 Android 官方建议取可用堆的 1/8，避免在低端机上撑爆。
 * 磁盘缓存放在应用私有目录，上限 80MB，超出按最后修改时间淘汰。
 */
public final class CoverLoader {

    private static final long MAX_DISK = 80L * 1024 * 1024;   // 磁盘上限 80MB
    /**
     * 每写入这么多张封面才检查一次磁盘总量。
     * 为什么不是每次都查：trimDisk 要对整个缓存目录 listFiles + 逐文件取长度，
     * 缓存到 500 张时，每存一张就要 stat 500 个文件，白白拖慢列表滚动。
     * 代价是磁盘可能短暂超出上限（约 20 张封面，几 MB），下次检查就会清回来。
     */
    private static final int TRIM_EVERY = 20;

    /** 缩略图（列表用）。 */
    public static final int SIZE_THUMB = 200;
    /** 大图（播放页用）。 */
    public static final int SIZE_LARGE = 1080;

    /**
     * 单例。volatile 同上：封面加载在后台线程触发，
     * 懒初始化必须保证只创建一个实例（否则内存/磁盘缓存会分裂成两份）。
     */
    private static volatile CoverLoader instance;

    private final LruCache<String, Bitmap> memory;
    // 单线程下载：3 个并发会同时抢带宽，把音频请求挤慢。
    // 封面是次要资源，慢一点无所谓，但不能拖累播放。
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final File diskDir;
    /** 自上次清理以来写入的张数。只在单线程池里访问，不需要同步。 */
    private int writeCount = 0;

    private CoverLoader(Context c) {
        // 用可用堆的 1/8 做内存上限，比写死 16MB 更安全
        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024 / 8);
        final int cap = Math.min(maxKb, 32 * 1024);   // 再封顶 32MB
        memory = new LruCache<String, Bitmap>(cap) {
            @Override protected int sizeOf(String key, Bitmap b) {
                return b == null ? 0 : b.getByteCount();
            }
        };
        diskDir = new File(c.getCacheDir(), "cover");
        if (!diskDir.exists()) diskDir.mkdirs();
    }

    public static synchronized CoverLoader get(Context c) {
        if (instance == null) instance = new CoverLoader(c.getApplicationContext());
        return instance;
    }

    public interface Callback {
        void onLoaded(String url, Bitmap bm);
    }

    /** 加载缩略图（列表用）。 */
    public void load(String url, Callback cb) {
        load(url, SIZE_THUMB, cb);
    }

    /**
     * 加载封面。
     *
     * @param targetPx 期望的解码边长。列表 200 够用，播放页需要 1080，
     *                 用同一张 200px 的图放大到 1000px 会明显发虚。
     */
    public void load(final String url, final int targetPx, final Callback cb) {
        if (url == null || url.isEmpty()) return;

        final String memKey = url + "@" + targetPx;

        // 1. 内存（按尺寸分别缓存：缩略图和大图互不影响）
        Bitmap cached = memory.get(memKey);
        if (cached != null) {
            cb.onLoaded(url, cached);
            return;
        }

        pool.execute(new Runnable() {
            @Override public void run() {
                // 2. 磁盘（按尺寸分开存，避免大图被缩略图覆盖）
                Bitmap bm = readDisk(url, targetPx);
                if (bm == null) {
                    bm = download(url, targetPx);
                    if (bm != null) writeDisk(url, targetPx, bm);
                }
                if (bm == null) return;
                memory.put(memKey, bm);
                final Bitmap fbm = bm;
                ui.post(new Runnable() {
                    @Override public void run() {
                        cb.onLoaded(url, fbm);
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------- disk cache

    private String keyOf(String url, int targetPx) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest((url + "#" + targetPx).getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(url.hashCode());
        }
    }

    private Bitmap readDisk(String url, int targetPx) {
        File f = new File(diskDir, keyOf(url, targetPx));
        if (!f.exists()) return null;
        try {
            FileInputStream is = new FileInputStream(f);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            byte[] data = bos.toByteArray();
            f.setLastModified(System.currentTimeMillis());   // 更新 LRU 时间
            return decode(data, targetPx);
        } catch (Exception e) {
            return null;
        }
    }

    private void writeDisk(String url, int targetPx, Bitmap bm) {
        FileOutputStream os = null;
        try {
            File f = new File(diskDir, keyOf(url, targetPx));
            os = new FileOutputStream(f);
            bm.compress(Bitmap.CompressFormat.JPEG, 85, os);
            if (++writeCount >= TRIM_EVERY) {
                writeCount = 0;
                trimDisk();
            }
        } catch (Exception ignored) {
            // 写盘失败只是少一张缓存图，不影响功能
        } finally {
            // 必须在 finally 里关：bm.compress 抛异常时原来的写法会漏掉 close，
            // 句柄一直占着，写多了会 EMFILE。
            if (os != null) {
                try { os.close(); } catch (Exception ignored) {}
            }
        }
    }

    /** 磁盘超限时按最后访问时间淘汰最旧的一半。 */
    private void trimDisk() {
        File[] files = diskDir.listFiles();
        if (files == null || files.length == 0) return;
        long total = 0;
        for (File f : files) total += f.length();
        if (total <= MAX_DISK) return;

        Arrays.sort(files, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                return Long.compare(a.lastModified(), b.lastModified());
            }
        });
        long target = MAX_DISK / 2;
        for (File f : files) {
            if (total <= target) break;
            total -= f.length();
            f.delete();
        }
    }

    // ---------------------------------------------------------------- network

    private Bitmap download(String url, int targetPx) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestProperty("User-Agent", Http.MEDIA_UA);
            c.setRequestProperty("Referer", "https://www.bilibili.com/");
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setInstanceFollowRedirects(true);
            InputStream is = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            byte[] data = bos.toByteArray();
            if (data.length < 200) return null;
            return decode(data, targetPx);
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 两遍解码：先取尺寸按需缩略，再用 RGB_565 省一半内存。 */
    private Bitmap decode(byte[] data, int targetPx) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);

        int sample = 1;
        while (o.outWidth / (sample * 2) >= targetPx) sample *= 2;

        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        o2.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(data, 0, data.length, o2);
    }
}
