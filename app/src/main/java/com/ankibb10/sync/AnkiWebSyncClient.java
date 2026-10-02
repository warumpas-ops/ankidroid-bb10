package com.ankibb10.sync;

import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import javax.net.ssl.HttpsURLConnection;

public class AnkiWebSyncClient {
    private static final String TAG = "AnkiWebSyncClient";

    private static final String CLIENT_VER = "ankidroid,2.16.4,android";
    private static final String USER_AGENT = "AnkiAndroid/2.16.4";

    private String mSyncBase;
    private String mHostKey;

    public AnkiWebSyncClient(String syncBase) {
        String base = syncBase.trim();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        if (!base.endsWith("/sync")) base = base + "/sync";
        this.mSyncBase = base;
    }

    public String authenticate(String username, String password) throws Exception {
        JSONObject creds = new JSONObject();
        creds.put("u", username);
        creds.put("p", password);
        byte[] gzippedCreds = gzipBytes(creds.toString().getBytes("UTF-8"));

        String boundary = "----AnkiSync" + System.currentTimeMillis();
        HttpURLConnection conn = openConnection(mSyncBase + "/hostKey", "POST");
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        conn.setDoOutput(true);

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        // 1. field c = 1
        writeField(body, boundary, "c", "1");
        // 2. field data = gzipped JSON
        writeFileField(body, boundary, "data", "data", gzippedCreds);
        writeBoundaryEnd(body, boundary);

        byte[] requestData = body.toByteArray();
        conn.setFixedLengthStreamingMode(requestData.length);

        OutputStream os = conn.getOutputStream();
        os.write(requestData);
        os.flush();
        os.close();

        int code = conn.getResponseCode();
        Log.i(TAG, "Auth response code: " + code);

        if (code == 403) {
            conn.disconnect();
            throw new Exception("Invalid AnkiWeb email or password.");
        }

        if (code != 200) {
            String err = tryReadError(conn);
            conn.disconnect();
            throw new Exception("Authentication failed (HTTP " + code + "): " + err);
        }

        String respStr = readString(conn.getInputStream());
        conn.disconnect();

        JSONObject json = new JSONObject(respStr);
        if (!json.has("key") || json.getString("key").isEmpty()) {
            throw new Exception("Authentication response did not contain a session key.");
        }

        mHostKey = json.getString("key");
        Log.i(TAG, "Authenticated successfully, hostKey obtained");
        return mHostKey;
    }

    public JSONObject getMeta() throws Exception {
        if (mHostKey == null || mHostKey.isEmpty()) {
            throw new Exception("No session key available");
        }
        String boundary = "----AnkiSync" + System.currentTimeMillis();
        HttpURLConnection conn = openConnection(mSyncBase + "/meta", "POST");
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        conn.setDoOutput(true);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(30000);

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeField(body, boundary, "k", mHostKey);
        writeField(body, boundary, "v", CLIENT_VER);
        writeField(body, boundary, "c", "1");
        writeBoundaryEnd(body, boundary);

        byte[] requestData = body.toByteArray();
        conn.setFixedLengthStreamingMode(requestData.length);

        OutputStream os = conn.getOutputStream();
        os.write(requestData);
        os.flush();
        os.close();

        int code = conn.getResponseCode();
        Log.i(TAG, "Meta response code: " + code);

        if (code == 403) {
            conn.disconnect();
            throw new Exception("SESSION_EXPIRED");
        }

        if (code != 200) {
            String err = tryReadError(conn);
            conn.disconnect();
            throw new Exception("Meta failed (HTTP " + code + "): " + err);
        }

        String respStr = readString(conn.getInputStream());
        conn.disconnect();
        return new JSONObject(respStr);
    }

    public void downloadCollection(String destPath) throws Exception {
        String boundary = "----AnkiSync" + System.currentTimeMillis();
        HttpURLConnection conn = openConnection(mSyncBase + "/download", "POST");
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        conn.setDoOutput(true);
        conn.setConnectTimeout(60000);
        conn.setReadTimeout(180000);

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeField(body, boundary, "k", mHostKey);
        writeField(body, boundary, "v", CLIENT_VER);
        writeField(body, boundary, "c", "1");
        writeBoundaryEnd(body, boundary);

        byte[] requestData = body.toByteArray();
        conn.setFixedLengthStreamingMode(requestData.length);

        OutputStream os = conn.getOutputStream();
        os.write(requestData);
        os.flush();
        os.close();

        int code = conn.getResponseCode();
        Log.i(TAG, "Download response code: " + code);

        if (code != 200) {
            String err = tryReadError(conn);
            conn.disconnect();
            throw new Exception("Download failed (HTTP " + code + "): " + err);
        }

        File tempFile = new File(destPath + ".tmp");
        if (tempFile.exists()) tempFile.delete();
        tempFile.getParentFile().mkdirs();

        InputStream in = conn.getInputStream();
        // Check if stream is gzipped
        String enc = conn.getHeaderField("Content-Encoding");
        InputStream is = "gzip".equalsIgnoreCase(enc) ? new GZIPInputStream(in) : in;

        FileOutputStream fos = new FileOutputStream(tempFile);
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) {
            fos.write(buf, 0, n);
        }
        fos.flush();
        fos.close();
        is.close();
        conn.disconnect();

        // If file is still gzipped (e.g. server sent gzip without header)
        if (isGzipFile(tempFile)) {
            Log.i(TAG, "Decompressing gzipped temp file");
            File unzipped = new File(destPath + ".tmp2");
            GZIPInputStream gis = new GZIPInputStream(new FileInputStream(tempFile));
            FileOutputStream uos = new FileOutputStream(unzipped);
            while ((n = gis.read(buf)) != -1) {
                uos.write(buf, 0, n);
            }
            uos.flush();
            uos.close();
            gis.close();
            tempFile.delete();
            tempFile = unzipped;
        }

        File dest = new File(destPath);
        if (dest.exists()) dest.delete();
        if (!tempFile.renameTo(dest)) {
            // Fallback copy
            copyFile(tempFile, dest);
            tempFile.delete();
        }

        Log.i(TAG, "Collection saved to " + destPath + " (" + dest.length() + " bytes)");
    }

    public void uploadCollection(String dbPath) throws Exception {
        File dbFile = new File(dbPath);
        if (!dbFile.exists()) throw new Exception("Database file not found: " + dbPath);

        // 1. Gzip compress collection to a temporary disk file with a small 8KB buffer (NO OutOfMemory!)
        File tempGz = new File(dbPath + ".upload.gz");
        if (tempGz.exists()) tempGz.delete();

        FileInputStream fis = new FileInputStream(dbFile);
        FileOutputStream fos = new FileOutputStream(tempGz);
        GZIPOutputStream gzos = new GZIPOutputStream(fos);
        byte[] buf = new byte[8192];
        int n;
        while ((n = fis.read(buf)) != -1) {
            gzos.write(buf, 0, n);
        }
        gzos.finish();
        gzos.flush();
        gzos.close();
        fis.close();

        // 2. Build multipart prefix and suffix
        String boundary = "----AnkiSync" + System.currentTimeMillis();
        String CRLF = "\r\n";

        ByteArrayOutputStream prefixBuf = new ByteArrayOutputStream();
        writeField(prefixBuf, boundary, "k", mHostKey);
        writeField(prefixBuf, boundary, "v", CLIENT_VER);
        writeField(prefixBuf, boundary, "c", "1");
        // File field header for gzipped collection
        prefixBuf.write(("--" + boundary + CRLF).getBytes("ASCII"));
        prefixBuf.write(("Content-Disposition: form-data; name=\"data\"; filename=\"data\"" + CRLF).getBytes("ASCII"));
        prefixBuf.write(("Content-Type: application/octet-stream" + CRLF + CRLF).getBytes("ASCII"));

        ByteArrayOutputStream suffixBuf = new ByteArrayOutputStream();
        suffixBuf.write((CRLF + "--" + boundary + "--" + CRLF).getBytes("ASCII"));

        byte[] prefix = prefixBuf.toByteArray();
        byte[] suffix = suffixBuf.toByteArray();
        long totalLength = prefix.length + tempGz.length() + suffix.length;

        HttpURLConnection conn = openConnection(mSyncBase + "/upload", "POST");
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        conn.setDoOutput(true);
        conn.setConnectTimeout(60000);
        conn.setReadTimeout(300000);
        conn.setFixedLengthStreamingMode((int) totalLength);

        OutputStream os = conn.getOutputStream();
        os.write(prefix);

        // 3. Stream compressed file directly to connection
        FileInputStream gzIn = new FileInputStream(tempGz);
        while ((n = gzIn.read(buf)) != -1) {
            os.write(buf, 0, n);
        }
        gzIn.close();

        os.write(suffix);
        os.flush();
        os.close();

        // Clean up temporary gzip file
        tempGz.delete();

        int code = conn.getResponseCode();
        Log.i(TAG, "Upload response code: " + code);

        if (code != 200) {
            String err = tryReadError(conn);
            conn.disconnect();
            throw new Exception("Upload failed (HTTP " + code + "): " + err);
        }
        conn.disconnect();
        Log.i(TAG, "Collection upload completed successfully (" + dbFile.length() + " bytes)");
    }

    private HttpURLConnection openConnection(String url, String method) throws IOException {
        URL u = new URL(url);
        HttpURLConnection conn = (HttpURLConnection) u.openConnection();
        if (conn instanceof HttpsURLConnection) {
            TLSSocketFactory.enableTLS((HttpsURLConnection) conn);
        }
        conn.setRequestMethod(method);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(60000);
        conn.setUseCaches(false);
        return conn;
    }

    private void writeField(OutputStream os, String boundary, String name, String value) throws IOException {
        os.write(("--" + boundary + "\r\n").getBytes("ASCII"));
        os.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes("ASCII"));
        os.write((value + "\r\n").getBytes("UTF-8"));
    }

    private void writeFileField(OutputStream os, String boundary, String fieldName, String filename, byte[] data) throws IOException {
        os.write(("--" + boundary + "\r\n").getBytes("ASCII"));
        os.write(("Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\"" + filename + "\"\r\n").getBytes("ASCII"));
        os.write(("Content-Type: application/octet-stream\r\n\r\n").getBytes("ASCII"));
        os.write(data);
        os.write(("\r\n").getBytes("ASCII"));
    }

    private void writeBoundaryEnd(OutputStream os, String boundary) throws IOException {
        os.write(("--" + boundary + "--\r\n").getBytes("ASCII"));
    }

    private String readString(InputStream is) throws IOException {
        BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        return sb.toString();
    }

    private String tryReadError(HttpURLConnection conn) {
        try {
            InputStream es = conn.getErrorStream();
            return es != null ? readString(es) : "";
        } catch (Exception e) {
            return "";
        }
    }

    private byte[] gzipBytes(byte[] in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        GZIPOutputStream gzos = new GZIPOutputStream(baos);
        gzos.write(in);
        gzos.finish();
        gzos.close();
        return baos.toByteArray();
    }

    private byte[] gzipFile(String path) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        GZIPOutputStream gzos = new GZIPOutputStream(baos);
        FileInputStream fis = new FileInputStream(path);
        byte[] buf = new byte[8192];
        int n;
        while ((n = fis.read(buf)) != -1) gzos.write(buf, 0, n);
        fis.close();
        gzos.finish();
        gzos.close();
        return baos.toByteArray();
    }

    private boolean isGzipFile(File f) {
        try {
            FileInputStream fis = new FileInputStream(f);
            byte[] magic = new byte[2];
            int r = fis.read(magic);
            fis.close();
            return r == 2 && (magic[0] == (byte) 0x1f) && (magic[1] == (byte) 0x8b);
        } catch (Exception e) {
            return false;
        }
    }

    private void copyFile(File src, File dst) throws IOException {
        FileInputStream in = new FileInputStream(src);
        FileOutputStream out = new FileOutputStream(dst);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        in.close();
        out.close();
    }

    public String getHostKey() { return mHostKey; }
    public void setHostKey(String hk) { mHostKey = hk; }
}