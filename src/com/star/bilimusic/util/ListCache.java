package com.star.bilimusic.util;

import android.content.Context;
import android.util.Log;

import com.star.bilimusic.api.Track;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * 收藏夹 / 合集列表的本地缓存。
 *
 * 为什么要缓存：合集的视频可能多达 900+ 首，服务端接口每页只给 30 条，
 * 一次拉完要 30 次请求；B 站对短时间内的高频请求会触发风控（-352），
 * 表现为「转圈很久后提示触发风控」。缓存之后第二次进入是秒开，
 * 只有首次进入或手动刷新才需要联网。
 *
 * 存储位置：files/lists/&lt;type&gt;_&lt;id&gt;.json
 *   - 与 SharedPreferences 分开：列表可达数百 KB，SharedPreferences
 *     是整文件 XML 读写，不适合存这种量级的数据。
 *   - 与封面缓存分开：封面走 cache/cover 的 LRU，有 80MB 上限；
 *     列表是文字数据，不需要淘汰机制（用户刷新时覆盖同一个文件）。
 */
public final class ListCache {

    private static final String TAG = "BiliListCache";
    private static final String DIR = "lists";
    /** 结构版本号。字段变化时递增，读到旧版本直接丢弃。 */
    private static final int VERSION = 1;

    private static File dir;

    private ListCache() {}

    public static void init(Context c) {
        dir = new File(c.getApplicationContext().getFilesDir(), DIR);
        if (!dir.exists()) dir.mkdirs();
    }

    /** 收藏夹缓存 key。 */
    public static String keyFolder(long mediaId) {
        return "fav_" + mediaId;
    }

    /** 合集缓存 key。 */
    public static String keySeason(long seasonId) {
        return "season_" + seasonId;
    }

    /**
     * BV 导入的列表缓存 key。
     *
     * listId 已经自带类型前缀（见 ImportedList.listId 的说明），
     * 这里再加 "import_" 是为了和「追更合集」的缓存分开存，
     * 避免同一个 season id 在两边互相覆盖。
     */
    public static String keyImport(String listId) {
        return "import_" + listId;
    }

    /**
     * 临时歌单的缓存 key。
     *
     * 前缀用 "temp_" 而不是 "t_"：搜索功能需要按前缀排除临时歌单
     * （临时歌单的内容本来就来自其它三个 tab，再搜一遍会重复），
     * 用完整单词做前缀更不容易误判。
     */
    public static String keyTemp(String listId) {
        return "temp_" + listId;
    }

    /** 列出缓存目录下所有列表 key（不含扩展名）。用于全量搜索。 */
    public static java.util.List<String> allKeys() {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (dir == null) return out;
        File[] files = dir.listFiles();
        if (files == null) return out;
        for (File f : files) {
            String n = f.getName();
            if (!n.endsWith(".json")) continue;      // 跳过 .tmp 等残留
            out.add(n.substring(0, n.length() - 5));
        }
        return out;
    }

    private static File fileOf(String key) {
        if (dir == null || key == null || key.isEmpty()) return null;
        return new File(dir, key + ".json");
    }

    /**
     * 读缓存。
     *
     * @return 缓存内容；没有缓存、文件损坏、版本不符都返回 null
     */
    public static List<Track> load(String key) {
        File f = fileOf(key);
        if (f == null || !f.exists()) return null;
        try {
            byte[] all = new byte[(int) f.length()];
            RandomAccessFile raf = new RandomAccessFile(f, "r");
            try {
                raf.readFully(all);
            } finally {
                raf.close();
            }
            JSONObject root = new JSONObject(new String(all, "UTF-8"));
            if (root.optInt("version", 0) != VERSION) {
                // 结构不兼容：直接丢掉，当作没有缓存
                f.delete();
                return null;
            }
            JSONArray arr = root.optJSONArray("items");
            if (arr == null) return null;
            List<Track> out = new ArrayList<Track>(arr.length());
            for (int i = 0; i < arr.length(); i++) {
                Track t = fromJson(arr.getJSONObject(i));
                if (t != null) out.add(t);
            }
            return out;
        } catch (Exception e) {
            Log.w(TAG, "load failed: " + key, e);
            return null;
        }
    }

    /** 写缓存（覆盖同名文件）。失败静默，不影响正常使用。 */
    public static void save(String key, List<Track> list) {
        File f = fileOf(key);
        if (f == null || list == null) return;
        try {
            JSONArray arr = new JSONArray();
            for (Track t : list) {
                JSONObject o = toJson(t);
                if (o != null) arr.put(o);
            }
            JSONObject root = new JSONObject();
            root.put("version", VERSION);
            root.put("savedAt", System.currentTimeMillis());
            root.put("items", arr);

            // 先写临时文件再改名，避免写到一半被杀进程导致文件损坏
            File tmp = new File(f.getAbsolutePath() + ".tmp");
            FileOutputStream os = new FileOutputStream(tmp);
            try {
                os.write(root.toString().getBytes("UTF-8"));
            } finally {
                os.close();
            }
            if (!tmp.renameTo(f)) {
                f.delete();
                if (!tmp.renameTo(f)) {
                    Log.w(TAG, "rename failed: " + key);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "save failed: " + key, e);
        }
    }

    /**
     * 删除单个列表缓存。
     *
     * @return true 表示确实删掉了一个存在的文件；false 表示本来就不存在。
     *         调用方（隐藏歌单时清缓存）靠它统计实际清理了几个。
     */
    public static boolean remove(String key) {
        File f = fileOf(key);
        if (f == null || !f.exists()) return false;
        return f.delete();
    }

    /** 清空全部列表缓存（退出登录时调用）。 */
    public static void clearAll() {
        if (dir == null) return;
        try {
            File[] files = dir.listFiles();
            if (files == null) return;
            for (File f : files) f.delete();
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------ JSON 转换

    private static JSONObject toJson(Track t) {
        if (t == null || t.bvid == null || t.bvid.isEmpty()) return null;
        try {
            JSONObject o = new JSONObject();
            o.put("bvid", t.bvid);
            o.put("cid", t.cid);
            o.put("aid", t.aid);
            o.put("page", t.page);
            o.put("title", t.title == null ? "" : t.title);
            o.put("author", t.author == null ? "" : t.author);
            o.put("cover", t.cover == null ? "" : t.cover);
            o.put("duration", t.duration);
            // 注意：不存 streamUrl / streamAt / quality。
            // 直链有服务端有效期（约 20 分钟），存下来下次也用不了，
            // 反而会让 fresh() 误判成「还有效」。播放时重新取流即可。
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    private static Track fromJson(JSONObject o) {
        try {
            String bvid = o.optString("bvid", "");
            if (bvid.isEmpty()) return null;
            Track t = new Track();
            t.bvid = bvid;
            t.cid = o.optLong("cid", 0);
            t.aid = o.optLong("aid", 0);
            t.page = o.optInt("page", 1);
            t.title = o.optString("title", "");
            t.author = o.optString("author", "");
            t.cover = o.optString("cover", "");
            t.duration = o.optLong("duration", 0);
            return t;
        } catch (Exception e) {
            return null;
        }
    }
}
