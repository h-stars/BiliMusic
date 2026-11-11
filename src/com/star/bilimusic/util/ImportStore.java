package com.star.bilimusic.util;

import android.content.Context;
import android.util.Log;

import com.star.bilimusic.api.ImportedList;

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
 * 「BV 导入」列表的索引。
 *
 * 只存元信息（标题、作者、封面、条目数、类型、导入时间），
 * 真正的曲目内容由 {@link ListCache} 按 {@code import_<listId>} 存。
 *
 * 为什么单独放一个索引文件而不是塞 SharedPreferences：
 * 导入项可能累积到几十上百个，且每项有 6~7 个字段，
 * 放 files/importIndex.json 里既能和列表缓存同生共死（退出登录一起清），
 * 又不必让 SharedPreferences 承担这种结构化数据。
 *
 * 文件格式：
 *   {"version":1,"items":[{"listId":"...","title":"...", ...}]}
 */
public final class ImportStore {

    private static final String TAG = "BiliImportStore";
    private static final String FILE_NAME = "importIndex.json";
    private static final int VERSION = 1;

    private static File file;
    private static boolean loaded = false;
    /** 内存索引，按 importedAt 倒序（最新的在最前）。 */
    private static final List<ImportedList> ITEMS = new ArrayList<ImportedList>();

    private ImportStore() {}

    /**
     * 初始化。必须幂等：Prefs.init 会被多处静态初始化分别调用，
     * 每次都清空内存会导致刚导入、还没落盘的条目消失。
     */
    public static void init(Context c) {
        synchronized (ImportStore.class) {
            File f = new File(c.getApplicationContext().getFilesDir(), FILE_NAME);
            if (file != null && file.getAbsolutePath().equals(f.getAbsolutePath())) return;
            file = f;
            loaded = false;
            ITEMS.clear();
        }
    }

    /** 取全部导入项（已按导入时间倒序）。返回的是副本，调用方可随意修改。 */
    public static synchronized List<ImportedList> all() {
        ensureLoaded();
        return new ArrayList<ImportedList>(ITEMS);
    }

    /** 按 listId 找一项，找不到返回 null。 */
    public static synchronized ImportedList find(String listId) {
        if (listId == null) return null;
        ensureLoaded();
        for (ImportedList it : ITEMS) {
            if (listId.equals(it.listId)) return it;
        }
        return null;
    }

    /**
     * 新增或覆盖一项。
     *
     * 重复导入同一个 BV 时，用新的元信息覆盖旧的（条目数可能变了），
     * 列表内容也要由调用方重新写 ListCache。
     */
    public static synchronized void put(ImportedList item) {
        if (item == null || item.listId == null || item.listId.isEmpty()) return;
        ensureLoaded();
        for (int i = 0; i < ITEMS.size(); i++) {
            if (item.listId.equals(ITEMS.get(i).listId)) {
                ITEMS.remove(i);
                break;
            }
        }
        ITEMS.add(item);
        sortLocked();
        saveLocked();
    }

    /**
     * 删除一项。
     *
     * 只删索引；对应的列表缓存由调用方用 ListCache.remove() 一并清掉。
     */
    /**
     * 只更新条目数（删歌之后调用）。
     *
     * 单独开一个方法而不是用 put()：put 会整体替换元信息，
     * 而删歌场景只需要 count 变化，其它字段（标题、来源 BV 等）保持不变。
     */
    public static synchronized void updateCount(String listId, int count) {
        if (listId == null) return;
        ensureLoaded();
        for (ImportedList p : ITEMS) {
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

    /** 清空全部索引（退出登录时调用）。 */
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

    private static void sortLocked() {
        Collections.sort(ITEMS, new Comparator<ImportedList>() {
            @Override public int compare(ImportedList a, ImportedList b) {
                return Long.compare(b.importedAt, a.importedAt);   // 新的在前
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
                    ImportedList it = fromJson(arr.optJSONObject(i));
                    if (it != null) ITEMS.add(it);
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
            for (ImportedList it : ITEMS) arr.put(toJson(it));
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
                if (!tmp.renameTo(file)) {
                    Log.w(TAG, "rename failed");
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "save failed", e);
        }
    }

    private static JSONObject toJson(ImportedList it) {
        try {
            JSONObject o = new JSONObject();
            o.put("listId", it.listId);
            o.put("title", it.title == null ? "" : it.title);
            o.put("author", it.author == null ? "" : it.author);
            o.put("cover", it.cover == null ? "" : it.cover);
            o.put("count", it.count);
            o.put("kind", it.kind);
            o.put("srcBvid", it.srcBvid == null ? "" : it.srcBvid);
            o.put("importedAt", it.importedAt);
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    private static ImportedList fromJson(JSONObject o) {
        if (o == null) return null;
        try {
            String id = o.optString("listId", "");
            if (id.isEmpty()) return null;
            ImportedList it = new ImportedList();
            it.listId = id;
            it.title = o.optString("title", "");
            it.author = o.optString("author", "");
            it.cover = o.optString("cover", "");
            it.count = o.optInt("count", 0);
            it.kind = o.optInt("kind", ImportedList.KIND_SINGLE);
            it.srcBvid = o.optString("srcBvid", "");
            it.importedAt = o.optLong("importedAt", 0);
            return it;
        } catch (Exception e) {
            return null;
        }
    }
}
