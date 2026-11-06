package com.vodtv.data;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** 极简 HTTP 封装，只依赖 HttpURLConnection，保证 API 14 可用。 */
public class Http {

    public static final String UA_MOBILE =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 "
                    + "(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1";

    public static final String UA_DESKTOP =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/131.0 Safari/537.36";

    public static Map<String, String> headers(String ua, String referer) {
        Map<String, String> h = new HashMap<String, String>();
        h.put("User-Agent", ua);
        if (referer != null) h.put("Referer", referer);
        h.put("Accept", "*/*");
        h.put("Accept-Encoding", "gzip");
        h.put("Connection", "close");
        return h;
    }

    public static String getText(String url, Map<String, String> headers, int timeoutMs) throws IOException {
        byte[] b = getBytes(url, headers, timeoutMs);
        return new String(b, "UTF-8");
    }

    public static byte[] getBytes(String url, Map<String, String> headers, int timeoutMs) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestMethod("GET");
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code);
            }
            InputStream in = conn.getInputStream();
            String enc = conn.getContentEncoding();
            if (enc != null && enc.toLowerCase().contains("gzip")) {
                in = new GZIPInputStream(in);
            }
            return readAll(in);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        try {
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
        return bos.toByteArray();
    }
}
