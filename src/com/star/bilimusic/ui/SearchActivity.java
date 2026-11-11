package com.star.bilimusic.ui;

import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.star.bilimusic.api.Track;
import com.star.bilimusic.util.ListCache;
import com.star.bilimusic.util.Task;
import com.star.bilimusic.util.TempStore;
import com.star.bilimusic.util.TrackSearcher;
import com.star.bilimusic.util.ImportStore;

import java.util.ArrayList;
import java.util.List;

/**
 * 搜索页。
 *
 * 只负责「查」和「把结果落成一个临时歌单」，不负责播放：
 * 搜索结果本身不能播，必须先点「生成临时歌单」，
 * 之后到「临时」tab 里播。这样做的好处是播放链路只有一条
 * （所有能播的东西都是某个歌单），不用为搜索单独维护队列。
 */
public class SearchActivity extends BaseActivity {

    private static final String TAG = "BiliSearchPage";

    private EditText input;
    private TextView searchBtn;
    private TextView status;
    private TextView saveBtn;
    private ListView list;

    private final List<TrackSearcher.Hit> hits = new ArrayList<TrackSearcher.Hit>();
    private Adapter adapter;
    /** 最近一次成功搜索的关键词，生成歌单时作为名字。 */
    private String lastQuery = "";
    private boolean searching = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        buildUi();
    }

    private void buildUi() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(Colors.BG);
        col.setPadding(dp(16), dp(12), dp(16), 0);

        // 顶栏
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
        title.setText("搜索");
        title.setTextColor(Colors.TEXT_PRIMARY);
        title.setTextSize(20);
        bar.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(bar);

        // 输入行
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(8));

        input = new EditText(this);
        input.setHint("歌名，或用 [and] / [or] 组合");
        input.setTextSize(14);
        input.setSingleLine(true);
        input.setTextColor(Colors.TEXT_PRIMARY);
        input.setHintTextColor(Colors.TEXT_DISABLED);
        input.setBackgroundColor(Colors.BG_CARD);
        input.setPadding(dp(12), dp(9), dp(12), dp(9));
        row.addView(input, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        searchBtn = new TextView(this);
        searchBtn.setText("搜索");
        searchBtn.setTextColor(Colors.ACCENT);
        searchBtn.setTextSize(15);
        searchBtn.setPadding(dp(14), dp(10), dp(6), dp(10));
        searchBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doSearch(); }
        });
        row.addView(searchBtn);
        col.addView(row);

        status = new TextView(this);
        status.setTextColor(Colors.TEXT_SECONDARY);
        status.setTextSize(13);
        status.setPadding(0, 0, 0, dp(8));
        status.setText("输入关键词后点搜索");
        col.addView(status);

        // 生成临时歌单按钮（有结果时才显示）
        saveBtn = new TextView(this);
        saveBtn.setText("＋ 生成临时歌单");
        saveBtn.setTextColor(Colors.ACCENT);
        saveBtn.setTextSize(15);
        saveBtn.setGravity(Gravity.CENTER);
        saveBtn.setPadding(0, dp(12), 0, dp(12));
        saveBtn.setBackgroundColor(Colors.BG_CARD);
        saveBtn.setVisibility(View.GONE);
        saveBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { saveAsTemp(); }
        });
        col.addView(saveBtn);

        list = new ListView(this);
        list.setDividerHeight(0);
        list.setBackgroundColor(Colors.BG);
        list.setCacheColorHint(Colors.BG);
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override public void onItemClick(android.widget.AdapterView<?> p, View v, int pos, long id) {
                // 搜索结果不直接播放：必须先落成临时歌单。
                // 静默无反应会让人以为卡住了，所以给明确提示。
                toast("请先点上方「＋ 生成临时歌单」");
            }
        });
        col.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(col);
    }

    // ------------------------------------------------------------ 搜索

    private void doSearch() {
        if (searching) return;
        final String raw = input.getText().toString().trim();
        // 本地先做一次语法校验，省掉一次后台往返，提示也更即时
        if (raw.isEmpty()) {
            toast("请输入搜索关键词");
            return;
        }
        boolean hasAnd = raw.contains("[and]");
        boolean hasOr = raw.contains("[or]");
        if (hasAnd && hasOr) {
            toast("不支持同时使用 [and] 和 [or]");
            return;
        }

        searching = true;
        searchBtn.setTextColor(Colors.TEXT_DISABLED);
        saveBtn.setVisibility(View.GONE);
        status.setText("搜索中…");
        hits.clear();
        adapter.notifyDataSetChanged();

        Task.run(new Runnable() {
            @Override public void run() {
                // 名字解析：从三个来源的索引里查歌单名，用于显示"来自哪里"
                TrackSearcher.NameResolver resolver = new TrackSearcher.NameResolver() {
                    @Override public String nameOf(String cacheKey) {
                        return nameOfKey(cacheKey);
                    }
                };
                final TrackSearcher.Result r = TrackSearcher.search(raw, resolver);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (isFinishing()) return;
                        searching = false;
                        searchBtn.setTextColor(Colors.ACCENT);
                        if (r.error != null) {
                            status.setText(r.error);
                            return;
                        }
                        hits.clear();
                        hits.addAll(r.items);
                        lastQuery = raw;
                        adapter.notifyDataSetChanged();
                        status.setText(hits.isEmpty()
                                ? "没有匹配的歌曲"
                                : ("匹配到 " + hits.size() + " 首"));
                        saveBtn.setVisibility(hits.isEmpty() ? View.GONE : View.VISIBLE);
                    }
                });
            }
        });
    }

    /** 把一个缓存 key 翻译成可读的歌单名。 */
    private String nameOfKey(String key) {
        try {
            if (key.startsWith("season_")) {
                // 合集名存在服务端的追更列表里，未必已经加载过；
                // 这里拿不到名字时用 id 兜底，至少能看出是哪个合集。
                long id = Long.parseLong(key.substring("season_".length()));
                return "合集 " + id;
            }
            if (key.startsWith("import_")) {
                String id = key.substring("import_".length());
                com.star.bilimusic.api.ImportedList meta = ImportStore.find(id);
                return meta == null ? ("导入 " + id) : meta.title;
            }
            if (key.startsWith("fav_")) {
                long id = Long.parseLong(key.substring("fav_".length()));
                return "收藏夹 " + id;
            }
        } catch (Exception e) {
            Log.w(TAG, "nameOfKey failed: " + key, e);
        }
        return "";
    }

    // ------------------------------------------------------------ 生成临时歌单

    private void saveAsTemp() {
        if (hits.isEmpty()) return;
        final String raw = lastQuery;

        // 先算名字：TempStore 负责重名编号（一旦重名，全部带编号）
        final String newTitle = TempStore.nextTitle(raw);
        final String listId = com.star.bilimusic.api.TempPlaylist.PREFIX
                + System.currentTimeMillis();
        final List<Track> items = new ArrayList<Track>();
        for (TrackSearcher.Hit h : hits) items.add(h.track);


        // 写缓存 + 写索引（都在后台做，涉及文件 I/O）
        Task.run(new Runnable() {
            @Override public void run() {
                ListCache.save(ListCache.keyTemp(listId), items);
                com.star.bilimusic.api.TempPlaylist p = new com.star.bilimusic.api.TempPlaylist();
                p.listId = listId;
                p.title = newTitle;
                p.count = items.size();
                p.createdAt = System.currentTimeMillis();
                TempStore.put(p);
                // 若产生了同名组，把整组按创建时间重新编号（①②③）
                TempStore.renumberGroup(raw);
                // 数据变了：搜索缓存必须失效，否则下次搜到的是旧内容
                TrackSearcher.invalidate();

                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (isFinishing()) return;
                        // 注意括号：字符串拼接的优先级高于三元运算符，
                        // 少写括号会变成 ("已生成…" + find()) != null 永远为真。
                        com.star.bilimusic.api.TempPlaylist created = TempStore.find(listId);
                        String shown = (created != null) ? created.title : newTitle;
                        toast("已生成临时歌单：" + shown);
                        // 通知主界面：新歌单已生成，请切到「临时」tab 并刷新列表
                        setResult(RESULT_OK);
                        finish();
                    }
                });
            }
        });
    }


    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------ 适配器

    private static class Holder {
        TextView title;
        TextView sub;
    }

    private class Adapter extends BaseAdapter {
        @Override public int getCount() { return hits.size(); }
        @Override public Object getItem(int i) { return hits.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int pos, View conv, ViewGroup parent) {
            Holder h;
            if (conv == null) {
                LinearLayout row = new LinearLayout(SearchActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(dp(4), dp(11), dp(4), dp(11));

                TextView title = new TextView(SearchActivity.this);
                title.setTextColor(Colors.TEXT_PRIMARY);
                title.setTextSize(14);
                title.setMaxLines(2);
                title.setEllipsize(android.text.TextUtils.TruncateAt.END);
                row.addView(title);

                TextView sub = new TextView(SearchActivity.this);
                sub.setTextColor(Colors.TEXT_SECONDARY);
                sub.setTextSize(11);
                sub.setPadding(0, dp(4), 0, 0);
                row.addView(sub);

                View line = new View(SearchActivity.this);
                line.setBackgroundColor(Colors.DIVIDER);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 1);
                lp.topMargin = dp(11);
                row.addView(line, lp);

                h = new Holder();
                h.title = title;
                h.sub = sub;
                row.setTag(h);
                conv = row;
            } else {
                h = (Holder) conv.getTag();
            }

            TrackSearcher.Hit hit = hits.get(pos);
            Track t = hit.track;
            h.title.setText(t == null ? "" : t.title);
            String dur = t == null ? "" : fmt(t.duration);
            String src = hit.sourceLabel == null || hit.sourceLabel.isEmpty()
                    ? "未知来源" : hit.sourceLabel;
            h.sub.setText(dur.isEmpty() ? src : (src + "  ·  " + dur));
            return conv;
        }
    }

    private static String fmt(long ms) {
        if (ms <= 0) return "";
        long s = ms / 1000;
        long m = s / 60;
        long ss = s % 60;
        if (m >= 60) return String.format("%d:%02d:%02d", m / 60, m % 60, ss);
        return String.format("%d:%02d", m, ss);
    }
}
