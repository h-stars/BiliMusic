package com.star.bilimusic.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.TextView;
import android.widget.Toast;

import com.star.bilimusic.player.PlayService;

/**
 * 定时暂停的双滑轮选择器（小时 + 分钟）。
 * 设置页和播放页共用，避免两处逻辑分叉。
 */
public final class SleepPickerDialog {

    private SleepPickerDialog() {}

    public interface OnPicked {
        void onPicked(int minutes);   // 0 表示取消定时
    }

    public static void show(final Context ctx, final OnPicked cb) {
        final PlayService ps = PlayService.get();
        if (ps == null) {
            Toast.makeText(ctx, "播放服务未启动，先播放一首歌", Toast.LENGTH_SHORT).show();
            return;
        }

        float d = ctx.getResources().getDisplayMetrics().density;
        final int pad = (int) (16 * d);

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, 0);

        // 两个滑轮并排
        LinearLayout wheels = new LinearLayout(ctx);
        wheels.setOrientation(LinearLayout.HORIZONTAL);
        wheels.setGravity(Gravity.CENTER);
        box.addView(wheels);

        final NumberPicker hourPicker = new NumberPicker(ctx);
        hourPicker.setMinValue(0);
        hourPicker.setMaxValue(5);
        final String[] hourLabels = new String[]{"0 时", "1 时", "2 时", "3 时", "4 时", "5 时"};
        hourPicker.setDisplayedValues(hourLabels);
        hourPicker.setWrapSelectorWheel(false);
        hourPicker.setValue(0);
        wheels.addView(hourPicker);

        final NumberPicker minPicker = new NumberPicker(ctx);
        minPicker.setMinValue(0);
        minPicker.setMaxValue(59);
        final String[] minLabels = new String[60];
        for (int i = 0; i < 60; i++) minLabels[i] = i + " 分";
        minPicker.setDisplayedValues(minLabels);
        minPicker.setWrapSelectorWheel(true);
        minPicker.setValue(15);
        wheels.addView(minPicker);

        // 当前状态提示
        final TextView hint = new TextView(ctx);
        hint.setTextColor(Colors.TEXT_SECONDARY);
        hint.setTextSize(12);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, pad, 0, 0);
        final int remain = ps.sleepRemainSeconds();
        hint.setText(remain > 0
                ? "当前定时剩余 " + fmt(remain)
                : "选择小时和分钟，到点自动暂停");
        box.addView(hint);

        final AlertDialog dlg = new AlertDialog.Builder(ctx)
                .setView(box)
                .setPositiveButton("开始", null)
                .setNegativeButton("取消", null)
                .setNeutralButton("关闭定时", null)
                .create();

        dlg.setOnShowListener(new android.content.DialogInterface.OnShowListener() {
            @Override public void onShow(android.content.DialogInterface di) {
                Button pos = dlg.getButton(AlertDialog.BUTTON_POSITIVE);
                pos.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        int h = hourPicker.getValue();
                        int m = minPicker.getValue();
                        int total = h * 60 + m;
                        if (total < 1) {
                            // 最少 1 分钟
                            hint.setText("至少 1 分钟");
                            hint.setTextColor(Colors.DANGER);
                            return;
                        }
                        dlg.dismiss();
                        if (cb != null) cb.onPicked(total);
                    }
                });

                Button neu = dlg.getButton(AlertDialog.BUTTON_NEUTRAL);
                if (remain > 0) {
                    // 只有已设定时才显示「关闭定时」，避免点了没意义
                    neu.setOnClickListener(new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            dlg.dismiss();
                            if (cb != null) cb.onPicked(0);
                        }
                    });
                } else {
                    neu.setVisibility(View.GONE);
                }
            }
        });

        dlg.show();
    }

    private static String fmt(int sec) {
        int m = sec / 60;
        int s = sec % 60;
        if (m > 0 && s > 0) return m + "分" + s + "秒";
        if (m > 0) return m + "分";
        return s + "秒";
    }
}
