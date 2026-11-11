package com.star.bilimusic.player;

import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import com.star.bilimusic.api.Bili;
import com.star.bilimusic.api.Track;
import com.star.bilimusic.util.Prefs;
import com.star.bilimusic.util.Task;
import com.star.bilimusic.util.Timing;
import com.star.bilimusic.media.CoverLoader;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 播放服务：前台服务 + MediaSession + 通知栏控制。
 * 全局单例，界面通过广播与它通信。
 */
public class PlayService extends Service {

    private static final String TAG = "PlayService";

    public static final String ACT_STATE = "com.star.bilimusic.STATE";
    public static final String ACT_PROGRESS = "com.star.bilimusic.PROGRESS";
    public static final String ACT_ERROR = "com.star.bilimusic.ERROR";
    public static final String EXTRA_ERR = "err";
    // 广播字段名集中定义：改名字时编译器能发现问题
    public static final String EXTRA_POS = "pos";
    public static final String EXTRA_DUR = "dur";
    public static final String EXTRA_PLAYING = "playing";
    public static final String EXTRA_LOADING = "loading";
    public static final String EXTRA_INDEX = "index";
    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_REMAIN = "remain";
    public static final String EXTRA_SPEED = "speed";

    // service actions
    private static final String A_PLAY = "a.play";
    private static final String A_PAUSE = "a.pause";
    static final String A_TOGGLE = "a.toggle";
    static final String A_NEXT = "a.next";
    static final String A_PREV = "a.prev";
    private static final String A_STOP = "a.stop";
    private static final String A_SEEK = "a.seek";
    private static final String A_OPEN = "a.open";
    private static final String A_QUEUE = "a.queue";

    private static final String CH_ID = "playback";

    /**
     * 服务单例。
     *
     * 用 volatile 是因为它会被后台线程读取（PlayService.get()、
     * Task.run 里的 PlayService.this 间接引用），而赋值发生在主线程的
     * onCreate。没有 volatile 的话，后台线程可能读到 null 或半构造对象。
     */
    private static volatile PlayService inst;

    private MediaPlayer mp;
    private MediaSession session;
    private AudioManager audio;
    private NotificationManager nm;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final List<Track> queue = new ArrayList<Track>();
    private int index = -1;
    private int mode = MODE_LOOP;         // 0 列表循环 1 单曲 2 随机
    public static final int MODE_LOOP = 0, MODE_SINGLE = 1, MODE_SHUFFLE = 2;

    /**
     * 播放倍速，默认 1.0。
     * 只存内存不持久化：退出 App 后回到 1.0，符合"单次设置"的要求。
     */
    private float speed = 1.0f;

    private boolean playing = false;
    private boolean loading = false;
    private boolean prepared = false;
    private int retry = 0;
    private int pendingSeek = -1;
    private boolean startWhenReady = false;

    private Bitmap coverBitmap;

    /**
     * setDataSource 的时刻，用于测量「播放器准备耗时」。
     *
     * 这段发生在 MediaPlayer 内部（DNS + TCP + TLS + 首字节 + 缓冲），
     * 代码摸不到进度，只能靠 onPrepared 回调反算。
     * 它和取流耗时是串联的两段，加起来才是用户点歌后真正等待的时间。
     */
    private long prepareStartMs = 0;

    /** 取流耗时（毫秒），由 Bili.audioUrl 通过回调写入，用于合并成总耗时。 */
    private int lastFetchMs = 0;

    public static PlayService get() {
        return inst;
    }

    public static boolean alive() {
        return inst != null;
    }

    // ------------------------------------------------------------- lifecycle

    @Override
    public void onCreate() {
        super.onCreate();
        inst = this;
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        initPlayer();
        initSession();
        initSleepTimer();
        notifier = new NotificationHelper(this, notifState);
        // startForeground 必须尽快调用（系统只给 5 秒）
        startForeground(1, notifier.build());
        sleepTimer.restore();
        // 恢复上次选择的播放模式。取不到或值非法时用列表循环兜底。
        int savedMode = Prefs.getPlayMode();
        mode = (savedMode >= MODE_LOOP && savedMode <= MODE_SHUFFLE) ? savedMode : MODE_LOOP;
        restoreQueue();
        Log.i(TAG, "service created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            handle(intent);
        }
        return START_STICKY;
    }

    private void handle(Intent it) {
        String a = it.getAction();
        if (A_TOGGLE.equals(a)) {
            if (playing) pause(); else play();
        } else if (A_PLAY.equals(a)) {
            play();
        } else if (A_PAUSE.equals(a)) {
            pause();
        } else if (A_NEXT.equals(a)) {
            next();
        } else if (A_PREV.equals(a)) {
            prev();
        } else if (A_STOP.equals(a)) {
            stopSelf();
        } else if (A_SEEK.equals(a)) {
            seekTo(it.getIntExtra("pos", 0));
        } else if (A_OPEN.equals(a)) {
            int idx = it.getIntExtra("index", 0);
            openIndex(idx, true);
        } else if (A_QUEUE.equals(a)) {
            if (pendingQueue != null) {
                setQueue(new ArrayList<Track>(pendingQueue), pendingIndex);
                pendingQueue = null;
            }
        }
    }

    @Override
    public void onDestroy() {
        // 退出前落最后一次位置
        if (prepared) {
            try { saveProgress(current(), mp.getCurrentPosition()); } catch (Exception ignored) {}
        }
        inst = null;
        cancelSleepTimer();
        // 进度广播也要停：否则销毁后还会多发一次无用的跨进程广播
        if (progressTick != null) {
            ui.removeCallbacks(progressTick);
            progressTick = null;
        }
        ui.removeCallbacksAndMessages(null);
        releasePlayer();
        if (session != null) {
            session.release();
            session = null;
        }
        if (audio != null) audio.abandonAudioFocus(focusListener);
        // cid 缓存是「攒够 20 条才落盘」的，服务销毁时主动冲一次，
        // 避免刚播过的那几首 cid 白丢（丢了只是下次多查一次接口，但没必要）。
        try {
            com.star.bilimusic.util.CidCache.flush();
        } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------------------------------------------------------------- player

    private void initPlayer() {
        if (mp != null) return;
        mp = new MediaPlayer();
        mp.setAudioStreamType(AudioManager.STREAM_MUSIC);
        mp.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
        mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer m) {
                prepared = true;
                loading = false;
                retry = 0;
                // 准备耗时：setDataSource -> 真正可播。
                // 和取流耗时分开记，最后汇总成一行「点歌到出声」的总时间。
                if (prepareStartMs > 0) {
                    long cost = System.currentTimeMillis() - prepareStartMs;
                    Track pt0 = current();
                    String no = pt0 == null ? "?" : (pt0.seq > 0 ? String.valueOf(pt0.seq) : "?");
                    int total = lastFetchMs + (int) cost;
                    // 用 cont 而不是 log：这一行属于上面那组的续行，
                    // 不再重复打时间戳。整组只有第一行「取流 xxx / 直链复用」带时间。
                    // 不再追加「(直链复用)」后缀：第一行已经写明了，这里重复。
                    Timing.cont("出声 #" + no + "  取流 " + lastFetchMs + "ms + 准备 " + cost
                            + "ms = 总 " + total + "ms");
                    prepareStartMs = 0;
                }
                if (pendingSeek >= 0) {
                    // 重试/切歌时指定的位置优先
                    try { m.seekTo(pendingSeek); } catch (Exception ignored) {}
                    pendingSeek = -1;
                } else {
                    // 否则用上次记住的播放位置
                    Track pt = current();
                    int saved = loadProgress(pt);
                    if (saved > 1000) {
                        int dur = 0;
                        try { dur = m.getDuration(); } catch (Exception ignored) {}
                        // 快播完时不必恢复，直接从头更合理
                        if (dur <= 0 || saved < dur - 3000) {
                            try { m.seekTo(saved); } catch (Exception ignored) {}
                        }
                    }
                }
                // 换源后倍速会重置，每次准备完都重新套用
                applySpeed();
                if (startWhenReady) {
                    doPlay();
                    startWhenReady = false;
                }
                Track t = current();
                if (t != null) {
                    int d = 0;
                    try { d = m.getDuration(); } catch (Exception ignored) {}
                    if (d > 0) t.duration = d;
                }
                broadcastState();
                pushMeta();
                refreshNotify();
                startProgress();
            }
        });
        mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer m) {
                // 定时到点刚暂停：不要再自动切下一首
                if (sleepFired) {
                    sleepFired = false;
                    playing = false;
                    broadcastState();
                    return;
                }
                if (mode == MODE_SINGLE) {
                    try { m.seekTo(0); m.start(); playing = true; broadcastState(); return; }
                    catch (Exception ignored) {}
                }
                playing = false;
                next();
            }
        });
        mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer m, int what, int extra) {
                Log.e(TAG, "error " + what + "/" + extra);
                if (what == -38 || extra == -38) return true;   // 瞬时非法状态
                Track t = current();
                // 告诉 Bili 这条线路可能坏了。
                // 这是「族记忆免探测」的配套保险：免探测之后，只有播放器报错
                // 才能发现记忆已失效。这里通知过去，会清掉族记忆，
                // 下次取流回到逐条探测，自动换到活线路。
                if (t != null && t.streamUrl != null) {
                    try { Bili.markHostFailed(t.streamUrl); } catch (Exception ignored) {}
                }
                if (t != null && plusRetry(t)) return true;
                playing = false;
                loading = false;
                broadcastError("播放失败 (" + what + ")");
                broadcastState();
                ui.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (!playing) next();
                    }
                }, 1200);
                return true;
            }
        });
    }

    /**
     * 播放出错后切换线路重试。
     *
     * 关键：要记住当前播放位置，重新取流后从那里继续，
     * 否则一首 10 小时的白噪音报错一次就从头开始，体验彻底崩坏。
     */
    private boolean plusRetry(Track t) {
        if (retry >= 3) return false;
        retry++;
        int pos = 0;
        try { pos = mp == null ? 0 : mp.getCurrentPosition(); } catch (Exception ignored) {}
        if (pos < 0) pos = 0;
        pendingSeek = pos;
        t.streamUrl = null;   // 强制重新取流（也是刷新过期地址）
        startSource(t);
        return true;
    }

    private void releasePlayer() {
        if (mp != null) {
            try { mp.reset(); mp.release(); } catch (Exception ignored) {}
            mp = null;
        }
        prepared = false;
        playing = false;
        loading = false;
    }

    // ---------------------------------------------------------------- session

    private void initSession() {
        if (session != null) return;
        session = new MediaSession(this, "dshbili");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { play(); }
            @Override public void onPause() { pause(); }
            @Override public void onSkipToNext() { next(); }
            @Override public void onSkipToPrevious() { prev(); }
            @Override public void onSeekTo(long pos) { seekTo((int) pos); }
            @Override public void onStop() { pause(); }
        });
        session.setActive(true);
    }

    private void pushMeta() {
        Track t = current();
        if (t == null || session == null || notifier == null) return;
        notifier.pushMeta(session, t, coverBitmap);
    }

    private void pushPlaybackState() {
        if (session == null) return;
        int st;
        if (loading) st = PlaybackState.STATE_BUFFERING;
        else if (playing) st = PlaybackState.STATE_PLAYING;
        else st = PlaybackState.STATE_PAUSED;
        long pos = 0;
        try { pos = mp == null ? 0 : mp.getCurrentPosition(); } catch (Exception ignored) {}
        // 播放速率必须带上倍速：PlaybackState 的第三个参数是「位置每秒前进多少」，
        // 写死 1.0f 时，1.5x 播放会让系统推算的位置持续落后于真实位置，
        // 通知栏进度条就会越走越偏。
        float rate = playing ? speed : 0f;
        PlaybackState.Builder b = new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SKIP_TO_NEXT
                        | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO)
                .setState(st, pos, rate);
        session.setPlaybackState(b.build());
    }

    // ------------------------------------------------------------------ load

    private Track current() {
        if (index < 0 || index >= queue.size()) return null;
        return queue.get(index);
    }

    public void openIndex(int idx, boolean autoplay) {
        if (idx < 0 || idx >= queue.size()) return;
        // 注意：这里不保存上一首的进度。
        // 切歌时 pendingSeek 固定为 0，切回来一定从头播，那条记录永远不会被读，
        // 存了只会让 SharedPreferences 无限增长。
        index = idx;
        Track t = queue.get(index);
        retry = 0;
        prepared = false;
        // 任何主动切歌都解除定时抑制，否则标志会残留到下一首
        sleepFired = false;
        // 主动切歌一律从头播：用 0 而不是 -1，
        // -1 的含义是"没指定，去读上次记录"，那是恢复场景才用的。
        pendingSeek = 0;
        startWhenReady = autoplay;
        saveCurrentTrack(t);
        startSource(t);
        broadcastState();
        pushMeta();
        refreshNotify();
        loadCover(t);
    }

    private void startSource(final Track t) {
        initPlayer();
        try { mp.reset(); } catch (Exception e) { releasePlayer(); initPlayer(); }
        prepared = false;
        playing = false;
        loading = true;
        broadcastState();
        refreshNotify();

        // 直链还新鲜时不必再走后台线程取流：
        // audioUrl 是一次网络往返（实测 300~700ms），而 fresh() 命中时
        // 我们手里已经有可用地址，多切两次线程纯属浪费。
        // 直接在主线程 setDataSource 即可，省掉「后台取流 -> ui.post 回来」
        // 这一来一回，用户感知的快慢主要就差在这里。
        if (t.fresh()) {
            try {
                Map<String, String> h = Bili.get(PlayService.this).mediaHeaders(t.bvid, t.page);
                prepareStartMs = System.currentTimeMillis();

                lastFetchMs = 0;          // 直链复用：取流耗时为 0
                mp.setDataSource(PlayService.this, Uri.parse(t.streamUrl), h);
                mp.prepareAsync();
                // 这条路径不经过 audioUrl，日志里原本什么都没有。
                // 补一行让「没走网络」这件事也可见。
                Bili.logReuse(t);
                return;
            } catch (Exception e) {
                // 直链可能已失效，退回正常取流路径重试一次
                Log.w(TAG, "fresh url failed, refetch", e);
            }
        }

        Task.run(new Runnable() {
            @Override public void run() {
                String url = null;
                String err = null;
                long t0 = System.currentTimeMillis();
                try {
                    url = Bili.get(PlayService.this).audioUrl(t);
                } catch (Exception e) {
                    err = e.getMessage();
                }
                final String fUrl = url;
                final String fErr = err;
                final int fFetchMs = (int) (System.currentTimeMillis() - t0);
                ui.post(new Runnable() {
                    @Override public void run() {
                        if (current() != t) return;
                        if (fUrl == null || fUrl.isEmpty()) {
                            loading = false;
                            playing = false;
                            broadcastError(fErr == null ? "取流失败" : fErr);
                            broadcastState();
                            refreshNotify();
                            return;
                        }
                        try {
                            Map<String, String> h = Bili.get(PlayService.this)
                                    .mediaHeaders(t.bvid, t.page);
                            lastFetchMs = fFetchMs;     // 记下取流耗时，onPrepared 里汇总
                            prepareStartMs = System.currentTimeMillis();

                            mp.setDataSource(PlayService.this, Uri.parse(fUrl), h);
                            mp.prepareAsync();
                        } catch (Exception e) {
                            loading = false;
                            playing = false;
                            broadcastError(e.getMessage() == null ? "播放器异常" : e.getMessage());
                            broadcastState();
                        }
                    }
                });
            }
        });
    }

    private void loadCover(final Track t) {
        coverBitmap = null;
        if (t.cover == null || t.cover.isEmpty()) return;

        // 播放页封面是整屏大图，必须按屏幕宽度解码；
        // 用列表那张 200px 缩略图放大会明显发虚。
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int target = Math.max(screenW, CoverLoader.SIZE_LARGE);

        CoverLoader.get(this).load(t.cover, target,
                new CoverLoader.Callback() {
                    @Override public void onLoaded(String url, Bitmap bm) {
                        if (bm == null) return;
                        if (current() == t) {
                            coverBitmap = bm;
                            pushMeta();
                            refreshNotify();
                        }
                    }
                });
    }

    // ------------------------------------------------------------- transport

    private final AudioManager.OnAudioFocusChangeListener focusListener =
            new AudioManager.OnAudioFocusChangeListener() {
        @Override public void onAudioFocusChange(int f) {
            if (f == AudioManager.AUDIOFOCUS_LOSS || f == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                pause();
            }
        }
    };

    public void play() {
        sleepFired = false;   // 用户主动播放，解除抑制
        Track t = current();
        if (t == null && !queue.isEmpty()) {
            openIndex(0, true);
            return;
        }
        if (t == null) return;
        if (!prepared) {
            startWhenReady = true;
            if (!loading) startSource(t);
            return;
        }
        int r = audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN);
        if (r != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.w(TAG, "focus denied");
        }
        doPlay();
    }

    private void doPlay() {
        try {
            mp.start();
            // 部分机型 pause 后再 start 会丢掉倍速，这里补一次
            applySpeed();
            playing = true;
            loading = false;
            broadcastState();
            pushPlaybackState();
            refreshNotify();
            startProgress();
        } catch (Exception e) {
            Log.e(TAG, "start", e);
        }
    }

    public void pause() {
        try {
            if (mp != null && prepared) mp.pause();
        } catch (Exception ignored) {}
        // 暂停时记下位置，下次打开能接着听
        if (prepared) {
            try { saveProgress(current(), mp.getCurrentPosition()); } catch (Exception ignored) {}
        }
        playing = false;
        broadcastState();
        pushPlaybackState();
        refreshNotify();
    }

    public void toggle() {
        if (loading && startWhenReady) { pause(); return; }
        if (playing) pause(); else play();
    }

    public void next() {
        if (queue.isEmpty()) return;

        int n;
        if (mode == MODE_SHUFFLE && queue.size() > 1) {
            do { n = (int) (Math.random() * queue.size()); } while (n == index);
        } else {
            n = (index + 1) % queue.size();
        }
        openIndex(n, true);
    }


    public void prev() {
        if (queue.isEmpty()) return;
        int n;
        if (mode == MODE_SHUFFLE && queue.size() > 1) {
            // 必须排除「随机到自己」，否则点上一首有 1/queue.size() 的概率
            // 落在当前曲目上，表现成「点了没反应但重新加载了一遍」。
            // next() 一直是这么写的，prev() 之前漏了。
            do { n = (int) (Math.random() * queue.size()); } while (n == index);
        } else {
            n = (index - 1 + queue.size()) % queue.size();
        }
        openIndex(n, true);
    }

    /** 当前倍速。 */
    public float speed() {
        return speed;
    }

    /**
     * 设置播放倍速，范围 0.5~2.0。
     * 立即作用于当前曲目；后续每首歌起播时也会自动套用。
     */
    public void setSpeed(float v) {
        if (v < 0.5f) v = 0.5f;
        if (v > 2.0f) v = 2.0f;
        speed = v;
        applySpeed();
        broadcastState();
    }

    /**
     * 把当前倍速应用到 MediaPlayer。
     *
     * PlaybackParams 只能在 prepare 之后设置，且 setDataSource 换源后会失效，
     * 所以每次起播都要重新套用一次。
     */
    private void applySpeed() {
        if (mp == null || !prepared) return;
        try {
            android.media.PlaybackParams pp = mp.getPlaybackParams();
            pp.setSpeed(speed);
            // 固定音高为 1.0，只改变速度不改音调。
            // 不设的话，1.5x 会像"快放"一样变尖。
            pp.setPitch(1.0f);
            mp.setPlaybackParams(pp);
        } catch (Exception e) {
            Log.w(TAG, "applySpeed failed", e);
        }
    }

    public void seekTo(int ms) {
        if (mp == null) return;
        if (!prepared) { pendingSeek = ms; return; }
        try {
            mp.seekTo(ms);
            // 立刻把新位置推给 MediaSession 和界面，避免通知栏先按旧值渲染一帧
            pushPlaybackState();
            broadcastProgress();
        } catch (Exception ignored) {}
    }

    public void setMode(int m) {
        // 只接受 0/1/2，防止脏值导致后续取模出错
        if (m < MODE_LOOP || m > MODE_SHUFFLE) return;
        mode = m;
        Prefs.setPlayMode(m);
        broadcastState();
    }

    // ----------------------------------------------------------------- state

    /**
     * 只替换队列内容，不重新起播。
     *
     * 用于「播放页打开时补全队列」这类场景：
     * 当前歌曲继续播放，只是让后续切歌有完整的列表可用。
     *
     * @return 当前曲目在新列表中的位置，找不到返回 -1
     */
    public int replaceQueueKeepPlaying(List<Track> list) {
        Track cur = current();
        if (cur == null || list == null || list.isEmpty()) return -1;

        int idx = -1;
        for (int i = 0; i < list.size(); i++) {
            if (cur.key().equals(list.get(i).key())) {
                idx = i;
                break;
            }
        }
        if (idx < 0) return -1;

        queue.clear();
        queue.addAll(list);
        index = idx;
        broadcastState();
        return idx;
    }

    public void setQueue(List<Track> list, int startIdx) {
        queue.clear();
        queue.addAll(list);
        openIndex(startIdx, true);
    }

    public List<Track> queue() {
        return queue;
    }

    public int index() {
        return index;
    }

    public int mode() {
        return mode;
    }

    public boolean isPlaying() {
        return playing;
    }

    public boolean isLoading() {
        return loading;
    }

    /**
     * 当前播放位置。
     *
     * 未就绪时不能直接返回 0：服务重启后曲目是"暂停在记录的进度"的，
     * 此时播放器还没 prepare，界面进度条若拿到 0 会显示在开头，
     * 与"点播放会从上次位置继续"不一致。这里用待恢复位置兜底。
     */
    public int position() {
        if (mp == null || !prepared) {
            // pendingSeek >= 0 表示已明确指定位置：
            //   0   = 主动切歌，从头播（这里必须返回 0，不能去读旧进度）
            //   >0  = 重试续播 / 用户拖动
            if (pendingSeek >= 0) return pendingSeek;
            // 未指定：服务重启后恢复的场景，返回记录的进度
            Track t = current();
            int saved = loadProgress(t);
            return saved > 0 ? saved : 0;
        }
        try { return mp.getCurrentPosition(); }
        catch (Exception e) { return 0; }
    }

    public int duration() {
        Track t = current();
        if (t != null && t.duration > 0) return (int) t.duration;
        try { return mp == null || !prepared ? 0 : mp.getDuration(); }
        catch (Exception e) { return 0; }
    }

    public Track track() {
        return current();
    }

    public Bitmap cover() {
        return coverBitmap;
    }

    // ---------------------------------------------------------------- events

    // ---------------------------------------------------------------- 定时暂停

    private SleepTimer sleepTimer;
    /**
     * 定时暂停刚触发时为 true。
     * 用于抑制「定时到点」与「歌曲播完自动切歌」撞车：
     * 否则定时 pause() 之后 onCompletion 的 next() 又会把下一首播起来。
     */
    private boolean sleepFired = false;
    private NotificationHelper notifier;

    /** 向通知助手暴露当前播放状态。 */
    private final NotificationHelper.State notifState = new NotificationHelper.State() {
        @Override public Track track() { return current(); }
        @Override public boolean playing() { return playing; }
        @Override public boolean loading() { return loading; }
        @Override public Bitmap cover() { return coverBitmap; }
        @Override public MediaSession session() { return session; }
    };
    static final String ACT_SLEEP = "com.star.bilimusic.SLEEP";

    /**
     * 服务重建时恢复上次的曲目（单曲队列），让界面底部能显示"正在播放"。
     * 不自动起播——用户回来时应该是暂停状态，点一下才播。
     */
    private void restoreQueue() {
        if (!queue.isEmpty()) return;
        Track t = loadCurrentTrack();
        if (t == null) return;

        // 先放单曲，保证界面立刻能显示。
        // seq 赋 1：单曲队列里它就是第 1 首，日志里不会显示成「#?」。
        // 之后 MainActivity 恢复完整列表时会重新按顺序赋号。
        t.seq = 1;
        queue.add(t);
        index = 0;

        broadcastState();
        pushMeta();
        refreshNotify();
        loadCover(t);
    }

    /** 服务初始化定时器（到点时暂停并提示）。 */
    private void initSleepTimer() {
        sleepTimer = new SleepTimer(this, new SleepTimer.OnFire() {
            @Override public void onFire() {
                // 先抑制自动切歌，再暂停，避免 onCompletion 把下一首播起来。
                // 顺序很重要：若先 pause 再设标志，中间可能被 onCompletion 插进来。
                sleepFired = true;
                pause();
                // 暂停后再确认一次（防止 loading 中的曲目刚 prepared 就自动播）
                if (playing) {
                    try { if (mp != null && prepared) mp.pause(); } catch (Exception ignored) {}
                    playing = false;
                    broadcastState();
                }
                // 若此刻正好在取流/准备中，取消"准备好就自动播"的意图
                startWhenReady = false;
                broadcastError("定时时间到，已暂停播放");
            }
        });
    }

    /** 设置定时暂停。minutes <= 0 表示取消。最短 1 分钟。 */
    public void setSleepTimer(int minutes) {
        if (sleepTimer != null) sleepTimer.set(minutes);
    }

    public void cancelSleepTimer() {
        if (sleepTimer != null) sleepTimer.cancel();
    }

    /** 距离定时暂停还有多少秒，未设置返回 -1。 */
    public int sleepRemainSeconds() {
        return sleepTimer == null ? -1 : sleepTimer.remainSeconds();
    }


    public static String ACT_SLEEP_TIMER() {
        return ACT_SLEEP;
    }

    // ------------------------------------------------------- 播放进度记忆

    /** 记录某首歌的播放位置（毫秒）。 */
    private void saveProgress(Track t, int ms) {
        if (t == null || t.bvid == null) return;
        if (ms < 0) ms = 0;
        Prefs.setProgress(t.key(), ms);
    }

    /** 记住当前播放的曲目信息，供服务重启后恢复。 */
    private void saveCurrentTrack(Track t) {
        if (t == null) return;
        Prefs.saveCurrentTrack(
                t.bvid, t.cid, t.aid, t.page,
                t.title, t.author, t.cover, t.duration);
    }

    /** 恢复上次播放的曲目，没有则返回 null。 */
    private Track loadCurrentTrack() {
        String bvid = Prefs.getCurBvid();
        if (bvid == null || bvid.isEmpty()) return null;
        android.content.SharedPreferences p = Prefs.player();
        if (p == null) return null;
        Track t = new Track();
        t.bvid = bvid;
        t.cid = p.getLong("cur_cid", 0);
        t.aid = p.getLong("cur_aid", 0);
        t.page = p.getInt("cur_page", 1);
        t.title = p.getString("cur_title", "");
        t.author = p.getString("cur_author", "");
        t.cover = p.getString("cur_cover", "");
        t.duration = p.getLong("cur_duration", 0);
        return t;
    }

    /** 读取记录的位置，仅当记录的就是这首歌时才返回。 */
    private int loadProgress(Track t) {
        if (t == null || t.bvid == null) return -1;
        String key = t.key();
        if (key == null || !key.equals(Prefs.getProgressKey())) {
            return -1;   // 记录的是别的歌
        }
        return Prefs.getProgress();
    }

    private Runnable progressTick;

    private void startProgress() {
        if (progressTick != null) ui.removeCallbacks(progressTick);
        progressTick = new Runnable() {
            private int tick = 0;
            @Override public void run() {
                if (playing) {
                    broadcastProgress();
                    // 每 5 秒落一次盘，防止进程被杀时丢失位置
                    if (++tick % 10 == 0) {
                        try { saveProgress(current(), mp.getCurrentPosition()); }
                        catch (Exception ignored) {}
                    }
                    ui.postDelayed(this, 500);
                }
            }
        };
        ui.postDelayed(progressTick, 500);
    }

    private void broadcastProgress() {
        Intent i = new Intent(ACT_PROGRESS);
        i.setPackage(getPackageName());
        int pos = position();
        i.putExtra(EXTRA_POS, pos);
        i.putExtra(EXTRA_DUR, duration());
        sendBroadcast(i);

        // 同步给 MediaSession。
        //
        // 系统通知栏的进度条读的是 PlaybackState 里的位置，
        // 之前只在「状态变化」时 push 一次，播放中从不更新，
        // 于是拖动进度条后 PlaybackState 还是那个陈旧的位置，
        // 系统下一帧就按旧值把滑块拉回去了——表现成「拖了又弹回」。
        // 这里跟着 500ms 的进度广播一起刷新，滑块就能稳定停在拖到的位置。
        pushPlaybackState();
    }

    private void broadcastState() {
        Intent i = new Intent(ACT_STATE);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_PLAYING, playing);
        i.putExtra(EXTRA_LOADING, loading);
        i.putExtra(EXTRA_INDEX, index);
        i.putExtra(EXTRA_MODE, mode);
        i.putExtra(EXTRA_SPEED, speed);
        sendBroadcast(i);
        pushPlaybackState();
    }

    private void broadcastError(String msg) {
        Intent i = new Intent(ACT_ERROR);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_ERR, msg);
        sendBroadcast(i);
    }

    // ------------------------------------------------------------ notification

    private void refreshNotify() {
        if (notifier != null) notifier.refresh();
    }

    // ------------------------------------------------------- 定时（静态入口）


    /**
     * 设置定时暂停。
     *
     * 关键：不依赖服务是否已启动。用户可能在还没播歌时就设定时，
     * 此时 inst 为 null，但设置必须生效——所以先落盘，再确保服务起来。
     */
    public static void scheduleSleep(Context c, int minutes) {
        PlayService s = inst;
        if (s != null) {
            // 服务活着：由它统一写入 prefs 并调度，避免两处各算一次时间
            if (minutes <= 0) s.cancelSleepTimer();
            else s.setSleepTimer(minutes);
            return;
        }
        // 服务未启动：先落盘，再拉起服务（onCreate 会读到并恢复）
        if (minutes <= 0) {
            Prefs.setSleepAt(0);
            return;
        }
        if (minutes < 1) minutes = 1;
        Prefs.setSleepAt(
                System.currentTimeMillis() + minutes * 60L * 1000L);
        // 服务没起来：启动它，onCreate 会 restoreSleepTimer
        Intent i = new Intent(c, PlayService.class);
        i.setAction(A_QUEUE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            c.startForegroundService(i);
        } else {
            c.startService(i);
        }
    }

    /**
     * 确保服务已启动（不播放，只为恢复上次曲目状态）。
     * 界面 onResume 时调用，让底部能显示"正在播放"。
     */
    public static void ensureAlive(Context c) {
        if (inst != null) return;
        // 没有上次记录就没必要拉起服务
        Prefs.init(c);
        if (Prefs.getCurBvid().isEmpty()) return;
        Intent i = new Intent(c, PlayService.class);
        i.setAction(A_QUEUE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            c.startForegroundService(i);
        } else {
            c.startService(i);
        }
    }

    /** 读取定时剩余秒数，未设置或不依赖服务实例。返回 -1 表示未设置。 */
    public static int sleepRemain(Context c) {
        PlayService s = inst;
        if (s != null) return s.sleepRemainSeconds();
        long at = Prefs.getSleepAt();
        if (at <= 0) return -1;
        long d = (at - System.currentTimeMillis()) / 1000;
        return d <= 0 ? 0 : (int) d;
    }

    /** 取消定时（静态）。 */
    public static void cancelSleep(Context c) {
        scheduleSleep(c, 0);
    }

    // ------------------------------------------------------------ static api

    /** 待播放队列（由界面设置，服务启动时消费）。 */
    private static List<Track> pendingQueue;
    private static int pendingIndex;

    /** 界面调用：设置队列并开始播放。 */
    public static void playQueue(Context c, List<Track> list, int idx) {
        pendingQueue = new ArrayList<Track>(list);
        pendingIndex = idx;
        PlayService s = inst;
        if (s != null) {
            s.setQueue(new ArrayList<Track>(list), idx);
            pendingQueue = null;
        } else {
            Intent i = new Intent(c, PlayService.class);
            i.setAction(A_QUEUE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                c.startForegroundService(i);
            } else {
                c.startService(i);
            }
        }
    }

    /**
     * 彻底停止播放并清空状态。
     * 用于退出登录：不应该再保留"正在播放某首歌"的任何痕迹。
     */
    public static void shutdown(Context c) {
        PlayService s = inst;
        if (s != null) {
            s.pause();
            s.queue.clear();
            s.index = -1;
            s.prepared = false;
            s.loading = false;
            s.speed = 1.0f;
            s.coverBitmap = null;
            com.star.bilimusic.util.Prefs.clearCurrentTrack();
            s.broadcastState();
            s.stopSelf();
        } else {
            // 服务没起，也要把持久化的曲目清掉
            com.star.bilimusic.util.Prefs.clearCurrentTrack();
        }
    }

    public static void sendAction(Context c, String action) {
        Intent i = new Intent(c, PlayService.class).setAction(action);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            c.startForegroundService(i);
        } else {
            c.startService(i);
        }
    }

    public static void toggleFrom(Context c) {
        sendAction(c, A_TOGGLE);
    }

    public static void nextFrom(Context c) {
        sendAction(c, A_NEXT);
    }

    public static void prevFrom(Context c) {
        sendAction(c, A_PREV);
    }
}
