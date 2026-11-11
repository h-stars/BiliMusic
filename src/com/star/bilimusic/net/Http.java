package com.star.bilimusic.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** 极简 HTTP 客户端，只用 HttpURLConnection，零依赖。 */
public final class Http {

    /** 接口请求用的 UA（Android 移动端）。 */
    public static final String UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    /**
     * 拉取 CDN 媒体文件用的 UA。
     *
     * 实测：B 站 CDN 对移动端 Android Chrome UA 会返回 403，
     * 换成桌面 Windows UA 后同样地址返回 206。
     * 6 个样本视频全部通过，因此媒体请求固定用这个 UA。
     */
    public static final String MEDIA_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    public static final String REFERER = "https://www.bilibili.com/";

    public static class Resp {

        public int code;
        public String body;
        public String location;
        public Map<String, String> headers = new LinkedHashMap<String, String>();
        /** 多值响应头（Set-Cookie 最重要），不能塞进 Map，否则会互相覆盖。 */
        public java.util.List<String[]> rawHeaders = new java.util.ArrayList<String[]>();
    }

    public static Map<String, String> baseHeaders() {
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put("User-Agent", UA);
        h.put("Referer", REFERER);
        h.put("Accept", "*/*");
        h.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        h.put("Accept-Encoding", "gzip");
        h.put("Connection", "keep-alive");
        return h;
    }

    /** GET，不跟随重定向。 */
    public static Resp get(String url, Map<String, String> headers) throws IOException {
        return open("GET", url, headers, null, false, 15000, 20000, 0);
    }

    /** GET，自定义连接/读取超时（探测小文件时用）。 */
    public static Resp get(String url, Map<String, String> headers,
                           int connectMs, int readMs) throws IOException {
        return open("GET", url, headers, null, false, connectMs, readMs, 0);
    }

    /**
     * GET，只读取响应体的前 maxBytes 字节就断开。
     *
     * 为什么需要：探测 CDN 线路时只想确认「这条线路通不通」，
     * 但 CDN 不一定遵守 Range 头——有的节点会忽略它、返回 200 整包。
     * 这时如果照常读完，就要把整首歌下载完才能判断，点歌会卡好几秒。
     * 这里读到上限就主动 abort，既拿到状态码也立刻释放连接。
     */
    public static Resp head(String url, Map<String, String> headers,
                            int connectMs, int readMs, int maxBytes) throws IOException {
        return open("GET", url, headers, null, false, connectMs, readMs, maxBytes);
    }

    /**
     * 同 head()，但跟随重定向。
     *
     * 拿 Set-Cookie 的场景必须用它：首页会 302 跳到带参数的地址，
     * buvid3 是在重定向之后那次响应里下发的，不跟随就永远拿不到。
     */
    public static Resp headFollow(String url, Map<String, String> headers,
                                 int connectMs, int readMs, int maxBytes) throws IOException {
        return open("GET", url, headers, null, true, connectMs, readMs, maxBytes);
    }

    /** GET，跟随重定向。 */
    public static Resp follow(String url, Map<String, String> headers) throws IOException {
        return open("GET", url, headers, null, true);
    }

    /** POST，无 body（B 站有些接口用 GET 风格参数 POST）。 */
    public static Resp post(String url, Map<String, String> headers, String body) throws IOException {
        return open("POST", url, headers, body, true);
    }

    private static Resp open(String method, String url, Map<String, String> headers,
                             String body, boolean follow) throws IOException {
        return open(method, url, headers, body, follow, 15000, 20000, 0);
    }

    private static Resp open(String method, String url, Map<String, String> headers,
                             String body, boolean follow,
                             int connectMs, int readMs, int maxBytes) throws IOException {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(connectMs);
            c.setReadTimeout(readMs);
            c.setInstanceFollowRedirects(follow);
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    c.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            if (body != null) {
                c.setDoOutput(true);
                byte[] b = body.getBytes("UTF-8");
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                c.setFixedLengthStreamingMode(b.length);
                OutputStream os = c.getOutputStream();
                os.write(b);
                os.close();
            }

            Resp r = new Resp();
            r.code = c.getResponseCode();
            r.location = c.getHeaderField("Location");
            for (int i = 0; ; i++) {
                String k = c.getHeaderFieldKey(i);
                String v = c.getHeaderField(i);
                if (k == null && v == null) break;
                if (k != null) {
                    r.headers.put(k, v);
                    r.rawHeaders.add(new String[]{k, v});
                }
            }

            InputStream is;
            try {
                is = (r.code >= 200 && r.code < 300) ? c.getInputStream() : c.getErrorStream();
            } catch (IOException e) {
                is = c.getErrorStream();
            }
            if (is != null) {
                String enc = c.getHeaderField("Content-Encoding");
                if (enc != null && enc.toLowerCase().contains("gzip")) {
                    is = new GZIPInputStream(is);
                }
                r.body = maxBytes > 0 ? readAtMost(is, maxBytes) : readAll(is);
            }
            return r;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String readAll(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return bos.toString("UTF-8");
    }

    /** 最多读 maxBytes 字节，够用就立刻关闭流（不把整包读完）。 */
    private static String readAtMost(InputStream is, int maxBytes) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
        byte[] buf = new byte[4096];
        int total = 0;
        try {
            while (total < maxBytes) {
                int want = Math.min(buf.length, maxBytes - total);
                int n = is.read(buf, 0, want);
                if (n <= 0) break;
                bos.write(buf, 0, n);
                total += n;
            }
        } finally {
            try { is.close(); } catch (Exception ignored) {}
        }
        return bos.toString("UTF-8");
    }

    public static String q(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    public static String qs(Map<String, String> params) {
        if (params == null || params.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(q(e.getKey())).append('=').append(q(e.getValue()));
        }
        return sb.toString();
    }
}
