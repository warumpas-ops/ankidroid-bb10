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
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

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

        // Update cluster URL if hostNum is returned (e.g. hostNum=10 -> sync10.ankiweb.net)
        if (json.has("hostNum")) {
            int hostNum = json.optInt("hostNum", 0);
            if (hostNum > 0) {
                mSyncBase = "https://sync" + hostNum + ".ankiweb.net/sync";
                Log.i(TAG, "Using cluster URL: " + mSyncBase);
            }
        } else if (json.has("syncUrl")) {
            String rawUrl = json.getString("syncUrl").trim();
            if (rawUrl.endsWith("/")) rawUrl = rawUrl.substring(0, rawUrl.length() - 1);
            if (!rawUrl.endsWith("/sync")) rawUrl = rawUrl + "/sync";
            mSyncBase = rawUrl;
            Log.i(TAG, "Using syncUrl: " + mSyncBase);
        }

        Log.i(TAG, "Authenticated successfully, hostKey obtained");
        return mHostKey;
    }

    /**
     * Call /sync/abort to clear any stuck pending transaction on the server.
     * Always safe to call even if the server has no pending transaction — returns OK either way.
     * This fixes HTTP 409 errors on subsequent /download calls.
     */
    public void abortSync() {
        if (mHostKey == null || mHostKey.isEmpty()) return;
        try {
            String boundary = "----AnkiSync" + System.currentTimeMillis();
            HttpURLConnection conn = openConnection(mSyncBase + "/abort", "POST");
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);

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
            Log.i(TAG, "Abort response code: " + code);
            try {
                if (code == 200) {
                    InputStream is = conn.getInputStream();
                    byte[] b = new byte[1024];
                    while (is.read(b) != -1) {}
                    is.close();
                } else {
                    InputStream es = conn.getErrorStream();
                    if (es != null) {
                        byte[] b = new byte[1024];
                        while (es.read(b) != -1) {}
                        es.close();
                    }
                }
            } catch (Exception ignored) {}
            conn.disconnect();
        } catch (Exception e) {
            // Abort is best-effort — ignore errors, just log
            Log.w(TAG, "Abort sync call failed (ignored): " + e.getMessage());
        }
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
        File wal = new File(destPath + "-wal");
        if (wal.exists()) wal.delete();
        File shm = new File(destPath + "-shm");
        if (shm.exists()) shm.delete();

        if (!tempFile.renameTo(dest)) {
            // Fallback copy
            copyFile(tempFile, dest);
            tempFile.delete();
        }

        if (!dest.exists() || dest.length() < 100) {
            throw new Exception("Downloaded collection is empty or corrupt");
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

        if (code == 403) {
            conn.disconnect();
            throw new Exception("SESSION_EXPIRED: AnkiWeb session expired. Please reconnect your account.");
        }

        if (code != 200) {
            String err = tryReadError(conn);
            conn.disconnect();
            throw new Exception("Upload failed (HTTP " + code + "): " + err);
        }

        // AnkiWeb returns HTTP 200 even for errors — must check the response body
        String respBody = "";
        try {
            respBody = readString(conn.getInputStream()).trim();
        } catch (Exception ignored) {}
        conn.disconnect();

        Log.i(TAG, "Upload response body: " + respBody);

        if (!"OK".equalsIgnoreCase(respBody) && !respBody.isEmpty()) {
            if ("hostKey".equalsIgnoreCase(respBody) || "forbidden".equalsIgnoreCase(respBody)) {
                throw new Exception("SESSION_EXPIRED: AnkiWeb session expired. Please reconnect your account.");
            }
            if (respBody.toLowerCase().contains("corrupt") || respBody.toLowerCase().contains("error")) {
                throw new Exception("Upload rejected by AnkiWeb: " + respBody);
            }
        }
        Log.i(TAG, "Collection upload completed successfully (" + dbFile.length() + " bytes), response: " + respBody);
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
    public String getSyncBase() { return mSyncBase; }

    // -------------------------------------------------------------------------
    // MEDIA SYNC
    // AnkiWeb media sync protocol:
    //   msync/begin        → get session key (sk) and server USN
    //   msync/mediaChanges → list of [filename, usn, md5] changed on server
    //   msync/downloadFiles→ zip of requested files
    //   msync/uploadChanges→ zip of local-new files  (we skip: read-only client)
    //   msync/mediaSanity  → finalize / confirm counts match
    // -------------------------------------------------------------------------

    private String mMediaBase; // e.g. https://sync5.ankiweb.net/msync

    private String getMediaBase() {
        if (mMediaBase != null) return mMediaBase;
        // Derive from mSyncBase: replace /sync suffix with /msync
        String base = mSyncBase;
        if (base.endsWith("/sync")) {
            mMediaBase = base.substring(0, base.length() - 5) + "/msync";
        } else {
            mMediaBase = base.replace("/sync/", "/msync/");
        }
        return mMediaBase;
    }

    public interface MediaSyncListener {
        void onProgress(String msg);
    }

    /**
     * Full media sync: downloads all server-side media files missing locally.
     * Returns number of files downloaded.
     */
    public int syncMedia(File mediaDir, MediaSyncListener listener) throws Exception {
        if (mHostKey == null || mHostKey.isEmpty()) throw new Exception("Not authenticated");
        if (!mediaDir.exists()) mediaDir.mkdirs();

        // 1. Begin media sync — get session key + server USN
        if (listener != null) listener.onProgress("Starting media sync...");
        String[] beginResult = mediaSyncBegin();
        String sk   = beginResult[0];
        int serverUsn = Integer.parseInt(beginResult[1]);
        Log.i(TAG, "Media sync begin: sk=" + sk + " serverUSN=" + serverUsn);

        // 2. Fetch list of server changes since USN 0 (full sync, grab everything)
        if (listener != null) listener.onProgress("Checking media files on AnkiWeb...");
        List<String[]> changes = mediaSyncChanges(sk, 0);
        Log.i(TAG, "Server has " + changes.size() + " media entries");

        // Filter: only files we don't have locally (or deleted marker = empty hash)
        List<String> toDownload = new ArrayList<>();
        for (String[] entry : changes) {
            String filename = entry[0];
            String md5      = entry[2]; // empty string means deleted on server
            if (md5 == null || md5.isEmpty()) continue; // skip deletions
            File local = new File(mediaDir, filename);
            if (!local.exists()) {
                toDownload.add(filename);
            }
        }
        Log.i(TAG, "Need to download " + toDownload.size() + " media files");

        // 3. Download in batches of 25 (AnkiWeb limit per request)
        int downloaded = 0;
        int batchSize  = 25;
        for (int i = 0; i < toDownload.size(); i += batchSize) {
            List<String> batch = toDownload.subList(i, Math.min(i + batchSize, toDownload.size()));
            if (listener != null) {
                listener.onProgress("Downloading media " + (i + 1) + "/" + toDownload.size() + "...");
            }
            int got = mediaSyncDownloadFiles(sk, batch, mediaDir);
            downloaded += got;
        }

        // 4. Finalize — tell server how many local files we have
        int localCount = mediaDir.listFiles() != null ? mediaDir.listFiles().length : 0;
        mediaSyncFinalize(sk, localCount);

        return downloaded;
    }

    /** POST /msync/begin → returns [sessionKey, serverUSN] */
    private String[] mediaSyncBegin() throws Exception {
        String boundary = "----AnkiMedia" + System.currentTimeMillis();
        HttpURLConnection conn = openConnection(getMediaBase() + "/begin", "POST");
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        conn.setDoOutput(true);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(30000);

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeField(body, boundary, "k", mHostKey);
        writeField(body, boundary, "v", CLIENT_VER + " media 1.0");
        writeBoundaryEnd(body, boundary);

        byte[] reqData = body.toByteArray();
        conn.setFixedLengthStreamingMode(reqData.length);
        OutputStream os = conn.getOutputStream();
        os.write(reqData);
        os.flush(); os.close();

        int code = conn.getResponseCode();
        Log.i(TAG, "msync/begin code=" + code);
        if (code != 200) {
            String err = tryReadError(conn); conn.disconnect();
            throw new Exception("msync/begin failed (" + code + "): " + err);
        }

        String resp = readGzipOrPlain(conn.getInputStream());
        conn.disconnect();
        Log.i(TAG, "msync/begin resp: " + resp);

        JSONObject json = new JSONObject(resp);
        String sk = "";
        int usn = 0;
        if (json.has("data") && !json.isNull("data")) {
            JSONObject data = json.getJSONObject("data");
            sk  = data.optString("sk", mHostKey);
            usn = data.optInt("usn", 0);
        } else {
            sk  = json.optString("sk", mHostKey);
            usn = json.optInt("usn", 0);
        }
        if (sk.isEmpty()) sk = mHostKey;
        return new String[]{sk, String.valueOf(usn)};
    }

    /** POST /msync/mediaChanges → list of [filename, usn, md5] */
    private List<String[]> mediaSyncChanges(String sk, int lastUsn) throws Exception {
        String boundary = "----AnkiMedia" + System.currentTimeMillis();
        HttpURLConnection conn = openConnection(getMediaBase() + "/mediaChanges", "POST");
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        conn.setDoOutput(true);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(60000);

        JSONObject payload = new JSONObject();
        payload.put("lastUsn", lastUsn);
        byte[] gz = gzipBytes(payload.toString().getBytes("UTF-8"));

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeField(body, boundary, "sk", sk);
        writeField(body, boundary, "c", "1");
        writeFileField(body, boundary, "data", "data", gz);
        writeBoundaryEnd(body, boundary);

        byte[] reqData = body.toByteArray();
        conn.setFixedLengthStreamingMode(reqData.length);
        OutputStream os = conn.getOutputStream();
        os.write(reqData); os.flush(); os.close();

        int code = conn.getResponseCode();
        Log.i(TAG, "msync/mediaChanges code=" + code);
        if (code != 200) {
            String err = tryReadError(conn); conn.disconnect();
            throw new Exception("msync/mediaChanges failed (" + code + "): " + err);
        }

        String resp = readGzipOrPlain(conn.getInputStream());
        conn.disconnect();

        List<String[]> result = new ArrayList<String[]>();
        JSONObject json = new JSONObject(resp);
        org.json.JSONArray files = json.optJSONArray("data");
        if (files != null) {
            for (int i = 0; i < files.length(); i++) {
                org.json.JSONArray entry = files.getJSONArray(i);
                String name = entry.getString(0);
                String usn  = entry.optString(1, "0");
                String md5  = entry.optString(2, "");
                result.add(new String[]{name, usn, md5});
            }
        }
        return result;
    }

    /**
     * POST /msync/downloadFiles — download a zip of media files and extract them.
     * Maps zip entries ('0', '1', ...) using _meta mapping.
     */
    private int mediaSyncDownloadFiles(String sk, List<String> filenames, File mediaDir) throws Exception {
        String boundary = "----AnkiMedia" + System.currentTimeMillis();
        HttpURLConnection conn = openConnection(getMediaBase() + "/downloadFiles", "POST");
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        conn.setDoOutput(true);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(120000);

        JSONObject payload = new JSONObject();
        org.json.JSONArray arr = new org.json.JSONArray();
        for (String f : filenames) arr.put(f);
        payload.put("files", arr);
        byte[] gz = gzipBytes(payload.toString().getBytes("UTF-8"));

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeField(body, boundary, "sk", sk);
        writeField(body, boundary, "c", "1");
        writeFileField(body, boundary, "data", "data", gz);
        writeBoundaryEnd(body, boundary);

        byte[] reqData = body.toByteArray();
        conn.setFixedLengthStreamingMode(reqData.length);
        OutputStream os = conn.getOutputStream();
        os.write(reqData); os.flush(); os.close();

        int code = conn.getResponseCode();
        Log.i(TAG, "msync/downloadFiles code=" + code + " for " + filenames.size() + " files");
        if (code != 200) {
            String err = tryReadError(conn); conn.disconnect();
            throw new Exception("msync/downloadFiles failed (" + code + "): " + err);
        }

        // Response is a zip file (may be gzip-wrapped)
        InputStream rawStream = conn.getInputStream();
        byte[] peek = new byte[2];
        int peekRead = rawStream.read(peek);
        InputStream stream;
        if (peekRead == 2 && peek[0] == (byte)0x1f && peek[1] == (byte)0x8b) {
            java.io.PushbackInputStream pis = new java.io.PushbackInputStream(rawStream, 2);
            pis.unread(peek, 0, peekRead);
            stream = new GZIPInputStream(pis);
        } else {
            java.io.PushbackInputStream pis = new java.io.PushbackInputStream(rawStream, 2);
            if (peekRead > 0) pis.unread(peek, 0, peekRead);
            stream = pis;
        }

        // Buffer all entries in the zip
        java.util.Map<String, byte[]> zipEntries = new java.util.HashMap<String, byte[]>();
        ZipInputStream zis = new ZipInputStream(stream);
        ZipEntry entry;
        byte[] buf = new byte[8192];
        while ((entry = zis.getNextEntry()) != null) {
            String name = entry.getName();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            int n;
            while ((n = zis.read(buf)) != -1) baos.write(buf, 0, n);
            zipEntries.put(name, baos.toByteArray());
            zis.closeEntry();
        }
        zis.close();
        conn.disconnect();

        // Parse _meta to map index -> real filename
        JSONObject meta = null;
        if (zipEntries.containsKey("_meta")) {
            try {
                String metaStr = new String(zipEntries.get("_meta"), "UTF-8");
                meta = new JSONObject(metaStr);
            } catch (Exception ex) {
                Log.w(TAG, "Failed to parse _meta: " + ex.getMessage());
            }
        }

        int count = 0;
        for (java.util.Map.Entry<String, byte[]> e : zipEntries.entrySet()) {
            String key = e.getKey();
            if ("_meta".equals(key)) continue;
            String realName = key;
            if (meta != null && meta.has(key)) {
                realName = meta.getString(key);
            }
            if (realName.isEmpty() || realName.contains("..")) continue;
            File out = new File(mediaDir, realName);
            FileOutputStream fos = new FileOutputStream(out);
            fos.write(e.getValue());
            fos.flush();
            fos.close();
            count++;
            Log.d(TAG, "Saved media file: " + realName + " (" + e.getValue().length + " bytes)");
        }
        Log.i(TAG, "Successfully extracted " + count + " media files in this batch");
        return count;
    }

    /** POST /msync/mediaSanity — confirm local file count matches server */
    private void mediaSyncFinalize(String sk, int localCount) {
        try {
            String boundary = "----AnkiMedia" + System.currentTimeMillis();
            HttpURLConnection conn = openConnection(getMediaBase() + "/mediaSanity", "POST");
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);

            JSONObject payload = new JSONObject();
            payload.put("local", localCount);
            byte[] gz = gzipBytes(payload.toString().getBytes("UTF-8"));

            ByteArrayOutputStream body = new ByteArrayOutputStream();
            writeField(body, boundary, "sk", sk);
            writeField(body, boundary, "c", "1");
            writeFileField(body, boundary, "data", "data", gz);
            writeBoundaryEnd(body, boundary);

            byte[] reqData = body.toByteArray();
            conn.setFixedLengthStreamingMode(reqData.length);
            OutputStream os = conn.getOutputStream();
            os.write(reqData); os.flush(); os.close();

            int code = conn.getResponseCode();
            Log.i(TAG, "msync/mediaSanity code=" + code);
            try { conn.getInputStream().close(); } catch (Exception ignored) {}
            conn.disconnect();
        } catch (Exception e) {
            Log.w(TAG, "mediaSanity failed (ignored): " + e.getMessage());
        }
    }

    /** Read response as plain string; handles optional gzip encoding */
    private String readGzipOrPlain(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
        byte[] raw = baos.toByteArray();
        if (raw.length >= 2 && raw[0] == (byte)0x1f && raw[1] == (byte)0x8b) {
            GZIPInputStream gz = new GZIPInputStream(new java.io.ByteArrayInputStream(raw));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            while ((n = gz.read(buf)) != -1) out.write(buf, 0, n);
            gz.close();
            return out.toString("UTF-8");
        }
        return new String(raw, "UTF-8");
    }
}
