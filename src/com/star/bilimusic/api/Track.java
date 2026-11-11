package com.star.bilimusic.api;

/** 一首可播放的曲目。 */
public class Track {
    public String bvid;
    public long cid;
    public long aid;
    public int page = 1;
    public String title = "";
    public String author = "";
    public String cover = "";
    public long duration = 0;      // 毫秒
    public String streamUrl;        // 音频直链，会过期
    public long streamAt = 0;
    public String quality = "";

    /**
     * 在收藏夹里的位置（从 1 开始），只用于日志中标识是哪首歌。
     *
     * 只存内存、不持久化：它只是给人看编号用的，
     * 列表重新加载时会重新分配，没必要写进 SharedPreferences。
     * 0 表示尚未分配（例如服务重启后恢复的单曲）。
     */
    public int seq = 0;

    public String key() {
        return bvid + "_p" + page;
    }

    /** 直链是否还新鲜（保守 20 分钟）。 */
    public boolean fresh() {
        return streamUrl != null && !streamUrl.isEmpty()
                && System.currentTimeMillis() - streamAt < 20L * 60 * 1000;
    }
}
