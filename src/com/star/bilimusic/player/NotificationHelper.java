package com.star.bilimusic.player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.os.Build;

import com.star.bilimusic.R;
import com.star.bilimusic.api.Track;

/**
 * 通知栏与 MediaSession 的构建。
 *
 * 从 PlayService 抽出来：服务只负责告诉它「现在是什么状态」，
 * 怎么拼通知、怎么同步 MediaMetadata 由这里处理。
 */
public final class NotificationHelper {

    private static final String CH_ID = "playback";
    private static final int NOTI_ID = 1;

    /** 服务向这里提供当前播放状态。 */
    public interface State {
        Track track();
        boolean playing();
        boolean loading();
        Bitmap cover();
        MediaSession session();
    }

    private final Context ctx;
    private final NotificationManager nm;
    private final State state;

    public NotificationHelper(Context c, State s) {
        this.ctx = c.getApplicationContext();
        this.nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        this.state = s;
        createChannel();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CH_ID, "播放控制", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(ch);
        }
    }

    /** 构建通知（也用于 startForeground）。 */
    public Notification build() {
        Track t = state.track();
        String title = t == null ? "未在播放" : t.title;
        String sub = t == null ? "" : t.author;

        // 用启动器 intent 打开 App，避免直接依赖 ui 包里的具体 Activity。
        // 这样换主界面时这里不用改，player 层也不必反向依赖 ui 层。
        Intent launch = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
        PendingIntent open = PendingIntent.getActivity(ctx, 0,
                launch == null ? new Intent() : launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent pp = PendingIntent.getService(ctx, 1,
                new Intent(ctx, PlayService.class).setAction(PlayService.A_TOGGLE),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent nx = PendingIntent.getService(ctx, 2,
                new Intent(ctx, PlayService.class).setAction(PlayService.A_NEXT),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent pv = PendingIntent.getService(ctx, 3,
                new Intent(ctx, PlayService.class).setAction(PlayService.A_PREV),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(ctx, CH_ID)
                : new Notification.Builder(ctx);

        b.setContentTitle(title)
                .setContentText(sub)
                .setSmallIcon(R.drawable.ic_music)
                .setContentIntent(open)
                .setOngoing(state.playing())
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(0, state.playing() ? "暂停" : "播放", pp)
                .addAction(0, "下一首", nx)
                .addAction(0, "上一首", pv);

        Bitmap cover = state.cover();
        if (cover != null) b.setLargeIcon(cover);

        Notification.MediaStyle style = new Notification.MediaStyle();
        MediaSession s = state.session();
        if (s != null) style.setMediaSession(s.getSessionToken());
        style.setShowActionsInCompactView(0, 1, 2);
        b.setStyle(style);

        return b.build();
    }

    /** 刷新通知栏。 */
    public void refresh() {
        try {
            nm.notify(NOTI_ID, build());
        } catch (Exception ignored) {
        }
    }

    /** MediaSession 元数据同步。 */
    public void pushMeta(MediaSession session, Track t, Bitmap cover) {
        if (t == null || session == null) return;
        MediaMetadata.Builder b = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, t.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, t.author)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, t.duration);
        if (cover != null) {
            b.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, cover);
        }
        session.setMetadata(b.build());
    }
}
