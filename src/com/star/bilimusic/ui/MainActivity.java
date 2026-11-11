package com.star.bilimusic.ui;

import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.pm.PackageManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ImageView;
import android.graphics.Bitmap;

import com.star.bilimusic.api.Bili;
import com.star.bilimusic.api.FavFolder;
import com.star.bilimusic.api.Season;
import com.star.bilimusic.api.Track;
import com.star.bilimusic.player.PlayService;
import com.star.bilimusic.media.CoverLoader;
import com.star.bilimusic.util.ImportStore;
import com.star.bilimusic.util.DuplicateFinder;
import com.star.bilimusic.util.ListCache;
import com.star.bilimusic.util.TempStore;
import com.star.bilimusic.util.TrackSearcher;
import com.star.bilimusic.util.TrackListLoader;
import com.star.bilimusic.util.Prefs;
import com.star.bilimusic.util.Task;

import java.util.ArrayList;
import java.util.List;

/**
 * 主界面：收藏夹列表 → 曲目列表 → 点击播放。
 * 全部用代码构建视图，不依赖 XML 布局（减少资源编译负担）。
 */
public class MainActivity extends BaseActivity {

    private static final int PAGE_FOLDERS = 0;   // 收藏夹列表
    private static final int PAGE_TRACKS = 1;    // 曲目列表（收藏夹或合集内）
    private static final int PAGE_SEASONS = 2;   // 合集列表
    private static final int PAGE_IMPORT = 3;    // BV 导入
    private static final int PAGE_TEMP = 4;      // 临时歌单

    /** 打开搜索页的请求码。 */
    private static final int REQ_SEARCH = 101;

    // 重建时用来恢复页面的 Bundle 键
    private static final String STATE_PAGE = "ui_page";
    private static final String STATE_SEASON_ID = "ui_season_id";
    private static final String STATE_FOLDER_ID = "ui_folder_id";
    private static final String STATE_TITLE = "ui_title";
    private static final String STATE_IMPORT_ID = "ui_import_id";
    private static final String STATE_TEMP_ID = "ui_temp_id";

    private FrameLayout root;
    /** 竖向总容器（顶栏 + 标题 + 列表区域）。 */
    private LinearLayout contentCol;
    /**
     * 列表区域容器：左右滑动的跟手位移和切换动画只作用在这一层。
     *
     * 不整块动的原因：顶栏的「收藏夹/合集/BV导入」Tab 和标题是固定参照，
     * 跟着一起平移会让位置感和点击区域都变得奇怪。
     */
    private LinearLayout listWrap;
    /**
     * 二级页面的标题行（标题 + 刷新按钮）。
     * 一级页面整行隐藏 —— 那两个用顶栏 Tab 表示。
     */
    private LinearLayout titleRow;
    private TextView titleBar;
    /** 列表刷新按钮（↻），只在二级页面显示。 */
    private TextView refreshBtn;
    /** 「去重」按钮，只在临时歌单的曲目页显示。 */
    private TextView dedupeBtn;
    /** 「批量」按钮，进入批量选择模式。 */
    private TextView batchBtn;

    /**
     * 去重面板的自定义关键词暂存。
     *
     * 关掉去重面板后不清空，下次打开同一首歌单还能接着用；
     * 只有真正离开「曲目列表页」（回到临时歌单列表等一级页）时才销毁，
     * 避免跨歌单串用上一次的关键词。
     */
    private final java.util.List<String> dedupeKeywords =
            new java.util.ArrayList<String>();

    // ---------------- 批量选择模式（只用于临时歌单的曲目页） ----------------
    /** 是否处于批量选择模式。 */
    private boolean batchMode = false;
    /** 批量模式下被选中的曲目下标。 */
    private final java.util.Set<Integer> batchSel = new java.util.HashSet<Integer>();
    /** 批量模式底部操作条。 */
    private LinearLayout batchBar;
    private TextView batchCount;
    private TextView batchAllBtn;

    private TextView subTitle;
    private ProgressBar bar;
    private ListView list;

    private final List<FavFolder> folders = new ArrayList<FavFolder>();
    private final List<Track> tracks = new ArrayList<Track>();
    private int page = PAGE_FOLDERS;
    private FavFolder curFolder;

    /** 合集列表（「我收藏的合集」）。 */
    private final List<Season> seasons = new ArrayList<Season>();
    /** 当前打开的是哪个合集（PAGE_TRACKS 时用来区分来源）。 */
    private Season curSeason;
    /** 当前打开的是哪个导入列表（PAGE_TRACKS 时用来区分来源）。 */
    private com.star.bilimusic.api.ImportedList curImport;

    private SeasonAdapter seasonAdapter;

    private FolderAdapter folderAdapter;
    private TrackAdapter trackAdapter;

    /** 悬浮按钮：跳到当前播放的歌曲。只在当前收藏夹里能找到它时才显示。 */
    private TextView locateBtn;
    /** 当前播放曲目在 tracks 里的下标，-1 表示不在本列表。 */
    private int playingIndex = -1;

    /** 顶栏三个并列 Tab：收藏夹 / 合集 / BV导入。 */
    private TextView tabFolders;
    private TextView tabSeasons;
    private TextView tabImport;
    private TextView tabTemp;
    /** 临时歌单列表数据与适配器。 */
    private final java.util.List<com.star.bilimusic.api.TempPlaylist> tempLists =
            new java.util.ArrayList<com.star.bilimusic.api.TempPlaylist>();
    private TempAdapter tempAdapter;
    /** 当前打开的是哪个临时歌单（PAGE_TRACKS 时区分来源）。 */
    private com.star.bilimusic.api.TempPlaylist curTemp;

    /** BV 导入的输入行（含输入框和「导入」按钮），只在 PAGE_IMPORT 显示。 */
    private LinearLayout importRow;
    private android.widget.EditText importInput;
    private TextView importBtn;
    /** 导入列表的适配器与数据。 */
    private ImportAdapter importAdapter;
    private final java.util.List<com.star.bilimusic.api.ImportedList> importedLists =
            new java.util.ArrayList<com.star.bilimusic.api.ImportedList>();

    private LinearLayout miniBar;
    /** 迷你条容器（含顶部分隔线）：控制显隐要连同分隔线一起。 */
    private LinearLayout miniWrap;
    private TextView miniTitle;
    private ImageView miniCover;
    private String miniCoverUrl;
    private TextView miniBtn;
    private LoadingSpinner miniSpin;
    private final android.os.Handler spinHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean spinning = false;

    /**
     * 加载请求序号。每次发起新加载就 +1，
     * 回调时序号不匹配说明已有更新的请求，直接丢弃结果。
     * 防止快速连点两个收藏夹时旧结果覆盖新结果。
     */
    private volatile int loadSeq = 0;

    /**
     * 是否正在加载列表。
     *
     * 首次进入一个收藏夹/合集时（要走网络逐页拉取）把列表点击挡住：
     * 此时 loader 还没结束，再点另一个列表会和当前加载抢同一个 loadSeq，
     * 结果是「点了 B，界面却在显示 A 的进度」这类混乱。
     * 有缓存时加载是瞬时完成的，这个标志几乎不会被人感知到。
     */
    private boolean loadingList = false;

    /**
     * 是否正在恢复播放队列。
     *
     * 迷你条可以连点：若不加这个标志，两次点击会各自起一个后台任务，
     * 并发读同一个 JSON，并先后启动两次播放页（返回栈里出现两个）。
     */
    private boolean restoringQueue = false;

    /**
     * 一级页面左右滑动切换 Tab。
     *
     * 只在收藏夹 / 合集 / BV导入 这三个列表页生效；
     * 进入曲目页（PAGE_TRACKS）后完全不响应，避免和列表纵向滚动打架。
     */
    private android.view.GestureDetector swipeDetector;
    /**
     * 本次手势是否已被「滑动翻页」吃掉。
     *
     * 一旦识别到横滑翻页，就要把这条事件链后面的 ACTION_UP 也拦掉，
     * 否则子 View（列表行）会收到一次完整点击，表现为
     * 「滑到下一个 Tab 的同时又点开了某个列表」。
     * 在 ACTION_DOWN（新一条手势）时复位。
     */
    private boolean swipeConsumed = false;
    /** 是否正在跟踪一次横滑（按下时确定，纵向跑偏后关闭）。 */
    private boolean swipeTracking = false;
    /** 本次手势的起点坐标，用于松手时算总位移。 */
    private float swipeStartX = 0;
    private float swipeStartY = 0;
    /**
     * 切换动画是否正在播放。
     *
     * 动画期间禁止再次触发滑动：否则两次 animate() 叠加，
     * 第二次会基于第一帧的中间值计算，结束时 translationX 可能回不到 0，
     * 内容就永久偏在一边了。
     */
    private boolean tabAnimating = false;
    /** 触发翻页的最小水平位移（dp）。 */
    private static final int SWIPE_MIN_DX_DP = 80;
    /** 纵向位移超过这个值就不算横向滑动（dp），防止斜着划误触。 */
    private static final int SWIPE_MAX_DY_DP = 80;
    /** 两页之间的固定顺序，滑动时按它在相邻项之间移动，不循环。 */
    private static final int[] PAGE_ORDER = { PAGE_FOLDERS, PAGE_SEASONS, PAGE_IMPORT, PAGE_TEMP };
    /** 恢复流程开始的时间。用于超时兜底：正常流程几十毫秒内就会复位。 */
    private long restoringSince = 0;
    /** 恢复流程超过这个时长就认为已失效（正常只需几十~几百毫秒）。 */
    private static final long RESTORE_TIMEOUT_MS = 10000;

    /** 进度动画：当前显示到的条数、目标条数、是否正在动画。 */
    private int progressShown = 0;
    private int progressTarget = 0;
    private boolean progressAnimating = false;
    /** 当前进度动画所属的加载任务序号，用来判断是否需要重置计数。 */
    private int progressSeq = -1;
    /**
     * 该任务是否已经结束（成功或失败）。
     *
     * 用途：onProgress 的回调是 runOnUiThread 排队的，可能在
     * showTrackList 之后才送达。那时文案已经写成「N 个视频」，
     * 若还让它启动动画就会把正确的文案覆盖回「已加载…」。
     * 所以结束后把这里置 true，回调直接丢弃。
     */
    private boolean progressDone = false;

    /**
     * 界面是否处于前台可见状态。
     *
     * 用 moveTaskToBack 退到后台后实例仍然活着，广播照样会送到这里。
     * 后台时没必要刷新列表和封面（View 不可见，刷了也是白费），
     * 回到前台再补一次即可。
     */
    private boolean uiVisible = false;

    /** 收藏夹多选模式：长按进入，选中若干后批量隐藏。 */
    private boolean selecting = false;
    private final java.util.Set<Long> selectedIds = new java.util.HashSet<Long>();
    private LinearLayout selectBar;
    private TextView selectConfirm;
    private TextView selectAll;

    private BroadcastReceiver stateRx;
    private BroadcastReceiver errRx;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        buildUi();
        registerRx();
        requestNotifyPermission();
        if (!restorePage(b)) {
            reload();          // 首次进入，按登录状态正常加载
        }
    }

    /**
     * 保存当前所在页面。
     *
     * 为什么需要：MainActivity 一旦离开前台（例如进过播放页被压到下面），
     * 就进入系统的可回收名单。内存紧张时它会被销毁，重建后 page 回落成
     * 默认值 PAGE_FOLDERS，用户会发现自己明明在合集页，回来却变成了收藏夹。
     * 这里把「在哪一页 + 打开了哪个列表」记进 Bundle，重建时原样恢复。
     *
     * 注意只存页面标识，不存歌曲列表本身：列表已有文件缓存
     * （ListCache），重新读一次是毫秒级的，塞进 Bundle 反而会撑爆它。
     */
    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt(STATE_PAGE, page);
        if (curTemp != null) {
            out.putString(STATE_TEMP_ID, curTemp.listId);
        } else if (curImport != null) {
        } else if (curSeason != null) {
            out.putLong(STATE_SEASON_ID, curSeason.seasonId);
            out.putString(STATE_TITLE, curSeason.title);
        } else if (curFolder != null) {
            out.putLong(STATE_FOLDER_ID, curFolder.id);
            out.putString(STATE_TITLE, curFolder.title);
        }
    }

    /**
     * 依据 savedInstanceState 恢复到之前的页面。
     *
     * @return true 表示已经按恢复流程处理（调用方不必再走 reload）
     */
    private boolean restorePage(Bundle b) {
        if (b == null || !b.containsKey(STATE_PAGE)) return false;
        if (!Bili.get(this).isLoggedIn()) return false;   // 没登录就直接走登录提示

        int saved = b.getInt(STATE_PAGE, PAGE_FOLDERS);
        long seasonId = b.getLong(STATE_SEASON_ID, 0);
        long folderId = b.getLong(STATE_FOLDER_ID, 0);
        String title = b.getString(STATE_TITLE, "");

        if (saved == PAGE_SEASONS) {
            page = PAGE_SEASONS;
            list.setAdapter(seasonAdapter);
            updateTabs();
            syncTabSubtitle();
            if (seasons.isEmpty()) loadSeasons();
            return true;
        }
        if (saved == PAGE_TEMP) {
            page = PAGE_TEMP;
            curSeason = null;
            curFolder = null;
            curImport = null;
            curTemp = null;
            list.setAdapter(tempAdapter);
            reloadTempList();
            updateTabs();
            syncTabSubtitle();
            return true;
        }
        if (saved == PAGE_IMPORT) {
            page = PAGE_IMPORT;
            curSeason = null;
            curFolder = null;
            curImport = null;
            curTemp = null;
            list.setAdapter(importAdapter);
            reloadImportList();
            updateTabs();
            syncTabSubtitle();
            return true;
        }
        if (saved == PAGE_FOLDERS) {
            page = PAGE_FOLDERS;
            list.setAdapter(folderAdapter);
            updateTabs();
            syncTabSubtitle();
            if (folders.isEmpty()) loadFolders();
            return true;
        }
        // PAGE_TRACKS：重新打开当初那个列表（走缓存，几乎瞬时）
        // 临时歌单优先：它的 listId 也是字符串，独立存在一个键里
        String tempId = b.getString(STATE_TEMP_ID, "");
        if (tempId != null && !tempId.isEmpty()) {
            com.star.bilimusic.api.TempPlaylist tp = TempStore.find(tempId);
            if (tp != null) {
                openTemp(tp);
                return true;
            }
        }

        String importId = b.getString(STATE_IMPORT_ID, "");
        if (importId != null && !importId.isEmpty()) {
            com.star.bilimusic.api.ImportedList meta = ImportStore.find(importId);
            if (meta != null) {
                openImported(meta);
                return true;
            }
        }
        if (seasonId > 0) {
            Season s = new Season(seasonId, title, 0, "");
            loadSeasonTracks(s);
            return true;
        }
        if (folderId > 0) {
            FavFolder f = new FavFolder(folderId, title, 0, false);
            loadTracks(f);
            return true;
        }
        return false;
    }

    private void requestNotifyPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                   != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 100);
        }
    }

    /** 按登录状态决定显示什么。 */
    private void reload() {
        if (Bili.get(this).isLoggedIn()) {
            updateTabs();
            loadFolders();
        } else {
            showLoginPrompt();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        uiVisible = true;
        // 兜底：恢复流程异常中断可能留下标志，导致迷你条点不动。
        // 只在明显超时时复位，避免误清正在进行的任务。
        if (restoringQueue
                && System.currentTimeMillis() - restoringSince > RESTORE_TIMEOUT_MS) {
            restoringQueue = false;
        }
        // 切页动画若因页面被销毁而中断，属性可能停在中间态。
        // 回到前台时无条件归位，并解除动画锁（此时不可能有动画在跑）。
        tabAnimating = false;
        if (contentCol != null) {
            listWrap.setTranslationX(0f);
            listWrap.setAlpha(1f);
        }
        // 从登录页回来时刷新
        if (Bili.get(this).isLoggedIn() && page == PAGE_FOLDERS && folders.isEmpty()) {
            loadFolders();
        } else if (!Bili.get(this).isLoggedIn()
                && list.getAdapter() != folderAdapter) {
            showLoginPrompt();
        }
        // 服务可能已被系统回收，先确保它活着才能读到上次的歌
        PlayService.ensureAlive(this);
        // 退到后台期间广播被跳过，这里补一次最新状态。
        // 必须放在 ensureAlive 之后：否则服务刚被回收时读到的是空状态。
        syncMiniBar();
        // 从登录页返回时，设置弹窗还开着但内容是旧的，重建一次
        SettingsDialog.refreshIfShowing(this);
    }

    /**
     * 退到后台时把 cid 缓存落盘。
     *
     * 为什么放在 onPause：cid 缓存是「攒够 10 条才写盘」的，
     * 而用户从最近任务里划掉 App、系统回收进程、甚至直接锁屏，
     * 都会先走 onPause —— 但 onDestroy 不保证执行（进程可能被直接杀）。
     * 在这里冲一次，能让已经播过的歌下次进来直接命中 cid。
     *
     * 锁屏也会触发本回调（系统会走 onPause → onStop），所以息屏同样安全。
     */
    @Override
    protected void onPause() {
        super.onPause();
        uiVisible = false;
        try {
            com.star.bilimusic.util.CidCache.flush();
        } catch (Exception ignored) {}
    }

    /** 开始/停止底部的加载转圈。 */
    private void setSpinning(boolean on) {
        if (spinning == on) return;
        spinning = on;
        if (miniSpin == null || miniBtn == null) return;

        if (on) {
            // 这里不判断登录态：调用方（状态广播）已经决定过显隐，
            // 再判一次只会让逻辑分散在两处。
            miniWrap.setVisibility(View.VISIBLE);
            miniBtn.setVisibility(View.INVISIBLE);
            miniSpin.setVisibility(View.VISIBLE);
            miniSpin.setAngle(0f);
            spinHandler.post(spinTick);
        } else {
            spinHandler.removeCallbacks(spinTick);
            miniSpin.setVisibility(View.GONE);
            miniBtn.setVisibility(View.VISIBLE);
        }
    }

    /** 每帧推进 12 度（约 30fps，足够顺滑且省电）。 */
    private final Runnable spinTick = new Runnable() {
        @Override public void run() {
            if (!spinning || miniSpin == null) return;
            miniSpin.setAngle((miniSpin.angle() + 12f) % 360f);
            spinHandler.postDelayed(this, 33);
        }
    };

    private void syncMiniBar() {
        // 未登录时不显示迷你条：没有账号就不该有"正在播放"的入口
        if (!Bili.get(this).isLoggedIn()) {
            if (miniWrap != null) miniWrap.setVisibility(View.GONE);
            if (locateBtn != null) locateBtn.setVisibility(View.GONE);
            return;
        }
        PlayService s = PlayService.get();
        if (s != null && s.track() != null) {
            miniWrap.setVisibility(View.VISIBLE);
            miniTitle.setText(s.track().title);
            miniBtn.setText(s.isPlaying() ? "⏸" : "▶");
            setSpinning(s.isLoading());
            loadMiniCover(s.track());
        }
        // 迷你条和定位按钮的显隐条件一致，一起更新免得两处状态不同步
        updateLocateButton();
    }

    /** 迷你条封面。同一首歌不重复请求，切换时清空避免显示上一首的图。 */
    private void loadMiniCover(final Track t) {
        if (miniCover == null || t == null) return;
        final String url = t.cover;
        if (url == null || url.isEmpty()) {
            miniCover.setImageDrawable(null);
            miniCoverUrl = null;
            return;
        }
        if (url.equals(miniCoverUrl)) return;   // 已经是这首歌的封面

        miniCoverUrl = url;
        miniCover.setImageDrawable(null);
        String thumb = Bili.thumbPic(url, 120);
        CoverLoader.get(this).load(thumb, CoverLoader.SIZE_THUMB,
                new CoverLoader.Callback() {
                    @Override public void onLoaded(String u, android.graphics.Bitmap bm) {
                        // 加载期间可能已经切歌，校验后再生效
                        if (bm != null && url.equals(miniCoverUrl)) {
                            miniCover.setImageBitmap(bm);
                        }
                    }
                });
    }

    // ------------------------------------------------------------------- UI

    private void buildUi() {
        root = new FrameLayout(this);
        initSwipe();
        root.setBackgroundColor(Colors.BG);

        LinearLayout col = new LinearLayout(this);
        contentCol = col;
        col.setOrientation(LinearLayout.VERTICAL);
        root.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 顶栏（第一行）：四个并列 Tab + 搜索图标 + 设置图标
        //
        // 四个 Tab 是并列的一级分类（收藏夹 / 合集 / BV导入 / 临时），
        // 互相不嵌套，所以等宽平级排列。
        // 搜索和设置属于「工具」而不是「分类」，用纯图标放在右侧，
        // 避免和 Tab 抢宽度——四个 Tab 加两个图标已经接近一屏宽了。
        LinearLayout barRow = new LinearLayout(this);
        barRow.setOrientation(LinearLayout.HORIZONTAL);
        barRow.setGravity(Gravity.CENTER_VERTICAL);
        barRow.setPadding(dp(8), dp(10), dp(6), dp(6));

        int tabSize = 18;   // 四个 Tab 并排，18sp 是放得下的上限

        tabFolders = new TextView(this);
        tabFolders.setText("收藏夹");
        tabFolders.setTextSize(tabSize);
        tabFolders.setGravity(Gravity.CENTER);
        tabFolders.setSingleLine(true);
        tabFolders.setPadding(0, dp(6), 0, dp(6));
        tabFolders.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { gotoPage(PAGE_FOLDERS); }
        });
        barRow.addView(tabFolders, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        tabSeasons = new TextView(this);
        tabSeasons.setText("合集");
        tabSeasons.setTextSize(tabSize);
        tabSeasons.setGravity(Gravity.CENTER);
        tabSeasons.setSingleLine(true);
        tabSeasons.setPadding(0, dp(6), 0, dp(6));
        tabSeasons.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { gotoPage(PAGE_SEASONS); }
        });
        barRow.addView(tabSeasons, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        tabImport = new TextView(this);
        tabImport.setText("BV导入");
        tabImport.setTextSize(tabSize);
        tabImport.setGravity(Gravity.CENTER);
        tabImport.setSingleLine(true);
        tabImport.setPadding(0, dp(6), 0, dp(6));
        tabImport.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { gotoPage(PAGE_IMPORT); }
        });
        barRow.addView(tabImport, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        tabTemp = new TextView(this);
        tabTemp.setText("临时");
        tabTemp.setTextSize(tabSize);
        tabTemp.setGravity(Gravity.CENTER);
        tabTemp.setSingleLine(true);
        tabTemp.setPadding(0, dp(6), 0, dp(6));
        tabTemp.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { gotoPage(PAGE_TEMP); }
        });
        barRow.addView(tabTemp, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 搜索：纯图标，进入独立页面
        TextView searchIcon = new TextView(this);
        searchIcon.setText("🔍");
        searchIcon.setTextSize(17);
        searchIcon.setGravity(Gravity.CENTER);
        searchIcon.setPadding(dp(8), dp(6), dp(8), dp(6));
        searchIcon.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // 用 forResult 打开：搜索页生成临时歌单后要回来切 Tab 并刷新
                startActivityForResult(
                        new Intent(MainActivity.this, SearchActivity.class), REQ_SEARCH);
            }
        });
        barRow.addView(searchIcon);

        // 设置：灰白色（原来用粉色太抢眼，和 Tab 高亮撞色），纯图标
        TextView setting = new TextView(this);
        setting.setText("⚙");
        setting.setTextColor(Colors.TEXT_HINT);
        setting.setTextSize(19);
        setting.setGravity(Gravity.CENTER);
        setting.setPadding(dp(10), dp(4), dp(10), dp(6));
        setting.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showSettingsMenu();
            }
        });
        barRow.addView(setting);

        col.addView(barRow);

        // 二级页面标题行：左边是名字，最右边是刷新按钮。
        // 一级页面（收藏夹/合集列表）整行隐藏——那里由顶栏 Tab 表示。
        titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setPadding(dp(16), dp(4), dp(8), dp(4));
        titleRow.setVisibility(View.GONE);

        titleBar = new TextView(this);
        titleBar.setText("");
        titleBar.setTextColor(Colors.TEXT_PRIMARY);
        titleBar.setTextSize(18);
        titleBar.setMaxLines(1);
        titleBar.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleRow.addView(titleBar, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 刷新：与标题同一行、贴最右边。
        // 只做二级页面用，一级页面没有「某个列表」可刷。
        refreshBtn = new TextView(this);
        refreshBtn.setText("↻");
        refreshBtn.setTextColor(Colors.ACCENT);
        refreshBtn.setTextSize(20);
        refreshBtn.setPadding(dp(12), dp(4), dp(12), dp(4));
        refreshBtn.setVisibility(View.GONE);
        refreshBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { refreshCurrentList(); }
        });
        titleRow.addView(refreshBtn);

        // 去重按钮：只在「临时歌单」的曲目页出现。
        // 其它三个来源不该在 App 内被删歌，所以默认隐藏。
        dedupeBtn = new TextView(this);
        dedupeBtn.setText("去重");
        dedupeBtn.setTextColor(Colors.ACCENT);
        dedupeBtn.setTextSize(14);
        dedupeBtn.setPadding(dp(12), dp(4), dp(12), dp(4));
        dedupeBtn.setVisibility(View.GONE);
        dedupeBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { dedupeCurrent(); }
        });
        titleRow.addView(dedupeBtn);

        // 批量选择按钮：同样只在临时歌单的曲目页出现。
        // 临时歌单经常需要「删掉一大堆不想听的」，逐首长按太低效。
        batchBtn = new TextView(this);
        batchBtn.setText("批量");
        batchBtn.setTextColor(Colors.ACCENT);
        batchBtn.setTextSize(14);
        batchBtn.setPadding(dp(12), dp(4), dp(12), dp(4));
        batchBtn.setVisibility(View.GONE);
        batchBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { enterBatchMode(); }
        });
        titleRow.addView(batchBtn);

        col.addView(titleRow);

        subTitle = new TextView(this);
        subTitle.setTextColor(Colors.TEXT_SECONDARY);
        subTitle.setTextSize(13);
        subTitle.setPadding(dp(16), 0, dp(16), dp(8));
        col.addView(subTitle);

        // BV 导入的输入行（只在「BV导入」Tab 显示）
        importRow = new LinearLayout(this);
        importRow.setOrientation(LinearLayout.HORIZONTAL);
        importRow.setGravity(Gravity.CENTER_VERTICAL);
        importRow.setPadding(dp(16), 0, dp(12), dp(8));
        importRow.setVisibility(View.GONE);

        importInput = new android.widget.EditText(this);
        importInput.setHint("粘贴 BV 号或视频链接");
        importInput.setTextSize(14);
        importInput.setSingleLine(true);
        importInput.setTextColor(Colors.TEXT_PRIMARY);
        importInput.setHintTextColor(Colors.TEXT_DISABLED);
        importInput.setBackgroundColor(Colors.BG_CARD);
        importInput.setPadding(dp(12), dp(9), dp(12), dp(9));
        importRow.addView(importInput, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        importBtn = new TextView(this);
        importBtn.setText("导入");
        importBtn.setTextColor(Colors.ACCENT);
        importBtn.setTextSize(15);
        importBtn.setPadding(dp(14), dp(10), dp(6), dp(10));
        importBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doImport(); }
        });
        importRow.addView(importBtn);

        col.addView(importRow);

        bar = new ProgressBar(this);
        bar.setVisibility(View.GONE);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.gravity = Gravity.CENTER_HORIZONTAL;
        blp.topMargin = dp(8);
        col.addView(bar, blp);

        // 列表区域单独包一层：左右滑动的动画只作用在这里，
        // 上面的顶栏 Tab、标题、副标题、导入输入框都保持不动。
        listWrap = new LinearLayout(this);
        listWrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        col.addView(listWrap, wlp);

        list = new ListView(this);
        list.setDividerHeight(0);
        list.setBackgroundColor(Colors.BG);
        list.setCacheColorHint(Colors.BG);
        listWrap.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        folderAdapter = new FolderAdapter();
        trackAdapter = new TrackAdapter();
        seasonAdapter = new SeasonAdapter();
        importAdapter = new ImportAdapter();
        tempAdapter = new TempAdapter();
        list.setAdapter(folderAdapter);

        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                if (loadingList && (page == PAGE_FOLDERS || page == PAGE_SEASONS || page == PAGE_IMPORT || page == PAGE_TEMP)) {
                    return true;   // 加载中，吞掉长按，不进入多选/删除
                }
                if (batchMode) {
                    toggleBatchItem(pos);
                    return true;
                }
                if (page == PAGE_TRACKS) {
                    // 临时歌单 / BV 导入列表里长按可以移除单曲；
                    // 收藏夹和合集是服务端数据，仍是打开播放页。
                    if (curTemp != null) {
                        deleteTempSong(pos);
                        return true;
                    }
                    if (curImport != null) {
                        deleteImportSong(pos);
                        return true;
                    }
                    startActivity(new Intent(MainActivity.this, PlayerActivity.class));
                    return true;
                }
                // 临时歌单：一级页长按删整个歌单
                if (page == PAGE_TEMP) {
                    if (pos >= 0 && pos < tempLists.size()) {
                        deleteTemp(tempLists.get(pos));
                        return true;
                    }
                    return false;
                }
                // 导入列表：长按直接删除（不搞多选，项数通常很少）
                if (page == PAGE_IMPORT) {
                    if (pos >= 0 && pos < importedLists.size()) {
                        deleteImported(importedLists.get(pos));
                        return true;
                    }
                    return false;
                }
                // 长按进入多选模式（收藏夹 / 合集各自一套）
                if (page == PAGE_SEASONS) {
                    if (pos >= 0 && pos < seasons.size()) {
                        enterSelectMode(seasons.get(pos).seasonId);
                        return true;
                    }
                    return false;
                }
                if (pos >= 0 && pos < folders.size()) {
                    enterSelectMode(folders.get(pos).id);
                    return true;
                }
                return false;
            }
        });

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                // 正在加载列表时，忽略对「列表项」的点击，避免两个加载互相打断。
                // 多选模式和曲目页点击不受影响（那时没有正在进行的列表加载）。
                if (loadingList && (page == PAGE_FOLDERS || page == PAGE_SEASONS || page == PAGE_IMPORT || page == PAGE_TEMP)) {
                    toast("正在加载，请稍候…");
                    return;
                }
                // 多选模式：点一下切换选中（收藏夹 / 合集都可能）
                if (selecting) {
                    if (page == PAGE_SEASONS) {
                        if (pos >= 0 && pos < seasons.size()) toggleSelect(seasons.get(pos).seasonId);
                    } else if (page == PAGE_FOLDERS) {
                        if (pos >= 0 && pos < folders.size()) toggleSelect(folders.get(pos).id);
                    }
                    return;
                }
                if (!Bili.get(MainActivity.this).isLoggedIn()) {
                    startActivity(new Intent(MainActivity.this, LoginActivity.class));
                    return;
                }
                // 批量模式：点一下切换选中，不播放
                if (batchMode) {
                    toggleBatchItem(pos);
                    return;
                }
                if (page == PAGE_FOLDERS) {
                    // 越界（理论上不会发生）直接忽略，而不是抛 IndexOutOfBounds。
                    // 来源字段（curFolder/curSeason/curImport/curTemp）统一由
                    // loadTracks/loadSeasonTracks/openTemp/openImported 内部设置，
                    // 这里不再重复赋值，避免两处不一致。
                    if (pos >= 0 && pos < folders.size()) {
                        loadTracks(folders.get(pos));
                    }
                } else if (page == PAGE_SEASONS) {
                    if (pos >= 0 && pos < seasons.size()) {
                        loadSeasonTracks(seasons.get(pos));
                    }
                } else if (page == PAGE_TEMP) {
                    if (pos >= 0 && pos < tempLists.size()) {
                        openTemp(tempLists.get(pos));
                    }
                } else if (page == PAGE_IMPORT) {
                    if (pos >= 0 && pos < importedLists.size()) {
                        openImported(importedLists.get(pos));
                    }
                } else {
                    playFrom(pos);
                }
            }
        });

        // 多选操作栏（仅收藏夹多选时显示）
        selectBar = new LinearLayout(this);
        selectBar.setOrientation(LinearLayout.HORIZONTAL);
        selectBar.setGravity(Gravity.CENTER_VERTICAL);
        selectBar.setBackgroundColor(Colors.BG_CARD);
        selectBar.setPadding(dp(16), dp(10), dp(16), dp(10));
        selectBar.setVisibility(View.GONE);

        TextView selectCancel = new TextView(this);
        selectCancel.setText("取消");
        selectCancel.setTextColor(Colors.TEXT_HINT);
        selectCancel.setTextSize(14);
        selectCancel.setPadding(dp(6), dp(8), dp(16), dp(8));
        selectCancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { exitSelectMode(); }
        });
        selectBar.addView(selectCancel);

        View selSpacer = new View(this);
        selectBar.addView(selSpacer, new LinearLayout.LayoutParams(0, 1, 1f));

        // 全选 / 全不选 切换
        selectAll = new TextView(this);
        selectAll.setText("全选");
        selectAll.setTextColor(Colors.TEXT_HINT);
        selectAll.setTextSize(14);
        selectAll.setPadding(dp(12), dp(8), dp(12), dp(8));
        selectAll.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleSelectAll(); }
        });
        selectBar.addView(selectAll);

        selectConfirm = new TextView(this);
        selectConfirm.setText("隐藏选中");
        selectConfirm.setTextColor(Colors.ACCENT);
        selectConfirm.setTextSize(14);
        selectConfirm.setPadding(dp(16), dp(8), dp(6), dp(8));
        selectConfirm.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { hideSelected(); }
        });
        selectBar.addView(selectConfirm);

        col.addView(selectBar);

        // 批量操作栏（仅临时歌单批量模式显示）
        batchBar = new LinearLayout(this);
        batchBar.setOrientation(LinearLayout.HORIZONTAL);
        batchBar.setGravity(Gravity.CENTER_VERTICAL);
        batchBar.setBackgroundColor(Colors.BG_CARD);
        batchBar.setPadding(dp(16), dp(10), dp(16), dp(10));
        batchBar.setVisibility(View.GONE);

        TextView batchCancel = new TextView(this);
        batchCancel.setText("取消");
        batchCancel.setTextColor(Colors.TEXT_HINT);
        batchCancel.setTextSize(14);
        batchCancel.setPadding(dp(6), dp(8), dp(14), dp(8));
        batchCancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { exitBatchMode(); }
        });
        batchBar.addView(batchCancel);

        batchCount = new TextView(this);
        batchCount.setText("已选 0 首");
        batchCount.setTextColor(Colors.TEXT_SECONDARY);
        batchCount.setTextSize(13);
        batchBar.addView(batchCount, new LinearLayout.LayoutParams(0, -2, 1f));

        batchAllBtn = new TextView(this);
        batchAllBtn.setText("全选");
        batchAllBtn.setTextColor(Colors.TEXT_HINT);
        batchAllBtn.setTextSize(14);
        batchAllBtn.setPadding(dp(10), dp(8), dp(10), dp(8));
        batchAllBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleBatchAll(); }
        });
        batchBar.addView(batchAllBtn);

        TextView batchDelete = new TextView(this);
        batchDelete.setText("删除选中");
        batchDelete.setTextColor(Colors.ACCENT);
        batchDelete.setTextSize(14);
        batchDelete.setPadding(dp(14), dp(8), dp(6), dp(8));
        batchDelete.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirmBatchDelete(); }
        });
        batchBar.addView(batchDelete);

        col.addView(batchBar);

        // 迷你播放条
        // 迷你条：与列表并列独占底部区域，用上边线和高一级的底色区分层次
        miniWrap = new LinearLayout(this);
        miniWrap.setOrientation(LinearLayout.VERTICAL);
        miniWrap.setBackgroundColor(Colors.BG_CARD);
        miniWrap.setVisibility(View.GONE);

        View miniTopLine = new View(this);
        miniTopLine.setBackgroundColor(Colors.DIVIDER);
        miniWrap.addView(miniTopLine, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        miniBar = new LinearLayout(this);
        miniBar.setOrientation(LinearLayout.HORIZONTAL);
        miniBar.setGravity(Gravity.CENTER_VERTICAL);
        miniBar.setPadding(dp(12), dp(10), dp(12), dp(10));
        miniWrap.setVisibility(View.GONE);
        miniWrap.addView(miniBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        miniBar.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                openPlayerWithCurrentList();
            }
        });

        // 封面缩略图：让迷你条一眼能认出是"正在播放"，与普通列表行区分开
        miniCover = new ImageView(this);
        int mcSize = dp(40);
        LinearLayout.LayoutParams mcLp = new LinearLayout.LayoutParams(mcSize, mcSize);
        mcLp.rightMargin = dp(10);
        miniCover.setLayoutParams(mcLp);
        miniCover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        miniCover.setBackgroundColor(Colors.PLACEHOLDER);
        miniBar.addView(miniCover);

        miniTitle = new TextView(this);
        miniTitle.setTextColor(Colors.TEXT_PRIMARY);
        miniTitle.setTextSize(14);
        miniTitle.setSingleLine(true);
        miniTitle.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
        miniTitle.setMarqueeRepeatLimit(-1);
        miniBar.addView(miniTitle, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        miniBtn = new TextView(this);
        miniBtn.setText("▶");
        miniBtn.setTextColor(Colors.ACCENT);
        miniBtn.setTextSize(20);
        miniBtn.setPadding(0, 0, 0, 0);
        miniBtn.setGravity(Gravity.CENTER);
        // 清掉主题默认高亮背景，避免出现大块色块
        miniBtn.setBackground(null);
        miniBtn.setIncludeFontPadding(false);
        miniBtn.setMinWidth(0);
        miniBtn.setMinimumWidth(0);
        miniBtn.setMinHeight(0);
        miniBtn.setMinimumHeight(0);
        miniBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                PlayService.toggleFrom(MainActivity.this);
            }
        });
        // 按钮和加载圈叠在一起，加载时隐藏文字显示圆环
        FrameLayout btnBox = new FrameLayout(this);
        int box = dp(40);
        miniBar.addView(btnBox, new LinearLayout.LayoutParams(box, box));

        btnBox.addView(miniBtn, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        miniSpin = new LoadingSpinner(this);
        int spinSize = dp(22);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(spinSize, spinSize);
        slp.gravity = Gravity.CENTER;
        btnBox.addView(miniSpin, slp);
        miniSpin.setVisibility(View.GONE);

        col.addView(miniWrap, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 悬浮「定位当前播放」按钮：叠在列表之上、迷你条上方
        locateBtn = new TextView(this);
        locateBtn.setText("♪");
        locateBtn.setTextColor(Colors.ACCENT);
        locateBtn.setTextSize(20);
        locateBtn.setGravity(Gravity.CENTER);
        locateBtn.setIncludeFontPadding(false);
        locateBtn.setBackground(makeLocateBg());
        locateBtn.setVisibility(View.GONE);   // 默认隐藏，进收藏夹后再判断
        locateBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { scrollToPlaying(); }
        });

        int lbSize = dp(48);
        FrameLayout.LayoutParams lbLp = new FrameLayout.LayoutParams(lbSize, lbSize);
        lbLp.gravity = Gravity.BOTTOM | Gravity.END;
        lbLp.rightMargin = dp(16);
        // 抬到迷你条（约 61dp）上方，再留出系统导航栏高度
        lbLp.bottomMargin = dp(72) + systemBottomInset();
        root.addView(locateBtn, lbLp);

        setContentView(root);
        applyInsets(col);
    }

    /**
     * 重新计算「当前播放歌曲」在 tracks 里的位置，并更新按钮可见性。
     * 歌曲不在当前收藏夹时隐藏按钮。
     */
    private void updateLocateButton() {
        if (locateBtn == null) return;

        playingIndex = -1;
        PlayService svc = PlayService.get();
        Track cur = svc == null ? null : svc.track();

        // 只在曲目列表页、且当前歌确实在列表里时才显示
        if (cur != null && page == PAGE_TRACKS && !tracks.isEmpty()) {
            String k = cur.key();
            if (k != null) {
                for (int i = 0; i < tracks.size(); i++) {
                    String ik = tracks.get(i).key();
                    if (k.equals(ik)) {
                        playingIndex = i;
                        break;
                    }
                }
            }
        }
        locateBtn.setVisibility(playingIndex >= 0 ? View.VISIBLE : View.GONE);

        // 当前播放的那一行要跟着显示/停止可视化条，这里触发一次重绘。
        // 只在曲目页做，避免在收藏夹/合集页白白刷新列表。
        if (page == PAGE_TRACKS && trackAdapter != null) {
            trackAdapter.notifyDataSetChanged();
        }
    }

    /**
     * 中止 ListView 当前的惯性滑动。
     *
     * AbsListView 没有公开的 abortAnimation()，这里用「从当前位置滑 0 像素」
     * 来打断正在跑的 fling——smoothScrollBy 内部会 cancel 掉旧的动画。
     * 比反射稳妥，也不依赖私有 API。
     */
    private void stopListFling() {
        // smoothScrollBy(0,0) 会走 AbsListView 内部的 startScroll，
        // 关掉正在跑的 fling 动画（removeCallbacks + 重置速度）。
        // 不用反射调私有 abortAnimation()：隐藏 API 在 Android 9+ 受限。
        try {
            list.smoothScrollBy(0, 0);
        } catch (Exception ignored) {
        }
    }

    /**
     * 滚动到当前播放的歌曲，让它的中心落在「列表区域」的中心。
     *
     * 做法是两跳，但用户基本看不见：
     *   1) 先用 setSelection 把目标行滚进可见区（ListView 默认贴顶，不猜行高）
     *   2) 下一帧读到这一行的真实高度，算出精确偏移再定位一次
     * 行高不固定（标题最多 2 行），所以必须实测，不能估算。
     * 靠近首尾时内容不足，ListView 会自动收敛，不强行居中。
     */
    private void scrollToPlaying() {
        if (playingIndex < 0 || playingIndex >= tracks.size()) return;

        final int target = playingIndex;
        final int seq = loadSeq;   // 切了收藏夹就不再校正

        // 先停掉用户手指离开后的惯性滑动。
        // 否则 setSelection 跳过去了，fling 还在继续跑，列表会被拖回去。
        stopListFling();

        try {
            list.setSelection(target);
        } catch (Exception e) {
            return;
        }

        list.post(new Runnable() {
            @Override public void run() {
                // 期间可能已返回列表页 / 切换收藏夹 / 切歌，任一变化都放弃校正
                if (page != PAGE_TRACKS) return;
                if (seq != loadSeq) return;
                if (playingIndex != target) return;

                int h = list.getHeight();
                if (h <= 0) return;

                int first = list.getFirstVisiblePosition();
                View row = list.getChildAt(target - first);
                if (row == null) return;

                int rh = row.getHeight();
                if (rh <= 0) return;

                int y = h / 2 - rh / 2;
                if (y < 0) y = 0;

                // 已经在这个位置就不用再动，避免无谓的二次跳动
                if (Math.abs(row.getTop() - y) <= 1) return;

                try {
                    list.setSelectionFromTop(target, y);
                } catch (Exception ignored) {
                }
            }
        });

        highlightRow(target);
    }

    /** 给目标行一个短暂高亮，便于确认落点。 */
    private void highlightRow(final int pos) {
        list.postDelayed(new Runnable() {
            @Override public void run() {
                if (playingIndex != pos) return;
                int first = list.getFirstVisiblePosition();
                View row = list.getChildAt(pos - first);
                if (row == null) return;
                row.setAlpha(0.45f);
                row.animate().alpha(1f).setDuration(450).start();
            }
        }, 180);
    }

    /** 悬浮按钮的圆形背景：半透明深色底 + 淡描边。 */
    private android.graphics.drawable.Drawable makeLocateBg() {
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor(0xE61E1E28);
        g.setStroke(1, Colors.ACCENT_STROKE);
        return g;
    }

    /** 系统底部导航栏高度；无导航栏时返回 0。 */
    private int systemBottomInset() {
        if (Build.VERSION.SDK_INT < 21) return 0;
        int id = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    private void applyInsets(View content) {
        if (Build.VERSION.SDK_INT >= 21) {
            content.setFitsSystemWindows(true);
        }
    }

    // -------------------------------------------------------------- network

    private void loadFolders() {
        exitSelectMode();
        setLoading(true);
        titleBar.setText("我的收藏夹");
        subTitle.setText("");
        final int seq = ++loadSeq;
        Task.run(new Runnable() {
            @Override public void run() {
                String err = null;
                List<FavFolder> got = null;
                try {
                    got = Bili.get(MainActivity.this).favFolders();
                } catch (Exception e) {
                    err = e.getMessage();
                }
                final List<FavFolder> fGot = got;
                final String fErr = err;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (seq != loadSeq) return;   // 已有更新的请求，丢弃
                        setLoading(false);
                        if (fGot == null) {
                            if (handleLoadError(fErr, "加载失败")) return;
                            return;
                        }
                        folders.clear();
                        java.util.Set<Long> hidden = Bili.get(MainActivity.this).hiddenFolders();
                        // 存活集合用 fGot（服务端完整列表，含已隐藏的），
                        // 不能用下面过滤后的 folders，否则隐藏的会被误判成已删除。
                        java.util.Set<Long> alive = new java.util.HashSet<Long>();
                        for (FavFolder f : fGot) alive.add(f.id);
                        pruneOrphanCaches(false, alive);
                        int hiddenCount = 0;
                        for (FavFolder f : fGot) {
                            if (hidden.contains(f.id)) {
                                hiddenCount++;
                                continue;   // 隐藏的收藏夹不进入列表，也就不会被点开、不加载内容
                            }
                            folders.add(f);
                        }
                        page = PAGE_FOLDERS;
                        list.setAdapter(folderAdapter);
                        folderAdapter.notifyDataSetChanged();
                        updateTabs();
                        syncTabSubtitle();
                        updateLocateButton();   // 回到列表页，定位按钮应隐藏
                        subTitle.setText(folders.size() + " 个收藏夹"
                                + (hiddenCount > 0 ? "（已隐藏 " + hiddenCount + " 个）" : "")
                                + "  ·  长按可隐藏");
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------ BV 导入

    /**
     * 执行一次导入。
     *
     * 全流程：提取 BV 号 → 请求接口（一次拿全）→ 写缓存与索引 → 刷新列表。
     * 已经导入过的同一个列表会被覆盖（条目数可能变了），并在提示里说明。
     */
    private void doImport() {
        if (loadingList) {
            toast("正在处理中，请稍候…");
            return;
        }
        String raw = importInput == null ? "" : importInput.getText().toString();
        final String bvid = Bili.extractBvid(raw);
        if (bvid.isEmpty()) {
            toast("没找到 BV 号，请粘贴 BV 号或视频链接");
            return;
        }
        if (!Bili.get(this).isLoggedIn()) {
            toast("请先登录");
            return;
        }

        importBtn.setTextColor(Colors.TEXT_DISABLED);
        importBtn.setEnabled(false);
        setLoading(true);
        subTitle.setText("正在解析 " + bvid + " …");

        final int seq = ++loadSeq;
        Task.run(new Runnable() {
            @Override public void run() {
                String err = null;
                Bili.ImportResult res = null;
                try {
                    res = Bili.get(MainActivity.this).importByBvid(bvid);
                } catch (Exception e) {
                    err = e.getMessage();
                }
                final Bili.ImportResult fRes = res;
                final String fErr = err;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (seq != loadSeq) return;
                        setLoading(false);
                        importBtn.setTextColor(Colors.ACCENT);
                        importBtn.setEnabled(true);
                        if (fRes == null) {
                            if (handleLoadError(fErr, "导入失败")) return;
                            subTitle.setText("导入失败");
                            return;
                        }
                        onImportDone(bvid, fRes);
                    }
                });
            }
        });
    }

    /**
     * 导入成功：写缓存 + 更新索引 + 刷新界面。
     *
     * 写文件和刷界面分开做：这个方法由 runOnUiThread 调用，
     * 而 ListCache.save / ImportStore.put 都是磁盘 I/O（395 首的选集
     * 要写几百 KB），放在主线程会卡住界面。
     */
    private void onImportDone(final String bvid, final Bili.ImportResult res) {
        final boolean existed = ImportStore.find(res.meta.listId) != null;
        final String what = res.meta.kindLabel();
        if (importInput != null) importInput.setText("");

        Task.run(new Runnable() {
            @Override public void run() {
                ListCache.save(ListCache.keyImport(res.meta.listId), res.items);
                ImportStore.put(res.meta);
                TrackSearcher.invalidate();
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (isFinishing()) return;
                        reloadImportList();
                        toast((existed ? "已更新：" : "已导入：") + what + " · " + res.meta.count + " 项");
                        subTitle.setText(res.meta.count + " 项 · " + what);
                    }
                });
            }
        });
    }

    /** 把 ImportStore 的内容同步到界面列表。 */
    private void reloadImportList() {
        importedLists.clear();
        importedLists.addAll(ImportStore.all());
        if (importAdapter != null) importAdapter.notifyDataSetChanged();
        if (page == PAGE_IMPORT) syncTabSubtitle();
    }

    /** 打开一个已导入的列表。 */
    private void openImported(com.star.bilimusic.api.ImportedList meta) {
        if (meta == null) return;
        curImport = meta;
        curSeason = null;
        curFolder = null;
        curTemp = null;
        openImportList(meta);
    }

    /**
     * 加载导入列表的内容。
     *
     * 与 openList 的区别：数据来自本地缓存（导入时已经写好），
     * 所以不需要联网；只有缓存缺失（比如被系统清了）才提示重新导入。
     */
    private void openImportList(final com.star.bilimusic.api.ImportedList meta) {
        setLoading(true);
        titleBar.setText(meta.title);
        titleRow.setVisibility(View.VISIBLE);
        refreshBtn.setVisibility(View.GONE);   // 导入列表不支持刷新，只有删除
        subTitle.setText("加载中…");

        final int seq = ++loadSeq;
        // 读文件必须在后台：一个 395 首的选集 JSON 有几百 KB
        Task.run(new Runnable() {
            @Override public void run() {
                final List<Track> cached = ListCache.load(ListCache.keyImport(meta.listId));
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (seq != loadSeq) return;
                        if (cached == null || cached.isEmpty()) {
                            setLoading(false);
                            subTitle.setText("列表缓存丢失");
                            toast("列表缓存丢失，请重新导入 " + meta.srcBvid);
                            return;
                        }
                        setLoading(false);
                        stopProgressAnimation();
                        tracks.clear();
                        tracks.addAll(cached);
                        for (int i = 0; i < tracks.size(); i++) tracks.get(i).seq = i + 1;
                        // 记住来源：服务重启后靠它恢复整个播放队列。
                        // 同时把 last_source 清空，避免和收藏夹/合集的旧记录混在一起。
                        Prefs.setLastImportId(meta.listId);
                        Prefs.setLastSource(-1, 0);
                        page = PAGE_TRACKS;
                        list.setAdapter(trackAdapter);
                        trackAdapter.notifyDataSetChanged();
                        titleBar.setText(meta.title);
                        titleRow.setVisibility(View.VISIBLE);
                        refreshBtn.setVisibility(View.GONE);
                        syncTabSubtitle();
                        updateTabs();
                        syncMiniBar();
                        updateLocateButton();
                    }
                });
            }
        });
    }

    /** 删除一个导入项（含索引与列表缓存）。 */
    private void deleteImported(final com.star.bilimusic.api.ImportedList meta) {
        if (meta == null) return;
        new android.app.AlertDialog.Builder(this)
                .setTitle("删除导入")
                .setMessage("将删除【" + meta.title + "】及其本地缓存。\n"
                        + "之后需要重新导入才能恢复。")
                .setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        ImportStore.remove(meta.listId);
                        ListCache.remove(ListCache.keyImport(meta.listId));
                        if (curImport != null && meta.listId.equals(curImport.listId)) {
                            curImport = null;
                        }
                        reloadImportList();
                        toast("已删除");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }
    // ------------------------------------------------------------ 临时歌单

    /** 把 TempStore 的内容同步到界面列表。 */
    private void reloadTempList() {
        tempLists.clear();
        tempLists.addAll(TempStore.all());
        if (tempAdapter != null) tempAdapter.notifyDataSetChanged();
        if (page == PAGE_TEMP) syncTabSubtitle();
    }

    /** 打开一个临时歌单。 */
    private void openTemp(com.star.bilimusic.api.TempPlaylist p) {
        if (p == null) return;
        // 打开新歌单前先退出批量模式，避免选中集错位
        if (batchMode) exitBatchMode();
        // 换了歌单，上一个歌单的去重关键词不再适用
        dedupeKeywords.clear();
        curTemp = p;
        curSeason = null;
        curFolder = null;
        curImport = null;

        setLoading(true);
        titleRow.setVisibility(View.VISIBLE);
        titleBar.setText(p.title);
        refreshBtn.setVisibility(View.GONE);   // 临时歌单是本地快照，不能刷新
        subTitle.setText("加载中…");

        final int seq = ++loadSeq;
        // 读文件放后台：临时歌单可能有上百首
        Task.run(new Runnable() {
            @Override public void run() {
                final List<Track> cached = ListCache.load(ListCache.keyTemp(p.listId));
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (seq != loadSeq) return;
                        if (cached == null || cached.isEmpty()) {
                            setLoading(false);
                            subTitle.setText("这个临时歌单是空的");
                            return;
                        }
                        setLoading(false);
                        stopProgressAnimation();
                        tracks.clear();
                        tracks.addAll(cached);
                        for (int i = 0; i < tracks.size(); i++) tracks.get(i).seq = i + 1;
                        page = PAGE_TRACKS;
                        list.setAdapter(trackAdapter);
                        trackAdapter.notifyDataSetChanged();
                        titleRow.setVisibility(View.VISIBLE);
                        titleBar.setText(p.title);
                        refreshBtn.setVisibility(View.GONE);
                        syncTabSubtitle();
                        updateTabs();
                        syncMiniBar();
                        updateLocateButton();
                    }
                });
            }
        });
    }

    /** 删除一个临时歌单（索引 + 列表缓存）。 */
    private void deleteTemp(final com.star.bilimusic.api.TempPlaylist p) {
        if (p == null) return;
        new android.app.AlertDialog.Builder(this)
                .setTitle("删除临时歌单")
                .setMessage("将删除【" + p.title + "】及其本地缓存。\n"
                        + "原始歌单不受影响。")
                .setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        TempStore.remove(p.listId);
                        ListCache.remove(ListCache.keyTemp(p.listId));
                        if (curTemp != null && p.listId.equals(curTemp.listId)) curTemp = null;
                        // 数据变了，搜索缓存必须失效
                        TrackSearcher.invalidate();
                        reloadTempList();
                        toast("已删除");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 从当前临时歌单里删掉某一首歌。
     *
     * 只改这个临时歌单自己的缓存文件，其它三个 tab 的原歌单完全不动
     * —— 它们是不同的文件，天然隔离。
     */
    private void deleteTempSong(final int pos) {
        if (curTemp == null) return;
        if (pos < 0 || pos >= tracks.size()) return;
        final Track target = tracks.get(pos);
        new android.app.AlertDialog.Builder(this)
                .setTitle("从临时歌单移除")
                .setMessage("从【" + curTemp.title + "】中移除：\n" + target.title
                        + "\n\n原始歌单中的这首歌不受影响。")
                .setPositiveButton("移除", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        tracks.remove(pos);
                        for (int i = 0; i < tracks.size(); i++) tracks.get(i).seq = i + 1;
                        final java.util.List<Track> snapshot = new java.util.ArrayList<Track>(tracks);
                        final String listId = curTemp.listId;
                        final int newCount = tracks.size();

                        Task.run(new Runnable() {
                            @Override public void run() {
                                ListCache.save(ListCache.keyTemp(listId), snapshot);
                                TempStore.updateCount(listId, newCount);
                                TrackSearcher.invalidate();
                                runOnUiThread(new Runnable() {
                                    @Override public void run() {
                                        if (isFinishing()) return;
                                        trackAdapter.notifyDataSetChanged();
                                        syncTabSubtitle();
                                        updateLocateButton();
                                        toast("已移除");
                                    }
                                });
                            }
                        });
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }
    /**
     * 从当前 BV 导入列表里删掉某一首歌。
     *
     * 与临时歌单的删单曲完全对称：只改这个导入列表自己的缓存文件，
     * 不碰 B 站服务端，也不碰其它歌单。
     */
    private void deleteImportSong(final int pos) {
        if (curImport == null) return;
        if (pos < 0 || pos >= tracks.size()) return;
        final Track target = tracks.get(pos);
        new android.app.AlertDialog.Builder(this)
                .setTitle("从导入列表移除")
                .setMessage("从【" + curImport.title + "】中移除：\n" + target.title
                        + "\n\n仅影响本地缓存，不影响 B 站上的原始视频。")
                .setPositiveButton("移除", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        tracks.remove(pos);
                        for (int i = 0; i < tracks.size(); i++) tracks.get(i).seq = i + 1;
                        final java.util.List<Track> snapshot = new java.util.ArrayList<Track>(tracks);
                        final String listId = curImport.listId;
                        final int newCount = tracks.size();

                        Task.run(new Runnable() {
                            @Override public void run() {
                                ListCache.save(ListCache.keyImport(listId), snapshot);
                                ImportStore.updateCount(listId, newCount);
                                TrackSearcher.invalidate();
                                runOnUiThread(new Runnable() {
                                    @Override public void run() {
                                        if (isFinishing()) return;
                                        trackAdapter.notifyDataSetChanged();
                                        syncTabSubtitle();
                                        updateLocateButton();
                                        toast("已移除");
                                    }
                                });
                            }
                        });
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }
    // ------------------------------------------------------------ 去重

    /**
     * 自定义关键词的长度上限（按显示宽度算，中文算 2）。
     *
     * 原来设 20，结果「在百万豪装录音棚大声听」（11 字 = 宽度 22）被拒，
     * 用户只能退而输入少一个字的版本，导致子串匹配不上、去重失效。
     * B 站标题里的固定前缀经常十几个字，上限必须放宽。
     */
    private static final int DEDUPE_KEYWORD_MAX = 60;

    /**
     * 临时歌单去重。
     *
     * 只在「临时歌单」的曲目页可用：其它三个来源的列表是快照或服务端数据，
     * 不应该在这里被删。
     */
    private void dedupeCurrent() {
        if (curTemp == null) return;
        if (tracks.size() < 2) {
            toast("歌曲太少，无需去重");
            return;
        }
        // 传入暂存的关键词：关掉面板再打开时不用重新输入
        showDedupePanel(dedupeKeywords);
    }

    /**
     * 去重面板：顶部关键词输入 + 分组勾选。
     *
     * 关键词改了就重新分组并重绘（同一段式交互，不来回切换弹窗）。
     *
     * 实现方式：弹窗内容用一个容器，每次重算时先清空再重建。
     * 关键词数组作为闭包变量在外层持有，重绘不会丢。
     */
    private void showDedupePanel(final java.util.List<String> keywords) {
        final android.widget.ScrollView sv = new android.widget.ScrollView(this);
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(6), dp(18), dp(6));
        sv.addView(root);

        // ---------- 关键词输入区 ----------
        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        inputRow.setPadding(0, dp(4), 0, dp(4));

        final android.widget.EditText kwInput = new android.widget.EditText(this);
        kwInput.setHint("可输入自定义无效子串");
        kwInput.setTextSize(13);
        kwInput.setSingleLine(true);
        kwInput.setTextColor(Colors.TEXT_PRIMARY);
        kwInput.setHintTextColor(Colors.TEXT_DISABLED);
        kwInput.setBackgroundColor(Colors.BG_CARD);
        kwInput.setPadding(dp(10), dp(7), dp(10), dp(7));
        inputRow.addView(kwInput, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView addBtn = new TextView(this);
        addBtn.setText("添加");
        addBtn.setTextColor(Colors.ACCENT);
        addBtn.setTextSize(14);
        addBtn.setPadding(dp(12), dp(8), dp(4), dp(8));
        inputRow.addView(addBtn);
        root.addView(inputRow);

        // 已添加的关键词（小字展示，长按清空）
        final TextView kwList = new TextView(this);
        kwList.setTextSize(11);
        kwList.setTextColor(Colors.TEXT_SECONDARY);
        kwList.setPadding(dp(2), 0, 0, 0);
        root.addView(kwList);

        // 匹配预览：显示每个关键词命中多少首、删掉后剩什么。
        // 这是排查「关键词打错一个字」的主要手段——命中 0 首立刻能看出来。
        final TextView preview = new TextView(this);
        preview.setTextSize(11);
        preview.setTextColor(Colors.TEXT_HINT);
        preview.setPadding(dp(2), dp(2), 0, dp(6));
        root.addView(preview);

        // ---------- 分组区（每次重算重建） ----------
        final LinearLayout groupBox = new LinearLayout(this);
        groupBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(groupBox);

        // 勾选状态：touched = 出现在弹窗里的下标；keep = 勾选保留的下标
        final java.util.Set<Integer> keep = new java.util.HashSet<Integer>();
        final java.util.Set<Integer> touched = new java.util.HashSet<Integer>();

        final AlertDialog[] dlg = new AlertDialog[1];

        /** 重新计算并重绘分组。 */
        final Runnable rebuild = new Runnable() {
            @Override public void run() {
                // 关键词列表文字
                if (keywords.isEmpty()) {
                    kwList.setText("（未添加自定义关键词）");
                } else {
                    StringBuilder sb = new StringBuilder("已添加：");
                    for (int i = 0; i < keywords.size(); i++) {
                        if (i > 0) sb.append("、");
                        sb.append(keywords.get(i));
                    }
                    sb.append("（点此清空）");
                    kwList.setText(sb.toString());
                }

                // 更新匹配预览（在主线程算，几百首的 contains 很便宜）
                updateKeywordPreview(preview, keywords);

                groupBox.removeAllViews();
                keep.clear();
                touched.clear();


                final java.util.List<Track> snapshot =
                        new java.util.ArrayList<Track>(tracks);
                final java.util.Map<Track, Integer> indexOf =
                        new java.util.IdentityHashMap<Track, Integer>();
                for (int i = 0; i < tracks.size(); i++) indexOf.put(tracks.get(i), i);

                // 重算放到后台：几百首时归一化不便宜
                Task.run(new Runnable() {
                    @Override public void run() {
                        final java.util.List<DuplicateFinder.Group> groups =
                                DuplicateFinder.find(snapshot, keywords);

                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                if (isFinishing()) return;
                                if (groups.isEmpty()) {
                                    TextView tv = new TextView(MainActivity.this);
                                    tv.setText("没有发现重复的歌曲");
                                    tv.setTextColor(Colors.TEXT_SECONDARY);
                                    tv.setTextSize(13);
                                    tv.setPadding(0, dp(12), 0, dp(12));
                                    groupBox.addView(tv);
                                    if (dlg[0] != null) {
                                        dlg[0].getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                                                .setEnabled(false);
                                    }
                                    return;
                                }
                                if (dlg[0] != null) {
                                    dlg[0].getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                                            .setEnabled(true);
                                }
                                buildGroups(groupBox, groups, indexOf, keep, touched);
                            }
                        });
                    }
                });
            }
        };

        // 添加关键词
        addBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String raw = kwInput.getText().toString().trim();
                if (raw.isEmpty()) {
                    toast("请输入要忽略的内容");
                    return;
                }
                if (displayWidth(raw) > DEDUPE_KEYWORD_MAX) {
                    toast("关键词太长（上限 " + DEDUPE_KEYWORD_MAX + " 个字）");
                    return;
                }
                if (keywords.contains(raw)) {
                    toast("这个关键词已经加过了");
                    return;
                }
                keywords.add(raw);
                kwInput.setText("");
                rebuild.run();
            }
        });

        // 长按关键词文字 → 清空全部。
        // 原来是「点一下就清空」，太容易误触（用户只是想看看列表就全没了）。
        kwList.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                if (keywords.isEmpty()) return false;
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("清空关键词")
                        .setMessage("将移除全部 " + keywords.size() + " 个自定义关键词。")
                        .setPositiveButton("清空", new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface d, int w) {
                                keywords.clear();
                                rebuild.run();
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
                return true;
            }
        });

        dlg[0] = new AlertDialog.Builder(this)
                .setTitle("去重")
                .setView(sv)
                .setPositiveButton("删除未勾选", null)
                .setNegativeButton("取消", null)
                .create();

        // 关键：不能用 setPositiveButton(文字, listener)。
        // 那个重载的行为是「点击后先关闭弹窗，再回调 listener」，
        // 于是去重面板会在确认弹窗弹出时直接消失，用户就丢了上下文。
        // 改成：传 null 建按钮，再在 show() 之后拿到真实按钮自己挂监听。
        // 这样点「删除未勾选」时去重面板保持打开，只是叠上一层确认框。
        dlg[0].setOnShowListener(new android.content.DialogInterface.OnShowListener() {
            @Override public void onShow(android.content.DialogInterface d) {
                if (!(d instanceof AlertDialog)) return;
                AlertDialog ad = (AlertDialog) d;
                ad.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(
                        new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        int willRemove = 0;
                        for (Integer idx : touched) {
                            if (!keep.contains(idx)) willRemove++;
                        }
                        if (willRemove == 0) {
                            toast("没有需要删除的歌曲");
                            return;
                        }
                        confirmDedupe(keep, touched, willRemove, dlg[0]);
                    }
                });
            }
        });

        dlg[0].show();
        rebuild.run();
    }

    /**
     * 按分组结果填充勾选列表。
     *
     * 每组默认勾选第一条（歌单顺序），可多选。
     */
    private void buildGroups(LinearLayout box,
                             java.util.List<DuplicateFinder.Group> groups,
                             java.util.Map<Track, Integer> indexOf,
                             java.util.Set<Integer> keep,
                             java.util.Set<Integer> touched) {
        box.removeAllViews();
        for (int gi = 0; gi < groups.size(); gi++) {
            DuplicateFinder.Group g = groups.get(gi);

            TextView head = new TextView(this);
            head.setText("组 " + (gi + 1) + " · " + g.key + "（" + g.tracks.size() + " 首）");
            head.setTextColor(Colors.ACCENT);
            head.setTextSize(13);
            head.setPadding(0, dp(gi == 0 ? 2 : 12), 0, dp(2));
            box.addView(head);

            for (int ti = 0; ti < g.tracks.size(); ti++) {
                final Track tr = g.tracks.get(ti);
                final Integer idx = indexOf.get(tr);
                if (idx == null) continue;
                touched.add(idx);

                android.widget.CheckBox cb = new android.widget.CheckBox(this);
                String dur = (tr.duration > 0) ? ("  " + fmt(tr.duration)) : "";
                cb.setText(tr.title + dur);
                cb.setTextSize(12);
                cb.setTextColor(Colors.TEXT_PRIMARY);
                cb.setPadding(0, dp(1), 0, dp(1));
                // 默认勾选每组第一条
                boolean def = (ti == 0);
                cb.setChecked(def);
                if (def) keep.add(idx);
                cb.setOnCheckedChangeListener(
                        new android.widget.CompoundButton.OnCheckedChangeListener() {
                    @Override public void onCheckedChanged(
                            android.widget.CompoundButton b, boolean checked) {
                        if (checked) keep.add(idx); else keep.remove(idx);
                    }
                });
                box.addView(cb);
            }
        }
    }

    /**
     * 去重的二次确认。
     *
     * 删除会直接改写临时歌单的缓存文件且没有撤销，多一步确认是必要的。
     */
    /**
     * @param panel 去重面板本身。删除成功要把它一并关掉，
     *              否则面板会停在一个已经过时的分组结果上。
     *              取消时则保持打开，让用户继续调整勾选。
     */
    private void confirmDedupe(final java.util.Set<Integer> keep,
                               final java.util.Set<Integer> touched,
                               int willRemove,
                               final AlertDialog panel) {
        new AlertDialog.Builder(this)
                .setTitle("确认删除")
                .setMessage("将从【" + (curTemp == null ? "" : curTemp.title) + "】删除 "
                        + willRemove + " 首未勾选的歌曲。\n\n"
                        + "此操作不可撤销，原始歌单不受影响。")
                .setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        // 只有真的删了才关面板；被保护拦下时保持打开，
                        // 让用户继续调整勾选，而不是丢掉刚才的选择。
                        if (applyDedupe(keep, touched)) {
                            if (panel != null && panel.isShowing()) panel.dismiss();
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 应用去重结果：保留 keep 里记录的曲目，其余从本临时歌单删除。
     *
     * 只写这个临时歌单自己的缓存文件，其它歌单的原件不受影响。
     *
     * 关键：只有 touched 里的曲目才可能被删。
     * 不在 touched 里的是「没有重复的单曲」，必须原样保留——
     * 早期实现用「不在 keep 里就删」，会把所有单曲也删掉，是严重错误。
     */
    /**
     * @return true 表示真的删掉了歌曲；false 表示因为保护或无事可做而提前返回，
     *         此时调用方不应关闭去重面板，否则用户会丢掉已经调好的勾选。
     */
    private boolean applyDedupe(final java.util.Set<Integer> keep,
                                final java.util.Set<Integer> touched) {
        if (curTemp == null) return false;

        // 安全阀：把所有重复组都取消勾选会导致「每组一首都不留」
        if (keep.isEmpty() && !touched.isEmpty()) {
            toast("请至少保留一首歌");
            return false;
        }

        final java.util.List<Track> kept = new java.util.ArrayList<Track>();
        int removed = 0;
        for (int i = 0; i < tracks.size(); i++) {
            // 只有「在弹窗里出现过」且「没被勾选」的才删
            boolean drop = touched.contains(i) && !keep.contains(i);
            if (drop) removed++;
            else kept.add(tracks.get(i));
        }
        if (removed == 0) {
            toast("没有需要删除的歌曲");
            return false;
        }
        tracks.clear();
        tracks.addAll(kept);
        for (int i = 0; i < tracks.size(); i++) tracks.get(i).seq = i + 1;

        final java.util.List<Track> snapshot = new java.util.ArrayList<Track>(tracks);
        final String listId = curTemp.listId;
        final int newCount = tracks.size();
        final int removedFinal = removed;
        trackAdapter.notifyDataSetChanged();
        syncTabSubtitle();
        updateLocateButton();

        Task.run(new Runnable() {
            @Override public void run() {
                ListCache.save(ListCache.keyTemp(listId), snapshot);
                TempStore.updateCount(listId, newCount);
                TrackSearcher.invalidate();
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (isFinishing()) return;
                        toast("已删除 " + removedFinal + " 首重复歌曲");
                    }
                });
            }
        });
        return true;
    }

    /**
     * 搜索页返回。
     *
     * 生成了临时歌单时会回传 RESULT_OK，这里切到「临时」Tab 并重新加载列表。
     * 之前没做这一步，用户生成完回到主界面看到的还是旧列表，
     * 必须手动切走再切回来才会刷新。
     */
    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req != REQ_SEARCH) return;
        if (result != RESULT_OK) return;
        gotoPage(PAGE_TEMP);
        // gotoPage 在已经处于该页时会直接 return，所以这里再强制刷一次
        if (tempAdapter != null) {
            reloadTempList();
        }
    }
    /**
     * 更新关键词匹配预览。
     *
     * 对每个关键词统计「有多少首标题包含它」，并给出一个示例：
     *   「在百万豪装录音棚」→ 命中 1 首
     *
     * 命中 0 首时用醒目颜色提示，帮用户发现「打错一个字」这种问题——
     * 这正是之前排查很久的原因：关键词少打一个字，子串匹配就完全失效。
     */
    private void updateKeywordPreview(TextView preview,
                                      java.util.List<String> keywords) {
        if (preview == null) return;
        if (keywords == null || keywords.isEmpty()) {
            preview.setText("");
            return;
        }
        // 直接用 DuplicateFinder 的归一化：预览和真实去重必须完全一致，
        // 否则「命中 N 首」会和实际分组对不上，反而误导用户。
        StringBuilder sb = new StringBuilder();
        boolean anyZero = false;
        for (String kw : keywords) {
            java.util.List<String> one = new java.util.ArrayList<String>();
            one.add(kw);

            int hit = 0;
            for (Track t : tracks) {
                if (t == null) continue;
                String raw = DuplicateFinder.basePublic(t.title);
                String after = DuplicateFinder.applyKeywordsPublic(raw, one);
                if (after.equals(raw)) continue;      // 没被删掉 = 没命中
                hit++;
            }
            if (hit == 0) anyZero = true;
            if (sb.length() > 0) sb.append('\n');
            sb.append("· 「").append(kw).append("」命中 ").append(hit).append(" 首");
            if (hit == 0) {
                sb.append("  ← 没有歌曲含这段文字，检查是否打错");
            }
        }
        preview.setText(sb.toString());
        preview.setTextColor(anyZero ? Colors.DANGER : Colors.TEXT_HINT);
    }

    // ------------------------------------------------------------ 批量删除

    /**
     * 进入批量选择模式。
     *
     * 只对临时歌单开放：其它三个来源的曲目列表是快照/服务端数据，
     * 不该在 App 内被删。
     */
    private void enterBatchMode() {
        if (!batchEditable()) return;
        if (tracks.isEmpty()) {
            toast("歌单是空的");
            return;
        }
        batchMode = true;
        batchSel.clear();
        if (batchBar != null) batchBar.setVisibility(View.VISIBLE);
        if (batchBtn != null) batchBtn.setVisibility(View.GONE);
        if (dedupeBtn != null) dedupeBtn.setVisibility(View.GONE);
        if (refreshBtn != null) refreshBtn.setVisibility(View.GONE);
        titleBar.setText("选择要删除的歌曲");
        subTitle.setText("点按选择，可全选");
        trackAdapter.notifyDataSetChanged();
        updateBatchCount();
    }

    /** 退出批量模式（不删除任何东西）。 */
    private void exitBatchMode() {
        if (!batchMode) return;
        batchMode = false;
        batchSel.clear();
        if (batchBar != null) batchBar.setVisibility(View.GONE);
        // 恢复标题栏按钮的显隐
        if (batchBtn != null) {
            batchBtn.setVisibility(View.VISIBLE);
        }
        if (dedupeBtn != null) {
            dedupeBtn.setVisibility(View.VISIBLE);
        }
        if (titleBar != null) {
            if (curTemp != null) titleBar.setText(curTemp.title);
            else if (curImport != null) titleBar.setText(curImport.title);
        }
        syncTabSubtitle();
        trackAdapter.notifyDataSetChanged();
    }

    /**
     * 当前正在看的列表标题（临时歌单名 / 导入列表名 / 收藏夹名 / 合集名）。
     * 只用于弹窗文案，取不到时返回空串。
     */
    private String currentListTitle() {
        if (curTemp != null) return curTemp.title == null ? "" : curTemp.title;
        if (curImport != null) return curImport.title == null ? "" : curImport.title;
        if (curSeason != null) return curSeason.title == null ? "" : curSeason.title;
        if (curFolder != null) return curFolder.title == null ? "" : curFolder.title;
        return "";
    }
    /**
     * 当前曲目页是否允许「删单曲」。
     *
     * 只有临时歌单和 BV 导入列表可以：它们都是本地快照，
     * 删歌只影响本地缓存，不会动 B 站服务端的收藏夹/合集。
     */
    private boolean batchEditable() {
        return curTemp != null || curImport != null;
    }
    /** 批量模式底部「已选 N 首」与「全选/全不选」文字。 */
    private void updateBatchCount() {
        if (batchCount == null) return;
        batchCount.setText("已选 " + batchSel.size() + " 首");
        if (batchAllBtn != null) {
            batchAllBtn.setText(batchSel.size() == tracks.size() ? "全不选" : "全选");
        }
    }

    /** 批量模式：全选 / 全不选。 */
    private void toggleBatchAll() {
        if (batchSel.size() == tracks.size()) {
            batchSel.clear();
        } else {
            batchSel.clear();
            for (int i = 0; i < tracks.size(); i++) batchSel.add(i);
        }
        updateBatchCount();
        trackAdapter.notifyDataSetChanged();
    }

    /** 批量模式：点某一行切换选中。 */
    private void toggleBatchItem(int pos) {
        if (pos < 0 || pos >= tracks.size()) return;
        if (batchSel.contains(pos)) batchSel.remove(pos);
        else batchSel.add(pos);
        updateBatchCount();
        trackAdapter.notifyDataSetChanged();
    }

    /** 批量删除二次确认。 */
    private void confirmBatchDelete() {
        if (batchSel.isEmpty()) {
            toast("还没有选中任何歌曲");
            return;
        }
        final int n = batchSel.size();
        new AlertDialog.Builder(this)
                .setTitle("确认删除")
                .setMessage("将从【" + currentListTitle() + "】删除 "
                        + n + " 首歌曲。\n\n"
                        + "此操作不可撤销，仅影响本地缓存。")
                .setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        applyBatchDelete();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 执行批量删除。
     *
     * 只改写当前列表自己的缓存文件（临时歌单或 BV 导入列表），
     * 其它歌单的原件、以及 B 站服务端的数据都不动。
     */
    private void applyBatchDelete() {
        if (!batchEditable()) return;
        if (batchSel.isEmpty()) return;

        // 先把落盘目标定下来：临时歌单走 keyTemp + TempStore，
        // 导入列表走 keyImport + ImportStore，两者结构完全对称。
        final boolean isTemp = (curTemp != null);
        final String cacheKey = isTemp ? ListCache.keyTemp(curTemp.listId)
                                       : ListCache.keyImport(curImport.listId);
        final String ownerId = isTemp ? curTemp.listId : curImport.listId;

        final java.util.List<Track> kept = new java.util.ArrayList<Track>();
        int removed = 0;
        for (int i = 0; i < tracks.size(); i++) {
            if (batchSel.contains(i)) {
                removed++;
            } else {
                kept.add(tracks.get(i));
            }
        }
        if (removed == 0) {
            toast("没有需要删除的歌曲");
            return;
        }

        tracks.clear();
        tracks.addAll(kept);
        for (int i = 0; i < tracks.size(); i++) tracks.get(i).seq = i + 1;

        final java.util.List<Track> snapshot = new java.util.ArrayList<Track>(tracks);
        final int newCount = tracks.size();
        final int removedFinal = removed;

        // 先退出批量模式（会刷新适配器），再落盘
        batchSel.clear();
        exitBatchMode();
        updateLocateButton();

        Task.run(new Runnable() {
            @Override public void run() {
                ListCache.save(cacheKey, snapshot);
                if (isTemp) TempStore.updateCount(ownerId, newCount);
                else ImportStore.updateCount(ownerId, newCount);
                TrackSearcher.invalidate();
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (isFinishing()) return;
                        toast("已删除 " + removedFinal + " 首");
                    }
                });
            }
        });
    }
    /** 按显示宽度算长度（中文算 2）。 */
    private static int displayWidth(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            w += s.charAt(i) > 0x2E80 ? 2 : 1;
        }
        return w;
    }
    /**
     * 清理「服务端已不存在」的收藏夹/合集缓存。
     *
     * 场景：用户在 B 站取消订阅某个合集，本地却还留着它的曲目缓存，
     * 占空间而且在「缓存管理」里能看到已经不该存在的东西。
     *
     * 判定方式：拿服务端返回的**完整**列表（fGot，未过滤隐藏项）当存活集合，
     * 遍历 ListCache 里的 season_* / fav_* 缓存，id 不在存活集合里的就删掉。
     *
     * 三道安全阀（缺一不可）：
     *   ① 只在请求成功（fGot != null）时调用——失败时绝不动缓存
     *   ② aliveIds 为空时直接返回——服务端返回空极可能是接口异常，
     *      宁可留垃圾也不能误删用户全部缓存
     *   ③ 只处理 season_ / fav_ 两种前缀，BV 导入（import_）和
     *      临时歌单（temp_）完全不碰——它们本来就不来自服务端
     *
     * 顺带清理 hidden_seasons / hidden_folders 里已经失效的 id：
     * 歌单都不存在了，隐藏记录留着只是垃圾。
     *
     * @param seasonMode true=合集，false=收藏夹
     * @param aliveIds   服务端返回的完整 id 集合（含已隐藏的）
     */
    private void pruneOrphanCaches(final boolean seasonMode, final java.util.Set<Long> aliveIds) {
        // 安全阀 ① ②
        if (aliveIds == null || aliveIds.isEmpty()) return;

        Task.run(new Runnable() {
            @Override public void run() {
                // 存活集合 = 服务端仍存在的全部（含隐藏项）。
                // 用 fGot 而不是界面上的 folders/seasons：后者已经过滤掉隐藏项，
                // 拿它当存活集合会把所有隐藏歌单误判成"已删除"。
                final java.util.Set<Long> alive = new java.util.HashSet<Long>(aliveIds);

                // 安全阀 ③：只碰这两种前缀
                final String prefix = seasonMode ? "season_" : "fav_";
                int removedCache = 0;
                for (String key : ListCache.allKeys()) {
                    if (key == null || !key.startsWith(prefix)) continue;
                    long id;
                    try {
                        id = Long.parseLong(key.substring(prefix.length()));
                    } catch (Exception e) {
                        continue;               // 解析不了就跳过，不冒险
                    }
                    if (alive.contains(id)) continue;
                    if (ListCache.remove(key)) removedCache++;
                }

                // 清理隐藏集合里的失效 id
                int removedHidden = 0;
                Bili bili = Bili.get(MainActivity.this);
                java.util.Set<Long> hidden = seasonMode ? bili.hiddenSeasons() : bili.hiddenFolders();
                java.util.List<Long> stale = new java.util.ArrayList<Long>();
                for (Long id : hidden) {
                    if (id != null && !alive.contains(id)) stale.add(id);
                }
                for (Long id : stale) {
                    if (seasonMode) bili.setSeasonHidden(id, false);
                    else bili.setFolderHidden(id, false);
                    removedHidden++;
                }

                final int rc = removedCache;
                final int rh = removedHidden;
                if (rc > 0 || rh > 0) {
                    // 缓存内容变了，跨歌单搜索的结果必须失效
                    TrackSearcher.invalidate();
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (isFinishing()) return;
                            StringBuilder m = new StringBuilder("已清理 ");
                            if (rc > 0) m.append(rc).append(" 个失效缓存");
                            if (rh > 0) {
                                if (rc > 0) m.append("、");
                                m.append(rh).append(" 个失效隐藏记录");
                            }
                            toast(m.toString());
                        }
                    });
                }
            }
        });
    }
    /** 打开收藏夹。 */
    private void loadTracks(final FavFolder f) {
        curFolder = f;
        curSeason = null;
        // 必须把其它来源也清掉：updateTabs 靠这几个字段判断当前归属哪个 Tab，
        // 残留会导致「点开的是收藏夹，顶栏却高亮临时歌单」。
        curImport = null;
        curTemp = null;
        openList(Bili.SRC_FOLDER, f.id, f.title, ListCache.keyFolder(f.id), false);
    }

    /** 打开合集。 */
    private void loadSeasonTracks(final Season s) {
        curSeason = s;
        curFolder = null;
        curImport = null;
        curTemp = null;
        openList(Bili.SRC_SEASON, s.seasonId, s.title, ListCache.keySeason(s.seasonId), false);
    }

    /**
     * 当前列表的刷新入口（点标题行右侧的 ↻）。
     *
     * 会弹窗确认，因为刷新会整份覆盖本地缓存。
     */
    private void refreshCurrentList() {
        // 导入列表是本地快照，没有「服务端版本」可对齐。
        // 按钮本来就隐藏了，这里再防御一次，避免以后误调用弹出空名字的弹窗。
        if (curImport != null) {
            toast("导入的列表不支持刷新，可长按删除后重新导入");
            return;
        }
        if (curSeason == null && curFolder == null) {
            return;   // 没有可刷新的目标（例如状态错乱），直接忽略
        }
        final boolean seasonMode = (curSeason != null);
        final String name = seasonMode ? curSeason.title : curFolder.title;
        new android.app.AlertDialog.Builder(this)
                .setTitle("刷新列表")
                .setMessage("将从服务端重新同步【" + name + "】的完整列表，"
                        + "并覆盖本地缓存。\n\n视频较多时需要等待一段时间，请勿退出。")
                .setPositiveButton("刷新", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        if (seasonMode && curSeason != null) {
                            openList(Bili.SRC_SEASON, curSeason.seasonId, curSeason.title,
                                    ListCache.keySeason(curSeason.seasonId), true);
                        } else if (curFolder != null) {
                            openList(Bili.SRC_FOLDER, curFolder.id, curFolder.title,
                                    ListCache.keyFolder(curFolder.id), true);
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 统一的列表打开逻辑（收藏夹与合集共用）。
     *
     * 流程：TrackListLoader 内部先查缓存，命中就直接返回；
     * 未命中则逐页拉取（页间随机延时避风控），完成后写缓存。
     * 这里只负责：显示进度、处理结果、进入曲目页。
     *
     * @param forceRefresh true 表示手动刷新，忽略缓存
     */
    private void openList(final int srcType, final long id, final String name,
                          final String cacheKey, final boolean forceRefresh) {
        setLoading(true);
        // 新任务开始：允许进度回调
        progressDone = false;
        titleBar.setText(name);
        titleRow.setVisibility(View.VISIBLE);
        refreshBtn.setVisibility(View.VISIBLE);
        subTitle.setText(forceRefresh ? "正在重新同步…" : "加载中…");

        final int seq = ++loadSeq;
        Task.run(new Runnable() {
            @Override public void run() {
                final TrackListLoader.Result res = TrackListLoader.load(
                        Bili.get(MainActivity.this), srcType, id, cacheKey, forceRefresh,
                        new TrackListLoader.Progress() {
                            @Override public void onProgress(final int loaded, final int page) {
                                runOnUiThread(new Runnable() {
                                    @Override public void run() {
                                        if (seq != loadSeq) return;
                                        animateProgressTo(seq, loaded);
                                    }
                                });
                            }
                        });
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (seq != loadSeq) return;   // 已切走，丢弃结果
                        setLoading(false);
                        if (res.items == null) {
                            stopProgressAnimation();
                            if (handleLoadError(res.error, "加载失败")) return;
                            subTitle.setText("加载失败");
                            return;
                        }
                        // 列表内容可能变了（首次拉取/手动刷新），搜索缓存要失效
                        TrackSearcher.invalidate();
                        showTrackList(srcType, id, res.items);
                    }
                });
            }
        });
    }

    /**
     * 让加载进度数字平滑上涨，而不是每 20/30 条硬跳一次。
     *
     * 服务端按页返回，原始进度只能是 30、60、90… 这种台阶。
     * 这里用 40ms 一帧把显示值逐步追上去，看起来像连续增长，
     * 但「目标值」始终是真实的已加载条数，不会漂移。
     */
    private void animateProgressTo(final int seq, final int target) {
        // 该任务已经结束：丢弃迟到的进度回调，避免覆盖最终文案
        if (progressDone && progressSeq == seq) return;
        // 换了新的加载任务：重置计数并丢弃旧的动画循环
        if (progressSeq != seq) {
            progressSeq = seq;
            progressShown = 0;
            progressAnimating = false;
            progressDone = false;
        }
        progressTarget = target;
        if (progressAnimating) return;      // 已有动画在跑，它会追到新的目标值
        progressAnimating = true;
        final Runnable[] tick = new Runnable[1];
        tick[0] = new Runnable() {
            @Override public void run() {
                // 任务换了或界面已关闭：停止动画
                if (!progressAnimating || seq != loadSeq) {
                    progressAnimating = false;
                    return;
                }
                if (progressShown < progressTarget) {
                    // 距离越远步子越大，避免几百条时爬得太慢
                    int step = Math.max(1, (progressTarget - progressShown) / 6);
                    progressShown = Math.min(progressTarget, progressShown + step);
                    subTitle.setText("已加载 " + progressShown + " 个视频…");
                    spinHandler.postDelayed(tick[0], 40);
                } else {
                    progressAnimating = false;   // 追平了，等下一个目标
                }
            }
        };
        spinHandler.post(tick[0]);
    }
    /**
     * 停止进度动画。
     *
     * 加载结束时必须调用：否则动画的 postDelayed 还在跑，
     * 会把刚显示好的「N 个视频」又改回「已加载 N 个视频…」。
     *
     * 置 progressDone 而不是把 progressSeq 归零：progressSeq 要留着，
     * 才能识别「这个迟到回调属于已结束的那个任务」，从而丢弃它。
     */
    private void stopProgressAnimation() {
        // 保留 progressSeq（迟到回调靠它判断是不是同一个任务），
        // 用 progressDone 标记该任务已结束。
        progressDone = true;
        progressAnimating = false;
        progressShown = 0;
        progressTarget = 0;
    }
    /** 把加载好的列表显示出来，并进入曲目页。 */
    private void showTrackList(int srcType, long id, List<Track> items) {
        // 先停掉进度动画：它还在 postDelayed 的话，会把下面的
        // 「N 个视频」重新覆盖成「已加载 N 个视频…」。
        stopProgressAnimation();
        tracks.clear();
        tracks.addAll(items);
        // 序号在这里统一分配：缓存重建的列表同样会走到这里，
        // 所以重启恢复后日志里的编号也不会再是「?」。
        for (int i = 0; i < tracks.size(); i++) tracks.get(i).seq = i + 1;

        Prefs.setLastSource(srcType, id);
        Prefs.setLastImportId("");   // 不是导入列表，清掉旧记录
        page = PAGE_TRACKS;
        list.setAdapter(trackAdapter);
        trackAdapter.notifyDataSetChanged();
        titleRow.setVisibility(View.VISIBLE);
        refreshBtn.setVisibility(View.VISIBLE);
        syncTabSubtitle();
        updateTabs();
        syncMiniBar();
        updateLocateButton();
    }

    /**
     * 打开播放页。
     *
     * 服务重启后队列只剩恢复的那一首。如果界面已经有完整列表，
     * 悄悄把队列补全，这样播放页里切歌才正常。
     * 列表还没加载时，先去拉上次用过的收藏夹，再打开播放页。
     */
    private void openPlayerWithCurrentList() {
        // 连点保护：一次只允许一个恢复流程在跑。
        // 但若上一个流程超过 10 秒还没复位（异常中断），视为已失效，放行。
        if (restoringQueue) {
            if (System.currentTimeMillis() - restoringSince < RESTORE_TIMEOUT_MS) return;
            restoringQueue = false;
        }
        PlayService svc = PlayService.get();
        final Track cur = svc == null ? null : svc.track();

        if (cur == null) {
            startActivity(new Intent(this, PlayerActivity.class));
            return;
        }

        // 已有完整列表且队列不完整：补上再走
        if (!tracks.isEmpty() && svc.queue().size() < tracks.size()) {
            int idx = -1;
            for (int i = 0; i < tracks.size(); i++) {
                if (cur.key().equals(tracks.get(i).key())) {
                    idx = i;
                    break;
                }
            }
            if (idx >= 0) {
                svc.replaceQueueKeepPlaying(new ArrayList<Track>(tracks));
            }
            startActivity(new Intent(this, PlayerActivity.class));
            return;
        }

        if (!tracks.isEmpty()) {
            startActivity(new Intent(this, PlayerActivity.class));
            return;
        }

        // 列表还没加载：按上次的来源恢复，补完队列再进播放页。
        //
        // 三种来源都要覆盖：
        //   导入列表（先判断，因为它的 id 是字符串，存在独立键里）
        //   合集 / 收藏夹（last_source 的 "type:longId"）
        final String importId = Prefs.getLastImportId();
        if (importId != null && !importId.isEmpty()) {
            // 必须在后台线程：ImportStore 首次访问要读索引文件，
            // ListCache.load 要读整个列表 JSON（几百 KB）。
            // 这里是迷你条的点击回调，跑在主线程，直接读会卡顿甚至 ANR。
            restoringQueue = true;
            restoringSince = System.currentTimeMillis();
            toast("正在恢复播放列表…");
            Task.run(new Runnable() {
                @Override public void run() {
                    final com.star.bilimusic.api.ImportedList meta = ImportStore.find(importId);
                    List<Track> cached = null;
                    try {
                        if (meta != null) {
                            cached = ListCache.load(ListCache.keyImport(meta.listId));
                        }
                    } catch (Exception ignored) {
                        // 读文件失败当作没有缓存，下面走单曲播放
                    }
                    final List<Track> fGot = cached;
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            PlayService s2 = PlayService.get();
                            restoringQueue = false;   // 无论成功失败都要复位
                            if (isFinishing()) return;
                            if (fGot != null && !fGot.isEmpty()) {
                                if (s2 != null) {
                                    tracks.clear();
                                    tracks.addAll(fGot);
                                    for (int i = 0; i < tracks.size(); i++) tracks.get(i).seq = i + 1;
                                    s2.replaceQueueKeepPlaying(new ArrayList<Track>(fGot));
                                }
                                startActivity(new Intent(MainActivity.this, PlayerActivity.class));
                                return;
                            }
                            // 索引或缓存丢了：清掉标记，退回单曲播放
                            Prefs.setLastImportId("");
                            startActivity(new Intent(MainActivity.this, PlayerActivity.class));
                        }
                    });
                }
            });
            return;
        }

        // lastSource() 内部已经过滤掉 id<=0 的无效记录（含我们写入的 "-1:0"），
        // 所以这里只需要判 null。
        final android.util.Pair<Integer, Long> src = lastSource();
        if (src == null) {
            startActivity(new Intent(this, PlayerActivity.class));
            return;
        }
        restoringQueue = true;
        restoringSince = System.currentTimeMillis();
        toast("正在恢复播放列表…");
        Task.run(new Runnable() {
            @Override public void run() {
                // 恢复同样走缓存优先：几乎总是瞬间完成，不会再联网拉 900 首
                // TrackListLoader.load 内部已吞异常并返回 res.items=null，
                // 外面再包一层是防止其它意外（如 OOM）让 restoringQueue 卡住。
                TrackListLoader.Result res;
                try {
                    res = TrackListLoader.load(
                            Bili.get(MainActivity.this), src.first, src.second,
                            src.first == Bili.SRC_SEASON
                                    ? ListCache.keySeason(src.second)
                                    : ListCache.keyFolder(src.second),
                            false, null);
                } catch (Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() { restoringQueue = false; }
                    });
                    return;
                }
                final List<Track> fGot = res.items;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        restoringQueue = false;   // 无论成功失败都要复位
                        if (isFinishing()) return;
                        PlayService s2 = PlayService.get();
                        if (fGot != null && s2 != null) {
                            tracks.clear();
                            tracks.addAll(fGot);
                            // 恢复的列表也要补序号，否则日志里永远是「?」
                            for (int i = 0; i < tracks.size(); i++) tracks.get(i).seq = i + 1;
                            s2.replaceQueueKeepPlaying(new ArrayList<Track>(fGot));
                        }
                        startActivity(new Intent(MainActivity.this, PlayerActivity.class));
                    }
                });
            }
        });
    }

    /**
     * 读取上次播放列表的来源。
     *
     * @return {类型, id}；没有记录返回 null
     */
    private android.util.Pair<Integer, Long> lastSource() {
        String raw = Prefs.getLastSource();
        if (raw == null || raw.isEmpty()) return null;
        int colon = raw.indexOf(':');
        if (colon <= 0) return null;
        try {
            int type = Integer.parseInt(raw.substring(0, colon));
            long id = Long.parseLong(raw.substring(colon + 1));
            if (id <= 0) return null;
            return new android.util.Pair<Integer, Long>(type, id);
        } catch (Exception e) {
            return null;
        }
    }

    private void playFrom(int pos) {
        if (pos < 0 || pos >= tracks.size()) return;
        Track clicked = tracks.get(pos);
        PlayService s = PlayService.get();

        // 点的就是当前这首：不重新起播，只切换播放/暂停。
        // 但仅限队列完整时——服务重启后恢复的是"单曲队列"，
        // 这时必须用完整列表覆盖，否则切歌永远只有一首。
        if (s != null) {
            Track cur = s.track();
            if (cur != null && cur.key() != null && cur.key().equals(clicked.key())
                    && s.queue().size() >= tracks.size()) {
                if (s.isPlaying()) {
                    s.pause();
                } else {
                    s.play();
                }
                return;
            }
        }

        // 只先更新标题和封面，不主动点亮转圈。
        // 之前在这里直接 setSpinning(true)，等于点下去就转，哪怕服务立刻返回
        // 也要转满一小段——凭空多出一截"等待感"。
        // 改由服务的 ACT_STATE 广播推 EXTRA_LOADING 来驱动转圈，
        // 什么时候真的在加载，才什么时候转。
        miniTitle.setText(clicked.title);
        loadMiniCover(clicked);
        miniWrap.setVisibility(View.VISIBLE);

        PlayService.playQueue(this, new ArrayList<Track>(tracks), pos);
    }

    // ---------------------------------------------------------- 收藏夹多选

    /** 进入多选模式，initialId 为长按选中的那一项。 */
    /**
     * 进入多选模式。
     *
     * 收藏夹和合集共用同一套多选界面，靠 page 区分当前在选哪一类。
     */
    private void enterSelectMode(long initialId) {
        selecting = true;
        selectedIds.clear();
        selectedIds.add(initialId);
        selectBar.setVisibility(View.VISIBLE);
        updateSelectBar();
        notifyCurrentAdapter();
    }

    private void exitSelectMode() {
        if (!selecting) return;
        selecting = false;
        selectedIds.clear();
        if (selectBar != null) selectBar.setVisibility(View.GONE);
        notifyCurrentAdapter();
    }

    private void toggleSelect(long id) {
        if (selectedIds.contains(id)) selectedIds.remove(id);
        else selectedIds.add(id);
        if (selectedIds.isEmpty()) {
            exitSelectMode();
            return;
        }
        updateSelectBar();
        notifyCurrentAdapter();
    }

    /** 全选 / 全不选（按当前所在页的全集来算）。 */
    private void toggleSelectAll() {
        java.util.List<Long> all = currentIdList();
        if (all.isEmpty()) return;
        if (selectedIds.size() == all.size()) {
            selectedIds.clear();          // 已全选 → 全不选（留在多选模式）
        } else {
            selectedIds.clear();
            selectedIds.addAll(all);
        }
        updateSelectBar();
        notifyCurrentAdapter();
    }

    /** 当前页所有条目的 id（收藏夹或合集）。 */
    private java.util.List<Long> currentIdList() {
        java.util.List<Long> out = new java.util.ArrayList<Long>();
        if (page == PAGE_SEASONS) {
            for (Season s : seasons) out.add(s.seasonId);
        } else {
            for (FavFolder f : folders) out.add(f.id);
        }
        return out;
    }

    /** 通知当前页对应的适配器刷新（多选高亮需要重绘）。 */
    private void notifyCurrentAdapter() {
        if (page == PAGE_SEASONS) {
            if (seasonAdapter != null) seasonAdapter.notifyDataSetChanged();
        } else {
            if (folderAdapter != null) folderAdapter.notifyDataSetChanged();
        }
    }

    private void updateSelectBar() {
        int n = selectedIds.size();
        String what = (page == PAGE_SEASONS) ? "合集" : "收藏夹";
        selectConfirm.setText(n > 0 ? ("隐藏选中" + what + " (" + n + ")") : "隐藏选中");
        // 未选中时置灰，避免误点
        selectConfirm.setTextColor(n > 0 ? Colors.ACCENT : Colors.TEXT_DISABLED);

        int total = currentIdList().size();
        boolean all = total > 0 && n == total;
        selectAll.setText(all ? "全不选" : "全选");
        selectAll.setTextColor(all ? Colors.ACCENT : Colors.TEXT_HINT);
    }

    /** 批量隐藏选中的收藏夹或合集。 */
    private void hideSelected() {
        if (selectedIds.isEmpty()) return;
        final boolean seasonMode = (page == PAGE_SEASONS);
        final String what = seasonMode ? "合集" : "收藏夹";
        final java.util.Set<Long> ids = new java.util.HashSet<Long>(selectedIds);
        new android.app.AlertDialog.Builder(this)
                .setTitle("隐藏" + what)
                .setMessage("将隐藏选中的 " + ids.size() + " 个" + what + "，"
                        + "之后可在设置里恢复。\n\n"
                        + "隐藏会同时清空这些" + what + "已缓存的歌曲列表，"
                        + "以释放空间。其它" + what + "不受影响。")
                .setPositiveButton("隐藏", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        Bili bili = Bili.get(MainActivity.this);
                        for (Long id : ids) {
                            if (seasonMode) bili.setSeasonHidden(id, true);
                            else bili.setFolderHidden(id, true);
                        }
                        exitSelectMode();
                        toast("已隐藏 " + ids.size() + " 个" + what);
                        // 隐藏意味着不再听，把它们已缓存的曲目列表一并删掉。
                        // 只删被隐藏这几个 id 对应的缓存文件，其它歌单的原样保留。
                        clearHiddenCaches(ids, seasonMode);
                        if (seasonMode) {
                            seasons.clear();
                            loadSeasons();
                        } else {
                            loadFolders();
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 删除被隐藏歌单的缓存文件。
     *
     * @param ids         被隐藏的 id 集合
     * @param seasonMode  true=合集（keySeason），false=收藏夹（keyFolder）
     *
     * 关键：只按传入的 id 精确构造 key 去删，绝不做「按前缀批量删」，
     * 否则会把没被隐藏的歌单缓存一起清掉。
     */
    private void clearHiddenCaches(final java.util.Set<Long> ids, final boolean seasonMode) {
        if (ids.isEmpty()) return;
        Task.run(new Runnable() {
            @Override public void run() {
                int removed = 0;
                for (Long id : ids) {
                    if (id == null) continue;
                    String key = seasonMode ? ListCache.keySeason(id) : ListCache.keyFolder(id);
                    if (ListCache.remove(key)) removed++;
                }
                // 列表内容变了，跨歌单搜索的缓存必须失效，
                // 否则搜出来的结果里还会带着刚清掉的歌。
                TrackSearcher.invalidate();
                final int n = removed;
                if (n > 0) {
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (isFinishing()) return;
                            toast("已清空 " + n + " 个列表缓存");
                        }
                    });
                }
            }
        });
    }

    // -------------------------------------------------------------- 设置菜单

    /** 从清单读取真实版本号，避免硬编码过期。 */
    private String appVersion() {
        try {
            android.content.pm.PackageInfo pi =
                    getPackageManager().getPackageInfo(getPackageName(), 0);
            return pi.versionName;
        } catch (Exception e) {
            return "未知";
        }
    }

    private void showSettingsMenu() {
        new SettingsDialog(this, new SettingsDialog.Host() {
            @Override public void reloadFolders() {
                // 恢复隐藏后收藏夹和合集的可见性都可能变（两个隐藏集合是独立的，
                // 但用户常常一起操作），所以两个列表都清掉再统一 reload()，
                // 免得切到另一个 Tab 还看到隐藏时的旧内容。
                folders.clear();
                seasons.clear();
                tracks.clear();
                exitSelectMode();
                reload();
            }
            @Override public void reloadSeasons() {
                folders.clear();
                seasons.clear();
                tracks.clear();
                exitSelectMode();
                reload();
            }
            @Override public String titleOf(long id, boolean season) {
                // 从当前已加载的列表里翻名字。隐藏的歌单不在列表里，
                // 所以大概率翻不到，这时返回 null，界面会退化成显示 id。
                if (season) {
                    for (Season s : seasons) {
                        if (s.seasonId == id) return s.title;
                    }
                } else {
                    for (FavFolder f : folders) {
                        if (f.id == id) return f.title;
                    }
                }
                return null;
            }
            @Override public void toast(String msg) {
                MainActivity.this.toast(msg);
            }
            @Override public String appVersion() {
                return MainActivity.this.appVersion();
            }
        }).show();
    }

    // ---------------------------------------------------------------- state

    private void setLoading(boolean on) {
        loadingList = on;
        bar.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------ 合集（season）

    /**
     * 切到某个一级页面（收藏夹 / 合集）。
     *
     * 这两个是并列的一级分类，点 Tab 直接进入，互不嵌套。
     * 从二级页面（曲目列表）点 Tab 时，等于放弃当前浏览位置回到一级。
     */
    private void gotoPage(int target) {
        exitSelectMode();
        // 切页时必须退出批量模式，否则操作栏会残留在别的页面上
        if (batchMode) exitBatchMode();
        // 切到一级页就等于离开了曲目列表，去重关键词不再保留
        if (page == PAGE_TRACKS) dedupeKeywords.clear();
        if (target == PAGE_TEMP) {
            if (page == PAGE_TEMP) return;
            page = PAGE_TEMP;
            curSeason = null;
            curFolder = null;
            curImport = null;
            curTemp = null;
            list.setAdapter(tempAdapter);
            reloadTempList();
            updateTabs();
            syncTabSubtitle();
            syncMiniBar();
            updateLocateButton();
            return;
        }
        if (target == PAGE_IMPORT) {
            if (page == PAGE_IMPORT) return;
            page = PAGE_IMPORT;
            curSeason = null;
            curFolder = null;
            curImport = null;
            curTemp = null;
            list.setAdapter(importAdapter);
            reloadImportList();
            updateTabs();
            syncTabSubtitle();
            syncMiniBar();
            updateLocateButton();
            return;
        }
        if (target == PAGE_SEASONS) {
            if (page == PAGE_SEASONS) return;          // 已在该页
            page = PAGE_SEASONS;
            curSeason = null;
            curFolder = null;
            curImport = null;
            curTemp = null;
            list.setAdapter(seasonAdapter);
            updateTabs();
            syncTabSubtitle();
            if (seasons.isEmpty()) loadSeasons();
            syncMiniBar();
            updateLocateButton();
            return;
        }
        // 收藏夹
        if (page == PAGE_FOLDERS) return;
        page = PAGE_FOLDERS;
        curSeason = null;
        curFolder = null;
        curImport = null;
        curTemp = null;
        list.setAdapter(folderAdapter);
        updateTabs();
        syncTabSubtitle();
        syncMiniBar();
        updateLocateButton();
    }

    /**
     * 刷新两个 Tab 的高亮状态。
     *
     * 只用字色区分（当前页主色、另一页次要色），不额外挂背景：
     * 背景色块在等宽 Tab 上会变成整块色带，视觉上很重，和整体风格不搭。
     */
    /**
     * 初始化一级页面的左右滑动切换。
     *
     * 为什么用 dispatchTouchEvent 而不是给 root 设 OnTouchListener：
     * root 是父容器，ListView 在里面；给 root 设监听不会收到子 View 消费掉的事件。
     * 走 dispatchTouchEvent 能在事件下发前先看一眼，识别出横向滑动时自己处理掉，
     * 否则继续交给子 View（ListView 纵向滚动、点击都正常）。
     *
     * 只在三个一级列表页生效，进入曲目页后直接返回 false，完全不插手。
     */
    private void initSwipe() {
        // 这里不再用 GestureDetector 的 onFling/onScroll 直接翻页。
        // 原因：那两个回调在手指还没抬起时就会触发，表现为「手一划就跳过去」。
        // 现在改成：拖动过程中只记录位移、给一点跟手的视觉反馈，
        // 松手（ACTION_UP/CANCEL）时才根据最终位移决定翻不翻页。
        swipeDetector = null;   // 保留字段以免其它地方判空出错
    }

    /** 开始记录一次可能的横滑。 */
    private void beginSwipe(android.view.MotionEvent ev) {
        swipeStartX = ev.getX();
        swipeStartY = ev.getY();
        swipeTracking = swipeEnabled();
        swipeConsumed = false;
    }

    /**
     * 拖动过程中：只更新跟手位移，不翻页。
     *
     * 平移量做了阻尼（乘 0.35），让内容像被轻轻带动，
     * 又不会真的移动一整屏（那需要两套页面同时存在，本实现不做）。
     */
    private void moveSwipe(android.view.MotionEvent ev) {
        if (!swipeTracking || swipeConsumed) return;
        float dx = ev.getX() - swipeStartX;
        float dy = ev.getY() - swipeStartY;

        // 纵向意图明显时放弃横滑，交给列表自己滚
        if (Math.abs(dy) > dp(SWIPE_MAX_DY_DP) && Math.abs(dy) > Math.abs(dx)) {
            swipeTracking = false;
            resetContentOffset();
            return;
        }
        if (Math.abs(dx) < dp(8) || Math.abs(dx) < Math.abs(dy)) return;

        // 到头了就不要再跟手，避免"拉得动但翻不过去"的错觉
        boolean atLeft = indexOfPage(page) <= 0;
        boolean atRight = indexOfPage(page) >= PAGE_ORDER.length - 1;
        if ((dx > 0 && atLeft) || (dx < 0 && atRight)) {
            resetContentOffset();
            return;
        }

        if (listWrap != null) {
            // 阻尼 0.6：内容跟随手指移动六成距离。
            // 之前取 0.35 太"黏"，拖动时几乎跟不上手，松手后却要滑出全屏，
            // 快慢落差很明显。0.6 让拖动阶段就有明确的位移感，衔接更自然。
            listWrap.setTranslationX(dx * 0.6f);
        }
    }

    /** 松手：按最终位移决定是否翻页，并把内容归位/做切换动画。 */
    private void endSwipe(android.view.MotionEvent ev, boolean canceled) {
        boolean wasTracking = swipeTracking;
        swipeTracking = false;
        if (!wasTracking || canceled || swipeConsumed) {
            resetContentOffset();
            return;
        }

        float dx = ev.getX() - swipeStartX;
        float dy = ev.getY() - swipeStartY;

        // 松手时再判定：横向要够远，且明显大于纵向
        boolean far = Math.abs(dx) >= dp(SWIPE_MIN_DX_DP);
        boolean straight = Math.abs(dy) <= dp(SWIPE_MAX_DY_DP)
                && Math.abs(dx) >= Math.abs(dy);

        if (!far || !straight) {
            // 没达标：平滑弹回原位，而不是瞬间跳回
            if (contentCol != null) {
                listWrap.animate().translationX(0f).setDuration(120).start();
            }
            return;
        }

        swipeConsumed = true;
        // 注意：这里不先归位。归位再动画会「先弹回、再滑出」顿一下；
        // 直接让切换动画从当前位移接管，观感是连续的。
        if (dx < 0) swipeToNextAnimated();
        else swipeToPrevAnimated();
    }

    /** 内容归位。 */
    private void resetContentOffset() {
        if (listWrap != null) listWrap.setTranslationX(0f);
    }

    /**
     * 带方向的切换动画：旧内容先移出一点并淡出，切完再移入淡入。
     *
     * 不用真正的 ViewPager：那需要三个页面同时存在，改动太大。
     * 这里用「平移 + 透明度」两个属性做 180ms 的过渡，
     * 视觉上足以消除生硬跳转，成本也很低。
     *
     * @param dir -1 向左切（下一个 Tab），+1 向右切（上一个 Tab）
     */
    private void animateTabSwitch(final int dir, final Runnable switchAction) {
        if (listWrap == null) {
            switchAction.run();
            return;
        }
        final int w = listWrap.getWidth() > 0
                ? listWrap.getWidth()
                : getResources().getDisplayMetrics().widthPixels;

        tabAnimating = true;

        // 第一段：从「当前手指留下的位置」继续向左/右滑出屏幕。
        //
        // 这里必须用 getTranslationX() 作为起点，而不是写死一个固定位移：
        // 松手时内容可能已经跟着手指移动了 100dp+，如果动画目标是固定 48dp，
        // 就会先往回拉一下再滑出，看起来像「回弹」。
        // 用当前值做起点，再接一个 (w * dir) 的终点，才是连续的甩出效果。
        float to = dir * w;          // dir=-1 → 向左边滑出；+1 → 向右

        listWrap.animate()
                .translationX(to)
                .alpha(0.2f)
                .setDuration(150)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .withEndAction(new Runnable() {
                    @Override public void run() {
                        // 换内容（此时列表已经滑出屏幕、几乎透明，看不见跳变）
                        switchAction.run();

                        // 第二段：新内容从反方向滑入
                        listWrap.setTranslationX(-to);
                        listWrap.animate()
                                .translationX(0f)
                                .alpha(1f)
                                .setDuration(180)
                                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                                .withEndAction(new Runnable() {
                                    @Override public void run() {
                                        // 双保险归位，动画结束必须回到完全正常的状态
                                        listWrap.setTranslationX(0f);
                                        listWrap.setAlpha(1f);
                                        tabAnimating = false;
                                    }
                                })
                                .start();
                    }
                })
                .start();


    }

    /** 一级页面才允许滑动切换。曲目页、未登录都返回 false。 */
    private boolean swipeEnabled() {
        if (page == PAGE_TRACKS) return false;
        if (selecting) return false;                 // 多选时不切页
        if (loadingList) return false;               // 加载中不切页
        if (tabAnimating) return false;              // 动画中不接新手势
        if (!Bili.get(this).isLoggedIn()) return false;
        return page == PAGE_FOLDERS || page == PAGE_SEASONS
                || page == PAGE_IMPORT || page == PAGE_TEMP;
    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
                beginSwipe(ev);
                break;
            case android.view.MotionEvent.ACTION_MOVE:
                moveSwipe(ev);
                break;
            case android.view.MotionEvent.ACTION_UP:
                endSwipe(ev, false);
                break;
            case android.view.MotionEvent.ACTION_CANCEL:
                endSwipe(ev, true);
                break;
            default:
                break;
        }
        if (swipeConsumed) {
            // 本次手势已判定为翻页：后续事件不再下发给子 View，
            // 避免滑完又触发一次列表点击。
            return true;
        }
        return super.dispatchTouchEvent(ev);
    }

    /** 左滑 → 下一个 Tab，带过渡动画。到最右端不动。 */
    private void swipeToNextAnimated() {
        final int idx = indexOfPage(page);
        if (idx < 0 || idx >= PAGE_ORDER.length - 1) return;   // 不循环
        animateTabSwitch(-1, new Runnable() {
            @Override public void run() { gotoPage(PAGE_ORDER[idx + 1]); }
        });
    }

    /** 右滑 → 上一个 Tab，带过渡动画。到最左端不动。 */
    private void swipeToPrevAnimated() {
        final int idx = indexOfPage(page);
        if (idx <= 0) return;                                   // 不循环
        animateTabSwitch(1, new Runnable() {
            @Override public void run() { gotoPage(PAGE_ORDER[idx - 1]); }
        });
    }

    private int indexOfPage(int p) {
        for (int i = 0; i < PAGE_ORDER.length; i++) {
            if (PAGE_ORDER[i] == p) return i;
        }
        return -1;
    }
    private void updateTabs() {
        // 一级页面直接看 page；二级页面（曲目列表）要看它当初是从哪进来的。
        int active;
        if (page == PAGE_FOLDERS) {
            active = PAGE_FOLDERS;
        } else if (page == PAGE_SEASONS) {
            active = PAGE_SEASONS;
        } else if (page == PAGE_IMPORT) {
            active = PAGE_IMPORT;
        } else if (page == PAGE_TEMP) {
            active = PAGE_TEMP;
        } else {
            // PAGE_TRACKS：按来源判断归属哪个 Tab
            if (curTemp != null) active = PAGE_TEMP;
            else if (curImport != null) active = PAGE_IMPORT;
            else if (curSeason != null) active = PAGE_SEASONS;
            else active = PAGE_FOLDERS;
        }
        if (tabFolders != null) {
            tabFolders.setTextColor(active == PAGE_FOLDERS ? Colors.ACCENT : Colors.TEXT_SECONDARY);
        }
        if (tabSeasons != null) {
            tabSeasons.setTextColor(active == PAGE_SEASONS ? Colors.ACCENT : Colors.TEXT_SECONDARY);
        }
        if (tabImport != null) {
            tabImport.setTextColor(active == PAGE_IMPORT ? Colors.ACCENT : Colors.TEXT_SECONDARY);
        }
        if (tabTemp != null) {
            tabTemp.setTextColor(active == PAGE_TEMP ? Colors.ACCENT : Colors.TEXT_SECONDARY);
        }
    }

    /**
     * 刷新副标题（收藏夹/合集的统计文案）。
     *
     * 两个一级页面都要显示自己的统计信息，所以统一由这里按当前页决定内容。
     */
    private void syncTabSubtitle() {
        if (subTitle == null) return;

        // 二级页面才显示标题行；一级页面由顶栏 Tab 表示
        boolean secondary = (page == PAGE_TRACKS);
        if (titleRow != null) {
            titleRow.setVisibility(secondary ? View.VISIBLE : View.GONE);
        }
        if (refreshBtn != null) {
            // 导入列表和临时歌单都是本地快照，没有「服务端版本」可对齐，
            // 所以只有收藏夹/合集来源的曲目页才显示刷新按钮。
            boolean canRefresh = secondary && curImport == null && curTemp == null;
            refreshBtn.setVisibility(canRefresh ? View.VISIBLE : View.GONE);
        }
        if (dedupeBtn != null) {
            // 只有临时歌单的曲目页才能去重：其它来源的列表不该在 App 内被改。
            dedupeBtn.setVisibility(
                    (secondary && curTemp != null && !batchMode) ? View.VISIBLE : View.GONE);
        }
        if (batchBtn != null) {
            // 批量删歌对「本地快照型」列表开放：临时歌单 + BV 导入。
            // 收藏夹/合集不行——那是服务端数据，本地删掉会造成不一致。
            batchBtn.setVisibility(
                    (secondary && batchEditable() && !batchMode) ? View.VISIBLE : View.GONE);
        }
        if (!secondary && titleBar != null) titleBar.setText("");
        // 导入输入框只在「BV导入」一级页出现
        if (importRow != null) {
            importRow.setVisibility(page == PAGE_IMPORT ? View.VISIBLE : View.GONE);
        }

        if (page == PAGE_SEASONS) {
            int hidden = Bili.get(this).hiddenSeasons().size();
            subTitle.setText(seasons.size() + " 个合集"
                    + (hidden > 0 ? "（已隐藏 " + hidden + " 个）" : "")
                    + "  ·  长按可隐藏");
        } else if (page == PAGE_FOLDERS) {
            int hidden = Bili.get(this).hiddenFolders().size();
            subTitle.setText(folders.size() + " 个收藏夹"
                    + (hidden > 0 ? "（已隐藏 " + hidden + " 个）" : "")
                    + "  ·  长按可隐藏");
        } else if (page == PAGE_TEMP) {
            subTitle.setText(tempLists.isEmpty()
                    ? "还没有临时歌单，去搜索页生成一个"
                    : tempLists.size() + " 个临时歌单  ·  长按可删除");
        } else if (page == PAGE_IMPORT) {
            subTitle.setText(importedLists.isEmpty()
                    ? "还没有导入任何列表，粘贴 BV 号试试"
                    : importedLists.size() + " 个导入的列表  ·  长按可删除");
        } else if (page == PAGE_TRACKS) {
            subTitle.setText(tracks.size() + " 个视频");
        }
    }

    /**
     * 加载「我收藏的合集」列表。
     *
     * 与 loadFolders 同样用 loadSeq 做竞态防护：快速切换两次时，
     * 只认最后一次的结果。
     */
    private void loadSeasons() {
        setLoading(true);
        final int seq = ++loadSeq;
        Task.run(new Runnable() {
            @Override public void run() {
                String err = null;
                List<Season> got = null;
                try {
                    got = Bili.get(MainActivity.this).seasons();
                } catch (Exception e) {
                    err = e.getMessage();
                }
                final List<Season> fGot = got;
                final String fErr = err;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (seq != loadSeq) return;   // 已有更新的请求
                        setLoading(false);
                        if (fGot == null) {
                            if (handleLoadError(fErr, "加载合集失败")) return;
                            subTitle.setText("加载失败");
                            return;
                        }
                        // 过滤掉被隐藏的合集
                        Bili bili = Bili.get(MainActivity.this);
                        java.util.Set<Long> hidden = bili.hiddenSeasons();
                        // 存活集合用 fGot（服务端完整列表，含已隐藏的）
                        java.util.Set<Long> alive = new java.util.HashSet<Long>();
                        for (Season s : fGot) alive.add(s.seasonId);
                        pruneOrphanCaches(true, alive);
                        seasons.clear();
                        for (Season s : fGot) {
                            if (!hidden.contains(s.seasonId)) seasons.add(s);
                        }
                        if (seasonAdapter != null) seasonAdapter.notifyDataSetChanged();
                        syncTabSubtitle();
                    }
                });
            }
        });
    }


    /**
     * 加载失败时的统一处理。
     *
     * 登录失效（-101）是特殊情形：此时 Bili 内部已经把 Cookie 清掉了，
     * 界面上必须同步成「未登录」，否则用户会一直看到空的收藏夹，
     * 却以为自己还登录着。
     *
     * @param err 错误信息，可能为 null
     * @param fallback 没有错误信息时的兜底提示
     * @return true 表示已按「登录失效」处理，调用方不必再提示
     */
    private boolean handleLoadError(String err, String fallback) {
        if (!Bili.get(this).isLoggedIn()) {
            // Bili 已经清掉登录态 → 说明是 -101，同步界面
            showLoginPrompt();
            toast("登录已失效，请重新登录");
            return true;
        }
        toast(err == null ? fallback : err);
        return false;
    }
    private void showLoginPrompt() {
        titleBar.setText("");
        titleRow.setVisibility(View.GONE);
        subTitle.setText("请先扫码登录 B 站账号");
        folders.clear();
        seasons.clear();
        tracks.clear();
        importedLists.clear();
        tempLists.clear();
        curSeason = null;
        curFolder = null;
        curImport = null;
        curTemp = null;
        page = PAGE_FOLDERS;
        list.setAdapter(folderAdapter);
        updateTabs();
        if (importRow != null) importRow.setVisibility(View.GONE);
        if (subTitle != null) subTitle.setText("请先扫码登录 B 站账号");
    }

    @Override
    public void onBackPressed() {
        // 批量模式优先退出，而不是退出页面
        if (batchMode) {
            exitBatchMode();
            return;
        }
        // 多选模式优先退出选择，而不是退出页面
        if (selecting) {
            exitSelectMode();
            return;
        }
        if (page == PAGE_TRACKS) {
            // 离开曲目列表页，去重关键词不再保留
            dedupeKeywords.clear();
            // 从曲目页返回：回到它所属的上一层（导入 / 合集 / 收藏夹）
            if (curTemp != null) {
                page = PAGE_TEMP;
                curTemp = null;
                list.setAdapter(tempAdapter);
                reloadTempList();
                updateTabs();
                syncTabSubtitle();
                syncMiniBar();
                updateLocateButton();
                return;
            }
            if (curImport != null) {
                page = PAGE_IMPORT;
                curImport = null;
                list.setAdapter(importAdapter);
                reloadImportList();
                updateTabs();
                syncTabSubtitle();
                syncMiniBar();
                updateLocateButton();
                return;
            }
            if (curSeason != null) {
                page = PAGE_SEASONS;
                list.setAdapter(seasonAdapter);
                updateTabs();
                syncTabSubtitle();
            } else {
                page = PAGE_FOLDERS;
                list.setAdapter(folderAdapter);
                updateTabs();
                syncTabSubtitle();
            }
            curSeason = null;
            syncMiniBar();
            updateLocateButton();
            return;
        }
        if (page == PAGE_SEASONS || page == PAGE_FOLDERS
                || page == PAGE_IMPORT || page == PAGE_TEMP) {
            // 一级页面按返回：退到后台，而不是 finish()。
            //
            // 为什么不用 super.onBackPressed()（它最终会 finish()）：
            //   finish() 不触发 onSaveInstanceState，页面状态会全部丢失，
            //   下次进入 onCreate(null) 只能回到收藏夹页；
            //   而且已经加载好的列表、封面都要重新拉一遍。
            // 实测现象：进过播放页再连续返回到桌面，重进就会「像重启了一样」
            // 停在收藏夹页。
            //
            // moveTaskToBack(true) 只是把任务挪到后台：实例、list、page 全保留，
            // 行为和「用最近任务切走」完全一致。音乐继续播，符合播放器类应用的习惯。
            moveTaskToBack(true);
            return;
        }
        // 其它情况（理论上不会到这里）保持默认行为
        super.onBackPressed();
    }

    // -------------------------------------------------------------------- UI

    private void registerRx() {
        stateRx = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                // 退到后台期间不刷新界面：View 不可见，刷了纯属白费电。
                // 回到前台时 onResume 会 syncMiniBar() 补一次最新状态。
                if (!uiVisible) return;
                boolean playing = i.getBooleanExtra(PlayService.EXTRA_PLAYING, false);
                boolean loading = i.getBooleanExtra(PlayService.EXTRA_LOADING, false);
                miniBtn.setText(playing ? "⏸" : "▶");

                // 取流/缓冲中显示转圈
                setSpinning(loading);

                PlayService s = PlayService.get();
                if (Bili.get(MainActivity.this).isLoggedIn()
                        && s != null && s.track() != null) {
                    miniWrap.setVisibility(View.VISIBLE);
                    miniTitle.setText(s.track().title);
                    // 切歌后封面也要跟着换，这里之前漏了
                    loadMiniCover(s.track());
                } else {
                    // 未登录或没有曲目：不显示
                    miniWrap.setVisibility(View.GONE);
                }
                // 切歌后当前曲目变了，定位按钮的显隐和落点要跟着变
                updateLocateButton();
            }
        };
        errRx = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                if (!uiVisible) return;   // 后台不弹提示
                toast(i.getStringExtra(PlayService.EXTRA_ERR));
            }
        };
        IntentFilter f1 = new IntentFilter(PlayService.ACT_STATE);
        IntentFilter f2 = new IntentFilter(PlayService.ACT_ERROR);
        registerLocal(stateRx, f1);
        registerLocal(errRx, f2);
    }

    @Override
    protected void onDestroy() {
        spinHandler.removeCallbacksAndMessages(null);
        try {
            if (stateRx != null) unregisterReceiver(stateRx);
            if (errRx != null) unregisterReceiver(errRx);
        } catch (Exception ignored) {}
        // 双保险：onPause 通常已经冲过了，这里再确认一次（无改动时是空操作）
        try {
            com.star.bilimusic.util.CidCache.flush();
        } catch (Exception ignored) {}
        super.onDestroy();
    }

    // ---------------------------------------------------------------- adapters

    /** 复用视图的底座，避免滚动时反复 new 对象。 */
    private static class Holder {
        View row;
        ImageView cover;
        TextView title;
        TextView sub;
        /** 封面中央的音频可视化三根条（仅当前播放的那一行显示）。 */
        VisualizerView viz;
    }

    private class FolderAdapter extends BaseAdapter {
        @Override public int getCount() { return folders.size(); }
        @Override public Object getItem(int i) { return folders.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int pos, View conv, ViewGroup parent) {
            Holder h;
            if (conv == null) {
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dp(16), dp(14), dp(16), dp(14));

                TextView title = new TextView(MainActivity.this);
                title.setTextColor(Colors.TEXT_PRIMARY);
                title.setTextSize(16);
                row.addView(title);

                TextView sub = new TextView(MainActivity.this);
                sub.setTextColor(Colors.TEXT_SECONDARY);
                sub.setTextSize(12);
                sub.setPadding(0, dp(4), 0, 0);
                row.addView(sub);

                h = new Holder();
                h.row = row;
                h.title = title;
                h.sub = sub;
                row.setTag(h);
                conv = row;
            } else {
                h = (Holder) conv.getTag();
            }

            FavFolder f = folders.get(pos);
            if (selecting && selectedIds.contains(f.id)) {
                h.title.setText("✓  " + f.title);
                h.title.setTextColor(Colors.ACCENT);
                h.row.setBackgroundColor(Colors.BG_ROW_ALT);
            } else {
                h.title.setText(f.title);
                h.title.setTextColor(Colors.TEXT_PRIMARY);
                h.row.setBackgroundColor(pos % 2 == 0 ? Colors.BG_ROW_ALT : Colors.BG);
            }
            h.sub.setText(f.mediaCount + " 个视频");
            return conv;
        }
    }

    /**
     * 合集列表适配器。
     *
     * 布局与曲目行类似（封面 + 两行文字），但显示的是
     * 合集名和「UP 主 · N 个视频」，并且不需要播放状态。
     */
    /**
     * 「BV导入」列表适配器。
     *
     * 显示：封面 + 标题 + 「类型 · 作者 · 条目数」。
     * 类型是这三种之一：合集 / 视频选集 / 单集，让用户知道导入的是什么。
     */
    /**
     * 「临时」列表适配器。
     *
     * 只有文字没有封面：临时歌单是一堆来自不同歌单的歌曲集合，
     * 本身没有统一的视觉封面，强行取第一首的封面反而会误导。
     */
    private class TempAdapter extends BaseAdapter {
        @Override public int getCount() { return tempLists.size(); }
        @Override public Object getItem(int i) { return tempLists.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int pos, View conv, ViewGroup parent) {
            LinearLayout box;
            TextView title;
            TextView sub;
            if (conv == null) {
                box = new LinearLayout(MainActivity.this);
                box.setOrientation(LinearLayout.VERTICAL);
                box.setPadding(dp(16), dp(12), dp(16), dp(12));

                title = new TextView(MainActivity.this);
                title.setTextColor(Colors.TEXT_PRIMARY);
                title.setTextSize(15);
                title.setMaxLines(1);
                title.setEllipsize(android.text.TextUtils.TruncateAt.END);
                box.addView(title);

                sub = new TextView(MainActivity.this);
                sub.setTextColor(Colors.TEXT_SECONDARY);
                sub.setTextSize(12);
                sub.setPadding(0, dp(4), 0, 0);
                box.addView(sub);

                Holder2 h2 = new Holder2();
                h2.title = title;
                h2.sub = sub;
                box.setTag(h2);
                conv = box;
            } else {
                box = (LinearLayout) conv;
                Holder2 h2 = (Holder2) box.getTag();
                title = h2.title;
                sub = h2.sub;
            }

            com.star.bilimusic.api.TempPlaylist p = tempLists.get(pos);
            title.setText(p.title);
            sub.setText(p.count + " 首  ·  " + fmtDate(p.createdAt));
            box.setBackgroundColor(pos % 2 == 0 ? Colors.BG_ROW_ALT : Colors.BG);
            return conv;
        }
    }

    private static class Holder2 {
        TextView title;
        TextView sub;
    }

    /** 把毫秒时间戳格式化成「2026-11-10 21:30」。 */
    private static String fmtDate(long ms) {
        if (ms <= 0) return "";
        java.text.SimpleDateFormat f =
                new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US);
        return f.format(new java.util.Date(ms));
    }
    private class ImportAdapter extends BaseAdapter {
        @Override public int getCount() { return importedLists.size(); }
        @Override public Object getItem(int i) { return importedLists.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int pos, View conv, ViewGroup parent) {
            Holder h;
            if (conv == null) {
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(16), dp(10), dp(16), dp(10));

                android.widget.FrameLayout box = new android.widget.FrameLayout(MainActivity.this);
                int cs = dp(64);
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(cs, cs);
                clp.rightMargin = dp(12);
                box.setLayoutParams(clp);

                ImageView cover = new ImageView(MainActivity.this);
                cover.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
                cover.setBackgroundColor(Colors.PLACEHOLDER);
                box.addView(cover);
                row.addView(box);

                LinearLayout texts = new LinearLayout(MainActivity.this);
                texts.setOrientation(LinearLayout.VERTICAL);
                row.addView(texts, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                TextView title = new TextView(MainActivity.this);
                title.setTextColor(Colors.TEXT_PRIMARY);
                title.setTextSize(15);
                title.setMaxLines(2);
                title.setEllipsize(android.text.TextUtils.TruncateAt.END);
                texts.addView(title);

                TextView sub = new TextView(MainActivity.this);
                sub.setTextColor(Colors.TEXT_SECONDARY);
                sub.setTextSize(12);
                sub.setPadding(0, dp(4), 0, 0);
                texts.addView(sub);

                h = new Holder();
                h.row = row;
                h.cover = cover;
                h.title = title;
                h.sub = sub;
                row.setTag(h);
                conv = row;
            } else {
                h = (Holder) conv.getTag();
            }

            final com.star.bilimusic.api.ImportedList it = importedLists.get(pos);
            h.title.setText(it.title);
            String au = (it.author == null || it.author.isEmpty()) ? "" : it.author + "  ·  ";
            h.sub.setText(it.kindLabel() + "  ·  " + au + it.count + " 项");
            h.row.setBackgroundColor(pos % 2 == 0 ? Colors.BG_ROW_ALT : Colors.BG);

            final String thumb = Bili.thumbPic(it.cover, 200);
            Object oldTag = h.cover.getTag();
            boolean same = thumb != null && !thumb.isEmpty() && thumb.equals(oldTag);
            h.cover.setTag(thumb);
            if (!same) {
                h.cover.setImageDrawable(null);
                if (thumb != null && !thumb.isEmpty()) {
                    final ImageView iv = h.cover;
                    CoverLoader.get(MainActivity.this).load(thumb, CoverLoader.SIZE_THUMB,
                            new CoverLoader.Callback() {
                        @Override public void onLoaded(String url, Bitmap bm) {
                            Object tag = iv.getTag();
                            if (url.equals(tag) && bm != null) iv.setImageBitmap(bm);
                        }
                    });
                }
            }
            return conv;
        }
    }
    private class SeasonAdapter extends BaseAdapter {
        @Override public int getCount() { return seasons.size(); }
        @Override public Object getItem(int i) { return seasons.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int pos, View conv, ViewGroup parent) {
            Holder h;
            if (conv == null) {
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(16), dp(10), dp(16), dp(10));

                // 封面外面套一层 FrameLayout：图片铺满，可视化条居中叠在上面
                android.widget.FrameLayout coverBox = new android.widget.FrameLayout(MainActivity.this);
                int cs = dp(64);
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(cs, cs);
                clp.rightMargin = dp(12);
                coverBox.setLayoutParams(clp);

                ImageView cover = new ImageView(MainActivity.this);
                cover.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
                cover.setBackgroundColor(Colors.PLACEHOLDER);
                coverBox.addView(cover);

                // 三根竖条：只占封面中央一块，不挡满整图
                VisualizerView viz = new VisualizerView(MainActivity.this);
                android.widget.FrameLayout.LayoutParams vlp = new android.widget.FrameLayout.LayoutParams(
                        Math.round(cs * 0.62f), Math.round(cs * 0.62f));
                vlp.gravity = Gravity.CENTER;
                coverBox.addView(viz, vlp);

                row.addView(coverBox);

                LinearLayout texts = new LinearLayout(MainActivity.this);
                texts.setOrientation(LinearLayout.VERTICAL);
                row.addView(texts, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                TextView title = new TextView(MainActivity.this);
                title.setTextColor(Colors.TEXT_PRIMARY);
                title.setTextSize(15);
                title.setMaxLines(2);
                title.setEllipsize(android.text.TextUtils.TruncateAt.END);
                texts.addView(title);

                TextView sub = new TextView(MainActivity.this);
                sub.setTextColor(Colors.TEXT_SECONDARY);
                sub.setTextSize(12);
                sub.setPadding(0, dp(4), 0, 0);
                texts.addView(sub);

                h = new Holder();
                h.row = row;
                h.cover = cover;
                h.title = title;
                h.sub = sub;
                h.viz = viz;
                row.setTag(h);
                conv = row;
            } else {
                h = (Holder) conv.getTag();
            }

            final Season s = seasons.get(pos);
            // 多选样式与收藏夹保持一致：选中项打勾 + 主色，其余正常
            if (selecting && selectedIds.contains(s.seasonId)) {
                h.title.setText("✓  " + s.title);
                h.title.setTextColor(Colors.ACCENT);
                h.row.setBackgroundColor(Colors.BG_ROW_ALT);
            } else {
                h.title.setText(s.title);
                h.title.setTextColor(Colors.TEXT_PRIMARY);
                h.row.setBackgroundColor(pos % 2 == 0 ? Colors.BG_ROW_ALT : Colors.BG);
            }
            String up = (s.author == null || s.author.isEmpty()) ? "" : s.author + "  ·  ";
            h.sub.setText(up + s.total + " 个视频");

            // 封面同样用 200px 缩略图，避免抢占带宽
            final String thumb = Bili.thumbPic(s.cover, 200);
            Object oldTag = h.cover.getTag();
            boolean same = thumb != null && !thumb.isEmpty() && thumb.equals(oldTag);
            h.cover.setTag(thumb);
            if (!same) {
                h.cover.setImageDrawable(null);
                if (thumb != null && !thumb.isEmpty()) {
                    final ImageView iv = h.cover;
                    CoverLoader.get(MainActivity.this).load(thumb, CoverLoader.SIZE_THUMB,
                            new CoverLoader.Callback() {
                        @Override public void onLoaded(String url, Bitmap bm) {
                            Object tag = iv.getTag();
                            if (url.equals(tag) && bm != null) iv.setImageBitmap(bm);
                        }
                    });
                }
            }
            return conv;
        }
    }
    private class TrackAdapter extends BaseAdapter {
        @Override public int getCount() { return tracks.size(); }
        @Override public Object getItem(int i) { return tracks.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int pos, View conv, ViewGroup parent) {
            Holder h;
            if (conv == null) {
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(16), dp(10), dp(16), dp(10));

                // 封面外面套一层 FrameLayout：图片铺满，可视化条居中叠在上面
                android.widget.FrameLayout coverBox = new android.widget.FrameLayout(MainActivity.this);
                int cs = dp(64);
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(cs, cs);
                clp.rightMargin = dp(12);
                coverBox.setLayoutParams(clp);

                ImageView cover = new ImageView(MainActivity.this);
                cover.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
                cover.setBackgroundColor(Colors.PLACEHOLDER);
                coverBox.addView(cover);

                // 三根竖条：只占封面中央一块，不挡满整图
                VisualizerView viz = new VisualizerView(MainActivity.this);
                android.widget.FrameLayout.LayoutParams vlp = new android.widget.FrameLayout.LayoutParams(
                        Math.round(cs * 0.62f), Math.round(cs * 0.62f));
                vlp.gravity = Gravity.CENTER;
                coverBox.addView(viz, vlp);

                row.addView(coverBox);

                LinearLayout texts = new LinearLayout(MainActivity.this);
                texts.setOrientation(LinearLayout.VERTICAL);
                row.addView(texts, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                TextView title = new TextView(MainActivity.this);
                title.setTextColor(Colors.TEXT_PRIMARY);
                title.setTextSize(15);
                title.setMaxLines(2);
                title.setEllipsize(android.text.TextUtils.TruncateAt.END);
                texts.addView(title);

                TextView sub = new TextView(MainActivity.this);
                sub.setTextColor(Colors.TEXT_SECONDARY);
                sub.setTextSize(12);
                sub.setPadding(0, dp(4), 0, 0);
                texts.addView(sub);

                h = new Holder();
                h.row = row;
                h.cover = cover;
                h.title = title;
                h.sub = sub;
                h.viz = viz;
                row.setTag(h);
                conv = row;
            } else {
                h = (Holder) conv.getTag();
            }

            final Track t = tracks.get(pos);
            h.title.setText(t.title);
            // 批量模式下用「序号 + 歌名」让用户看清选了哪几首
            h.sub.setText(t.author + "  ·  " + fmt(t.duration));

            if (batchMode) {
                boolean sel = batchSel.contains(pos);
                h.row.setBackgroundColor(sel ? Colors.BG_SELECTED
                        : (pos % 2 == 0 ? Colors.BG_ROW_ALT : Colors.BG));
                // 批量模式用序号前缀代替勾选框，避免再引入一种控件
                h.title.setText((sel ? "✓ " : "○ ") + t.title);
            } else {
                h.row.setBackgroundColor(pos % 2 == 0 ? Colors.BG_ROW_ALT : Colors.BG);
            }

            // 列表只用 200px 缩略图（原图 491KB/张，8 行就吃掉 3.8MB 带宽，
            // 会把音频请求挤慢好几秒）；播放页才用原图大图。
            final String thumb = Bili.thumbPic(t.cover, 200);
            Object oldTag = h.cover.getTag();
            boolean sameCover = thumb != null && !thumb.isEmpty() && thumb.equals(oldTag);
            h.cover.setTag(thumb);
            if (!sameCover) {
                // 换了封面就先清空，防止短暂显示上一行的图
                h.cover.setImageDrawable(null);
                if (thumb != null && !thumb.isEmpty()) {
                    final ImageView iv = h.cover;
                    CoverLoader.get(MainActivity.this).load(thumb, CoverLoader.SIZE_THUMB,
                            new CoverLoader.Callback() {
                        @Override public void onLoaded(String url, Bitmap bm) {
                            Object tag = iv.getTag();
                            if (url.equals(tag) && bm != null) {
                                iv.setImageBitmap(bm);
                            }
                        }
                    });
                }
            }
            // 可视化三根条：只有「当前正在播放的那一行」才显示。
            // 正在播 → 跳动；已暂停 → 停住；切歌后由 updateLocateButton()
            // 触发 notifyDataSetChanged，各行会重新绑定状态。
            PlayService svc = PlayService.get();
            boolean isCurrent = (pos == playingIndex);
            boolean playingNow = isCurrent && svc != null && svc.isPlaying();
            if (h.viz != null) {
                if (!isCurrent) {
                    h.viz.hideBars();
                } else if (playingNow) {
                    h.viz.start();
                } else {
                    h.viz.freeze();
                }
            }

            return conv;
        }
    }

    private static String fmt(long ms) {
        if (ms <= 0) return "--:--";
        long s = ms / 1000;
        long m = s / 60;
        long ss = s % 60;
        if (m >= 60) {
            return String.format("%d:%02d:%02d", m / 60, m % 60, ss);
        }
        return String.format("%d:%02d", m, ss);
    }
}
