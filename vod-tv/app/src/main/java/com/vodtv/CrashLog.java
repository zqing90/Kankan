package com.vodtv;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 崩溃现场记录。
 *
 * 盒子/电视上没有 adb 时，靠这个把上一次的堆栈留下来，下次启动在首页弹出来。
 * 位置：files/last_crash.txt（清除缓存会一起清掉）。
 */
public class CrashLog {

    private static final String TAG = "Kankan";
    private static final String FILE = "last_crash.txt";

    public static void install(final Context app) {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    save(app, t, e);
                } catch (Throwable ignored) {
                }
                if (prev != null) {
                    prev.uncaughtException(t, e);
                }
            }
        });
    }

    private static void save(Context c, Thread t, Throwable e) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        PrintWriter w = new PrintWriter(bos);
        w.println(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
        w.println("device = " + android.os.Build.MODEL
                + " / Android " + android.os.Build.VERSION.RELEASE
                + " (API " + android.os.Build.VERSION.SDK_INT + ")");
        w.println("thread = " + (t == null ? "?" : t.getName()));
        w.println();
        if (e != null) e.printStackTrace(w);
        w.flush();
        String text = bos.toString();

        Log.e(TAG, "==== crash ====\n" + text);

        byte[] raw;
        try {
            raw = text.getBytes("UTF-8");
        } catch (Throwable ex) {
            raw = text.getBytes();
        }
        // 内部存储：App 自己弹窗用
        write(new File(c.getFilesDir(), FILE), raw);
        // 外部存储：电视/盒子上用文件管理器就能找到，方便拷出来
        try {
            File ext = c.getExternalFilesDir(null);
            if (ext != null) write(new File(ext, FILE), raw);
        } catch (Throwable ignored) {
        }
    }

    private static void write(File f, byte[] raw) {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(f);
            out.write(raw);
            out.flush();
        } catch (Throwable ignored) {
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 没有崩溃记录时返回 null */
    public static String read(Context c) {
        File f = new File(c.getFilesDir(), FILE);
        if (!f.exists()) return null;
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] buf = new byte[(int) f.length()];
            int n = in.read(buf);
            if (n <= 0) return null;
            return new String(buf, 0, n, "UTF-8");
        } catch (Throwable e) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    public static void clear(Context c) {
        try {
            new File(c.getFilesDir(), FILE).delete();
        } catch (Throwable ignored) {
        }
    }
}
