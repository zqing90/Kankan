package com.vodtv;

import android.app.Application;

import com.vodtv.data.HistoryStore;
import com.vodtv.data.PlayCache;
import com.vodtv.data.ImageLoader;
import com.vodtv.data.Prefs;
import com.vodtv.data.SearchHistory;
import com.vodtv.data.SourceStats;
import com.vodtv.data.SourceStore;

import java.net.Socket;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 应用入口。
 *
 * 这里做一件对整个 App 至关重要的事：给 Android 4.x 强制开 TLS 1.2。
 * Android 4.0~4.3 的默认协议是 TLSv1，而豆瓣和片源 CDN 基本只接受 TLS 1.2，
 * 不处理的话这些机器上所有网络请求都会直接 SSLException。
 */
public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        // 最先装：后面任何一步崩了都能留下现场
        CrashLog.install(this);
        enableTls12();
        Prefs.init(this);
        SourceStore.init(this);
        SourceStats.init(this);
        HistoryStore.init(this);
        PlayCache.init(this);
        // 漏了这一行会导致 SearchHistory.sp 为 null：add() 直接 return、list() 永远空，
        // 搜索页的「最近搜索」整块永久隐藏（1.3.0 就是这个 bug）
        SearchHistory.init(this);
        ImageLoader.init(this);
    }

    private void enableTls12() {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{new PermissiveTrustManager()}, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(new Tls12SocketFactory(ctx.getSocketFactory()));
        } catch (Throwable ignored) {
            // 系统实在太老就放弃，退回到默认行为
        }
    }

    /**
     * 部分老 ROM 的证书链校验会失败（根证书过期/缺失），
     * 这里放宽校验以保证能出图、能取流。若做公开发行版请换回系统校验。
     */
    private static class PermissiveTrustManager implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    /** 把 socket 的启用协议补上 TLSv1.2（若系统支持） */
    private static class Tls12SocketFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate;

        Tls12SocketFactory(SSLSocketFactory d) {
            this.delegate = d;
        }

        private Socket patch(Socket s) {
            if (s instanceof SSLSocket) {
                SSLSocket ss = (SSLSocket) s;
                List<String> supported = Arrays.asList(ss.getSupportedProtocols());
                List<String> want = new ArrayList<String>();
                for (String p : new String[]{"TLSv1.2", "TLSv1.1", "TLSv1"}) {
                    if (supported.contains(p)) want.add(p);
                }
                if (!want.isEmpty()) {
                    ss.setEnabledProtocols(want.toArray(new String[want.size()]));
                }
            }
            return s;
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return delegate.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites();
        }

        @Override
        public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws java.io.IOException {
            return patch(delegate.createSocket(s, host, port, autoClose));
        }

        @Override
        public Socket createSocket(String host, int port) throws java.io.IOException {
            return patch(delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(String host, int port, java.net.InetAddress localHost, int localPort) throws java.io.IOException {
            return patch(delegate.createSocket(host, port, localHost, localPort));
        }

        @Override
        public Socket createSocket(java.net.InetAddress host, int port) throws java.io.IOException {
            return patch(delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(java.net.InetAddress address, int port, java.net.InetAddress localAddress, int localPort) throws java.io.IOException {
            return patch(delegate.createSocket(address, port, localAddress, localPort));
        }

        @Override
        public Socket createSocket() throws java.io.IOException {
            return patch(delegate.createSocket());
        }
    }
}
