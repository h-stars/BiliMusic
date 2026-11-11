package com.star.bilimusic.util;

import android.content.Context;
import android.util.Log;

import com.star.bilimusic.api.TempPlaylist;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 「临时歌单」索引。
 *
 * 与 ImportStore 完全对称：只存元信息（名称、条目数、创建时间），
 * 曲目本体在 {@link ListCache} 里，key 由 {@link ListCache#keyTemp} 生成。
 *
 * 文件：files/tempIndex.json
 *   {"version":1,"items":[{"listId":"t_...","title":"...","count":N,"createdAt":T}]}
 */
public final class TempStore {

    private static final String TAG = "BiliTempStore";
    private static final String FILE_NAME = "tempIndex.json";
    private static final int VERSION = 1;

    /** 配置里的同名编号字符。下标 0 对应 ①。 */
    private static final char[] CIRCLE_NUM = {
            '①', '②', '③', '④', '⑤', '⑥', '⑦', '⑧', '⑨', '⑩',
            '⑪', '⑫', '⑬', '⑭', '⑮', '⑯', '⑰', '⑱', '⑲', '⑳'
    };

    private static File file;
    private static boolean loaded = false;
    private static final List<TempPlaylist> ITEMS = new ArrayList<TempPlaylist>();

    private TempStore() {}

    /** 必须幂等：Prefs.init 会被多处静态初始化分别调用。 */
    public static void init(Context c) {
        synchronized (TempStore.class) {
            File f = new File(c.getApplicationContext().getFilesDir(), FILE_NAME);
            if (file != null && file.getAbsolutePath().equals(f.getAbsolutePath())) return;
            file = f;
            loaded = false;
            ITEMS.clear();
        }
    }

    public static synchronized List<TempPlaylist> all() {
        ensureLoaded();
        return new ArrayList<TempPlaylist>(ITEMS);
    }

    public static synchronized TempPlaylist find(String listId) {
        if (listId == null) return null;
        ensureLoaded();
        for (TempPlaylist p : ITEMS) {
            if (listId.equals(p.listId)) return p;
        }
        return null;
    }


    /**
     * 生成一个不重复的歌单名。
     *
     * 规则（按需求）：一旦出现同名，所有同名歌单都要带上编号——
     * 包括最早创建的那个。所以这里不仅要算出新名字，还要返回
     * 「需要改名」的旧项清单，由调用方一起更新。
     *
     * 实现方式：用新的完整列表（含新项）重新编号后统一比较。
     * 为了简单，调用方拿到 newName 后直接调用 renumberAll() 重排。
     */
    public static synchronized String nextTitle(String rawTitle) {
        ensureLoaded();
        String base = stripCircle(rawTitle);
        int same = 0;
        for (TempPlaylist p : ITEMS) {
            if (base.equals(stripCircle(p.title))) same++;
        }
        if (same == 0) return base;                    // 没有同名，直接用原名
        int idx = same;                                // 已存在 same 个，新的是第 same+1 个
        return base + circleOf(idx);
    }

    /**
     * 把同名的所有歌单按创建时间重新编号。
     *
     * 目的是满足「一旦重名，全部带编号」：第一个 ①、第二个 ②……
     * 调用时机：新增一个同名歌单之后。
     *
     * @return 是否有改动（有则调用方需要保存）
     */
    public static synchronized boolean renumberGroup(String rawTitle) {
        ensureLoaded();
        String base = stripCircle(rawTitle);
        List<TempPlaylist> group = new ArrayList<TempPlaylist>();
        for (TempPlaylist p : ITEMS) {
            if (base.equals(stripCircle(p.title))) group.add(p);
        }
        if (group.size() <= 1) return false;   // 只有一个，不需要编号

        // 按创建时间升序：最早的拿 ①
        Collections.sort(group, new Comparator<TempPlaylist>() {
            @Override public int compare(TempPlaylist a, TempPlaylist b) {
                return Long.compare(a.createdAt, b.createdAt);
            }
        });
        boolean changed = false;
        for (int i = 0; i < group.size(); i++) {
            String want = base + circleOf(i);
            if (!want.equals(group.get(i).title)) {
                group.get(i).title = want;
                changed = true;
            }
        }
        if (changed) saveLocked();
        return changed;
    }

    public static synchronized void put(TempPlaylist item) {
        if (item == null || item.listId == null || item.listId.isEmpty()) return;
        ensureLoaded();
        for (int i = 0; i < ITEMS.size(); i++) {
            if (item.listId.equals(ITEMS.get(i).listId)) {
                ITEMS.set(i, item);
                saveLocked();
                return;
            }
        }
        ITEMS.add(item);
        sortLocked();
        saveLocked();
    }

    /**
     * 只更新条目数（删歌之后调用）。
     *
     * 单独开一个方法而不是用 put()：put 会重置 title 等字段，
     * 而删歌场景只需要 count 变化，title 已经被编号逻辑改过了，
     * 从搜索词重新传进来会把编号覆盖掉。
     */
    public static synchronized void updateCount(String listId, int count) {
        if (listId == null) return;
        ensureLoaded();
        for (TempPlaylist p : ITEMS) {
            if (listId.equals(p.listId)) {
                p.count = count;
                saveLocked();
                return;
            }
        }
    }

    public static synchronized void remove(String listId) {
        if (listId == null || listId.isEmpty()) return;
        ensureLoaded();
        for (int i = 0; i < ITEMS.size(); i++) {
            if (listId.equals(ITEMS.get(i).listId)) {
                ITEMS.remove(i);
                saveLocked();
                return;
            }
        }
    }

    public static synchronized void clearAll() {
        ITEMS.clear();
        loaded = true;
        try {
            if (file != null && file.exists()) file.delete();
        } catch (Exception e) {
            Log.w(TAG, "clearAll failed", e);
        }
    }

    // ------------------------------------------------------------ 内部实现

    /** 去掉末尾的圈号，得到原始搜索词。 */
    private static String stripCircle(String s) {
        if (s == null || s.isEmpty()) return "";
        char last = s.charAt(s.length() - 1);
        for (char c : CIRCLE_NUM) {
            if (c == last) return s.substring(0, s.length() - 1);
        }
        return s;
    }

    /** 0 → ①，1 → ② …超出去用 (n+1)。 */
    private static String circleOf(int idx) {
        if (idx >= 0 && idx < CIRCLE_NUM.length) return String.valueOf(CIRCLE_NUM[idx]);
        return "(" + (idx + 1) + ")";
    }

    private static void sortLocked() {
        Collections.sort(ITEMS, new Comparator<TempPlaylist>() {
            @Override public int compare(TempPlaylist a, TempPlaylist b) {
                return Long.compare(b.createdAt, a.createdAt);   // 新的在前
            }
        });
    }

    private static void ensureLoaded() {
        if (loaded) return;
        if (file == null) return;
        if (!file.exists()) { loaded = true; return; }
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
                file.delete();
                loaded = true;
                return;
            }
            JSONArray arr = root.optJSONArray("items");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    TempPlaylist p = fromJson(arr.optJSONObject(i));
                    if (p != null) ITEMS.add(p);
                }
            }
            sortLocked();
            loaded = true;
        } catch (Exception e) {
            Log.w(TAG, "load failed", e);
            ITEMS.clear();
            loaded = true;
        }
    }

    private static void saveLocked() {
        if (file == null) return;
        try {
            JSONArray arr = new JSONArray();
            for (TempPlaylist p : ITEMS) arr.put(toJson(p));
            JSONObject root = new JSONObject();
            root.put("version", VERSION);
            root.put("items", arr);

            File tmp = new File(file.getAbsolutePath() + ".tmp");
            FileOutputStream os = new FileOutputStream(tmp);
            try {
                os.write(root.toString().getBytes("UTF-8"));
            } finally {
                os.close();
            }
            if (!tmp.renameTo(file)) {
                file.delete();
                if (!tmp.renameTo(file)) Log.w(TAG, "rename failed");
            }
        } catch (Exception e) {
            Log.w(TAG, "save failed", e);
        }
    }

    private static JSONObject toJson(TempPlaylist p) {
        try {
            JSONObject o = new JSONObject();
            o.put("listId", p.listId);
            o.put("title", p.title == null ? "" : p.title);
            o.put("count", p.count);
            o.put("createdAt", p.createdAt);
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    private static TempPlaylist fromJson(JSONObject o) {
        if (o == null) return null;
        try {
            String id = o.optString("listId", "");
            if (id.isEmpty()) return null;
            TempPlaylist p = new TempPlaylist();
            p.listId = id;
            p.title = o.optString("title", "");
            p.count = o.optInt("count", 0);
            p.createdAt = o.optLong("createdAt", 0);
            return p;
        } catch (Exception e) {
            return null;
        }
    }
}
