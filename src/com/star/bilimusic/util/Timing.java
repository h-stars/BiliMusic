package com.star.bilimusic.util;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 轻量耗时打点，用于定位「点歌后要等好几秒」这类问题。
 *
 * 默认关闭。关闭时 log() 只是一次布尔判断的开销，不写 logcat 也不写文件。
 * 在「设置 → 耗时日志」里打开后才开始记录。
 *
 * 注意：关闭开关**不再删除已有日志**。排查问题时常常是先关掉开关、
 * 再去日志页翻记录，这时旧记录必须还在。
 */
public final class Timing {

    private static final String TAG = "BiliTiming";
    private static final String NAME = "timing.log";

    /** 日志文件上限 1MB（约 5000 次取流），超出就滚动保留后半部分。 */
    private static final long MAX_BYTES = 1024 * 1024;
    /** 滚动后保留的比例：删掉前一半，留后一半。 */
    private static final long KEEP_BYTES = MAX_BYTES / 2;

    /** 由 Bili 初始化时注入，避免在静态方法里持有 Context。 */
    static File dir;

    private Timing() {}

    public static void init(Context c) {
        Context app = c.getApplicationContext();
        dir = app.getFilesDir();
        Prefs.init(c);
    }

    /** 开关状态，默认 false（关闭）。 */
    public static boolean enabled() {
        return Prefs.isTimingEnabled();
    }

    /**
     * 开关诊断日志。
     *
     * 关闭时**只改开关，不删日志**：用户点开日志页正是为了回看刚才的记录，
     * 一关就清空等于把证据销毁了。要清空有专门的 clear() 和界面按钮。
     */
    public static void setEnabled(boolean on) {
        Prefs.setTimingEnabled(on);
    }

    /** 删除日志文件。由「清空」按钮或退出登录调用，不经开关触发。 */
    public static void clear() {
        try {
            File f = file();
            if (f != null && f.exists()) f.delete();
        } catch (Exception ignored) {
        }
    }

    public static void log(String msg) {
        if (!enabled()) return;
        String line = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date())
                + "  " + msg;
        write(line);
    }

    /**
     * 写一条「续行」：不加时间戳，只在本组第一行已经打过时间时用。
     *
     * 为什么需要：一次取流会写 4 行，全部带时间戳既占宽度又冗余
     * （4 行时间几乎一样）。改成只有第一行带时间，后面三行省下
     * 13 个字符的宽度，线路那行就足够完整显示了。
     */
    public static void cont(String msg) {
        if (!enabled()) return;
        write(msg);
    }

    /**
     * 写一条不带时间戳的分隔线。
     *
     * 为什么单独开一个方法：log() 会给每一行都加 "HH:mm:ss.SSS  "，
     * 分隔线加上时间戳反而更乱（一根线前面挂个时间，看着像正文）。
     * 分隔线只是视觉分组，不需要时间信息。
     */
    public static void divider(String line) {
        if (!enabled()) return;
        write(line);
    }

    /**
     * 统一写入入口：三个公开方法都走这里，避免各写一份滚动逻辑。
     *
     * 滚动策略：超过 1MB 时**保留后半部分**而不是整文件删除。
     * 直接删整个文件的做法会让「写满那一刻之前的全部记录」瞬间消失，
     * 而排查问题往往正需要往前翻。
     */
    private static void write(String text) {
        Log.i(TAG, text);
        File f = file();
        if (f == null) return;
        try {
            if (f.length() > MAX_BYTES) roll(f);
            FileWriter w = new FileWriter(f, true);
            w.write(text);
            w.write('\n');
            w.close();
        } catch (Exception ignored) {
        }
    }

    /**
     * 滚动日志：只保留文件末尾 KEEP_BYTES 字节。
     *
     * 从「末尾往前」多读一点，然后从第一个换行符之后开始保留，
     * 避免留下半行残缺内容。
     */
    private static void roll(File f) {
        try {
            long len = f.length();
            if (len <= KEEP_BYTES) return;
            long start = len - KEEP_BYTES;
            byte[] buf = new byte[(int) (len - start)];
            RandomAccessFile raf = new RandomAccessFile(f, "r");
            try {
                raf.seek(start);
                raf.readFully(buf);
            } finally {
                raf.close();
            }
            // 找到第一个换行，从它之后开始保留，防止首行是半截
            int nl = -1;
            for (int i = 0; i < buf.length && i < 200; i++) {
                if (buf[i] == '\n') { nl = i; break; }
            }
            int from = (nl >= 0) ? nl + 1 : 0;
            File tmp = new File(f.getAbsolutePath() + ".tmp");
            java.io.FileOutputStream os = new java.io.FileOutputStream(tmp);
            try {
                os.write(buf, from, buf.length - from);
            } finally {
                os.close();
            }
            if (!tmp.renameTo(f)) {
                // 同目录内 rename 一般成功；失败说明原文件被占用之类。
                // 先删原文件再试一次，但第二次必须检查结果：
                // 若两次都失败，原文件已被删掉，只能保留 tmp 以免日志彻底丢失。
                f.delete();
                if (!tmp.renameTo(f)) {
                    Log.w(TAG, "roll: rename failed twice, keeping " + tmp.getName());
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "roll failed", e);
        }
    }

    public static File file() {
        return dir == null ? null : new File(dir, NAME);
    }

    /** 读出全部日志内容；文件不存在返回空串。 */
    public static String readAll() {
        try {
            File f = file();
            if (f == null || !f.exists()) return "";
            byte[] all = new byte[(int) f.length()];
            RandomAccessFile raf = new RandomAccessFile(f, "r");
            try {
                raf.readFully(all);
            } finally {
                raf.close();
            }
            return new String(all, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    /** 记录一条不依赖诊断开关的日志（只进 logcat，不写文件）。 */
    public static void warn(String msg) {
        Log.w(TAG, msg);
    }
}
