package com.star.bilimusic.api;

/**
 * 一次「BV 导入」产生的列表。
 *
 * 用户粘贴一个 BV 号，可能得到三种结果：
 *   1. 该视频属于某个合集（ugc_season）→ 整个合集，每集 bvid 各不相同
 *   2. 该视频是「视频选集」（多分P）      → 整个选集，bvid 全部相同、靠 page 区分
 *   3. 普通单集视频                       → 只有一条
 *
 * 这三种在界面上都是一份可播放的列表，所以统一用这个类描述。
 */
public class ImportedList {

    /** 来自 UP 主做的合集，可以订阅的那种。 */
    public static final int KIND_SEASON = 0;
    /** 视频选集（多分P），B 站页面上显示「视频选集」，不能整体订阅。 */
    public static final int KIND_PAGES = 1;
    /** 普通单集视频。 */
    public static final int KIND_SINGLE = 2;

    /**
     * 列表 ID：导入后作为缓存键的一部分，也是「是否重复导入」的判断依据。
     *
     * 取值：
     *   - 合集：合集本身的 season id，形如 "2046621"
     *   - 选集：用 "p_" 前缀 + 父视频 bvid，形如 "p_BV1C54y1W7TW"
     *   - 单集：用 "v_" 前缀 + bvid，形如 "v_BV1xx411c7mD"
     *
     * 之所以要区分前缀：合集 id 是纯数字，而 bvid 是字母数字混合，
     * 不会撞，但加前缀后一眼能看出类型，排查时更清楚。
     */
    public String listId = "";
    /** 列表标题（合集名 / 视频标题）。 */
    public String title = "";
    /** UP 主名字。 */
    public String author = "";
    /** 封面 URL。 */
    public String cover = "";
    /** 条目数。 */
    public int count = 0;
    /** 三种类型之一，见上面的 KIND_* 常量。 */
    public int kind = KIND_SINGLE;
    /** 用户最初粘贴的、或从链接里提取出的 BV 号，用于展示与去重。 */
    public String srcBvid = "";
    /** 导入时间（毫秒），列表按它倒序排列。 */
    public long importedAt = 0;

    /** 类型的中文说法，用于列表里显示。 */
    public String kindLabel() {
        switch (kind) {
            case KIND_SEASON: return "合集";
            case KIND_PAGES: return "视频选集";
            default: return "单集";
        }
    }
}
