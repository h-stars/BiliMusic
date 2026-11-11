package com.star.bilimusic.ui;

import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.star.bilimusic.util.Task;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 缓存管理页（2.3.0 第一版：只展示，不提供清理）。
 *
 * 把 App 实际会写的缓存文件全部列出来，让人一眼看出「哪个文件干什么用、
 * 占多大、存在哪」。之前这些知识只散落在各个类的注释里，排查问题或想
 * 手动清理时很不方便。
 *
 * 三条硬约束：
 *   1. 只读：不删除、不修改任何文件，避免误操作。
 *   2. 全部在后台线程扫描：封面目录可能有上千个文件，在主线程 stat 会卡。
 *   3. 单个文件读取失败不影响整页：每一项独立 try/catch。
 */
public class CacheManagerActivity extends BaseActivity {

    /** 一行缓存信息。 */
    private static class Item {
        String name;        // 文件名或目录名
        String path;        // 完整路径
        String size;        // 格式化后的大小
        String desc;        // 内容含义
        long bytes;         // 原始字节数，用于排序
    }

    private ListView list;
    private TextView summary;
    private final List<Item> items = new ArrayList<Item>();
    private Adapter adapter;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        buildUi();
        loadAsync();
    }

    private void buildUi() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Colors.BG);
        col.setPadding(dp(16), dp(12), dp(16), 0);

        // 顶栏：返回 + 标题
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        TextView back = new TextView(this);
        back.setText("‹ 返回");
        back.setTextColor(Colors.ACCENT);
        back.setTextSize(15);
        back.setPadding(0, dp(8), dp(16), dp(8));
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        bar.addView(back);

        TextView title = new TextView(this);
        title.setText("缓存管理");
        title.setTextColor(Colors.TEXT_PRIMARY);
        title.setTextSize(20);
        bar.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        col.addView(bar);

        summary = new TextView(this);
        summary.setText("正在统计…");
        summary.setTextColor(Colors.TEXT_SECONDARY);
        summary.setTextSize(13);
        summary.setPadding(0, dp(4), 0, dp(10));
        col.addView(summary);

        list = new ListView(this);
        list.setDividerHeight(0);
        list.setBackgroundColor(Colors.BG);
        list.setCacheColorHint(Colors.BG);
        adapter = new Adapter();
        list.setAdapter(adapter);
        col.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(col);
    }

    /** 后台扫描目录，完成后回主线程刷新。 */
    private void loadAsync() {
        Task.run(new Runnable() {
            @Override public void run() {
                final List<Item> got = scan();
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (isFinishing()) return;
                        items.clear();
                        items.addAll(got);
                        adapter.notifyDataSetChanged();
                        long total = 0;
                        for (Item it : items) total += it.bytes;
                        summary.setText(items.size() + " 项 · 合计 " + fmtSize(total));
                    }
                });
            }
        });
    }

    /**
     * 扫描所有缓存。
     *
     * 路径与下面的说明必须和实际代码保持一致，改代码时记得同步这里：
     *   files/timing.log      Timing.java
     *   files/cidList.json    CidCache.java
     *   files/lists/*.json    ListCache.java
     *   cache/cover/          CoverLoader.java
     */
    private List<Item> scan() {
        List<Item> out = new ArrayList<Item>();
        File filesDir = getFilesDir();
        File cacheDir = getCacheDir();

        // 1. 诊断日志
        addFile(out, new File(filesDir, "timing.log"),
                "诊断日志（耗时、取流记录）\n上限 1MB，超出后删掉前半段");

        // 2. cid 缓存
        // 条目数从 CidCache 取：它反映内存里实际可用的量。
        // 文件可能比内存少几条（攒够 10 条才落盘），所以不解析文件。
        int cidCount = 0;
        try {
            cidCount = com.star.bilimusic.util.CidCache.size();
        } catch (Exception ignored) {
        }
        addFile(out, new File(filesDir, "cidList.json"),
                "cid 缓存（bvid#page → cid）\n上限 2000 条，攒够 10 条才写盘",
                cidCount + " 条");

        // 3. 列表缓存目录
        File listsDir = new File(filesDir, "lists");
        long listsBytes = 0;
        int listsCount = 0;
        if (listsDir.exists() && listsDir.isDirectory()) {
            File[] kids = listsDir.listFiles();
            if (kids != null) {
                for (File k : kids) {
                    if (k.isFile()) { listsBytes += k.length(); listsCount++; }
                }
            }
        }
        Item lists = new Item();
        lists.name = "lists/";
        lists.path = listsDir.getAbsolutePath();
        lists.bytes = listsBytes;
        lists.size = fmtSize(listsBytes) + "（" + listsCount + " 个文件）";
        lists.desc = "收藏夹 / 合集的列表缓存\n每个列表一个 json，刷新时覆盖";
        out.add(lists);

        // 4. 封面缓存目录
        File coverDir = new File(cacheDir, "cover");
        long coverBytes = 0;
        int coverCount = 0;
        if (coverDir.exists() && coverDir.isDirectory()) {
            File[] kids = coverDir.listFiles();
            if (kids != null) {
                for (File k : kids) {
                    if (k.isFile()) { coverBytes += k.length(); coverCount++; }
                }
            }
        }
        Item cover = new Item();
        cover.name = "cover/";
        cover.path = coverDir.getAbsolutePath();
        cover.bytes = coverBytes;
        cover.size = fmtSize(coverBytes) + "（" + coverCount + " 张）";
        cover.desc = "封面图磁盘缓存\n上限 80MB，超出后删掉最旧的一半";
        out.add(cover);

        // 按占用从大到小排，方便一眼看出谁最占地方
        Collections.sort(out, new Comparator<Item>() {
            @Override public int compare(Item a, Item b) {
                return Long.compare(b.bytes, a.bytes);
            }
        });
        return out;
    }

    /** 加一个单文件项（不存在也列出，让人知道它本该在哪）。 */
    private void addFile(List<Item> out, File f, String desc) {
        addFile(out, f, desc, null);
    }

    /**
     * 加一个单文件项，并可附带一句计数说明。
     *
     * @param extra 形如「123 条」；null 表示不显示。文件不存在时忽略
     */
    private void addFile(List<Item> out, File f, String desc, String extra) {
        Item it = new Item();
        it.name = f.getName();
        it.path = f.getAbsolutePath();
        it.desc = desc;
        boolean exists;
        try {
            exists = f.exists();
            it.bytes = exists ? f.length() : 0;
        } catch (Exception e) {
            exists = false;
            it.bytes = 0;
        }
        if (!exists) {
            it.size = "未生成";
        } else if (extra != null) {
            it.size = fmtSize(it.bytes) + "（" + extra + "）";
        } else {
            it.size = fmtSize(it.bytes);
        }
        out.add(it);
    }

    private static String fmtSize(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format("%.1f KB", b / 1024.0);
        return String.format("%.1f MB", b / 1024.0 / 1024.0);
    }

    private class Adapter extends BaseAdapter {
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int pos, View conv, ViewGroup parent) {
            LinearLayout box;
            if (conv == null) {
                box = new LinearLayout(CacheManagerActivity.this);
                box.setOrientation(LinearLayout.VERTICAL);
                box.setPadding(dp(4), dp(12), dp(4), dp(12));
                conv = box;
            } else {
                box = (LinearLayout) conv;
                box.removeAllViews();
            }

            Item it = items.get(pos);

            // 第一行：文件名 + 大小（左右对齐）
            LinearLayout head = new LinearLayout(CacheManagerActivity.this);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);

            TextView name = new TextView(CacheManagerActivity.this);
            name.setText(it.name);
            name.setTextColor(Colors.TEXT_PRIMARY);
            name.setTextSize(15);
            head.addView(name, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView size = new TextView(CacheManagerActivity.this);
            size.setText(it.size);
            size.setTextColor(Colors.ACCENT);
            size.setTextSize(13);
            head.addView(size);

            box.addView(head);

            // 第二行：路径
            TextView path = new TextView(CacheManagerActivity.this);
            path.setText(it.path);
            path.setTextColor(Colors.TEXT_HINT);
            path.setTextSize(10);
            path.setPadding(0, dp(3), 0, 0);
            box.addView(path);

            // 第三行：含义
            TextView desc = new TextView(CacheManagerActivity.this);
            desc.setText(it.desc);
            desc.setTextColor(Colors.TEXT_SECONDARY);
            desc.setTextSize(11);
            desc.setPadding(0, dp(5), 0, 0);
            box.addView(desc);

            // 分隔线
            View line = new View(CacheManagerActivity.this);
            line.setBackgroundColor(Colors.DIVIDER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1);
            lp.topMargin = dp(12);
            box.addView(line, lp);

            return conv;
        }
    }
}
