package com.star.bilimusic.util;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 偏好存储统一入口。
 *
 * 注意：文件名和键名保持与历史版本完全一致（"bili" / "player" 两个文件），
 * 否则用户升级后登录态、播放进度、隐藏收藏夹会全部丢失。
 * 这里只做「访问方式」的集中，不改存储格式。
 */
public final class Prefs {

    /** 账号相关：Cookie、音质、隐藏收藏夹、可用线路记忆。 */
    private static final String FILE_ACCOUNT = "bili";
    /** 播放相关：定时、进度、当前曲目、诊断开关。 */
    private static final String FILE_PLAYER = "player";

    /**
     * 上次播放列表的来源，格式 "0:<id>" 或 "1:<id>"。
     *
     * 为什么不再只用 last_folder_id：现在播放的可能来自合集，
     * 只记收藏夹 id 的话，重启后完全无法恢复合集队列
     * （表现为「迷你条一直在同一首，切不了歌」）。
     */
    public static String getLastSource() {
        return player == null ? "" : player.getString("last_source", "");
    }

    public static void setLastSource(int srcType, long id) {
        if (player != null) {
            player.edit().putString("last_source", srcType + ":" + id).apply();
        }
    }

    /**
     * 上次播放列表来自某个「BV 导入」项时，记录它的 listId。
     *
     * 为什么不复用 last_source：那里的 id 是 long（收藏夹 id / 合集 id），
     * 而导入项的 listId 是字符串（"2046621" / "p_BV1C54y1W7TW" / "v_BV1xx"）。
     * 单独一个键，格式不冲突，也便于判断"上次是不是播放导入列表"。
     *
     * 置空表示上次不是来自导入列表。
     */
    public static String getLastImportId() {
        return player == null ? "" : player.getString("last_import_id", "");
    }

    public static void setLastImportId(String listId) {
        if (player != null) {
            player.edit().putString("last_import_id", listId == null ? "" : listId).apply();
        }
    }

    private static SharedPreferences account;
    private static SharedPreferences player;

    private Prefs() {}

    public static void init(Context c) {
        Context app = c.getApplicationContext();
        if (account == null) {
            account = app.getSharedPreferences(FILE_ACCOUNT, Context.MODE_PRIVATE);
        }
        if (player == null) {
            player = app.getSharedPreferences(FILE_PLAYER, Context.MODE_PRIVATE);
        }
        // 列表缓存目录（files/lists）在这里准备好，
        // 之后 ListCache 的所有读写都依赖它已初始化。
        ListCache.init(app);
        // cid 缓存（files/cidList.json）同理
        CidCache.init(app);
        // BV 导入索引（files/importIndex.json）
        ImportStore.init(app);
        // 临时歌单索引（files/tempIndex.json）
        TempStore.init(app);
    }

    // ------------------------------------------------------------ 账号相关

    public static SharedPreferences account() {
        return account;
    }

    public static String getCookie() {
        return account == null ? "" : account.getString("cookie", "");
    }

    public static void setCookie(String v) {
        if (account != null) account.edit().putString("cookie", v).apply();
    }

    /** 音质：0=64K 1=132K 2=192K。 */
    public static int getQuality() {
        return account == null ? 2 : account.getInt("quality", 2);
    }

    public static void setQuality(int q) {
        if (account != null) account.edit().putInt("quality", q).apply();
    }

    public static String getHiddenFolders() {
        return account == null ? "" : account.getString("hidden_folders", "");
    }

    public static void setHiddenFolders(String v) {
        if (account != null) account.edit().putString("hidden_folders", v).apply();
    }

    /** 被隐藏的合集 id（逗号分隔）。与收藏夹分开存，互不影响。 */
    public static String getHiddenSeasons() {
        return account == null ? "" : account.getString("hidden_seasons", "");
    }

    public static void setHiddenSeasons(String v) {
        if (account != null) account.edit().putString("hidden_seasons", v).apply();
    }


    // -------------------------------------------------- CDN 域名族记忆

    /**
     * 最近一次亲测可用的 CDN「族」，例如 mcdn.bilivideo.cn。
     *
     * 为什么要按族而不是按主机名：B 站同一批音轨分散在几十个节点上，
     * 实测 20 首歌用了 19 个不同主机名（xy183x234x98x153xy.mcdn.bilivideo.cn
     * 与 xy36x156x63x29xy.mcdn.bilivideo.cn 是不同主机但同一族）。
     * 按主机名记忆几乎永远命中不了，按族记才能让「免探测」真正生效。
     */
    public static String getTrustedFamily() {
        return account == null ? "" : account.getString("trusted_family", "");
    }

    public static void setTrustedFamily(String v) {
        if (account == null) return;
        if (v == null || v.isEmpty()) account.edit().remove("trusted_family").apply();
        else account.edit().putString("trusted_family", v).apply();
    }
    // cid 缓存已迁移到 util/CidCache（独立文件 + 内存 Map + 批量落盘）。
    // 不再放 SharedPreferences：那份数据会随切歌频繁重写整个 bili.xml。

    public static void clearAccount() {
        if (account != null) account.edit().clear().apply();
    }

    // ------------------------------------------------------------ 播放相关

    public static SharedPreferences player() {
        return player;
    }

    public static long getSleepAt() {
        return player == null ? 0 : player.getLong("sleep_at", 0);
    }

    public static void setSleepAt(long v) {
        if (player == null) return;
        if (v <= 0) player.edit().remove("sleep_at").apply();
        else player.edit().putLong("sleep_at", v).apply();
    }

    /** 播放模式：0=列表循环 1=单曲循环 2=随机。 */
    public static int getPlayMode() {
        return player == null ? 0 : player.getInt("play_mode", 0);
    }

    public static void setPlayMode(int m) {
        if (player != null) player.edit().putInt("play_mode", m).apply();
    }

    /**
     * 播放进度：只保存"当前正在播放的那一首"。
     *
     * 历史歌曲的进度没有意义——切歌时一律从头播，那条记录永远不会被读，
     * 存多了只会让 SharedPreferences 无限增长。
     */
    public static int getProgress() {
        return player == null ? -1 : player.getInt("progress_ms", -1);
    }

    public static String getProgressKey() {
        return player == null ? "" : player.getString("progress_key", "");
    }

    public static void setProgress(String key, int ms) {
        if (player == null) return;
        player.edit()
                .putString("progress_key", key == null ? "" : key)
                .putInt("progress_ms", ms)
                .apply();
    }




    /** 当前曲目（用于服务重启后恢复显示）。 */
    public static String getCurBvid() {
        return player == null ? "" : player.getString("cur_bvid", "");
    }

    public static void saveCurrentTrack(String bvid, long cid, long aid, int page,
                                        String title, String author, String cover,
                                        long duration) {
        if (player == null) return;
        player.edit()
                .putString("cur_bvid", bvid)
                .putLong("cur_cid", cid)
                .putLong("cur_aid", aid)
                .putInt("cur_page", page)
                .putString("cur_title", title)
                .putString("cur_author", author)
                .putString("cur_cover", cover)
                .putLong("cur_duration", duration)
                .apply();
    }

    public static void clearCurrentTrack() {
        if (player == null) return;
        player.edit()
                .remove("cur_bvid").remove("cur_cid").remove("cur_aid").remove("cur_page")
                .remove("cur_title").remove("cur_author").remove("cur_cover")
                .remove("cur_duration").apply();
    }

    /** 诊断日志开关，默认关闭。 */
    public static boolean isTimingEnabled() {
        return account != null && account.getBoolean("timing_enabled", false);
    }

    public static void setTimingEnabled(boolean on) {
        if (account != null) account.edit().putBoolean("timing_enabled", on).apply();
    }
}
