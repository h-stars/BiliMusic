package com.star.bilimusic.api;

import android.content.Context;

import com.star.bilimusic.net.Http;
import com.star.bilimusic.util.Timing;
import com.star.bilimusic.util.CidCache;
import com.star.bilimusic.util.Prefs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * B 站接口客户端。
 * 只依赖 org.json + HttpURLConnection，零第三方库。
 */
public final class Bili {

    public static class ApiException extends Exception {
        public final int code;
        public ApiException(int code, String msg) {
            super(msg);
            this.code = code;
        }
    }

    private static final String API = "https://api.bilibili.com";
    private static final String PASSPORT = "https://passport.bilibili.com";

    /**
     * 单例。加 volatile 是因为 get() 会在任意线程被调用
     * （取流跑在 Task.run 的后台线程），没有 volatile 时
     * 懒初始化可能被多线程同时执行，创建出两个实例。
     */
    private static volatile Bili instance;

    private final Wbi wbi = new Wbi();
    private boolean sessionReady = false;
    private final Map<String, String> cookies = new LinkedHashMap<String, String>();


    private Bili(Context c) {
        Prefs.init(c);
        Timing.init(c);
        loadCookies();
    }

    public static synchronized Bili get(Context c) {
        if (instance == null) instance = new Bili(c);
        return instance;
    }

    // ---------------------------------------------------------------- cookies

    private void loadCookies() {
        cookies.clear();
        String raw = Prefs.getCookie();
        if (raw == null || raw.isEmpty()) return;
        for (String part : raw.split(";")) {
            int i = part.indexOf('=');
            if (i > 0) {
                cookies.put(part.substring(0, i).trim(), part.substring(i + 1).trim());
            }
        }
    }

    private void saveCookies() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : cookies.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        Prefs.setCookie(sb.toString());
    }

    public boolean isLoggedIn() {
        return cookies.containsKey("SESSDATA");
    }


    public void saveLoginCookie(String cookieHeader) {
        if (cookieHeader == null) return;
        for (String part : cookieHeader.split(";")) {
            int i = part.indexOf('=');
            if (i > 0) {
                String k = part.substring(0, i).trim();
                String v = part.substring(i + 1).trim();
                if (!k.isEmpty() && !v.isEmpty()) cookies.put(k, v);
            }
        }
        saveCookies();
    }

    /**
     * 导出当前登录态。
     * 只导出登录必需的几个字段，不带 buvid3 这类设备指纹。
     */
    public String exportCookie() {
        StringBuilder sb = new StringBuilder();
        for (String k : new String[]{"SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5"}) {
            String v = cookies.get(k);
            if (v != null && !v.isEmpty()) {
                if (sb.length() > 0) sb.append("; ");
                sb.append(k).append('=').append(v);
            }
        }
        return sb.toString();
    }

    /**
     * 从文本里提取并导入 Cookie。
     *
     * 兼容三种粘贴方式：
     *   1. 完整的 "SESSDATA=xxx; bili_jct=yyy; ..."
     *   2. 只粘了 SESSDATA 的值
     *   3. 从浏览器 DevTools 复制的整段 Cookie
     *
     * @return 是否至少拿到了 SESSDATA
     */
    public boolean importCookie(String text) {
        if (text == null || text.trim().isEmpty()) return false;

        String t = text.trim();
        // 只粘了 SESSDATA 的值（没有等号，长度像是一段 token）
        if (!t.contains("=") && t.length() > 20) {
            cookies.put("SESSDATA", t);
            saveCookies();
            // 同样要校验：格式对不代表有效
            if (verifyLoginByServer()) return true;
            clearInvalidLogin();
            return false;
        }

        boolean gotSess = false;
        for (String part : t.split("[;\n\r]")) {
            int i = part.indexOf('=');
            if (i <= 0) continue;
            String k = part.substring(0, i).trim();
            String v = part.substring(i + 1).trim();
            // 去掉可能带上的引号
            if (v.startsWith("\"") && v.endsWith("\"") && v.length() > 1) {
                v = v.substring(1, v.length() - 1);
            }
            if (k.isEmpty() || v.isEmpty()) continue;
            cookies.put(k, v);
            if ("SESSDATA".equals(k)) gotSess = true;
        }
        if (!gotSess) return false;

        // 关键：先向服务端确认这份 Cookie 真的有效，再落盘。
        // 旧实现只要格式里有 SESSDATA 就返回「导入成功」，
        // 但失效的 Cookie 同样满足格式，结果是 App 显示已登录、
        // 实际什么都加载不出来，用户很难判断问题出在哪。
        saveCookies();                       // 先存，才能带上完整 Cookie 去验
        if (verifyLoginByServer()) return true;

        // 校验失败：把刚存进去的登录态清掉，避免留下假登录状态
        clearInvalidLogin();
        return false;
    }

    /**
     * 向服务端确认当前 Cookie 是否真的有效。
     *
     * 为什么需要：isLoggedIn() 只检查本地有没有 SESSDATA，
     * 但 Cookie 可能早已失效（改密码、别处强制下线）。
     * 只有 nav 接口返回的 isLogin 才是权威判断。
     *
     * 注意：本方法必须能在「尚未确认登录」时安全调用。
     *
     * @return true 表示服务端认可当前 Cookie
     */
    public boolean verifyLoginByServer() {
        try {
            Map<String, String> h = Http.baseHeaders();
            h.put("Accept", "application/json, text/plain, */*");
            h.put("Origin", "https://www.bilibili.com");
            String ck = cookieHeader();
            if (ck.isEmpty()) return false;
            h.put("Cookie", ck);

            // 不走 call()：那里失败会抛异常，且会递归调用 ensureSession
            Http.Resp r = Http.follow(API + "/x/web-interface/nav", h);
            if (r.body == null) return false;
            JSONObject jo = new JSONObject(r.body);
            if (jo.optInt("code", -1) != 0) return false;
            JSONObject d = jo.optJSONObject("data");
            return d != null && d.optBoolean("isLogin", false);
        } catch (Exception e) {
            Timing.warn("verifyLoginByServer 失败：" + e);
            return false;
        }
    }
    /** 退出登录：清 Cookie。 */
    public void logout() {
        cookies.remove("SESSDATA");
        cookies.remove("bili_jct");
        cookies.remove("DedeUserID");
        cookies.remove("DedeUserID__ckMd5");
        cookies.remove("sid");
        saveCookies();
    }

    /**
     * 服务端判定登录失效时调用：清掉本地登录态。
     *
     * 与 logout() 的区别：只清「身份相关」的部分，
     * 音质、隐藏收藏夹、播放进度这些用户偏好保留，
     * 避免一次密码变更把用户设置全清空。
     *
     * 同时清掉 cid/族记忆——那是按账号建立的，换账号后不再适用。
     */
    private void clearInvalidLogin() {
        logout();
        sessionReady = false;
        wbi.clear();
        try {
            CidCache.clearAll();
            Prefs.setTrustedFamily("");
        } catch (Exception ignored) {
        }
        Timing.warn("登录已失效，已清除本地登录态");
    }

    /**
     * 退出登录并清空本地数据。
     *
     * 清理范围：Cookie、音质/隐藏收藏夹/线路记忆等偏好、
     * 封面磁盘缓存、诊断日志。
     * 不清 getCacheDir 以外的用户数据（本 App 没有上传或下载的内容）。
     */
    public void logoutAndClear(Context c) {
        logout();
        cookies.clear();
        Prefs.clearAccount();
        sessionReady = false;
        wbi.clear();

        // 封面缓存
        try {
            java.io.File cover = new java.io.File(c.getCacheDir(), "cover");
            deleteTree(cover);
        } catch (Exception ignored) {}

        // 列表缓存（收藏夹/合集）与 cid 缓存。
        // 两者都按账号建立，换号后不应残留。
        try {
            com.star.bilimusic.util.ListCache.clearAll();
        } catch (Exception ignored) {}
        try {
            com.star.bilimusic.util.CidCache.clearAll();
        } catch (Exception ignored) {}
        // BV 导入的索引。列表本体存在 ListCache 里，上面已经一并清掉。
        try {
            com.star.bilimusic.util.ImportStore.clearAll();
        } catch (Exception ignored) {}
        // 临时歌单索引（曲目本体同样在 ListCache 里，已清）
        try {
            com.star.bilimusic.util.TempStore.clearAll();
        } catch (Exception ignored) {}
        // 搜索缓存也一并清掉，避免退出后还留着上一个账号的数据
        try {
            com.star.bilimusic.util.TrackSearcher.invalidate();
        } catch (Exception ignored) {}
        // 播放来源记录：不清的话下次启动会去恢复一个已经不存在的列表
        try {
            com.star.bilimusic.util.Prefs.setLastImportId("");
            com.star.bilimusic.util.Prefs.setLastSource(-1, 0);
        } catch (Exception ignored) {}

        // 诊断日志
        try {
            java.io.File log = new java.io.File(c.getFilesDir(), "timing.log");
            if (log.exists()) log.delete();
        } catch (Exception ignored) {}
    }

    private static void deleteTree(java.io.File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            java.io.File[] kids = f.listFiles();
            if (kids != null) {
                for (java.io.File k : kids) deleteTree(k);
            }
        }
        f.delete();
    }

    // ------------------------------------------------------------ 音质偏好

    /** 音质档位：0=64K 1=132K 2=192K。默认 192K。 */
    public static final int Q_LOW = 0;
    public static final int Q_MID = 1;
    public static final int Q_HIGH = 2;

    public int quality() {
        // 默认 2（192K）
        return Prefs.getQuality();
    }

    public void setQuality(int q) {
        if (q < 0 || q > 2) q = Q_HIGH;
        Prefs.setQuality(q);
    }

    public static String qualityLabel(int q) {
        if (q == Q_LOW) return "64K（省流量）";
        if (q == Q_MID) return "132K（均衡）";
        return "192K（最佳）";
    }

    /** 音质档位对应的音轨 id 优先级列表。 */
    private static int[] priorityFor(int q) {
        if (q == Q_LOW) return new int[]{30216, 30232, 30280};
        if (q == Q_MID) return new int[]{30232, 30280, 30216};
        return new int[]{30280, 30232, 30216};
    }

    // ---------------------------------------------------------- 隐藏收藏夹

    /** 被隐藏的收藏夹 id 集合（用逗号分隔存在 prefs 里）。 */
    public Set<Long> hiddenFolders() {
        Set<Long> out = new HashSet<Long>();
        String raw = Prefs.getHiddenFolders();
        if (raw == null || raw.isEmpty()) return out;
        for (String part : raw.split(",")) {
            try {
                out.add(Long.parseLong(part.trim()));
            } catch (Exception ignored) {}
        }
        return out;
    }

    /** 被隐藏的合集 id 集合。 */
    public Set<Long> hiddenSeasons() {
        Set<Long> out = new HashSet<Long>();
        String raw = Prefs.getHiddenSeasons();
        if (raw == null || raw.isEmpty()) return out;
        for (String part : raw.split(",")) {
            try {
                out.add(Long.parseLong(part.trim()));
            } catch (Exception ignored) {}
        }
        return out;
    }


    public void setSeasonHidden(long id, boolean hidden) {
        Set<Long> set = hiddenSeasons();
        if (hidden) set.add(id); else set.remove(id);
        StringBuilder sb = new StringBuilder();
        for (Long x : set) {
            if (sb.length() > 0) sb.append(',');
            sb.append(x);
        }
        Prefs.setHiddenSeasons(sb.toString());
    }

    public void setFolderHidden(long id, boolean hidden) {
        Set<Long> set = hiddenFolders();
        if (hidden) set.add(id); else set.remove(id);
        StringBuilder sb = new StringBuilder();
        for (Long x : set) {
            if (sb.length() > 0) sb.append(',');
            sb.append(x);
        }
        Prefs.setHiddenFolders(sb.toString());
    }

    public String myMid() {
        String v = cookies.get("DedeUserID");
        return v == null ? "" : v;
    }

    private String cookieHeader() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : cookies.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    public void resetSession() {
        sessionReady = false;
        wbi.clear();
    }

    // ------------------------------------------------------------- session

    /** 拿游客指纹 cookie + wbi 密钥。必须在子线程调用。 */
    public synchronized void ensureSession() {
        if (sessionReady && !wbi.stale()) return;

        // 1. 访问首页拿 buvid3
        try {
            // 只需要响应头里的 Set-Cookie，正文（整页 HTML）没有用处。
            // 用 head() 只读 1 字节，拿到头就断，省掉几百 KB 下载。
            Http.Resp r = Http.headFollow("https://www.bilibili.com/",
                    Http.baseHeaders(), 15000, 20000, 1);
            parseSetCookie(r);
        } catch (Exception e) {
            // 拿不到游客指纹还能走指纹接口兜底，但记一条便于排查
            Timing.warn("session: 首页 cookie 失败 " + e.getMessage());
        }

        // 2. 拿不到就显式请求指纹接口
        if (!cookies.containsKey("buvid3")) {
            try {
                Http.Resp r = Http.follow(API + "/x/frontend/finger/spi", apiHeaders());
                JSONObject jo = new JSONObject(r.body);
                JSONObject d = jo.optJSONObject("data");
                if (d != null) {
                    String b3 = d.optString("b_3", "");
                    String b4 = d.optString("b_4", "");
                    if (!b3.isEmpty()) cookies.put("buvid3", b3);
                    if (!b4.isEmpty()) cookies.put("buvid4", b4);
                    cookies.put("b_nut", String.valueOf(System.currentTimeMillis() / 1000));
                    saveCookies();
                }
            } catch (Exception e) {
                Timing.warn("session: 指纹接口失败 " + e.getMessage());
            }
        }

        // 3. 拿 wbi 密钥
        try {
            Http.Resp r = Http.follow(API + "/x/web-interface/nav", apiHeaders());
            JSONObject jo = new JSONObject(r.body);
            JSONObject d = jo.optJSONObject("data");
            if (d != null) {
                JSONObject im = d.optJSONObject("wbi_img");
                if (im != null) {
                    String iu = im.optString("img_url", "");
                    String su = im.optString("sub_url", "");
                    wbi.setKeys(tail(iu), tail(su));
                }
                boolean login = d.optBoolean("isLogin", false);
                if (login) {
                    long mid = d.optLong("mid", 0);
                    if (mid > 0 && !cookies.containsKey("DedeUserID")) {
                        cookies.put("DedeUserID", String.valueOf(mid));
                        saveCookies();
                    }
                }
            }
        } catch (Exception e) {
            Timing.warn("session: wbi 密钥失败 " + e.getMessage());
        }

        sessionReady = true;
    }

    private static String tail(String url) {
        if (url == null) return "";
        int s = url.lastIndexOf('/');
        String t = s >= 0 ? url.substring(s + 1) : url;
        int d = t.lastIndexOf('.');
        return d > 0 ? t.substring(0, d) : t;
    }

    /**
     * 解析响应里的 Set-Cookie，用于跟随服务端的续期。
     *
     * 安全边界（很重要，写错会把用户踢下线）：
     *   - 值非空才覆盖，空值一律忽略（那是删除指令）
     *   - Max-Age <= 0 / Expires 是过去时间 也忽略
     *   - 只接受已知的登录相关字段，避免被无关 cookie 污染
     */
    private void parseSetCookie(Http.Resp r) {
        if (r == null) return;
        boolean got = false;
        for (String[] kv : r.rawHeaders) {
            if (!"Set-Cookie".equalsIgnoreCase(kv[0])) continue;
            String v = kv[1];
            if (v == null) continue;

            int semi = v.indexOf(';');
            String pair = semi > 0 ? v.substring(0, semi) : v;
            String attrs = semi > 0 ? v.substring(semi + 1) : "";

            int i = pair.indexOf('=');
            if (i <= 0) continue;

            String k = pair.substring(0, i).trim();
            String val = pair.substring(i + 1).trim();

            if (k.isEmpty() || val.isEmpty()) continue;      // 空值 = 删除指令，忽略
            if (isExpired(attrs)) continue;                  // 已过期，忽略
            if (!isLoginCookie(k)) continue;                 // 只收登录相关字段

            cookies.put(k, val);
            got = true;
        }
        if (got) saveCookies();
    }

    /** 是否是登录相关的 Cookie 字段。 */
    private static boolean isLoginCookie(String name) {
        return "SESSDATA".equals(name)
                || "bili_jct".equals(name)
                || "DedeUserID".equals(name)
                || "DedeUserID__ckMd5".equals(name)
                || "sid".equals(name)
                || "buvid3".equals(name)
                || "buvid4".equals(name)
                || "b_nut".equals(name);
    }

    /** 从属性串里判断这个 cookie 是否已被标记删除 / 已过期。 */
    private static boolean isExpired(String attrs) {
        if (attrs == null || attrs.isEmpty()) return false;
        String lower = attrs.toLowerCase();
        // Max-Age<=0 是删除指令
        int ma = lower.indexOf("max-age=");
        if (ma >= 0) {
            int end = lower.indexOf(';', ma);
            String num = lower.substring(ma + 8, end < 0 ? lower.length() : end).trim();
            try {
                if (Long.parseLong(num) <= 0) return true;
            } catch (Exception ignored) {
            }
        }
        // Expires 早于现在也是删除
        int ex = lower.indexOf("expires=");
        if (ex >= 0) {
            int end = lower.indexOf(';', ex);
            String date = attrs.substring(ex + 8, end < 0 ? attrs.length() : end).trim();
            try {
                java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat(
                        "EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US);
                java.util.Date d = fmt.parse(date);
                if (d != null && d.getTime() < System.currentTimeMillis()) return true;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private Map<String, String> apiHeaders() {
        Map<String, String> h = Http.baseHeaders();
        h.put("Accept", "application/json, text/plain, */*");
        h.put("Origin", "https://www.bilibili.com");
        String ck = cookieHeader();
        if (!ck.isEmpty()) h.put("Cookie", ck);
        return h;
    }

    /**
     * 调用接口。
     * 策略：先不带签名请求（多数接口不需要），
     * 若被风控（-352 / -412 / -403）再补 wbi 签名重试。
     */
    private JSONObject call(String url, Map<String, String> params) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                String full;
                if (attempt == 0 || !wbi.ready()) {
                    String qs = Http.qs(params);
                    full = url + (qs.isEmpty() ? "" : "?" + qs);
                } else {
                    ensureSession();
                    full = url + "?" + wbi.sign(params, System.currentTimeMillis() / 1000L);
                }
                Http.Resp r = Http.follow(full, apiHeaders());
                // 跟随服务端续期：B 站会定期在响应头里下发新的 SESSDATA，
                // 不解析的话 6 个月后就会过期。parseSetCookie 内部有多重保护，
                // 删除指令 / 过期时间 / 非登录字段都会被忽略。
                parseSetCookie(r);
                if (r.body == null) throw new ApiException(-1, "空响应");
                JSONObject jo = new JSONObject(r.body);
                int code = jo.optInt("code", -1);
                if (code == 0) return jo;
                if (code == -101) {
                    // 服务端明确说「未登录」，说明本地 Cookie 已经失效
                    // （最常见原因是改过密码，或账号在别处被强制下线）。
                    // 这里必须把本地状态清掉，否则界面会一直显示「已登录」，
                    // 用户却什么都加载不出来，还会以为是自己网络的问题。
                    clearInvalidLogin();
                    throw new ApiException(code, "登录已失效，请重新登录");
                }
                if ((code == -352 || code == -412 || code == -403) && attempt == 0) {
                    last = explain(code, jo.optString("message", ""));
                    continue;   // 带签名重试
                }
                throw explain(code, jo.optString("message", ""));
            } catch (ApiException e) {
                last = e;
                if ((e.code == -352 || e.code == -412 || e.code == -403) && attempt == 0) continue;
                break;
            } catch (Exception e) {
                last = e;
                break;
            }
        }
        if (last instanceof ApiException) throw last;
        throw new ApiException(-1, last == null ? "请求失败" : String.valueOf(last.getMessage()));
    }

    private Exception explain(int code, String msg) {
        switch (code) {
            case -403: return new ApiException(code, "被风控拦截，稍后再试");
            case -352: return new ApiException(code, "触发风控(-352)，请重新登录");
            case -101: return new ApiException(code, "未登录，请先扫码登录");
            case -404: return new ApiException(code, "内容不存在或已删除");
            case -509:
            case -799: return new ApiException(code, "请求太频繁，稍后再试");
            case -412: return new ApiException(code, "请求被拦截(-412)");
            default:
                return new ApiException(code, msg == null || msg.isEmpty() ? ("接口错误 " + code) : msg);
        }
    }

    // ------------------------------------------------------------- 登录

    public static class QrInfo {
        public String url;
        public String key;
    }

    /** 生成扫码登录二维码内容。 */
    public QrInfo qrGenerate() throws Exception {
        Map<String, String> h = Http.baseHeaders();
        h.put("Accept", "application/json");
        Http.Resp r = Http.follow(PASSPORT + "/x/passport-login/web/qrcode/generate?source=main-fe-header", h);
        JSONObject jo = new JSONObject(r.body);
        if (jo.optInt("code", -1) != 0) throw new ApiException(-1, jo.optString("message", "二维码生成失败"));
        JSONObject d = jo.getJSONObject("data");
        QrInfo q = new QrInfo();
        q.url = d.optString("url", "");
        q.key = d.optString("qrcode_key", "");
        return q;
    }

    /**
     * 轮询扫码状态。
     * @return 0 待扫码 / 1 已扫码待确认 / 2 成功 / 3 已失效
     */
    public int qrPoll(String key, String[] outCookie) throws Exception {
        Map<String, String> h = Http.baseHeaders();
        h.put("Accept", "application/json");
        Http.Resp r = Http.follow(PASSPORT + "/x/passport-login/web/qrcode/poll?qrcode_key=" + Http.q(key), h);
        // 登录成功后 Set-Cookie 会返回多个头（SESSDATA / bili_jct / DedeUserID ...），
        // 必须遍历原始 header 列表，不能用 Map（会互相覆盖）
        StringBuilder ck = new StringBuilder();
        for (String[] kv : r.rawHeaders) {
            if (!"Set-Cookie".equalsIgnoreCase(kv[0])) continue;
            String v = kv[1];
            if (v == null) continue;
            int semi = v.indexOf(';');
            String pair = semi > 0 ? v.substring(0, semi) : v;
            if (pair.contains("=")) {
                if (ck.length() > 0) ck.append("; ");
                ck.append(pair);
            }
        }
        if (ck.length() > 0 && outCookie != null) outCookie[0] = ck.toString();

        JSONObject jo = new JSONObject(r.body);
        if (jo.optInt("code", -1) != 0) return 3;
        JSONObject d = jo.optJSONObject("data");
        if (d == null) return 3;
        int code = d.optInt("code", 3);
        if (code == 0) return 2;
        if (code == 86038) return 3;
        if (code == 86090) return 1;
        return 0;
    }

    // ------------------------------------------------------------- 收藏夹

    /** 获取当前账号的收藏夹列表。 */
    public List<FavFolder> favFolders() throws Exception {
        if (!isLoggedIn()) throw new ApiException(-101, "未登录，请先扫码登录");
        // Cookie 里可能缺 DedeUserID，先借 nav 接口补齐
        String mid = myMid();
        if (mid.isEmpty()) {
            ensureSession();
            mid = myMid();
        }
        if (mid.isEmpty()) throw new ApiException(-101, "无法获取账号 ID，请重新登录");

        Map<String, String> p = new LinkedHashMap<String, String>();
        p.put("up_mid", mid);
        p.put("platform", "web");
        JSONObject jo = call(API + "/x/v3/fav/folder/created/list-all", p);
        JSONObject data = jo.optJSONObject("data");
        List<FavFolder> out = new ArrayList<FavFolder>();
        if (data == null) return out;
        JSONArray arr = data.optJSONArray("list");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            out.add(new FavFolder(
                    o.optLong("id", 0),
                    o.optString("title", "未命名"),
                    o.optInt("media_count", 0),
                    o.optInt("attr", 0) == 0 && i == 0));
        }
        return out;
    }

    /**
     * 列表来源类型。
     *
     * 收藏夹和合集在「怎么拉数据」上不同，但在缓存、进度、刷新、
     * 重启恢复这些环节完全一样，所以统一成同一个抽象。
     */
    public static final int SRC_FOLDER = 0;
    public static final int SRC_SEASON = 1;

    /** 一页的结果（收藏夹与合集共用）。 */
    public static class TrackPage {
        public List<Track> items = new ArrayList<Track>();
        /** 是否还有下一页。 */
        public boolean hasMore;
    }

    /**
     * 拉取收藏夹的一页（pagesize 20，B 站收藏夹接口上限）。
     *
     * @param pn 页码，从 1 开始
     */
    public TrackPage folderPage(long mediaId, int pn) throws Exception {
        TrackPage page = new TrackPage();
        Map<String, String> p = new LinkedHashMap<String, String>();
        p.put("media_id", String.valueOf(mediaId));
        p.put("platform", "web");
        p.put("ps", "20");
        p.put("pn", String.valueOf(pn));
        JSONObject jo = call(API + "/x/v3/fav/resource/list", p);

        JSONObject data = jo.optJSONObject("data");
        if (data == null) return page;
        JSONArray arr = data.optJSONArray("medias");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                Track t = parseFavMedia(arr.getJSONObject(i));
                if (t != null) page.items.add(t);
            }
        }
        page.hasMore = data.optBoolean("has_more", false);
        return page;
    }

    /**
     * 拉取合集的一页（pagesize 30）。
     *
     * @param pageNum 页码，从 1 开始
     */
    public TrackPage seasonPage(long seasonId, int pageNum) throws Exception {
        TrackPage page = new TrackPage();
        Map<String, String> p = new LinkedHashMap<String, String>();
        p.put("season_id", String.valueOf(seasonId));
        p.put("sort_reverse", "false");
        p.put("page_num", String.valueOf(pageNum));
        p.put("page_size", "30");
        JSONObject jo = call(API + "/x/polymer/web-space/seasons_archives_list", p);

        JSONObject data = jo.optJSONObject("data");
        if (data == null) return page;
        JSONArray arr = data.optJSONArray("archives");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                Track t = parseSeasonArchive(arr.getJSONObject(i));
                if (t != null) page.items.add(t);
            }
        }
        // 合集接口没有 has_more，用「本页是否满」判断
        page.hasMore = page.items.size() >= 30;
        return page;
    }

    /**
     * 统一入口：按来源类型拉一页。
     *
     * 上层（MainActivity 的加载器）只依赖这个，不必关心两类接口的差异。
     */
    public TrackPage pageOf(int srcType, long id, int pageNum) throws Exception {
        return srcType == SRC_SEASON ? seasonPage(id, pageNum) : folderPage(id, pageNum);
    }

    /**
     * 把收藏夹接口里的一个 media 对象转成 Track。失效视频返回 null。
     */
    private static Track parseFavMedia(JSONObject o) {
        if (o.optInt("attr", 0) != 0) return null;   // 失效视频
        Track t = new Track();
        t.bvid = o.optString("bvid", "");
        t.aid = o.optLong("id", 0);
        t.title = o.optString("title", "");
        JSONObject up = o.optJSONObject("upper");
        t.author = up != null ? up.optString("name", "") : "";
        t.cover = normalizePic(o.optString("cover", ""));
        t.duration = o.optLong("duration", 0) * 1000L;
        t.page = 1;
        if (t.bvid == null || t.bvid.isEmpty()) return null;
        return t;
    }
    // ------------------------------------------------------------- BV 导入

    /**
     * 从用户粘贴的文本里提取 BV 号。
     *
     * 支持的输入形式：
     *   BV1C54y1W7TW
     *   https://www.bilibili.com/video/BV1C54y1W7TW
     *   https://www.bilibili.com/video/BV1C54y1W7TW?p=3&t=10
     *   【标题】BV1C54y1W7TW 之类的混合文本
     *
     * 不支持 b23.tv 短链：它需要先跟随一次 302 才知道真实 BV 号，
     * 这一版刻意不做，提示用户去浏览器复制完整链接更省事。
     *
     * @return 大写的 BV 号；找不到返回空串
     */
    public static String extractBvid(String text) {
        if (text == null) return "";
        // BV 号固定为 BV + 10 位 base58 字符（大小写敏感，但 B 站实际只产生这 58 个）
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("BV[0-9A-Za-z]{10}")
                .matcher(text);
        if (!m.find()) return "";
        return m.group();
    }

    /** 导入结果：元信息 + 展开后的曲目列表。 */
    public static class ImportResult {
        public ImportedList meta;
        public List<Track> items = new ArrayList<Track>();
    }

    /**
     * 按 BV 号导入整个列表。
     *
     * 只发一次 /x/web-interface/view 请求（实测不需要登录），
     * 服务端会把整个系列一起返回，因此不存在翻页和风控问题。
     *
     * 三种结构按优先级判断：
     *   1. ugc_season 存在 → 合集，遍历 sections[].episodes[]
     *   2. pages 多于一条   → 视频选集，遍历 pages[]
     *   3. 其它             → 普通单集
     */
    public ImportResult importByBvid(String bvid) throws Exception {
        if (bvid == null || bvid.isEmpty()) throw new ApiException(-1, "BV 号为空");

        Map<String, String> p = new LinkedHashMap<String, String>();
        p.put("bvid", bvid);
        JSONObject jo = call(API + "/x/web-interface/view", p);
        JSONObject d = jo.optJSONObject("data");
        if (d == null) throw new ApiException(-1, "未取到视频信息");

        ImportResult r = new ImportResult();
        r.meta = new ImportedList();
        r.meta.srcBvid = bvid;
        r.meta.importedAt = System.currentTimeMillis();

        JSONObject owner = d.optJSONObject("owner");
        String ownerName = owner != null ? owner.optString("name", "") : "";
        String pic = normalizePic(d.optString("pic", ""));
        String vTitle = d.optString("title", "");

        // ---------- 1) 合集 ----------
        JSONObject season = d.optJSONObject("ugc_season");
        if (season != null) {
            long sid = season.optLong("id", 0);
            if (sid > 0) {
                r.meta.listId = String.valueOf(sid);
                r.meta.kind = ImportedList.KIND_SEASON;
                r.meta.title = season.optString("title", vTitle);
                r.meta.author = ownerName;
                String sc = normalizePic(season.optString("cover", ""));
                r.meta.cover = sc.isEmpty() ? pic : sc;

                JSONArray sections = season.optJSONArray("sections");
                if (sections != null) {
                    for (int s = 0; s < sections.length(); s++) {
                        JSONObject sec = sections.optJSONObject(s);
                        if (sec == null) continue;
                        JSONArray eps = sec.optJSONArray("episodes");
                        if (eps == null) continue;
                        for (int i = 0; i < eps.length(); i++) {
                            Track t = parseEpisode(eps.optJSONObject(i), ownerName, pic);
                            if (t != null) r.items.add(t);
                        }
                    }
                }
                if (!r.items.isEmpty()) {
                    r.meta.count = r.items.size();
                    return r;
                }
                // 合集解析失败就继续往下试其它结构
            }
        }

        // ---------- 2) 视频选集（多分P） ----------
        JSONArray pages = d.optJSONArray("pages");
        if (pages != null && pages.length() > 0) {
            r.meta.listId = "p_" + bvid;
            r.meta.kind = ImportedList.KIND_PAGES;
            r.meta.title = vTitle;
            r.meta.author = ownerName;
            r.meta.cover = pic;
            // 多分P 共用同一个 bvid，靠 page 区分，所以不能给每首单独设 bvid
            for (int i = 0; i < pages.length(); i++) {
                JSONObject pg = pages.optJSONObject(i);
                if (pg == null) continue;
                Track t = new Track();
                t.bvid = bvid;
                t.page = pg.optInt("page", i + 1);
                t.cid = pg.optLong("cid", 0);
                // 标题必须用 part：data.title 是整张专辑的名字，395 条会全一样
                String part = pg.optString("part", "");
                t.title = part.isEmpty() ? (vTitle + " P" + t.page) : part;
                t.author = ownerName;
                t.cover = pic;
                t.duration = pg.optLong("duration", 0) * 1000L;
                if (t.cid <= 0) continue;      // 没有 cid 无法播放
                r.items.add(t);
            }
            if (!r.items.isEmpty()) {
                r.meta.count = r.items.size();
                return r;
            }
        }

        // ---------- 3) 普通单集 ----------
        long cid = d.optLong("cid", 0);
        if (cid <= 0) throw new ApiException(-1, "该视频无法解析出播放信息");
        Track t = new Track();
        t.bvid = bvid;
        t.page = 1;
        t.cid = cid;
        t.aid = d.optLong("aid", 0);
        t.title = vTitle;
        t.author = ownerName;
        t.cover = pic;
        t.duration = d.optLong("duration", 0) * 1000L;
        r.items.add(t);
        r.meta.listId = "v_" + bvid;
        r.meta.kind = ImportedList.KIND_SINGLE;
        r.meta.title = vTitle;
        r.meta.author = ownerName;
        r.meta.cover = pic;
        r.meta.count = 1;
        return r;
    }

    /** 把一个合集 episode 转成 Track。 */
    private static Track parseEpisode(JSONObject ep, String ownerName, String fallbackPic) {
        if (ep == null) return null;
        JSONObject page = ep.optJSONObject("page");
        long cid = page != null ? page.optLong("cid", 0) : 0;
        String bv = ep.optString("bvid", "");
        if (cid <= 0 || bv.isEmpty()) return null;

        Track t = new Track();
        t.bvid = bv;
        t.cid = cid;
        t.aid = ep.optLong("aid", 0);
        t.page = page != null ? page.optInt("page", 1) : 1;
        t.title = ep.optString("title", "");

        JSONObject arc = ep.optJSONObject("arc");
        if (arc != null) {
            if (t.title.isEmpty()) t.title = arc.optString("title", "");
            JSONObject au = arc.optJSONObject("author");
            t.author = au != null ? au.optString("name", ownerName) : ownerName;
            String ap = normalizePic(arc.optString("pic", ""));
            t.cover = ap.isEmpty() ? fallbackPic : ap;
            t.duration = arc.optLong("duration", 0) * 1000L;
        } else {
            t.author = ownerName;
            t.cover = fallbackPic;
        }
        return t;
    }


    /** 合集列表接口每页最多 20 条（实测 21 会返回 -400）。 */
    private static final int SEASON_PAGE_SIZE = 20;
    /** 合集列表翻页上限，防接口异常时无限循环。 */
    private static final int SEASON_MAX_PAGES = 50;

    /**
     * 取「我收藏/追更的合集」列表（自动翻页）。
     *
     * 接口：x/v3/fav/folder/collected/list?up_mid=我的mid
     *
     * 注意这不是「我发布的合集」——那个走 seasons_series_list，
     * 需要别人 mid 才能看。这里要的是自己在「我的 → 收藏 → 追更」
     * 里看到的那批，属于「收藏了别人做的合集」。
     *
     * 实测返回的每一条都满足 type==21 且 link 里带 is_from_ugc_season=1，
     * 说明这个接口返回的就是合集，不会混入普通收藏夹。
     * 其中 id 字段就是后续请求合集内容用的 season_id。
     */
    public List<Season> seasons() throws Exception {
        if (!isLoggedIn()) throw new ApiException(-101, "未登录，请先扫码登录");
        String mid = myMid();
        if (mid.isEmpty()) {
            ensureSession();
            mid = myMid();
        }
        if (mid.isEmpty()) throw new ApiException(-101, "无法获取账号 ID，请重新登录");

        List<Season> out = new ArrayList<Season>();
        for (int page = 1; page <= SEASON_MAX_PAGES; page++) {
            Map<String, String> p = new LinkedHashMap<String, String>();
            p.put("up_mid", mid);
            p.put("platform", "web");
            p.put("ps", String.valueOf(SEASON_PAGE_SIZE));
            p.put("pn", String.valueOf(page));
            p.put("order", "mtime");
            JSONObject jo = call(API + "/x/v3/fav/folder/collected/list", p);

            JSONObject data = jo.optJSONObject("data");
            if (data == null) break;
            JSONArray arr = data.optJSONArray("list");
            int got = 0;
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    Season s = parseSeason(arr.getJSONObject(i));
                    if (s != null) { out.add(s); got++; }
                }
            }
            if (got < SEASON_PAGE_SIZE) break;   // 不满一页 = 最后一页
            if (!data.optBoolean("has_more", true)) break;
        }
        return out;
    }

    /**
     * 解析一个「收藏的合集」对象。
     *
     * 字段来源：data.list[i]
     *   id            → season_id（请求合集内容时用）
     *   title         → 合集名
     *   media_count   → 视频数
     *   cover         → 封面
     *   upper.name    → UP 主名
     *
     * 只接受 type==21（合集）。万一接口将来混入普通收藏夹（type 不同），
     * 这里直接跳过，避免把收藏夹当成合集打开。
     */
    private static Season parseSeason(JSONObject o) {
        int type = o.optInt("type", 0);
        if (type != 0 && type != 21) return null;   // 0 容错：字段缺失时不拦

        long id = o.optLong("id", 0);
        if (id <= 0) return null;
        String title = o.optString("title", "未命名合集");
        int total = o.optInt("media_count", 0);
        String cover = normalizePic(o.optString("cover", ""));
        JSONObject up = o.optJSONObject("upper");
        String author = up != null ? up.optString("name", "") : "";

        Season s = new Season(id, title, total, cover);
        s.author = author;
        return s;
    }

    /** 把合集里的一个 archive 对象转成 Track。 */
    private static Track parseSeasonArchive(JSONObject o) {
        Track t = new Track();
        t.bvid = o.optString("bvid", "");
        t.aid = o.optLong("aid", 0);
        t.title = o.optString("title", "");
        // 合集接口不返回 UP 主名，由调用方按需补（本 App 里列表不显示它）
        t.author = "";
        t.cover = normalizePic(o.optString("pic", ""));
        // duration 单位是「秒」，Track 统一用毫秒，这里乘 1000
        t.duration = o.optLong("duration", 0) * 1000L;
        t.page = 1;
        if (t.bvid == null || t.bvid.isEmpty()) return null;
        return t;
    }

    // ------------------------------------------------------------- 取流

    /**
     * 取视频的 cid（多 P 时取第 page 个）。
     *
     * 先查按视频的持久记忆：cid 由 B 站分配后不再变，(bvid, page) -> cid
     * 是纯函数，可以安全复用。命中后省掉一次 /x/web-interface/view 往返
     * （实测 77~196ms），这对「退出收藏夹再进来」「重启 App」这两种
     * 最常见的重播场景都有用。
     */
    public long cidOf(Track t) throws Exception {
        String memKey = (t.bvid == null ? "" : t.bvid) + "#" + t.page;
        long cached = CidCache.get(memKey);
        if (cached > 0) return cached;

        Map<String, String> p = new LinkedHashMap<String, String>();
        if (t.bvid != null && !t.bvid.isEmpty()) {
            p.put("bvid", t.bvid);
        } else {
            p.put("aid", String.valueOf(t.aid));
        }
        JSONObject jo = call(API + "/x/web-interface/view", p);
        JSONObject d = jo.optJSONObject("data");
        if (d == null) throw new ApiException(-1, "取视频信息失败");
        JSONArray pages = d.optJSONArray("pages");
        long cid;
        if (pages != null && pages.length() > 0) {
            int idx = Math.min(Math.max(t.page, 1), pages.length()) - 1;
            cid = pages.getJSONObject(idx).optLong("cid", 0);
        } else {
            cid = d.optLong("cid", 0);
        }
        CidCache.put(memKey, cid);
        return cid;
    }

    /**
     * 取音频直链。
     *
     * 关键：B 站把不同音轨分配到不同 CDN，某些节点（upos-*.bilivideo.com）
     * 对第三方客户端返回 403，而 mcdn.bilivideo.cn 线路往往可用。
     * 因此不能只看码率取一条，必须按「码率从高到低 × 每条线路」逐个探测，
     * 返回第一个真正能拉到数据的地址。
     */
    public String audioUrl(Track t) throws Exception {
        long tStart = System.currentTimeMillis();
        if (t.cid <= 0) t.cid = cidOf(t);
        long tCid = System.currentTimeMillis();

        // 给 logFlow 用的分段耗时上下文。
        // 放在这里是因为所有分支（本曲记忆 / 全局记忆 / 逐条探测 / 兜底）
        // 都要用同一套数字，集中赋值比到处传参清爽。
        flowStartMs = tStart;
        flowCidMs = (int) (tCid - tStart);
        flowPlayMs = 0;
        flowTrack = t;

        Map<String, String> p = new LinkedHashMap<String, String>();
        p.put("bvid", t.bvid);
        p.put("cid", String.valueOf(t.cid));
        p.put("fnval", "16");
        p.put("fnver", "0");
        p.put("fourk", "1");
        p.put("otype", "json");
        p.put("platform", "pc");

        JSONObject jo = call(API + "/x/player/playurl", p);
        long tPlay = System.currentTimeMillis();
        flowPlayMs = (int) (tPlay - tCid);
        JSONObject data = jo.optJSONObject("data");
        if (data == null) throw new ApiException(-1, "无播放数据");

        // 收集候选：(音轨id, 码率, 地址)，按 码率优先级 → mcdn 优先 排序
        List<Cand> cands = new ArrayList<Cand>();
        JSONObject dash = data.optJSONObject("dash");
        if (dash != null) {
            JSONArray arr = dash.optJSONArray("audio");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject a = arr.getJSONObject(i);
                    int id = a.optInt("id", 0);
                    if (id == 30250) continue;   // 杜比，多数设备解不了
                    int bw = a.optInt("bandwidth", 0);

                    List<String> urls = new ArrayList<String>();
                    String bu = a.optString("baseUrl", a.optString("base_url", ""));
                    if (bu != null && !bu.isEmpty()) urls.add(bu);
                    JSONArray backups = a.optJSONArray("backupUrl");
                    if (backups == null) backups = a.optJSONArray("backup_url");
                    if (backups != null) {
                        for (int k = 0; k < backups.length(); k++) {
                            String x = backups.optString(k, "");
                            if (x != null && !x.isEmpty()) urls.add(x);
                        }
                    }
                    for (String u : urls) {
                        cands.add(new Cand(id, bw, u));
                    }
                }
            }
        }

        if (cands.isEmpty()) {
            JSONArray durl = data.optJSONArray("durl");
            if (durl != null && durl.length() > 0) {
                String u = durl.getJSONObject(0).optString("url", "");
                if (u != null && !u.isEmpty()) {
                    t.streamUrl = u;
                    t.streamAt = System.currentTimeMillis();
                    t.quality = "混流";
                    return u;
                }
            }
            throw new ApiException(-1, "没有取到音频流");
        }

        // 排序：按用户选择的音质档位优先，同档位里 mcdn 线路优先
        final int[] prio = priorityFor(quality());
        Collections.sort(cands, new Comparator<Cand>() {
            @Override public int compare(Cand a, Cand b) {
                int r = rankIn(a.id, prio) - rankIn(b.id, prio);
                if (r != 0) return r;
                boolean am = isMcdn(a.url), bm = isMcdn(b.url);
                if (am != bm) return am ? -1 : 1;
                return 0;
            }
        });

        // 探测首选线路；超时或失败就顺延下一条。
        //
        // 实测：B 站 CDN 偶发对同一节点限速，一个 1KB 的 Range 请求
        // 可能从 200ms 涨到 12 秒。之前的 4 条并发探测会同时压同一批节点，
        // 反而更容易触发限速（日志里 probe 出现 1873ms / 7088ms）。
        //
        // 现在改为「单条快速探测 + 短超时」：
        //   - 首选线路通常 100-500ms 就能确认，命中即返回
        //   - 超过探测超时未响应，直接把它当成可疑线路，试下一条
        //   - 都失败才退回首选线路（播放器自己会连，支持续传和重试）
        //
        // 连接和读取分开设：
        //   连接超时 600ms —— 活线路的 TCP+TLS 握手实测在 300ms 内（日志：
        //     55/176/284/300ms），连不上就是连不上，不必等 800ms。
        //   读取超时 700ms —— 发完请求后 CDN 已经开始回数据，首个字节
        //     通常几十毫秒就到；给到 700ms 已经足够宽松。
        //
        // 之前两者都是 800ms，最坏要 1600ms 才放弃一条死线路，
        // 日志里那条 mcdn 实际卡了 1091ms 就是这个原因。
        final int PROBE_CONNECT_MS = 600;
        final int PROBE_READ_MS = 700;

        // 两层决策：族记忆 → 逐条探测。
        //
        // 早先还有「本曲记忆」和「全局记忆」两层，实测都没用，已移除：
        //   本曲记忆：要求主机名完全一致，但 B 站每次下发的节点都不同
        //             （实测 26 次取流只命中 4 次），且族记忆必然也能覆盖它；
        //   全局记忆：只存一个主机名，是族记忆的子集，命中率更低。
        // 族记忆用「族」匹配（如 bilivideo.com），实测命中率 60% 以上。
        int probedIdx = -1;
        Cand rc = null;
        String fam = Prefs.getTrustedFamily();
        if (!fam.isEmpty()) {
            for (int i = 0; i < cands.size(); i++) {
                if (familyOf(hostOf(cands.get(i).url)).equals(fam)) {
                    rc = cands.get(i); probedIdx = i;
                    break;
                }
            }
        }

        if (rc != null) {
            // 族记忆是「这个 CDN 族最近亲测可用」，直接信任，不再探测。
            // 失效的纠错交给播放器：onError 会调 markHostFailed() 清掉族记忆，
            // 下次回到逐条探测，自动换到活着的族。
            t.streamUrl = rc.url;
            t.streamAt = System.currentTimeMillis();
            t.quality = qualityName(rc.id);
            logFlow("族记忆", hostOf(rc.url), 0, -1);
            return rc.url;
        }

        for (int i = 0; i < cands.size(); i++) {
            Cand c = cands.get(i);
            // 每条都探测（含最后一条），否则「只有末位线路可用」时会误退回首选。
            long tp = System.currentTimeMillis();
            if (probe(c.url, t, PROBE_CONNECT_MS, PROBE_READ_MS)) {
                markFamilyOk(hostOf(c.url));   // 记住这个族可用，下次免探测
                t.streamUrl = c.url;
                t.streamAt = System.currentTimeMillis();
                t.quality = qualityName(c.id);
                logFlow("探测选中 #" + i, hostOf(c.url),
                        (int) (System.currentTimeMillis() - tp), -1);
                return c.url;
            }
        }

        // 探测全部失败或超时：直接用首选地址，交给播放器去连。
        // 播放器带 Referer/Cookie 且支持 Range 续传，通常能正常起播。
        Cand first = cands.get(0);
        t.streamUrl = first.url;
        t.streamAt = System.currentTimeMillis();
        t.quality = qualityName(first.id);
        logFlow("未确认线路,用首选", hostOf(first.url), -1, -1);
        return first.url;
    }

    /**
     * 统一格式的取流日志。
     *
     * 格式设计：一条取流写成两行——
     *   第一行：结论（怎么选中的线路）+ 总耗时
     *   第二行：三个分段的耗时，按固定宽度对齐
     *
     * 为什么要对齐：之前所有字段挤成一行「cid=97ms playurl=105ms probe=86ms 总=288ms」，
     * 数字宽度不一时很难纵向比较。现在每段固定 9 字符宽，一眼就能看出哪段是瓶颈。
     *
     * 示例：
     *   取流 │ 本曲记忆  upos-sz-mirrorcos.bilivideo.com        总 198ms
     *        │ cid  97   playurl 101   probe    0
     *
     * @param how      选线方式，如「本曲记忆」「探测选中 #1」
     * @param host     命中的域名
     * @param probeMs  探测耗时；-1 表示没探测（免探测路径）
     * @param totalMs  总耗时；-1 表示让方法自己算
     */
    private void logFlow(String how, String host, int probeMs, int totalMs) {
        if (!Timing.enabled()) return;   // 关着就不做字符串拼接，省掉开销
        long now = System.currentTimeMillis();
        // 分段耗时由调用方在方法内已有变量里取，这里用字段临时传，避免改一堆签名
        int cidMs = flowCidMs;
        int playMs = flowPlayMs;
        int total = totalMs >= 0 ? totalMs : (int) (now - flowStartMs);

        String hostShow = shortHost(host);
        Track cur = flowTrack;

        // 宽度限制：日志 TextView 是 10sp 等宽字体，手机可用宽度约 300dp，
        // 每行只能放 45 个左右 ASCII 字符。超过就会被系统折行，一对齐就全乱。
        //
        // 从 1.3.2 起改成「只有第一行带时间戳」：
        // 4 行日志的时间戳几乎相同，省掉 3 个时间戳就腾出 39 个字符宽度，
        // 线路行因此能完整显示，不再出现省略号。
        Timing.divider("──────────────────────────");

        // 第一行：取流方式 + 音质。带时间戳，作为这一组的起点。
        // 音质取自实际选中的流（t.quality），不是用户设置项 ——
        // 因为缺档时 B 站可能给了别的码率，日志要反映真实情况。
        String q = (cur != null && cur.quality != null && !cur.quality.isEmpty())
                ? " " + cur.quality : "";
        Timing.log("取流 " + how + q);

        // 第二行：三段耗时，固定宽度纵向可比。
        Timing.cont("cid " + pad(String.valueOf(cidMs), 5)
                + "play " + pad(String.valueOf(playMs), 5)
                + "probe " + (probeMs >= 0 ? String.valueOf(probeMs) : "-"));

        // 第三行：线路。没有时间戳前缀后可用的宽度从 28 提到 40，
        // 现有的「节点 · 族」组合都能完整显示。
        // 不再加「线路」二字：这一行内容本身就是线路名，
        // 省下的 3 个字符宽度留给域名，减少截断。
        Timing.cont(truncByWidth(hostShow, 44));
    }

    /**
     * 把域名缩短到手机上放得下的长度。
     *
     * 完整域名如 upos-sz-mirrorcos.bilivideo.com 有 33 个字符，
     * 加上前缀后必然折行。这里只保留「第一个点之前的部分」并截断，
     * 例如 upos-sz-mirrorcos，日志里足够区分是哪条线路了。
     */
    /**
     * 把主机名缩成「节点 · 族」的简短形式，例如
     * xy112x17x39x2xy.mcdn.bilivideo.cn → "xy112x17x39x2xy.mcdn · bilivideo"
     *
     * 注意：这里不截断。宽度限制统一由调用方决定（日志行是 44），
     * 早期这里写死了 30，导致外面按 44 传也只会得到 30 宽的结果，
     * 白白早截了 14 个字符的可用空间。
     */
    private static String shortHost(String host) {
        if (host == null || host.isEmpty()) return "-";
        String fam = familyOf(host);
        String node = host;
        // 去掉族后缀，只留节点前缀
        int cut = host.length() - fam.length();
        if (cut > 1) node = host.substring(0, cut - 1);
        else node = host;
        // 简洁形式：节点名（族只保留第一段，例如 mcdn / bilivideo）
        String famShort = fam;
        int dot = fam.indexOf('.');
        if (dot > 0) famShort = fam.substring(0, dot);
        return node + " · " + famShort;
    }

    /**
     * 按「显示宽度」截断字符串（中文按 2 宽算），超出加省略号。
     * 为什么不用 String.length()：中文标题按字符数算是够的，
     * 但实际显示会占两倍宽度，导致日志行被撑破、后面的列对不齐。
     */
    private static String truncByWidth(String s, int maxWidth) {
        if (s == null) return "";
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            w += c > 0x2E80 ? 2 : 1;
            if (w > maxWidth) return s.substring(0, i) + "…";
        }
        return s;
    }

    /** 固定宽度左对齐，超长截断并补省略号，保证多行日志能对齐。 */
    private static String pad(String s, int width) {
        if (s == null) s = "";
        // 中文按 2 个字符宽计算，否则中文行会比英文行短一截，对不齐
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            w += s.charAt(i) > 0x2E80 ? 2 : 1;
        }
        if (w > width) return s + " ";
        StringBuilder sb = new StringBuilder(s);
        for (int i = w; i < width; i++) sb.append(' ');
        return sb.toString();
    }

    /** logFlow 用的临时上下文，避免给 audioUrl 里每处调用都传一堆参数。 */
    private long flowStartMs;
    private int flowCidMs;
    private int flowPlayMs;
    /** 本次取流对应的曲目，用于在日志里显示编号和歌名。 */
    private Track flowTrack;
    private static class Cand {
        final int id;
        final int bw;
        final String url;
        Cand(int id, int bw, String url) { this.id = id; this.bw = bw; this.url = url; }
    }

    /**
     * 播放器报错时通知：这条 CDN 族很可能不可用了。
     *
     * 这是「族记忆免探测」的配套保险。族记忆一旦被信任就会跳过探测，
     * 如果它失效却不清理，以后每首歌都会直接撞在这个死族上，
     * 只能靠播放器超时报错才发现。失败一次就丢掉，代价最小。
     *
     * @param url 播放失败的地址，用来判断属于哪个族
     */
    public static void markHostFailed(String url) {
        try {
            String bad = hostOf(url);
            if (bad.isEmpty()) return;
            String fam = Prefs.getTrustedFamily();
            if (!fam.isEmpty() && fam.equals(familyOf(bad))) {
                Prefs.setTrustedFamily("");
                Timing.warn("族记忆不可用，已忘记：" + fam);
            }
        } catch (Exception e) {
            // 这里不能完全静默：清理失败会导致族记忆一直卡在死族上，
            // 表现为「每首歌都触发一次播放错误重试」，很难排查。
            Timing.warn("markHostFailed 异常：" + e);
        }
    }
    /**
     * 取域名的「族」，即最后两段。
     *
     * 为什么需要族：B 站会把同一批音轨分散到几十个节点上，
     * 例如 xy183x234x98x153xy.mcdn.bilivideo.cn 和 xy36x156x63x29xy.mcdn.bilivideo.cn
     * 主机名完全不同，但属于同一个 CDN 族。
     *
     * 实测 20 首歌用了 19 个不同主机名，按主机名记忆几乎永远命中不了；
     * 但按族看只有两类（mcdn.bilivideo.cn 和 bilivideo.com），
     * 命中率能从三成提升到九成以上。
     */
    private static String familyOf(String host) {
        if (host == null || host.isEmpty()) return "";
        String h = host.toLowerCase();
        // 去掉可能的端口号
        int colon = h.indexOf(':');
        if (colon > 0) h = h.substring(0, colon);
        String[] parts = h.split("\\.");
        if (parts.length > 2) {
            // 常规情况：取最后两段，bilivideo.com / bilivideo.cn 等
            return parts[parts.length - 2] + "." + parts[parts.length - 1];
        }
        if (parts.length == 2) {
            // 两段域名（例如 upos-lcdn-cqgl01.solseed）：
            // 这种 TLD 本身就是 CDN 名，若按「整个域名」当族，
            // 每个节点都会变成独立的族，彼此无法互相免探测。
            // 取第一段作为族名，让同类节点能归到一起。
            return parts[0];
        }
        return h;
    }


    /** 记下这个族最近可用。 */
    private static void markFamilyOk(String host) {
        String fam = familyOf(host);
        if (fam.isEmpty()) return;
        Prefs.setTrustedFamily(fam);
    }
    /**
     * 记录一次「直链复用」。
     *
     * 这条路径不经过 audioUrl()：PlayService 发现 streamUrl 还在 20 分钟
     * 有效期内就直接交给播放器，所以之前日志里完全看不到它。
     * 单独开一个入口，让日志能反映「这次没走网络」。
     *
     * 格式与普通取流保持一致：只有第一行带时间戳，
     * 后续的「出声」行由 PlayService 用 cont() 续写。
     * 显示格式：「20:56:32.775  直链复用 132K」，与「取流 族记忆 132K」对齐。
     */
    public static void logReuse(Track t) {
        if (!Timing.enabled() || t == null) return;
        String q = (t.quality != null && !t.quality.isEmpty()) ? " " + t.quality : "";
        Timing.divider("──────────────────────────");
        Timing.log("直链复用" + q);
    }
    /** 取 URL 的主机名，用于记住可用线路。 */
    private static String hostOf(String url) {
        if (url == null) return "";
        try {
            java.net.URL u = new java.net.URL(url);
            String h = u.getHost();
            return h == null ? "" : h;
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean isMcdn(String url) {
        return url != null && url.contains("mcdn.bilivideo");
    }

    /**
     * 探测地址是否可用：只拉 1KB，检查状态码和内容。
     *
     * @param timeoutMs 连接/读取超时。CDN 偶发限速时单次可能拖到十几秒，
     *                  必须设上限，否则「点歌」会卡在这个探测里。
     */
    /**
     * 探测地址是否可用：只读 1KB，检查状态码和内容。
     *
     * 用 Http.head() 而不是 get()：CDN 不保证遵守 Range 头，
     * 有的节点会忽略它、返回 200 整包。只读 1KB 就断开，
     * 避免「探测一条线路却把整首歌下载完」的极端情况。
     */
    private boolean probe(String url, Track t, int connectMs, int readMs) {
        long t0 = System.currentTimeMillis();
        try {
            Map<String, String> h = mediaHeaders(t.bvid, t.page);
            h.put("Range", "bytes=0-1023");
            // 响应体在 Http.head 内部读满上限就关闭了，这里不需要再关
            Http.Resp r = Http.head(url, h, connectMs, readMs, 1024);
            boolean ok = (r.code == 200 || r.code == 206)
                    && !(r.body != null && r.body.startsWith("<"));
            // 探测行属于当前这次取流的续行：用 cont() 而不是 log()，
            // 避免单独打时间戳、也避免和上一组日志混在一起分不清归属。
            // 探测行的域名列固定 20 宽：先截断再 pad，否则超宽域名会把整行撑破折行
            Timing.cont("探测 " + pad(truncByWidth(shortHost(hostOf(url)), 20), 20)
                    + pad(String.valueOf(r.code), 5)
                    + pad((System.currentTimeMillis() - t0) + "ms", 7)
                    + (ok ? "OK" : "坏"));
            return ok;
        } catch (Exception e) {
            Timing.cont("探测 " + pad(truncByWidth(shortHost(hostOf(url)), 20), 20)
                    + pad("异常", 5)
                    + pad((System.currentTimeMillis() - t0) + "ms", 7));
            return false;
        }
    }

    private static int rankIn(int id, int[] prio) {
        for (int i = 0; i < prio.length; i++) {
            if (prio[i] == id) return i;
        }
        return 50;
    }

    private static String qualityName(int id) {
        if (id == 30280) return "192K";
        if (id == 30232) return "132K";
        if (id == 30216) return "64K";
        return String.valueOf(id);
    }

    /** 播放 CDN 文件所需的请求头。 */
    public Map<String, String> mediaHeaders(String bvid, int page) {
        Map<String, String> h = new LinkedHashMap<String, String>();
        // 关键：媒体请求必须用桌面 UA，移动端 UA 会被 CDN 返回 403
        h.put("User-Agent", Http.MEDIA_UA);
        String ref = "https://www.bilibili.com/video/" + (bvid == null ? "" : bvid);
        if (page > 1) ref += "?p=" + page;
        h.put("Referer", ref);
        String ck = cookieHeader();
        if (!ck.isEmpty()) h.put("Cookie", ck);
        return h;
    }

    /**
     * 生成缩略图地址。
     *
     * B 站图床支持 @Nw 后缀按需缩略：原图 491KB(2560x1440)，
     * @200w 只要 5KB(200x113)。列表一次显示 8 行，
     * 用原图会一次吃掉约 3.8MB 带宽，把音频请求挤慢 5 秒左右。
     *
     * 只用于列表缩略图；播放页大图仍用原图。
     */
    public static String thumbPic(String url, int width) {
        if (url == null || url.isEmpty()) return "";
        // 已经是缩略格式就不重复加
        if (url.contains("@")) return url;
        // B 站格式是在完整文件名「后面」追加 @Nw.jpg，
        // 例如 xxx.jpg@200w.jpg（不是 xxx@200w.jpg，后者会 404）
        String suffix = "@" + width + "w.jpg";
        int dot = url.lastIndexOf('.');
        String ext = dot >= 0 ? url.substring(dot) : "";
        if (ext.startsWith(".png") || ext.startsWith(".webp")) {
            return url + "@" + width + "w" + ext;
        }
        return url + suffix;
    }

    private static String normalizePic(String pic) {
        if (pic == null) return "";
        String p = pic.trim();
        if (p.isEmpty()) return "";
        if (p.startsWith("//")) return "https:" + p;
        if (p.startsWith("http://")) return "https://" + p.substring(7);
        return p;
    }
}
