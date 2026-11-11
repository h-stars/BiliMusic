package com.star.bilimusic.util;

import android.util.Log;

import com.star.bilimusic.api.Track;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 跨歌单搜索。
 *
 * 搜索源：ListCache 里的 fav_ / season_ / import_ 三类缓存。
 * 刻意排除 temp_（临时歌单的内容本来就来自前三者，再搜会重复）。
 *
 * 匹配字段：只用 Track.title。原因：这些 title 实际是 B 站视频标题，
 * 通常已经带有歌名/歌手；而 Track.author 是 UP 主名字（搬运号之类），
 * 匹配它反而会引入大量噪声。
 *
 * 语法：只支持单一运算符，且必须带方括号。
 *   陈奕迅                  包含「陈奕迅」
 *   陈奕迅[and]爱情转移      同时包含两者（顺序不限）
 *   陈奕迅[or]周杰伦         包含任一
 *   混用 [and] 与 [or]      视为语法错误，直接提示
 */
public final class TrackSearcher {

    private static final String TAG = "BiliSearch";
    private static final String OP_AND = "[and]";
    private static final String OP_OR = "[or]";

    /** 一次搜索的命中项。 */
    public static class Hit {
        public Track track;
        /** 来源说明，如「收藏夹 · 我的收藏」。 */
        public String sourceLabel = "";
        /** 来源歌单的缓存 key，供以后需要导航时用。 */
        public String sourceKey = "";
    }

    /** 搜索结果。 */
    public static class Result {
        public List<Hit> items = new ArrayList<Hit>();
        /** 语法错误或没有关键词时，这里带可展示的提示。 */
        public String error;
    }

    /**
     * 解析后的查询条件。
     *
     * 拆出来单独测：运算符识别是这条链路上最容易出错的地方。
     */
    static final class Query {
        List<String> terms = new ArrayList<String>();
        boolean useOr = false;
    }

    /**
     * 解析查询串。
     *
     * @return null 表示语法不合法（空串、只有运算符、混用运算符）
     */
    static Query parse(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;

        boolean hasAnd = s.contains(OP_AND);
        boolean hasOr = s.contains(OP_OR);
        if (hasAnd && hasOr) return null;      // 不支持混用

        Query q = new Query();
        q.useOr = hasOr;
        String splitter = hasAnd ? "\\[and\\]" : (hasOr ? "\\[or\\]" : null);

        String[] parts;
        if (splitter == null) {
            parts = new String[]{s};
        } else {
            parts = s.split(splitter, -1);
        }
        for (String p : parts) {
            String t = p.trim();
            if (!t.isEmpty()) q.terms.add(t.toLowerCase(Locale.US));
        }
        // 空串、或者只剩运算符（如 "[and]" / "a[and]"）都算无效
        if (q.terms.isEmpty()) return null;
        return q;
    }

    /** 单条文本是否满足条件。 */
    static boolean matches(Query q, String title) {
        if (q == null) return false;
        String t = title == null ? "" : title.toLowerCase(Locale.US);
        for (String term : q.terms) {
            boolean has = t.contains(term);
            if (q.useOr) {
                if (has) return true;
            } else {
                if (!has) return false;        // AND：一个不满足就淘汰
            }
        }
        return !q.useOr;                       // OR 全不中则为 false；AND 全部满足为 true
    }

    // ------------------------------------------------------------ 缓存

    /**
     * 解析后的歌单缓存：key → 曲目列表。
     *
     * 首次搜索要读几十个 JSON（可能几百 ms），缓存后再次搜索几乎瞬时。
     * 任何会改变列表内容的操作都必须调 {@link #invalidate()}，
     * 否则会搜到旧数据——这是这个方案最容易出错的地方。
     */
    private static final Map<String, List<Track>> CACHE = new HashMap<String, List<Track>>();

    /** 清空缓存。生成/删除临时歌单、刷新列表、导入新歌单后都要调用。 */
    public static synchronized void invalidate() {
        CACHE.clear();
    }

    private static synchronized List<Track> loadCached(String key) {
        if (CACHE.containsKey(key)) return CACHE.get(key);
        List<Track> list = ListCache.load(key);
        if (list == null) list = new ArrayList<Track>();
        CACHE.put(key, list);
        return list;
    }

    /** 某个 key 对应的可读来源名。 */
    private static String sourceLabelOf(String key) {
        if (key == null) return "";
        String tab;
        String name = "";
        if (key.startsWith("fav_")) {
            tab = "收藏夹";
            // 收藏夹列表项里没有歌单名，这里只能用 id 段的占位，
            // 具体名字在 MainActivity 侧用已加载的 folders 补全。
            name = "";
        } else if (key.startsWith("season_")) {
            tab = "合集";
        } else if (key.startsWith("import_")) {
            tab = "BV导入";
        } else {
            tab = "";
        }
        return name.isEmpty() ? tab : (tab + " · " + name);
    }

    /**
     * 执行搜索。必须在后台线程调用（会读文件）。
     *
     * @param raw       原始输入
     * @param nameOfKey 由调用方提供的「key → 歌单名」映射，用于显示来源；
     *                  允许返回 null（此时只显示 tab 名）
     */
    public static Result search(String raw, NameResolver nameOfKey) {
        Result r = new Result();
        Query q = parse(raw);
        if (q == null) {
            r.error = "请输入搜索关键词";
            return r;
        }

        List<String> keys = ListCache.allKeys();
        for (String key : keys) {
            // 排除临时歌单：它的内容本来就来自其它三类，再搜会重复
            if (key.startsWith("temp_")) continue;
            // 只搜这三类
            if (!(key.startsWith("fav_") || key.startsWith("season_") || key.startsWith("import_"))) continue;

            List<Track> list = loadCached(key);
            String tabAndName = sourceLabelOf(key);
            String listName = nameOfKey == null ? null : nameOfKey.nameOf(key);
            String label = (listName == null || listName.isEmpty())
                    ? tabAndName
                    : (tabAndName + " · " + listName);

            for (Track t : list) {
                if (t == null) continue;
                if (!matches(q, t.title)) continue;
                Hit h = new Hit();
                h.track = t;
                h.sourceLabel = label;
                h.sourceKey = key;
                r.items.add(h);
            }
        }
        Log.i(TAG, "search \"" + raw + "\" -> " + r.items.size() + " hits, " + keys.size() + " keys");
        return r;
    }

    /** 由调用方提供歌单名（收藏夹名、合集名、导入列表名）。 */
    public interface NameResolver {
        String nameOf(String cacheKey);
    }

    private TrackSearcher() {}
}
