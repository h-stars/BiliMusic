package com.star.bilimusic.api;

/**
 * B 站「合集」（season）。
 *
 * 与收藏夹的区别：
 *   - 收藏夹是用户手动归档的视频集合；
 *   - 合集是 UP 主投稿时归类的系列（同一系列的多期视频）。
 *
 * 本 App 里展示的是「我收藏/追更的合集」，通过
 * x/v3/fav/folder/collected/list 按自己的 mid 拉取，
 * 其中该接口返回的 id 字段就是这里的 seasonId。
 */
public class Season {
    /** 合集 ID（接口里的 id 字段），请求合集内视频时必填。 */
    public long seasonId;
    /** 合集标题。 */
    public String title = "";
    /** 合集内视频总数。 */
    public int total = 0;
    /** 合集封面。 */
    public String cover = "";
    /** 合集所属 UP 主名（接口的 upper.name）。 */
    public String author = "";

    public Season(long seasonId, String title, int total, String cover) {
        this.seasonId = seasonId;
        this.title = title;
        this.total = total;
        this.cover = cover;
    }
}
