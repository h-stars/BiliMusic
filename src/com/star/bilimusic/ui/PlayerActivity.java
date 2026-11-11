package com.star.bilimusic.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.star.bilimusic.api.Track;
import com.star.bilimusic.player.PlayService;

/** 播放界面：封面 + 标题 + 进度条 + 控制按钮。 */
public class PlayerActivity extends BaseActivity {

    private ImageView cover;
    private TextView title, artist, cur, dur, playBtn, modeBtn;
    private SeekBar seek;
    private TextView sleepBtn;
    private TextView speedBtn;
    private LoadingSpinner playSpin;
    private final android.os.Handler spinH = new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean spinning = false;

    private BroadcastReceiver rx;
    private boolean dragging = false;
    private final Handler h = new Handler();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        buildUi();
        registerRx();
        refresh();
        startTick();
    }

    private void buildUi() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Colors.BG);
        col.setPadding(dp(24), dp(40), dp(24), dp(24));
        col.setGravity(Gravity.CENTER_HORIZONTAL);

        cover = new ImageView(this);
        int size = getResources().getDisplayMetrics().widthPixels - dp(80);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(size, size);
        clp.topMargin = dp(20);
        cover.setLayoutParams(clp);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setBackgroundColor(Colors.BG_CARD);
        col.addView(cover);

        title = new TextView(this);
        title.setTextColor(Colors.TEXT_PRIMARY);
        title.setTextSize(18);
        title.setGravity(Gravity.CENTER);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(24);
        col.addView(title, tlp);

        artist = new TextView(this);
        artist.setTextColor(Colors.TEXT_SECONDARY);
        artist.setTextSize(13);
        artist.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(6);
        col.addView(artist, alp);

        // 进度
        LinearLayout progRow = new LinearLayout(this);
        progRow.setOrientation(LinearLayout.HORIZONTAL);
        progRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.topMargin = dp(28);
        col.addView(progRow, plp);

        cur = new TextView(this);
        cur.setText("0:00");
        cur.setTextColor(Colors.TEXT_SECONDARY);
        cur.setTextSize(11);
        progRow.addView(cur);

        seek = new SeekBar(this);
        progRow.addView(seek, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        dur = new TextView(this);
        dur.setText("0:00");
        dur.setTextColor(Colors.TEXT_SECONDARY);
        dur.setTextSize(11);
        progRow.addView(dur);

        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) cur.setText(fmt(p));
            }
            @Override public void onStartTrackingTouch(SeekBar s) { dragging = true; }
            @Override public void onStopTrackingTouch(SeekBar s) {
                dragging = false;
                PlayService svc = PlayService.get();
                if (svc != null) svc.seekTo(s.getProgress());
            }
        });

        // 控制按钮
        LinearLayout ctrl = new LinearLayout(this);
        ctrl.setOrientation(LinearLayout.HORIZONTAL);
        ctrl.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ctlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ctlp.topMargin = dp(28);
        col.addView(ctrl, ctlp);

        TextView prev = mkBtn("⏮", 26);
        prev.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                PlayService s = PlayService.get();
                if (s != null) s.prev(); else PlayService.prevFrom(PlayerActivity.this);
                h.postDelayed(refreshRunnable, 300);
            }
        });
        LinearLayout.LayoutParams pvl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pvl.leftMargin = dp(24);
        pvl.rightMargin = dp(24);
        ctrl.addView(prev, pvl);

        playBtn = mkBtn("▶", 40);
        playBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                PlayService s = PlayService.get();
                if (s != null) s.toggle(); else PlayService.toggleFrom(PlayerActivity.this);
                h.postDelayed(refreshRunnable, 200);
            }
        });

        // 播放键 + 加载圈叠放
        android.widget.FrameLayout pbBox = new android.widget.FrameLayout(this);
        int pb = dp(72);
        ctrl.addView(pbBox, new LinearLayout.LayoutParams(pb, pb));
        int pbs = dp(64);
        android.widget.FrameLayout.LayoutParams pbl =
                new android.widget.FrameLayout.LayoutParams(pbs, pbs);
        pbl.gravity = Gravity.CENTER;
        pbBox.addView(playBtn, pbl);

        playSpin = new LoadingSpinner(this);
        int ps = dp(30);
        android.widget.FrameLayout.LayoutParams psp =
                new android.widget.FrameLayout.LayoutParams(ps, ps);
        psp.gravity = Gravity.CENTER;
        pbBox.addView(playSpin, psp);
        playSpin.setVisibility(View.GONE);

        TextView next = mkBtn("⏭", 26);
        next.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                PlayService s = PlayService.get();
                if (s != null) s.next(); else PlayService.nextFrom(PlayerActivity.this);
                h.postDelayed(refreshRunnable, 300);
            }
        });
        LinearLayout.LayoutParams nxl = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nxl.leftMargin = dp(24);
        nxl.rightMargin = dp(24);
        ctrl.addView(next, nxl);

        // 底部三个胶囊按钮横排：模式 / 定时 / 收起
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams chipsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chipsLp.topMargin = dp(20);
        col.addView(chips, chipsLp);

        // 播放模式用文字（列表 / 单曲 / 随机）。
        // 曾经改过符号（↻ ↻¹ ⤨），但符号字形高度和中文字不同，
        // 会把 modeBtn 撑得比旁边三个胶囊高，四个按钮大小不一致；
        // 加上部分机型缺字会显示成方块，所以改回文字。
        // 不用 setTextSize 覆盖：胶囊高度必须统一由 mkChip 的字号决定。
        modeBtn = mkChip("列表", false);
        modeBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                PlayService s = PlayService.get();
                if (s == null) return;
                int m = (s.mode() + 1) % 3;
                s.setMode(m);
                refresh();
                // 符号没有文字那么直白，切换时补一条轻提示说明当前模式
                android.widget.Toast.makeText(PlayerActivity.this,
                        modeLabel(m), android.widget.Toast.LENGTH_SHORT).show();
            }
        });
        chips.addView(modeBtn, chipParams());

        speedBtn = mkChip("1.0x", false);
        speedBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showSpeedPicker();
            }
        });
        chips.addView(speedBtn, chipParams());

        sleepBtn = mkChip("定时", false);
        sleepBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showSleepPicker();
            }
        });
        chips.addView(sleepBtn, chipParams());

        TextView back = mkChip("收起", true);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        chips.addView(back, chipParams());

        setContentView(col);
    }

    /** 底部胶囊按钮。accent 为主色（用于「收起」）。 */
    /**
     * 播放模式的完整名称，用于切换时的 Toast 提示。
     *
     * 胶囊上只有「列表 / 单曲 / 随机」两个字的缩写，
     * 切换时补一条完整说明，用户不用猜「单曲」是单曲循环还是不循环。
     */
    private static String modeLabel(int m) {
        if (m == PlayService.MODE_SINGLE) return "单曲循环";
        if (m == PlayService.MODE_SHUFFLE) return "随机播放";
        return "列表循环";
    }
    private TextView mkChip(String text, boolean accent) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(accent ? Colors.ACCENT : Colors.TEXT_HINT);
        t.setPadding(dp(6), dp(11), dp(6), dp(11));
        t.setBackground(makeChipBg(accent));
        t.setSingleLine(true);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** 圆角背景。带一点描边，深色底上更立体。 */
    private android.graphics.drawable.Drawable makeChipBg(boolean accent) {
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        g.setCornerRadius(dp(20));
        g.setColor(accent ? 0x22FB7299 : Colors.BG_CARD);
        g.setStroke(1, accent ? 0x55FB7299 : Colors.DIVIDER);
        return g;
    }

    private LinearLayout.LayoutParams chipParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(5);
        lp.rightMargin = dp(5);
        return lp;
    }

    private TextView mkBtn(String text, int size) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Colors.TEXT_PRIMARY);
        t.setTextSize(size);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, 0, 0, 0);
        // 清掉主题默认的点击高亮背景（部分 ROM 会给出大块色块），
        // 也让控件不被 padding 撑宽
        t.setBackground(null);
        t.setIncludeFontPadding(false);
        t.setMinWidth(0);
        t.setMinimumWidth(0);
        t.setMinHeight(0);
        t.setMinimumHeight(0);
        return t;
    }

    private void startTick() {
        // 先立即刷一次：否则打开播放页后要等 500ms 才显示位置，
        // 期间进度条停在 0，看起来像"没记忆到上次位置"。
        syncProgress();
        h.postDelayed(new Runnable() {
            @Override public void run() {
                syncProgress();
                h.postDelayed(this, 500);
            }
        }, 500);
    }

    /** 把当前位置同步到进度条和两端的文字。 */
    private void syncProgress() {
        if (dragging) return;
        PlayService s = PlayService.get();
        if (s == null) return;
        int pos = s.position();
        int d = s.duration();
        if (d > 0) {
            seek.setMax(d);
            seek.setProgress(pos);
        }
        cur.setText(fmt(pos));
        dur.setText(fmt(d));
    }

    private final Runnable refreshRunnable = new Runnable() {
        @Override public void run() { refresh(); }
    };

    private void refresh() {
        PlayService s = PlayService.get();
        if (s == null) {
            title.setText("未在播放");
            artist.setText("");
            return;
        }
        Track t = s.track();
        if (t != null) {
            title.setText(t.title);
            String sub = (t.author == null ? "" : t.author);
            if (t.quality != null && !t.quality.isEmpty()) sub += "  ·  " + t.quality;
            artist.setText(sub);
        }
        Bitmap bm = s.cover();
        if (bm != null) cover.setImageBitmap(bm);
        boolean loading = s.isLoading();
        setSpinning(loading);
        if (!loading) {
            playBtn.setText(s.isPlaying() ? "⏸" : "▶");
        }
        // 4 个胶囊并排，文案要短
        int m = s.mode();
        modeBtn.setText(m == 0 ? "列表" : (m == 1 ? "单曲" : "随机"));

        // 倍速按钮显示当前值；非 1.0 时用主题色提示"已改变"
        float sp = s.speed();
        speedBtn.setText(String.format(java.util.Locale.US, "%.1fx", sp));
        speedBtn.setTextColor(Math.abs(sp - 1.0f) < 0.01f
                ? Colors.TEXT_HINT : Colors.ACCENT);

        int remain = PlayService.sleepRemain(this);
        if (remain > 0) {
            int mm = remain / 60, ss = remain % 60;
            // 胶囊空间有限，用 5:30 这种紧凑格式
            sleepBtn.setText(mm > 0
                    ? String.format("%d:%02d", mm, ss)
                    : (ss + "秒"));
            sleepBtn.setTextColor(Colors.ACCENT);
        } else {
            sleepBtn.setText("定时");
            sleepBtn.setTextColor(Colors.TEXT_HINT);
        }
    }

    /** 起停加载转圈。 */
    private void setSpinning(boolean on) {
        if (spinning == on) return;
        spinning = on;
        if (playSpin == null || playBtn == null) return;
        if (on) {
            playBtn.setVisibility(View.INVISIBLE);
            playSpin.setVisibility(View.VISIBLE);
            playSpin.setAngle(0f);
            spinH.post(spinTick);
        } else {
            spinH.removeCallbacks(spinTick);
            playSpin.setVisibility(View.GONE);
            playBtn.setVisibility(View.VISIBLE);
        }
    }

    private final Runnable spinTick = new Runnable() {
        @Override public void run() {
            if (!spinning || playSpin == null) return;
            playSpin.setAngle((playSpin.angle() + 12f) % 360f);
            spinH.postDelayed(this, 33);
        }
    };

    /**
     * 倍速选择：0.5x ~ 2.0x，步进 0.1x，共 16 档。
     * 用滑块而不是列表——数值连续，拖动比逐项点更快。
     */
    private void showSpeedPicker() {
        final PlayService s = PlayService.get();
        if (s == null) return;

        final float[] steps = new float[16];
        final String[] labels = new String[16];
        for (int i = 0; i < 16; i++) {
            steps[i] = 0.5f + i * 0.1f;
            labels[i] = String.format(java.util.Locale.US, "%.1fx", steps[i]);
        }

        // 当前值对应的下标
        int cur = Math.round((s.speed() - 0.5f) / 0.1f);
        if (cur < 0) cur = 0;
        if (cur > 15) cur = 15;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);

        final TextView value = new TextView(this);
        value.setText(labels[cur]);
        value.setTextColor(Colors.ACCENT);
        value.setTextSize(22);
        value.setGravity(Gravity.CENTER);
        value.setPadding(0, dp(12), 0, dp(8));
        box.addView(value);

        final android.widget.SeekBar bar = new android.widget.SeekBar(this);
        bar.setMax(15);
        bar.setProgress(cur);
        box.addView(bar);

        // 两端的刻度提示
        LinearLayout marks = new LinearLayout(this);
        marks.setOrientation(LinearLayout.HORIZONTAL);
        TextView left = new TextView(this);
        left.setText("0.5x");
        left.setTextColor(Colors.TEXT_SECONDARY);
        left.setTextSize(11);
        TextView right = new TextView(this);
        right.setText("2.0x");
        right.setTextColor(Colors.TEXT_SECONDARY);
        right.setTextSize(11);
        right.setGravity(Gravity.END);
        marks.addView(left, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        marks.addView(right, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(marks);

        bar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar b, int p, boolean fromUser) {
                value.setText(labels[p]);
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar b) { }
            @Override public void onStopTrackingTouch(android.widget.SeekBar b) {
                // 松手即时应用，让用户当场听到效果（试听）。
                // 取消 / 返回时会恢复成打开前的倍速。
                PlayService svc = PlayService.get();
                if (svc != null) svc.setSpeed(steps[b.getProgress()]);
            }
        });

        // 打开时的原始倍速：取消 / 返回时用它恢复
        final float original = s.speed();

        final android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setTitle("播放倍速")
                .setView(box)
                .setPositiveButton("确定", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        // 预览值就是最终值，不用再设一次
                        refresh();
                    }
                })
                .setNegativeButton("取消", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        PlayService svc = PlayService.get();
                        if (svc != null) svc.setSpeed(original);
                        refresh();
                    }
                })
                .create();

        // 返回键 / 点外部关闭，等同于取消
        dlg.setOnCancelListener(new android.content.DialogInterface.OnCancelListener() {
            @Override public void onCancel(android.content.DialogInterface d) {
                PlayService svc = PlayService.get();
                if (svc != null) svc.setSpeed(original);
                refresh();
            }
        });

        dlg.show();
    }

    /** 播放页里的定时选择：双滑轮。 */
    private void showSleepPicker() {
        SleepPickerDialog.show(this, new SleepPickerDialog.OnPicked() {
            @Override public void onPicked(int minutes) {
                if (minutes <= 0) {
                    PlayService.cancelSleep(PlayerActivity.this);
                    Toast.makeText(PlayerActivity.this, "已取消定时", Toast.LENGTH_SHORT).show();
                } else {
                    PlayService.scheduleSleep(PlayerActivity.this, minutes);
                    Toast.makeText(PlayerActivity.this,
                            minutes + " 分钟后自动暂停", Toast.LENGTH_SHORT).show();
                }
                refresh();
            }
        });
    }

    private void registerRx() {
        rx = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                refresh();
            }
        };
        IntentFilter f1 = new IntentFilter(PlayService.ACT_STATE);
        IntentFilter f2 = new IntentFilter(PlayService.ACT_ERROR);
        IntentFilter f3 = new IntentFilter(PlayService.ACT_SLEEP_TIMER());
        registerLocal(rx, f1, f2, f3);
    }

    /**
     * 退到后台时把 cid 缓存落盘。
     *
     * 为什么 PlayerActivity 也要写一次：用户可能停在播放页直接锁屏或按 Home，
     * 此时 MainActivity 早已 onStop，它的 onPause 不会再触发，
     * 而 onDestroy 又不保证执行（进程可能被系统直接杀掉）。
     * 锁屏同样会走本回调，所以息屏场景也覆盖到了。
     */
    @Override
    protected void onPause() {
        super.onPause();
        try {
            com.star.bilimusic.util.CidCache.flush();
        } catch (Exception ignored) {}
    }

    @Override
    protected void onDestroy() {
        try { if (rx != null) unregisterReceiver(rx); } catch (Exception ignored) {}
        h.removeCallbacksAndMessages(null);
        spinH.removeCallbacksAndMessages(null);
        try {
            com.star.bilimusic.util.CidCache.flush();
        } catch (Exception ignored) {}
        super.onDestroy();
    }

    private static String fmt(int ms) {
        if (ms <= 0) return "0:00";
        int s = ms / 1000;
        return String.format("%d:%02d", s / 60, s % 60);
    }

}
