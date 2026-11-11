package com.star.bilimusic.api;

/**
 * 一个「临时歌单」。
 *
 * 由搜索结果转换而来：用户在搜索页把结果保存成一个歌单，
 * 之后就像其他歌单一样能播放、能长按删除单曲。
 *
 * 与 ImportedList 的区别：
 *   - ImportedList 来自一个 BV 号（服务端抓取，内容由 B 站决定）
 *   - TempPlaylist 来自本地搜索结果（内容由用户搜索词决定，还能逐首删除）
 * 两者都不支持「从服务端刷新」，都是本地快照。
 */
public class TempPlaylist {

    /** 列表 ID，作为缓存键的一部分，形如 "t_1794138886000"。 */
    public String listId = "";
    /** 歌单名：就是当初的搜索关键词。 */
    public String title = "";
    /** 条目数。 */
    public int count = 0;
    /** 创建时间（毫秒），用于排序与重名编号。 */
    public long createdAt = 0;

    /** 缓存 key 前缀，和 ListCache.keyTemp 对应。 */
    public static final String PREFIX = "t_";
}
