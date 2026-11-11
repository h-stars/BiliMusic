package com.star.bilimusic.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.star.bilimusic.api.Bili;
import com.star.bilimusic.api.Track;
import com.star.bilimusic.player.PlayService;
import com.star.bilimusic.util.Timing;

/**
 * 设置弹窗。
 *
 * 从 MainActivity 抽出来：它只是一组「点进去做某件事」的入口，
 * 与收藏夹列表本身没有耦合。需要宿主配合的三件事（刷新列表、
 * 弹提示、读版本号）通过 {@link Host} 回调，不直接依赖 Activity 内部状态。
 */
final class SettingsDialog {

    /** 宿主需要提供的配合。 */
    interface Host {
        /** 隐藏的收藏夹变化后，重新拉一次列表。 */
        /** 刷新收藏夹列表（顺带清空合集，恢复隐藏后两边都可能需要更新）。 */
        void reloadFolders();

        /** 刷新合集列表。 */
        void reloadSeasons();

        /**
         * 按 id 查歌单名字，用于「恢复隐藏」的多选列表。
         *
         * 隐藏集合里只存 id，直接列 id 用户看不懂是哪个歌单；
         * 名字由宿主从已加载的列表里翻，翻不到时返回 null，
         * 界面会退化成显示 id。
         *
         * @param season true=查合集，false=查收藏夹
         */
        String titleOf(long id, boolean season);
        /** 弹一条短提示。 */
        void toast(String msg);
        /** 版本号，显示在弹窗顶部。 */
        String appVersion();
    }

    /** 当前打开的设置弹窗。从别的页面返回时需要重建它以刷新内容。 */
    private static SettingsDialog current;

    private final Activity act;
    /** 一级设置弹窗的引用：二级流程需要主动关掉它。 */
    private AlertDialog mainDialog;
    /** 打开时的登录状态，用于判断返回时是否需要重建。 */
    private boolean loggedWhenShown;
    private final Host host;
    /** 一级设置里「音质」那一行的视图，用于切换后立即刷新文字。 */
    private TextView qualityRow;

    // ---- 菜单项索引（与 show() 里的 items 数组一一对应）----
    // 集中定义：插入新项时只改这里，不用在 handleSetting 里数数字。
    private static final int IDX_QUALITY = 0;
    private static final int IDX_SLEEP = 1;
    private static final int IDX_HIDDEN = 2;
    private static final int IDX_HIDDEN_SEASON = 3;
    private static final int IDX_TIMING = 4;
    private static final int IDX_VIEW_LOG = 5;
    private static final int IDX_CACHE = 6;
    private static final int IDX_ACCOUNT = 7;
    private static final int IDX_LOGOUT = 8;

    SettingsDialog(Activity act, Host host) {
        this.act = act;
        this.host = host;
    }

    void show() {
        final Bili bili = Bili.get(act);
        final boolean logged = bili.isLoggedIn();

        // 自定义视图：顶部作者/版本，中间功能项。
        // 不用 setMessage + setItems —— 同时设这两个时 message 会把列表顶掉。
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);

        TextView head = new TextView(act);
        head.setText("作者：star　版本：" + host.appVersion());
        head.setTextColor(Colors.TEXT_SECONDARY);
        head.setTextSize(12);
        head.setPadding(dp(20), dp(16), dp(20), dp(10));
        box.addView(head);

        int remain = PlayService.sleepRemain(act);
        String sleepLabel = remain > 0
                ? "定时暂停：剩余 " + fmtRemain(remain) + "（点击修改）"
                : "定时暂停：未设置";

        String[] items = new String[]{
                "音质：" + Bili.qualityLabel(bili.quality()),
                sleepLabel,
                "恢复隐藏的收藏夹",
                "恢复隐藏的合集",
                "诊断日志：" + (Timing.enabled() ? "已开启" : "已关闭") + "（点按切换）",
                "查看耗时日志",
                "缓存管理",
                logged ? "账号（已登录 " + bili.myMid() + "）" : "登录",
                logged ? "退出登录" : ""
        };

        final AlertDialog[] holder = new AlertDialog[1];

        for (int i = 0; i < items.length; i++) {
            if (items[i].isEmpty()) continue;
            final int idx = i;
            final TextView row = new TextView(act);
            row.setText(items[i]);
            // 记住音质那一行：切换音质后要立刻刷新文字，
            // 否则用户看到的是旧值，必须关掉重开才变（实际已生效）。
            if (i == 0) qualityRow = row;
            // 只有「退出登录」用红色。
            // 注意：这个索引必须跟着 items 数组里的位置改，
            // 插入新菜单项时要同步更新，否则会标错行。
            row.setTextColor(i == IDX_LOGOUT ? Colors.DANGER : Colors.TEXT_PRIMARY);
            row.setTextSize(15);
            row.setPadding(dp(20), dp(14), dp(20), dp(14));
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    handleSetting(idx, logged, bili);
                }
            });
            box.addView(row);

            View line = new View(act);
            line.setBackgroundColor(Colors.DIVIDER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1);
            lp.leftMargin = dp(20);
            lp.rightMargin = dp(20);
            box.addView(line, lp);
        }

        mainDialog = new AlertDialog.Builder(act)
                .setView(box)
                .setNegativeButton("关闭", null)
                .create();
        loggedWhenShown = bili.isLoggedIn();
        mainDialog.show();
        current = this;
    }

    private void handleSetting(int which, boolean logged, Bili bili) {
        // 打开「二级窗口」的项不关闭设置本身，返回时才能回到设置列表。
        // 只有直接生效的项（诊断日志开关、跳转登录）才关掉设置。
        switch (which) {
            case IDX_QUALITY:
                pickQuality();          // 二级窗口
                return;
            case IDX_SLEEP:
                pickSleepTimer();       // 二级窗口
                return;
            case IDX_HIDDEN:
                restoreHiddenFolders(); // 二级窗口
                return;
            case IDX_HIDDEN_SEASON:
                restoreHiddenSeasons(); // 二级窗口
                return;
            case IDX_TIMING:
                boolean now = !Timing.enabled();
                Timing.setEnabled(now);
                host.toast(now ? "诊断日志已开启" : "诊断日志已关闭");
                break;
            case IDX_VIEW_LOG:
                showTimingLog();        // 二级窗口
                return;
            case IDX_CACHE:
                // 缓存管理是独立页面（要展示多个文件的路径和大小，
                // 用弹窗放不下）。同样不关设置：返回时还能回到设置列表。
                act.startActivity(new Intent(act, CacheManagerActivity.class));
                return;
            case IDX_ACCOUNT:
                // 不关闭设置：从登录页返回时，用户应该回到设置列表而不是主界面。
                // AlertDialog 挂在 Activity 的 window 上，startActivity 后会一直保留，
                // 返回时自然还在。
                act.startActivity(new Intent(act, LoginActivity.class));
                return;
            case IDX_LOGOUT:
                if (logged) {
                    confirmLogout(bili); // 二级窗口
                    return;
                }
                break;

            default:
                break;
        }
        dismissMain();
    }

    /** 关掉一级设置弹窗（离开设置时用）。 */
    private void dismissMain() {
        if (mainDialog != null && mainDialog.isShowing()) {
            mainDialog.dismiss();
        }
        mainDialog = null;
        if (current == this) current = null;
    }

    /**
     * 从别的页面返回时调用：内容变了（比如刚登录），重建一次弹窗。
     * 没有打开的设置窗口时什么也不做。
     */
    static void refreshIfShowing(Activity act) {
        SettingsDialog d = current;
        if (d == null || d.mainDialog == null || !d.mainDialog.isShowing()) {
            current = null;
            return;
        }
        if (d.act != act) return;

        // 只有"登录状态"变了才重建，否则从播放页返回也会重建一次，弹窗会闪。
        boolean loggedNow = Bili.get(act).isLoggedIn();
        if (loggedNow == d.loggedWhenShown) return;

        d.dismissMain();
        new SettingsDialog(act, d.host).show();
    }

    // ------------------------------------------------------------ 各项设置

    /** 音质三档选择。 */
    private void pickQuality() {
        final Bili bili = Bili.get(act);
        final int cur = bili.quality();
        final String[] labels = new String[]{
                Bili.qualityLabel(Bili.Q_LOW),
                Bili.qualityLabel(Bili.Q_MID),
                Bili.qualityLabel(Bili.Q_HIGH)
        };
        new AlertDialog.Builder(act)
                .setTitle("播放音质")
                .setSingleChoiceItems(labels, cur, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        bili.setQuality(which);
                        d.dismiss();
                        // 清掉队列里已缓存的直链，让当前播放的歌也按新音质重新取流
                        PlayService svc = PlayService.get();
                        if (svc != null) {
                            for (Track t : svc.queue()) {
                                t.streamUrl = null;
                                t.streamAt = 0;
                            }
                        }
                        host.toast("音质已切换：" + Bili.qualityLabel(which) + "，下一首起生效");
                        // 立刻刷新设置页那一行的文字。
                        // 之前只写了 SharedPreferences，界面还显示旧值，
                        // 用户必须关掉设置重开才能看到变化（实际早已生效）。
                        if (qualityRow != null) {
                            qualityRow.setText("音质：" + Bili.qualityLabel(which));
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 定时暂停：双滑轮选择小时 + 分钟（最少 1 分钟）。 */
    private void pickSleepTimer() {
        SleepPickerDialog.show(act, new SleepPickerDialog.OnPicked() {
            @Override public void onPicked(int minutes) {
                if (minutes <= 0) {
                    PlayService.cancelSleep(act);
                    host.toast("已取消定时暂停");
                } else {
                    PlayService.scheduleSleep(act, minutes);
                    host.toast(fmtRemain(minutes * 60) + "后自动暂停");
                }
            }
        });
    }

    /** 恢复隐藏的收藏夹（多选）。 */
    private void restoreHiddenFolders() {
        showRestorePicker(false);
    }

    /**
     * 恢复隐藏的合集（多选）。
     *
     * 与收藏夹完全对称：两者是并列列表，各自有独立的隐藏集合
     * （hidden_seasons / hidden_folders），所以恢复也要分开做。
     */
    private void restoreHiddenSeasons() {
        showRestorePicker(true);
    }

    /**
     * 弹出一个多选列表，让用户挑选要恢复哪些。
     *
     * 默认全部不勾选（用户是来挑的，不是来全恢复的）；
     * 底部提供「全选」方便一次恢复全部，等价于原来的行为。
     */
    private void showRestorePicker(final boolean season) {
        final Bili bili = Bili.get(act);
        final java.util.Set<Long> hidden = season ? bili.hiddenSeasons() : bili.hiddenFolders();
        final String what = season ? "合集" : "收藏夹";

        if (hidden.isEmpty()) {
            host.toast("没有隐藏的" + what);
            return;
        }

        // 固定顺序，避免每次打开时条目位置乱跳
        final java.util.List<Long> ids = new java.util.ArrayList<Long>(hidden);
        java.util.Collections.sort(ids);
        final boolean[] checked = new boolean[ids.size()];

        final String[] labels = new String[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            long id = ids.get(i);
            String title = host.titleOf(id, season);
            labels[i] = (title == null || title.isEmpty()) ? ("(" + id + ")") : title;
        }

        final AlertDialog dlg = new AlertDialog.Builder(act)
                .setTitle("恢复隐藏的" + what)
                .setMultiChoiceItems(labels, checked,
                        new DialogInterface.OnMultiChoiceClickListener() {
                    @Override public void onClick(DialogInterface d, int which, boolean isChecked) {
                        checked[which] = isChecked;
                    }
                })
                .setPositiveButton("恢复选中", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        int n = 0;
                        for (int i = 0; i < checked.length; i++) {
                            if (!checked[i]) continue;
                            if (season) bili.setSeasonHidden(ids.get(i), false);
                            else bili.setFolderHidden(ids.get(i), false);
                            n++;
                        }
                        if (n == 0) {
                            host.toast("没有选中任何" + what);
                            return;
                        }
                        host.toast("已恢复 " + n + " 个" + what);
                        if (season) host.reloadSeasons();
                        else host.reloadFolders();
                    }
                })
                .setNeutralButton("全选", null)
                .setNegativeButton("取消", null)
                .create();

        // 「全选」要能勾上列表里所有项，且用普通按钮（点击后不关闭弹窗）。
        // setNeutralButton(文字, null) 之后拿真实按钮自己挂监听，
        // 这样点它不会像 Positive/Negative 那样关掉弹窗。
        dlg.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override public void onShow(DialogInterface d) {
                android.widget.Button all = dlg.getButton(AlertDialog.BUTTON_NEUTRAL);
                all.setOnClickListener(new android.view.View.OnClickListener() {
                    @Override public void onClick(android.view.View v) {
                        android.widget.ListView lv = dlg.getListView();
                        if (lv == null) return;
                        boolean allOn = true;
                        for (boolean c : checked) { if (!c) { allOn = false; break; } }
                        for (int i = 0; i < checked.length; i++) {
                            checked[i] = !allOn;
                            lv.setItemChecked(i, !allOn);
                        }
                        all.setText(allOn ? "全选" : "全不选");
                    }
                });
            }
        });
        dlg.show();
    }
    /** 退出登录并清空本地数据，二次确认。 */
    private void confirmLogout(final Bili bili) {
        new AlertDialog.Builder(act)
                .setTitle("退出登录")
                .setMessage("将清除：\n"
                        + "· 登录状态（Cookie）\n"
                        + "· 隐藏收藏夹、音质等设置\n"
                        + "· 封面缓存\n"
                        + "· 诊断日志\n\n"
                        + "确定退出吗？")
                .setPositiveButton("退出并清除", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        bili.logoutAndClear(act);
                        // 退出登录必须停止播放：否则未登录状态还在放别人的歌
                        PlayService.shutdown(act);
                        // 设置页在未登录后没有意义，一并关掉
                        dismissMain();
                        host.toast("已退出并清除数据");
                        host.reloadFolders();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 显示取流耗时日志，用于定位慢的问题。 */
    private void showTimingLog() {
        // 不再要求「必须先打开开关」才让看：
        // 用户关掉开关后往往还要回看刚才的记录，这里直接放行。
        // 没有记录时下面的 readTimingLog 会给出提示。
        // 读完整日志。日志文件上限 1MB 且已做滚动保留（超出时删前一半留后一半），
        // 所以这里全读进来不会有内存压力。
        String text = readTimingLog();

        TextView tv = new TextView(act);
        tv.setText(text);
        tv.setTextSize(10);
        tv.setTextColor(Colors.TEXT_PRIMARY);
        tv.setPadding(dp(12), dp(12), dp(12), dp(12));
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);

        android.widget.ScrollView sv = new android.widget.ScrollView(act);
        sv.addView(tv);

        // 复制用同一份内容：上面已经完整读入 text，不必再读一次文件。
        // （日志上限 1MB，重复读会白白多一次磁盘 IO。）
        final String fullText = text;

        AlertDialog dlg = new AlertDialog.Builder(act)
                .setTitle("取流耗时（最近）")
                .setView(sv)
                .setPositiveButton("关闭", null)
                .setNeutralButton("复制", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        if (copyToClipboard(fullText)) host.toast("日志已复制");
                        else host.toast("复制失败");
                    }
                })
                .setNegativeButton("清空", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        // 二次确认：日志是排查问题的依据，误点清空就没了
                        new AlertDialog.Builder(act)
                                .setTitle("清空日志")
                                .setMessage("将删除全部诊断日志记录。\n此操作不可撤销。")
                                .setPositiveButton("清空", new android.content.DialogInterface.OnClickListener() {
                                    @Override public void onClick(android.content.DialogInterface d2, int w2) {
                                        clearTimingLog();
                                        host.toast("日志已清空");
                                    }
                                })
                                .setNegativeButton("取消", null)
                                .show();
                    }
                })
                .create();

        // 打开时自动滚到最新一行。
        // 为什么不能在 show() 之后立刻滚：这时布局还没量完，
        // getChildAt(0).getHeight() 还是 0，滚过去也没用。
        // 用 OnGlobalLayoutListener 等真正量完再滚，然后立刻摘掉监听，
        // 否则每次布局变化都会把用户拽回底部，想往上翻就看不了了。
        dlg.setOnShowListener(new android.content.DialogInterface.OnShowListener() {
            @Override public void onShow(android.content.DialogInterface d) {
                final android.view.View content = ((AlertDialog) d).getWindow() == null
                        ? null : ((AlertDialog) d).getWindow().getDecorView();
                if (content == null) return;
                content.getViewTreeObserver().addOnGlobalLayoutListener(
                        new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override public void onGlobalLayout() {
                        int scrollH = sv.getHeight();
                        int childH = sv.getChildCount() == 0 ? 0 : sv.getChildAt(0).getHeight();
                        if (scrollH <= 0 || childH <= 0) return;   // 还没量完，等下次
                        sv.scrollTo(0, childH - scrollH);          // 滚到底
                        // 滚到位就摘掉，避免后续布局变化时反复把用户拽回底部
                        if (content.getViewTreeObserver().isAlive()) {
                            content.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        }
                    }
                });
            }
        });
        dlg.show();
    }

    /**
     * 读出完整日志文本；没有记录时返回提示语。
     *
     * 实际读取交给 Timing.readAll()，避免这里和它各写一份读文件的代码。
     * 注意：即使诊断开关已关闭也能读——关闭开关不再删除日志文件。
     */
    private String readTimingLog() {
        String s = Timing.readAll();
        if (s == null || s.isEmpty()) return "还没有记录。\n先去点几首歌，再回来看。";
        return s;
    }

    /** 清空日志文件，复用 Timing 的统一入口。 */
    private void clearTimingLog() {
        Timing.clear();
    }

    /**
     * 复制到剪贴板。
     *
     * 日志可能很长，有的机型对 ClipboardManager 的单条长度有限制，
     * 这里不做截断——截断反而会让人以为是日志本身不全。
     */
    private boolean copyToClipboard(String text) {
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) act.getSystemService(act.CLIPBOARD_SERVICE);
            if (cm == null) return false;
            cm.setPrimaryClip(android.content.ClipData.newPlainText("BiliMusic 诊断日志", text));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------ 工具

    private static String fmtRemain(int sec) {
        if (sec <= 0) return "0秒";
        int m = sec / 60;
        int s = sec % 60;
        if (m > 0 && s > 0) return m + "分" + s + "秒";
        if (m > 0) return m + "分";
        return s + "秒";
    }

    private int dp(int v) {
        return (int) (v * act.getResources().getDisplayMetrics().density + 0.5f);
    }
}
