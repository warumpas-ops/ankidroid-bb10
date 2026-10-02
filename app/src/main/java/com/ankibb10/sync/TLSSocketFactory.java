package com.ankibb10.sync;

import android.util.Log;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Enables TLS 1.2 on Android 4.3 (API 18) and provides modern SSL compatibility.
 */
public class TLSSocketFactory extends SSLSocketFactory {
    private static final String TAG = "TLSSocketFactory";

    private final SSLSocketFactory mInternalFactory;

    public TLSSocketFactory() throws KeyManagementException, NoSuchAlgorithmException {
        SSLContext context = SSLContext.getInstance("TLS");
        TrustManager[] trustAllCerts = new TrustManager[] {
            new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                public void checkClientTrusted(X509Certificate[] certs, String authType) throws CertificateException {}
                public void checkServerTrusted(X509Certificate[] certs, String authType) throws CertificateException {}
            }
        };
        context.init(null, trustAllCerts, new SecureRandom());
        mInternalFactory = context.getSocketFactory();
    }

    public static void enableTLS(HttpsURLConnection conn) {
        try {
            TLSSocketFactory factory = new TLSSocketFactory();
            conn.setSSLSocketFactory(factory);
            conn.setHostnameVerifier(new javax.net.ssl.HostnameVerifier() {
                public boolean verify(String hostname, javax.net.ssl.SSLSession session) {
                    return true;
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Failed to enable TLS 1.2 on connection", e);
        }
    }

    private Socket patch(Socket socket) {
        if (socket instanceof SSLSocket) {
            SSLSocket ssl = (SSLSocket) socket;
            String[] supported = ssl.getSupportedProtocols();
            List<String> enabled = new ArrayList<String>();
            for (String p : supported) {
                if ("TLSv1.2".equals(p) || "TLSv1.1".equals(p) || "TLSv1".equals(p)) {
                    enabled.add(p);
                }
            }
            if (!enabled.isEmpty()) {
                ssl.setEnabledProtocols(enabled.toArray(new String[0]));
            }
        }
        return socket;
    }

    @Override
    public String[] getDefaultCipherSuites() {
        return mInternalFactory.getDefaultCipherSuites();
    }

    @Override
    public String[] getSupportedCipherSuites() {
        return mInternalFactory.getSupportedCipherSuites();
    }

    @Override
    public Socket createSocket() throws IOException {
        return patch(mInternalFactory.createSocket());
    }

    @Override
    public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
        return patch(mInternalFactory.createSocket(s, host, port, autoClose));
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        return patch(mInternalFactory.createSocket(host, port));
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        return patch(mInternalFactory.createSocket(host, port, localHost, localPort));
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        return patch(mInternalFactory.createSocket(host, port));
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
        return patch(mInternalFactory.createSocket(address, port, localAddress, localPort));
    }
}