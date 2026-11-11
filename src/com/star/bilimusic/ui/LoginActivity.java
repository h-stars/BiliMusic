package com.star.bilimusic.ui;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.star.bilimusic.api.Bili;
import com.star.bilimusic.util.Task;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.BarcodeFormat;

/**
 * 扫码登录页：生成二维码 → 轮询状态 → 保存 Cookie。
 */
public class LoginActivity extends Activity {

    private TextView status;
    private ImageView qrView;
    private Button refreshBtn, logoutBtn, importBtn, exportBtn;
    private volatile boolean polling = false;
    /** Activity 是否已销毁。轮询线程靠它判断能不能碰界面。 */
    private volatile boolean destroyed = false;
    /**
     * 登录轮次号。每次 startLogin() 递增。
     * 旧轮询线程发现自己的轮次已过期就主动退出，避免刷新二维码时
     * 多个线程同时占着 Task 的固定线程池。
     */
    private volatile int loginRound = 0;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        buildUi();
        if (Bili.get(this).isLoggedIn()) {
            showLoggedIn();
        } else {
            startLogin();
        }
    }

    private void buildUi() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Colors.BG);
        col.setPadding(dp(32), dp(60), dp(32), dp(32));
        col.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView t = new TextView(this);
        t.setText("登录");
        t.setTextColor(Colors.TEXT_PRIMARY);
        t.setTextSize(22);
        col.addView(t);

        TextView hint = new TextView(this);
        hint.setText("用 B 站 App 扫描下方二维码");
        hint.setTextColor(Colors.TEXT_SECONDARY);
        hint.setTextSize(13);
        hint.setPadding(0, dp(8), 0, dp(24));
        col.addView(hint);

        qrView = new ImageView(this);
        int size = dp(220);
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(size, size);
        qrView.setLayoutParams(qlp);
        qrView.setBackgroundColor(Color.WHITE);
        qrView.setPadding(dp(12), dp(12), dp(12), dp(12));
        col.addView(qrView);

        status = new TextView(this);
        status.setText("正在生成二维码…");
        status.setTextColor(Colors.TEXT_SECONDARY);
        status.setTextSize(13);
        status.setPadding(0, dp(20), 0, 0);
        col.addView(status);

        refreshBtn = new Button(this);
        refreshBtn.setText("刷新二维码");
        refreshBtn.setVisibility(View.GONE);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(16);
        col.addView(refreshBtn, rlp);
        refreshBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startLogin(); }
        });

        logoutBtn = new Button(this);
        logoutBtn.setText("退出登录");
        logoutBtn.setVisibility(View.GONE);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = dp(16);
        col.addView(logoutBtn, llp);
        logoutBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Bili.get(LoginActivity.this).logout();
                setResult(RESULT_OK);
                finish();
            }
        });

        // 导入 Cookie：扫码之外的登录方式
        importBtn = new Button(this);
        importBtn.setText("导入 Cookie 登录");
        importBtn.setVisibility(View.GONE);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.topMargin = dp(16);
        col.addView(importBtn, ilp);
        importBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showImportDialog(); }
        });

        // 导出 Cookie：登录后可用于备份或迁移到别的设备
        exportBtn = new Button(this);
        exportBtn.setText("导出 Cookie");
        exportBtn.setVisibility(View.GONE);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.topMargin = dp(8);
        col.addView(exportBtn, elp);
        exportBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showExportDialog(); }
        });

        setContentView(col);
    }

    /** 导入 Cookie：粘贴后校验并保存。 */
    private void showImportDialog() {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("粘贴 Cookie，或只粘 SESSDATA 的值");
        input.setTextSize(12);
        input.setMinLines(4);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setPadding(dp(16), dp(12), dp(16), dp(12));

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(input);
        android.widget.LinearLayout wrap = new android.widget.LinearLayout(this);
        wrap.setPadding(dp(12), dp(8), dp(12), 0);
        wrap.addView(sv, new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(180)));
        // 说明文字
        android.widget.TextView tip = new android.widget.TextView(this);
        tip.setText("从已登录的浏览器或本 App 导出的 Cookie 粘贴到这里。\n"
                + "只需要包含 SESSDATA 字段即可登录。");
        tip.setTextSize(11);
        tip.setTextColor(Colors.TEXT_SECONDARY);
        tip.setPadding(dp(16), dp(4), dp(16), 0);
        wrap.addView(tip);

        new android.app.AlertDialog.Builder(this)
                .setTitle("导入 Cookie")
                .setView(wrap)
                .setPositiveButton("导入", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        final String text = input.getText().toString();
                        final Bili bili = Bili.get(LoginActivity.this);
                        // importCookie 现在会真的请求一次 nav 接口做校验，
                        // 属于网络操作，必须放到后台线程，不能在点击回调里直接跑。
                        status.setText("正在验证…");
                        status.setTextColor(Colors.TEXT_SECONDARY);
                        Task.run(new Runnable() {
                            @Override public void run() {
                                final boolean ok = bili.importCookie(text);
                                uiIfAlive(new Runnable() {
                                    @Override public void run() {
                                        if (!ok) {
                                            // 已做服务端校验，返回 false 通常是
                                            // 「Cookie 已失效」而不是「格式不对」。
                                            toast("Cookie 无效或已过期，请重新获取");
                                            status.setText("未登录");
                                            return;
                                        }
                                        // 换号后会话信息要重取
                                        bili.resetSession();
                                        verifyImported();
                                    }
                                });
                            }
                        });
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 导入后验证一下是不是真的能取到账号信息。 */
    private void verifyImported() {
        status.setText("正在验证…");
        status.setTextColor(Colors.TEXT_SECONDARY);
        uiIfAlive(new Runnable() {
            @Override public void run() {
                Task.run(new Runnable() {
                    @Override public void run() {
                        String mid = null;
                        String err = null;
                        try {
                            Bili bili = Bili.get(LoginActivity.this);
                            bili.ensureSession();
                            mid = bili.myMid();
                        } catch (Exception e) {
                            err = e.getMessage();
                        }
                        final String fMid = mid;
                        final String fErr = err;
                        uiIfAlive(new Runnable() {
                            @Override public void run() {
                                if (fMid != null && !fMid.isEmpty()) {
                                    showLoggedIn();
                                    toast("登录成功");
                                    setResult(RESULT_OK);
                                } else {
                                    status.setText("Cookie 无效或已过期"
                                            + (fErr == null ? "" : ("：" + fErr)));
                                    status.setTextColor(Colors.DANGER);
                                }
                            }
                        });
                    }
                });
            }
        });
    }

    /** 导出 Cookie：只读文本框 + 复制按钮。 */
    private void showExportDialog() {
        final String cookie = Bili.get(this).exportCookie();
        if (cookie == null || cookie.isEmpty()) {
            toast("当前没有可导出的登录信息");
            return;
        }

        final android.widget.EditText out = new android.widget.EditText(this);
        out.setText(cookie);
        out.setTextSize(11);
        out.setMinLines(4);
        out.setGravity(Gravity.TOP | Gravity.START);
        out.setKeyListener(null);   // 只读，但可长按选择复制
        out.setPadding(dp(16), dp(12), dp(16), dp(12));

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(out);
        android.widget.LinearLayout wrap = new android.widget.LinearLayout(this);
        wrap.setOrientation(android.widget.LinearLayout.VERTICAL);
        wrap.setPadding(dp(12), dp(8), dp(12), 0);
        wrap.addView(sv, new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(180)));

        android.widget.TextView tip = new android.widget.TextView(this);
        tip.setText("⚠️ Cookie 等同于账号登录凭证，请勿分享给他人。\n"
                + "泄露后他人可操作你的账号，建议用完后修改密码。");
        tip.setTextSize(11);
        tip.setTextColor(Colors.DANGER);
        tip.setPadding(dp(16), dp(6), dp(16), 0);
        wrap.addView(tip);

        new android.app.AlertDialog.Builder(this)
                .setTitle("导出 Cookie")
                .setView(wrap)
                .setPositiveButton("复制", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        android.content.ClipboardManager cm =
                                (android.content.ClipboardManager)
                                        getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("cookie", cookie));
                        toast("已复制到剪贴板");
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    private void showLoggedIn() {
        qrView.setVisibility(View.GONE);
        String mid = Bili.get(this).myMid();
        status.setText(mid == null || mid.isEmpty() ? "已登录" : ("已登录：" + mid));
        status.setTextColor(Colors.SUCCESS);
        logoutBtn.setVisibility(View.VISIBLE);
        exportBtn.setVisibility(View.VISIBLE);
        refreshBtn.setVisibility(View.GONE);
        importBtn.setVisibility(View.GONE);
        setResult(RESULT_OK);
    }

    private void startLogin() {
        // 防重复启动：Task 用的是固定 4 线程池，反复点「刷新二维码」会让
        // 多个轮询线程同时占着池子。用递增的「轮次号」让旧循环自动失效：
        // 每次启动 +1，旧循环在 while 里发现自己那一轮已经不是最新就退出。
        final int myRound = ++loginRound;
        polling = true;
        // 未登录：可以扫码，也可以手动导入 Cookie
        logoutBtn.setVisibility(View.GONE);
        exportBtn.setVisibility(View.GONE);
        importBtn.setVisibility(View.VISIBLE);
        refreshBtn.setVisibility(View.GONE);
        status.setText("正在生成二维码…");
        status.setTextColor(Colors.TEXT_SECONDARY);

        Task.run(new Runnable() {
            @Override public void run() {
                Bili.QrInfo q;
                try {
                    q = Bili.get(LoginActivity.this).qrGenerate();
                } catch (Exception e) {
                    final String m = e.getMessage();
                    uiIfAlive(new Runnable() {
                        @Override public void run() {
                            status.setText("生成失败：" + m);
                            refreshBtn.setVisibility(View.VISIBLE);
                        }
                    });
                    return;
                }

                final Bitmap bm = encodeQr(q.url);
                uiIfAlive(new Runnable() {
                    @Override public void run() {
                        if (bm != null) qrView.setImageBitmap(bm);
                        status.setText("等待扫码…");
                    }
                });

                int last = -1;
                long start = System.currentTimeMillis();
                while (polling && myRound == loginRound && System.currentTimeMillis() - start < 90000) {
                    try {
                        Thread.sleep(1500);
                    } catch (InterruptedException e) {
                        break;
                    }
                    if (!polling || myRound != loginRound) break;
                    String[] ck = new String[1];
                    int st;
                    try {
                        st = Bili.get(LoginActivity.this).qrPoll(q.key, ck);
                    } catch (Exception e) {
                        continue;
                    }
                    if (st != last) {
                        last = st;
                        final int fst = st;
                        uiIfAlive(new Runnable() {
                            @Override public void run() {
                                if (fst == 0) status.setText("等待扫码…");
                                else if (fst == 1) status.setText("已扫码，请在手机上确认");
                                else if (fst == 3) {
                                    status.setText("二维码已失效，请刷新");
                                    refreshBtn.setVisibility(View.VISIBLE);
                                }
                            }
                        });
                    }
                    if (st == 2) {
                        if (ck[0] != null && !ck[0].isEmpty()) {
                            Bili.get(LoginActivity.this).saveLoginCookie(ck[0]);
                        }
                        Bili.get(LoginActivity.this).resetSession();
                        uiIfAlive(new Runnable() {
                            @Override public void run() {
                                showLoggedIn();
                                Toast.makeText(LoginActivity.this, "登录成功", Toast.LENGTH_SHORT).show();
                                setResult(RESULT_OK);
                            }
                        });
                        polling = false;
                        return;
                    }
                    if (st == 3) {
                        polling = false;
                        return;
                    }
                }
                polling = false;
                uiIfAlive(new Runnable() {
                    @Override public void run() {
                        if (!isFinishing() && !Bili.get(LoginActivity.this).isLoggedIn()) {
                            status.setText("二维码已过期");
                            refreshBtn.setVisibility(View.VISIBLE);
                        }
                    }
                });
            }
        });
    }

    /** 用 ZXing 生成二维码位图。 */
    private Bitmap encodeQr(String content) {
        try {
            int size = dp(220);
            BitMatrix m = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size);
            int w = m.getWidth();
            int h = m.getHeight();
            int[] px = new int[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    px[y * w + x] = m.get(x, y) ? Color.BLACK : Color.WHITE;
                }
            }
            return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        polling = false;
        super.onDestroy();
    }

    /**
     * 安全地在主线程更新界面。
     * 轮询线程可能在页面销毁后还把回调排进队列，这里统一挡掉。
     */
    private void uiIfAlive(final Runnable r) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (destroyed || isFinishing()) return;
                r.run();
            }
        });
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
