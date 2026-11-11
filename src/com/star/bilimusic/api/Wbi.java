package com.star.bilimusic.api;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * B 站 wbi 请求签名。
 * 算法：imgKey + subKey 拼 64 位 rawKey，按固定混淆表取 32 位 mixinKey，
 * 参数按 key 升序排序、过滤 !'()* 四类字符、URL 编码，
 * w_rid = MD5(排序后的 query + mixinKey)。
 */
final class Wbi {

    private static final int[] TABLE = new int[]{
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
            27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
            37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
            22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52
    };

    // URL 编码必须用大写十六进制，小写会导致 w_rid 与 B 站服务端计算结果不一致
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();
    // MD5 的小写输出（w_rid 惯例是小写）
    private static final char[] HEX_LOW = "0123456789abcdef".toCharArray();
    private static final long TTL = 4L * 60 * 60 * 1000;

    private String rawKey = "";
    private long keyAt = 0;

    public boolean ready() {
        return rawKey != null && rawKey.length() == 64;
    }

    public boolean stale() {
        return !ready() || (System.currentTimeMillis() - keyAt > TTL);
    }

    public void setKeys(String imgKey, String subKey) {
        if (imgKey == null || subKey == null || imgKey.isEmpty() || subKey.isEmpty()) return;
        this.rawKey = imgKey + subKey;
        this.keyAt = System.currentTimeMillis();
    }

    public void clear() {
        rawKey = "";
        keyAt = 0;
    }

    private String mixinKey() {
        StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < 32; i++) {
            int idx = TABLE[i];
            if (idx < rawKey.length()) sb.append(rawKey.charAt(idx));
        }
        return sb.toString();
    }

    /** 返回已签名的 query（不含前导 ?）。 */
    public String sign(Map<String, String> src, long nowSec) {
        Map<String, String> params = new TreeMap<String, String>(src);
        params.put("wts", String.valueOf(nowSec));
        List<String> keys = new ArrayList<String>(params.keySet());
        Collections.sort(keys);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keys.size(); i++) {
            String k = keys.get(i);
            String v = params.get(k);
            if (v == null) v = "";
            v = filter(v);
            if (i > 0) sb.append('&');
            sb.append(enc(k)).append('=').append(enc(v));
        }
        String base = sb.toString();
        return base + "&w_rid=" + md5(base + mixinKey());
    }

    private static String filter(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '!' || c == '\'' || c == '(' || c == ')' || c == '*') continue;
            sb.append(c);
        }
        return sb.toString();
    }

    private static String enc(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean keep = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~';
            if (keep) {
                sb.append(c);
            } else {
                byte[] b;
                try {
                    b = String.valueOf(c).getBytes("UTF-8");
                } catch (Exception e) {
                    b = new byte[]{(byte) c};
                }
                for (byte one : b) {
                    sb.append('%').append(HEX[(one >> 4) & 0xF]).append(HEX[one & 0xF]);
                }
            }
        }
        return sb.toString();
    }

    /** MD5 输出小写（w_rid 惯例）。 */
    static String md5(String s) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(HEX_LOW[(b >> 4) & 0xF]).append(HEX_LOW[b & 0xF]);
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
