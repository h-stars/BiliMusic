package com.star.bilimusic.util;

import com.star.bilimusic.api.Track;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 临时歌单里的「重复歌曲」检测。
 *
 * 目标：把「陈奕迅-爱情转移」「001.爱情转移-陈奕迅」「【陈奕迅】爱情转移 官方MV」
 * 这类其实同一首歌、只是标题写法不同的条目归到一组，让用户一次决定留哪首。
 *
 * 算法经过 5 版 Dry Run 对比（见开发记录），最终采用：
 *
 *   ① 基础归一化：全角转半角、去括号内容、去开头编号、去噪声词、只留汉字字母数字。
 *   ② 找「公共词」：统计这一批标题里出现率 >= 60% 的子串，长的优先，
 *      被更长的包含的丢掉。典型结果就是歌手名（搜索「陈奕迅」时每条都含它）。
 *   ③ 从每条里删掉公共词，剩下的就是歌名 → 作为分组键。
 *   ④ 保护：删完不足 2 字说明删过头（歌名本身被当成公共词删了），
 *      这时退回「只做基础归一化」的结果。
 *
 * 已知取舍：宁可漏合并，也不误合并。
 *   - 漏合并：两条同一首歌因为写法差太多没归到一组 → 用户手动删，无损失。
 *   - 误合并：不同歌被当成同一组 → 可能删掉用户还想听的歌，代价更高。
 *   Dry Run 显示「搜索歌手名」这个主场景下准确率最高；
 *   「搜索词恰好是歌名」「不同歌手的同名翻唱」这两种边缘情况会漏合并，符合预期。
 */
public final class DuplicateFinder {

    /** 出现率超过这个比例的公共子串才被当成「歌手/专辑名」删掉。 */
    private static final double COMMON_RATIO = 0.6;

    /** 噪声词：这些词对区分歌曲没有帮助，直接删掉。 */
    private static final String[] NOISE_WORDS = {
            "官方", "mv", "高清", "完整版", "hd", "4k", "1080p", "720p",
            "中字", "字幕", "无损", "高音质", "音质", "原版", "超清",
            "live", "现场", "翻唱", "cover", "演唱会",
            // 音质/规格标记：常在标题里带或不带括号出现，一律视为噪声
            "hi-res", "hires", "hi res", "无损音质", "杜比", "dolby",
            "flac", "wav", "320k", "hq", "sq"
    };

    /** 一组重复歌曲。 */
    public static class Group {
        /** 分组依据（归一化后的歌名），仅用于调试与显示。 */
        public String key = "";
        /** 组内所有曲目，顺序与歌单里一致。 */
        public List<Track> tracks = new ArrayList<Track>();
    }

    private DuplicateFinder() {}

    /**
     * 找出所有重复组。
     *
     * @param tracks 待检测的曲目（顺序即歌单顺序，决定组内第一条是谁）
     * @return 只包含 size >= 2 的组，按组内第一首在原列表里的位置排序
     */
    public static List<Group> find(List<Track> tracks) {
        return find(tracks, null);
    }

    /**
     * 找出所有重复组，并支持用户自定义的「无效子串」。
     *
     * 为什么需要自定义子串：自动算法只认「出现率 >= 60% 的公共词」，
     * 像「在百万豪装录音棚大声听」这种长前缀虽然每条都有，但因为它太长、
     * 且只出现在部分标题里，自动识别不到。让用户手动指定就能精准去掉。
     *
     * @param userKeywords 用户输入的无效子串；可为 null
     */
    public static List<Group> find(List<Track> tracks, List<String> userKeywords) {
        List<Group> out = new ArrayList<Group>();
        if (tracks == null || tracks.size() < 2) return out;

        // ① 基础归一化（先做通用清洗，再删用户关键词）
        List<String> bases = new ArrayList<String>(tracks.size());
        for (Track t : tracks) {
            String s = base(t == null ? "" : t.title);
            s = stripUserKeywords(s, userKeywords);
            bases.add(s);
        }

        // ② 找公共词（出现率 >= 60%，长的优先）
        List<String> common = commonTerms(bases);

        // ③ 算分组键
        Map<String, Group> byKey = new LinkedHashMap<String, Group>();
        for (int i = 0; i < tracks.size(); i++) {
            String k = groupKey(bases.get(i), common);
            if (k.isEmpty()) continue;               // 归一化后什么都没有，不参与去重

            Group g = byKey.get(k);
            if (g == null) {
                g = new Group();
                g.key = k;
                byKey.put(k, g);
            }
            g.tracks.add(tracks.get(i));
        }

        // ④ 后缀容错合并：把「一条 key 是另一条 key 的后缀、且只差 1~2 字」的组并起来。
        //
        // 为什么需要这一步：用户输入的自定义关键词很难恰好完整。
        // 例如标题「在百万豪装录音棚大声听X」，用户只输入「在百万豪装录音棚大声」（少个「听」），
        // 删完就剩「听X」，而另一首是「X」——差了开头一个字，分组键不同。
        // 后缀容错能把这类「只差一两个残留字」的情况补回来。
        //
        // 安全约束（宁可漏合并，也不误合并）：
        //   · 只允许「后缀」关系，不允许任意近似
        //   · 多出来的部分最多 2 个字
        //   · 较短的那条至少 4 个字（防止「豆」和「红豆」这种极短串被合并）
        mergeBySuffix(byKey, tracks);

        // ⑤ 只保留 size >= 2 的组
        for (Group g : byKey.values()) {
            if (g.tracks.size() >= 2) out.add(g);
        }
        return out;
    }

    /**
     * 把「后缀关系且只差 1~2 字」的分组合并。
     *
     * 直接用并查集：先把 byKey 的每个 key 当成一个节点，
     * 两两检查是否满足条件，满足就 union；最后把同一集合的曲目并到一起。
     */
    private static void mergeBySuffix(Map<String, Group> byKey, final List<Track> order) {
        if (byKey.size() < 2) return;
        List<String> keys = new ArrayList<String>(byKey.keySet());
        int n = keys.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;

        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (nearBySuffix(keys.get(i), keys.get(j))) {
                    int ri = findRoot(parent, i);
                    int rj = findRoot(parent, j);
                    if (ri != rj) parent[ri] = rj;
                }
            }
        }

        // 合并结果
        Map<Integer, Group> merged = new LinkedHashMap<Integer, Group>();
        for (int i = 0; i < n; i++) {
            int root = findRoot(parent, i);
            Group src = byKey.get(keys.get(i));
            Group dst = merged.get(root);
            if (dst == null) {
                dst = new Group();
                // key 统一用「最短的那个」：它最接近纯净歌名，显示更干净
                dst.key = src.key;
                merged.put(root, dst);
            } else if (src.key.length() < dst.key.length()) {
                dst.key = src.key;
            }
            dst.tracks.addAll(src.tracks);
        }

        // 组内按曲目在原列表里的位置排序。
        // 不能依赖 Track.seq——它是界面加载时才赋的，去重时可能还是 0。
        // 这里用「在入参 tracks 里的下标」当序，由调用方顺序决定。
        for (Group g : merged.values()) {
            Collections.sort(g.tracks, new java.util.Comparator<Track>() {
                @Override public int compare(Track a, Track b) {
                    return order.indexOf(a) - order.indexOf(b);
                }
            });
        }

        // 重建 byKey：用「最短 key + 集合根」做唯一标识，避免不同组合并后互相覆盖
        byKey.clear();
        for (Map.Entry<Integer, Group> e : merged.entrySet()) {
            byKey.put(e.getValue().key + "\u0000" + e.getKey(), e.getValue());
        }
    }


    private static int findRoot(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];      // 路径压缩
            i = parent[i];
        }
        return i;
    }

    /**
     * 两条 key 是否「只差开头的 1~2 个残留字」。
     *
     * 判定：一个是另一个的后缀，且长度差 1~2，
     * 且较短的那条长度 >= 4（太短的串容易误判）。
     */
    static boolean nearBySuffix(String a, String b) {
        if (a == null || b == null) return false;
        if (a.equals(b)) return true;
        String longer = a.length() >= b.length() ? a : b;
        String shorter = a.length() >= b.length() ? b : a;
        if (shorter.length() < 4) return false;         // 太短不可信
        if (!longer.endsWith(shorter)) return false;
        int diff = longer.length() - shorter.length();
        return diff >= 1 && diff <= 2;
    }

    // ------------------------------------------------------------ 归一化

    /** 全角转半角（中文标题里常见的全角括号、全角数字）。 */
    private static String toHalfWidth(String s) {
        char[] c = s.toCharArray();
        for (int i = 0; i < c.length; i++) {
            if (c[i] == 0x3000) c[i] = ' ';
            else if (c[i] > 0xFF00 && c[i] < 0xFF5F) c[i] = (char) (c[i] - 0xFEE0);
        }
        return new String(c);
    }

    /** 只保留汉字、字母、数字。 */
    private static String keepAlnum(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean han = c >= 0x4E00 && c <= 0x9FA5;
            boolean letter = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
            boolean digit = c >= '0' && c <= '9';
            if (han || letter || digit) sb.append(c);
        }
        return sb.toString();
    }

    /**
     * 基础归一化：把标题变成「只剩歌名相关字符」的形式。
     *
     * 注意不能用正则做括号删除：Java 的 String.replaceAll 对中文标点没问题，
     * 但为了零额外依赖和可读性，这里用 indexOf 手动找成对括号。
     */
    /**
     * 基础归一化（对外暴露，供界面做「关键词匹配预览」）。
     *
     * 预览必须和真实去重用同一套归一化，否则用户看到的
     * 「命中 N 首」会和实际结果对不上，反而误导。
     */
    public static String basePublic(String title) {
        return base(title);
    }

    /**
     * 按关键词删一遍（对外暴露，供预览显示「删完剩什么」）。
     * 与 find() 内部的处理完全一致。
     */
    public static String applyKeywordsPublic(String baseStr, List<String> keywords) {
        return stripUserKeywords(baseStr, keywords);
    }

    static String base(String title) {
        if (title == null) return "";
        String s = toHalfWidth(title).toLowerCase(Locale.US);

        // 删括号及内容（兼容全角/半角/方头/花括号）
        s = stripBrackets(s);

        // 删开头编号：001.  01、  1)  第1集
        s = stripLeadingIndex(s);

        // 删噪声词
        for (String w : NOISE_WORDS) {
            s = s.replace(w, "");
        }
        return keepAlnum(s);
    }

    /**
     * 开括号字符集合。
     *
     * 只包含「括元信息」的括号：半角/全角圆括号、方括号、花括号、方头括号【】。
     *
     * 刻意排除中文书名号《》和引号「」『』、角括号〈〉：
     * B 站音乐区标题的通用格式是「UP主前缀 + 歌手 + 《歌名》 + 音质标记」，
     * 《》 里括的绝大多数就是歌名本身。删掉它会让
     *   陈奕迅《爱情转移》 / 陈奕迅《十年》 / 陈奕迅《浮夸》
     * 全部归一化成「陈奕迅」，进而被误判成同一首歌而全部删掉。
     *
     * 相比之下【】里括的是 Hi-res / 官方MV / 1080P 这类元信息，
     * 删掉是必要的，否则会残留在分组键里造成漏合并。
     */
    private static boolean isOpenBracket(char c) {
        return c == '(' || c == '[' || c == '{'
                || c == '（' || c == '［' || c == '｛'
                || c == '【';
    }

    /** 闭括号字符集合（与 isOpenBracket 对应，不含《》「」『』〈〉）。 */
    private static boolean isCloseBracket(char c) {
        return c == ')' || c == ']' || c == '}'
                || c == '）' || c == '］' || c == '｝'
                || c == '】';
    }

    /**
     * 删除成对括号及其内容，支持多层嵌套。
     *
     * 删除括号及其内容，但不处理书名号《》（理由见 isOpenBracket 注释）。
     *
     * 之前只认 () [] {}，漏掉了【】，导致
     * 「…《爱情转移》【Hi-res】」里的「Hi-res」变成 hires 残留在
     * 分组键里，和「001.陈奕迅-爱情转移」差一个词而无法合并。
     *
     * 删完如果整条第于空（说明整句话都在括号里），由调用方按「保留原文」
     * 兜底，不会把分组键算成空字符串。
     */
    private static String stripBrackets(String s) {
        StringBuilder sb = new StringBuilder(s);
        boolean changed = true;
        while (changed) {
            changed = false;
            int start = -1, end = -1;
            for (int i = 0; i < sb.length(); i++) {
                char c = sb.charAt(i);
                if (isOpenBracket(c)) {
                    start = i;
                }
                if (isCloseBracket(c) && start >= 0) {
                    end = i;
                    break;
                }
            }
            if (start >= 0 && end > start) {
                sb.delete(start, end + 1);
                changed = true;
            }
        }
        return sb.toString();
    }

    /** 删掉开头的数字编号（001. / 01、/ 1) / 第1集）。 */
    private static String stripLeadingIndex(String s) {
        int i = 0;
        while (i < s.length() && s.charAt(i) == ' ') i++;
        // 可选的「第」字
        if (i < s.length() && s.charAt(i) == '第') i++;
        int numStart = i;
        while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
        if (i == numStart) return s;                    // 没有数字，不动
        int numLen = i - numStart;
        if (numLen > 3) return s;                       // 太长的数字可能是年份之类，不动
        // 数字后面跟可选的「集/章/话」
        if (i < s.length() && (s.charAt(i) == '集' || s.charAt(i) == '章' || s.charAt(i) == '话')) i++;
        // 再跟一个可选的分隔符
        if (i < s.length()) {
            char c = s.charAt(i);
            if (c == '.' || c == '、' || c == ')' || c == ':' || c == '-' || c == ' ') i++;
        }
        return s.substring(i);
    }

    /**
     * 找这一批标题里的「公共词」。
     *
     * 只看每个标题的「前缀」和「后缀」：歌手名通常写在最前面或最后面。
     * 这样能避免把「出现在中间的歌名」误判成公共词。
     *
     * 但 Dry Run 显示「必须每条都有」太严格会导致一个都识别不出，
     * 所以用 60% 的宽松阈值 + 「删过头就退回」的保护来兜底。
     */
    private static List<String> commonTerms(List<String> bases) {
        int n = bases.size();
        if (n < 2) return Collections.emptyList();

        Map<String, Integer> freq = new HashMap<String, Integer>();
        for (String s : bases) {
            if (s.isEmpty()) continue;
            Set<String> seen = new HashSet<String>();
            int maxLen = Math.min(6, s.length());
            for (int len = 2; len <= maxLen; len++) {
                seen.add(s.substring(0, len));                          // 前缀
                seen.add(s.substring(s.length() - len));                // 后缀
            }
            for (String sub : seen) {
                Integer old = freq.get(sub);
                freq.put(sub, old == null ? 1 : old + 1);
            }
        }

        int threshold = Math.max(2, (int) Math.ceil(n * COMMON_RATIO));
        List<String> cands = new ArrayList<String>();
        for (Map.Entry<String, Integer> e : freq.entrySet()) {
            if (e.getValue() >= threshold) cands.add(e.getKey());
        }
        // 长的优先，这样先删长的，短碎片就不会重复生效
        Collections.sort(cands, new java.util.Comparator<String>() {
            @Override public int compare(String a, String b) {
                int d = b.length() - a.length();
                return d != 0 ? d : a.compareTo(b);
            }
        });

        // 去掉被更长候选包含的（冗余）
        List<String> out = new ArrayList<String>();
        for (String c : cands) {
            boolean covered = false;
            for (String keep : out) {
                if (keep.indexOf(c) >= 0) { covered = true; break; }
            }
            if (!covered) out.add(c);
        }
        return out;
    }

    /**
     * 删掉用户在界面上指定的无效子串。
     *
     * 关键词本身也要走同样的归一化（全角转半角、只留汉字字母数字），
     * 否则用户输入带标点的词就匹配不上。
     *
     * 保护：删完不足 2 字说明删过头（关键词本身就是歌名），
     * 这时退回不删的版本——和公共词的保护逻辑保持一致。
     */
    private static String stripUserKeywords(String s, List<String> keywords) {
        if (s == null || s.isEmpty()) return "";
        if (keywords == null || keywords.isEmpty()) return s;
        String out = s;
        for (String kw : keywords) {
            if (kw == null || kw.isEmpty()) continue;
            String norm = keepAlnum(toHalfWidth(kw).toLowerCase(Locale.US));
            if (norm.isEmpty()) continue;
            out = out.replace(norm, "");
        }
        if (out.length() < 2) return s;     // 删过头，退回
        return out;
    }
    /**
     * 算一条曲目的分组键。
     *
     * 删掉公共词后如果不足 2 字，说明删过头了（歌名本身被当成公共词），
     * 这时退回基础归一化结果——宁可漏合并，也不误合并。
     */
    static String groupKey(String baseStr, List<String> common) {
        if (baseStr == null || baseStr.isEmpty()) return "";
        String dropped = baseStr;
        for (String t : common) {
            if (!t.isEmpty()) dropped = dropped.replace(t, "");
        }
        if (dropped.length() < 2) return baseStr;
        return dropped;
    }
}
