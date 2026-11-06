package com.vodtv.data;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import com.vodtv.R;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 极简图片加载器。
 *
 * 不用 Glide/Fresco 的原因：
 *   1. 它们的新版本都要求 API 21+，会直接砍掉 4.x 设备；
 *   2. 豆瓣海报必须带 Referer，需要一个能塞自定义 header 的加载器；
 *   3. TV 上卡片尺寸固定，inSampleSize 一算就够，不需要复杂变换。
 */
public class ImageLoader {

    private static LruCache<String, Bitmap> mem;
    private static ExecutorService pool = Executors.newFixedThreadPool(4);
    private static Handler main = new Handler(Looper.getMainLooper());
    private static File cacheDir;

    public static void init(Context c) {
        if (mem != null && cacheDir != null) return; // 已在 Application 里初始化过，重复调用会清空缓存
        cacheDir = new File(c.getCacheDir(), "img");
        if (!cacheDir.exists()) cacheDir.mkdirs();
        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024 / 8);
        mem = new LruCache<String, Bitmap>(maxKb) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
    }

    /** 海报地址：走服务端时换成它自己的代理，直连时用原地址（下载时补 Referer） */
    public static String resolve(String raw) {
        if (raw == null || raw.length() == 0) return "";
        if (Prefs.useServer()) {
            try {
                return Prefs.serverUrl() + "/api/poster?u=" + java.net.URLEncoder.encode(raw, "UTF-8");
            } catch (Exception e) {
                return raw;
            }
        }
        return raw;
    }

    public static void load(final ImageView iv, final String rawUrl, final int targetW) {
        final String url = resolve(rawUrl);
        if (url.length() == 0) {
            iv.setImageResource(R.drawable.ph_poster);
            return;
        }
        iv.setTag(url);

        Bitmap cached = mem.get(url);
        if (cached != null) {
            iv.setImageBitmap(cached);
            return;
        }
        iv.setImageResource(R.drawable.ph_poster);

        pool.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap bm = fromDisk(url, targetW);
                if (bm == null) {
                    bm = download(url, targetW);
                    if (bm != null) toDisk(url, bm);
                }
                final Bitmap result = bm;
                if (result != null) mem.put(url, result);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (url.equals(iv.getTag()) && result != null) {
                            iv.setImageBitmap(result);
                        }
                    }
                });
            }
        });
    }

    private static Bitmap download(String url, int targetW) {
        try {
            java.util.Map<String, String> h = Http.headers(Http.UA_DESKTOP, null);
            if (url.contains("doubanio.com")) {
                h.put("Referer", "https://movie.douban.com/");
            }
            byte[] data = Http.getBytes(url, h, 15000);
            return decode(data, targetW);
        } catch (Exception e) {
            return null;
        }
    }

    private static Bitmap decode(byte[] data, int targetW) {
        BitmapFactory.Options probe = new BitmapFactory.Options();
        probe.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, probe);

        int sample = 1;
        if (targetW > 0 && probe.outWidth > targetW * 2) {
            sample = probe.outWidth / targetW;
            if (sample < 1) sample = 1;
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.RGB_565; // TV 上省一半内存
        try {
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (OutOfMemoryError e) {
            mem.evictAll();
            o.inSampleSize = sample * 2;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        }
    }

    private static File fileOf(String url) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(url.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return new File(cacheDir, sb.toString());
        } catch (Exception e) {
            return new File(cacheDir, String.valueOf(url.hashCode()));
        }
    }

    private static Bitmap fromDisk(String url, int targetW) {
        File f = fileOf(url);
        if (!f.exists()) return null;
        try {
            FileInputStream in = new FileInputStream(f);
            byte[] buf = new byte[(int) f.length()];
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) break;
                off += n;
            }
            in.close();
            return decode(buf, targetW);
        } catch (Exception e) {
            return null;
        }
    }

    private static void toDisk(String url, Bitmap bm) {
        try {
            File f = fileOf(url);
            FileOutputStream out = new FileOutputStream(f);
            bm.compress(Bitmap.CompressFormat.JPEG, 85, out);
            out.close();
        } catch (Exception ignored) {
        }
    }
}
