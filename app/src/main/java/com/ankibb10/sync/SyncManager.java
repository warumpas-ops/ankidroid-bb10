package com.ankibb10.sync;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.util.Log;

import com.ankibb10.db.AnkiDatabase;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

public class SyncManager {
    private static final String TAG = "SyncManager";

    public static final String PREFS       = "ankibb10_prefs";
    public static final String KEY_HOSTKEY = "host_key";
    public static final String KEY_SYNCURL = "sync_url";
    public static final String KEY_USER    = "username";
    public static final String KEY_PASS    = "password";
    public static final String KEY_LAST_SYNC = "last_sync_time";
    public static final String DEFAULT_URL = "https://sync.ankiweb.net";

    private static final AtomicBoolean sIsSyncing = new AtomicBoolean(false);

    public interface SyncListener {
        void onProgress(String message);
        void onSuccess(String message);
        void onError(String error);
    }

    private final Context mContext;
    public SyncManager(Context context) { mContext = context.getApplicationContext(); }

    public boolean isNetworkAvailable() {
        try {
            ConnectivityManager cm = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            NetworkInfo netInfo = cm.getActiveNetworkInfo();
            return netInfo != null && netInfo.isConnected();
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isSyncing() {
        return sIsSyncing.get();
    }

    public void login(String username, String password, SyncListener listener) {
        SharedPreferences prefs = mContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String syncUrl = prefs.getString(KEY_SYNCURL, DEFAULT_URL);
        try {
            listener.onProgress("Connecting to AnkiWeb...");
            AnkiWebSyncClient client = new AnkiWebSyncClient(syncUrl);
            String hkey = client.authenticate(username, password);

            // Save credentials and cluster URL immediately after auth — before any download attempt
            SharedPreferences.Editor ed = prefs.edit()
                    .putString(KEY_HOSTKEY, hkey)
                    .putString(KEY_USER, username)
                    .putString(KEY_PASS, password);
            // Persist the cluster URL so future syncs route to the correct server
            if (client.getSyncBase() != null && !client.getSyncBase().equals(syncUrl)) {
                ed.putString(KEY_SYNCURL, client.getSyncBase());
            }
            ed.commit();

            // Abort any stuck server transaction before downloading (fixes 409)
            listener.onProgress("Preparing sync...");
            client.abortSync();
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}

            // Clean stale WAL/SHM before download
            new File(getDbPath() + "-wal").delete();
            new File(getDbPath() + "-shm").delete();

            // Download collection
            listener.onProgress("Downloading collection from AnkiWeb...");
            client.downloadCollection(getDbPath());
            try {
                AnkiDatabase localDb = new AnkiDatabase(getDbPath());
                localDb.checkpointWal();
                localDb.close();
            } catch (Exception ignored) {}
            prefs.edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).commit();
            listener.onSuccess("Logged in and collection synced!");
        } catch (Exception e) {
            Log.e(TAG, "Login failed", e);
            String msg = e.getMessage();
            if (msg == null || msg.isEmpty()) msg = e.toString();
            listener.onError(msg);
        }
    }

    public void autoSync(final SyncListener listener) {
        if (!isLoggedIn()) return;
        if (!isNetworkAvailable()) {
            if (listener != null) listener.onError("Offline: changes saved locally");
            return;
        }
        if (sIsSyncing.get()) return;

        new Thread(new Runnable() {
            public void run() {
                sync(listener);
            }
        }).start();
    }

    public void sync(SyncListener listener) {
        if (listener == null) {
            listener = new SyncListener() {
                public void onProgress(String message) { Log.d(TAG, "Sync progress: " + message); }
                public void onSuccess(String message)  { Log.i(TAG, "Sync success: " + message); }
                public void onError(String error)      { Log.w(TAG, "Sync error: " + error); }
            };
        }

        if (!sIsSyncing.compareAndSet(false, true)) {
            listener.onError("Sync is already in progress. Please wait a moment.");
            return;
        }

        try {
            SharedPreferences prefs = mContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String hkey     = prefs.getString(KEY_HOSTKEY, null);
            String username = prefs.getString(KEY_USER, null);
            String password = prefs.getString(KEY_PASS, null);
            String syncUrl  = prefs.getString(KEY_SYNCURL, DEFAULT_URL);
            String dbPath   = getDbPath();

            if ((hkey == null || hkey.isEmpty()) && (username == null || password == null)) {
                listener.onError("Not logged in to AnkiWeb");
                return;
            }

            if (!isNetworkAvailable()) {
                listener.onError("Offline: study activity saved locally");
                return;
            }

            AnkiWebSyncClient client = new AnkiWebSyncClient(syncUrl);

            // Re-authenticate if credentials available to keep session fresh and ensure correct cluster URL
            if (username != null && password != null && !password.isEmpty()) {
                listener.onProgress("Connecting to AnkiWeb...");
                try {
                    hkey = client.authenticate(username, password);
                    prefs.edit()
                            .putString(KEY_HOSTKEY, hkey)
                            .putString(KEY_SYNCURL, client.getSyncBase())
                            .commit();
                    client.setHostKey(hkey);
                } catch (Exception authEx) {
                    Log.w(TAG, "Re-auth during sync failed, using existing key: " + authEx.getMessage());
                    if (hkey != null && !hkey.isEmpty()) {
                        client.setHostKey(hkey);
                    } else {
                        throw authEx;
                    }
                }
            } else if (hkey != null && !hkey.isEmpty()) {
                client.setHostKey(hkey);
            } else {
                listener.onError("Not logged in to AnkiWeb");
                return;
            }

            // Always clear any pending server lock before doing any operation
            client.abortSync();
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}

            File dbFile = new File(dbPath);
            if (!dbFile.exists()) {
                // No local collection exists yet -> Download from AnkiWeb
                listener.onProgress("Downloading full collection from AnkiWeb...");
                new File(dbPath + "-wal").delete();
                new File(dbPath + "-shm").delete();

                client.downloadCollection(dbPath);
                try {
                    AnkiDatabase localDb = new AnkiDatabase(dbPath);
                    localDb.checkpointWal();
                    localDb.close();
                } catch (Exception ignored) {}

                prefs.edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).commit();
                listener.onSuccess("Downloaded latest collection!");
                return;
            }

            // Check if user has offline changes on device
            boolean hasLocalChanges = false;
            try {
                AnkiDatabase localDb = new AnkiDatabase(dbPath);
                hasLocalChanges = localDb.hasUnsyncedChanges();
                localDb.checkpointWal();
                localDb.close();
            } catch (Exception e) {
                Log.e(TAG, "Error checking local DB status", e);
            }

            if (hasLocalChanges) {
                // User studied cards on device -> Upload progress to AnkiWeb
                listener.onProgress("Preparing study activity for upload...");
                try {
                    AnkiDatabase localDb = new AnkiDatabase(dbPath);
                    localDb.prepareForUpload(1, System.currentTimeMillis() / 1000L);
                    localDb.close();
                } catch (Exception e) {
                    Log.e(TAG, "Error preparing upload", e);
                }

                listener.onProgress("Uploading study activity to AnkiWeb...");
                client.uploadCollection(dbPath);
                prefs.edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).commit();
                listener.onSuccess("Synced: study activity uploaded to AnkiWeb!");
            } else {
                // No local changes on device -> Download latest from PC/AnkiWeb
                listener.onProgress("Downloading latest collection from AnkiWeb...");
                new File(dbPath + "-wal").delete();
                new File(dbPath + "-shm").delete();

                client.downloadCollection(dbPath);
                try {
                    AnkiDatabase localDb = new AnkiDatabase(dbPath);
                    localDb.checkpointWal();
                    localDb.close();
                } catch (Exception ignored) {}

                prefs.edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).commit();
                listener.onSuccess("Synced: latest collection downloaded from AnkiWeb!");
            }

        } catch (Exception e) {
            Log.e(TAG, "Sync failed", e);
            String msg = e.getMessage();
            if (msg == null || msg.isEmpty()) msg = e.toString();
            // Make the error message actionable
            if (msg.contains("403") || msg.contains("SESSION_EXPIRED") || msg.contains("Invalid") || msg.contains("credentials")) {
                listener.onError("SESSION_EXPIRED: AnkiWeb session expired. Tap 'Reconnect Account' to re-enter your credentials.");
            } else if (msg.contains("UnknownHost") || msg.contains("timeout") || msg.contains("SocketTimeout") || msg.contains("ConnectException")) {
                listener.onError("Network error. Check your Wi-Fi/mobile connection and try again.");
            } else {
                listener.onError("Sync failed: " + msg);
            }
        } finally {
            sIsSyncing.set(false);
        }
    }

    public void forceDownload(final SyncListener listener) {
        if (!isLoggedIn()) {
            if (listener != null) listener.onError("Not logged in");
            return;
        }
        if (!isNetworkAvailable()) {
            if (listener != null) listener.onError("No network available");
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                try {
                    sIsSyncing.set(true);
                    SharedPreferences prefs = mContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                    String username = prefs.getString(KEY_USER, null);
                    String password = prefs.getString(KEY_PASS, null);
                    String syncUrl  = prefs.getString(KEY_SYNCURL, DEFAULT_URL);
                    String dbPath   = getDbPath();

                    // Always re-authenticate: guarantees a fresh hostKey AND the correct cluster URL
                    if (listener != null) listener.onProgress("Authenticating with AnkiWeb...");
                    if (username == null || password == null || password.isEmpty()) {
                        if (listener != null) listener.onError("No credentials stored. Please log out and log in again.");
                        return;
                    }

                    AnkiWebSyncClient client = new AnkiWebSyncClient(syncUrl);
                    String hkey = client.authenticate(username, password);
                    prefs.edit()
                        .putString(KEY_HOSTKEY, hkey)
                        .putString(KEY_SYNCURL, client.getSyncBase())
                        .commit();

                    // Clean stale WAL/SHM files before writing new DB
                    new File(dbPath + "-wal").delete();
                    new File(dbPath + "-shm").delete();

                    // Attempt download — abort stuck transactions between retries
                    Exception lastError = null;
                    int maxAttempts = 3;
                    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                        if (listener != null) listener.onProgress(
                            attempt == 1
                                ? "Downloading collection from AnkiWeb..."
                                : "Retrying download (attempt " + attempt + "/" + maxAttempts + ")...");

                        // Always abort before each attempt to clear any server-side lock
                        client.abortSync();

                        // Small pause to let the server process the abort
                        try { Thread.sleep(800); } catch (InterruptedException ignored) {}

                        try {
                            client.downloadCollection(dbPath);
                            // Success!
                            prefs.edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).commit();
                            if (listener != null) listener.onSuccess("Collection downloaded successfully!");
                            return;
                        } catch (Exception e) {
                            lastError = e;
                            Log.w(TAG, "Download attempt " + attempt + " failed: " + e.getMessage());
                            if (attempt < maxAttempts) {
                                try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
                            }
                        }
                    }

                    // All attempts exhausted
                    String errMsg = lastError != null ? lastError.getMessage() : "Unknown error";
                    if (listener != null) listener.onError("Download failed after " + maxAttempts + " attempts: " + errMsg);

                } catch (Exception e) {
                    Log.e(TAG, "Force download failed", e);
                    if (listener != null) listener.onError("Download failed: " + e.getMessage());
                } finally {
                    sIsSyncing.set(false);
                }
            }
        }).start();
    }

    public void forceUpload(final SyncListener listener) {
        if (!isLoggedIn()) {
            if (listener != null) listener.onError("Not logged in");
            return;
        }
        if (!isNetworkAvailable()) {
            if (listener != null) listener.onError("No network available");
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                try {
                    sIsSyncing.set(true);
                    if (listener != null) listener.onProgress("Connecting to AnkiWeb...");
                    SharedPreferences prefs = mContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                    String hkey     = prefs.getString(KEY_HOSTKEY, null);
                    String username = prefs.getString(KEY_USER, null);
                    String password = prefs.getString(KEY_PASS, null);
                    String syncUrl  = prefs.getString(KEY_SYNCURL, DEFAULT_URL);
                    String dbPath   = getDbPath();

                    AnkiWebSyncClient client = new AnkiWebSyncClient(syncUrl);
                    if (username != null && password != null && !password.isEmpty()) {
                        if (listener != null) listener.onProgress("Authenticating with AnkiWeb...");
                        hkey = client.authenticate(username, password);
                        prefs.edit()
                                .putString(KEY_HOSTKEY, hkey)
                                .putString(KEY_SYNCURL, client.getSyncBase())
                                .commit();
                        client.setHostKey(hkey);
                    } else if (hkey != null && !hkey.isEmpty()) {
                        client.setHostKey(hkey);
                    } else {
                        if (listener != null) listener.onError("Not logged in to AnkiWeb");
                        return;
                    }

                    // Abort any pending server lock before upload
                    client.abortSync();
                    try { Thread.sleep(500); } catch (InterruptedException ignored) {}

                    // Checkpoint WAL and prepare collection for upload
                    if (listener != null) listener.onProgress("Preparing collection for upload...");
                    try {
                        AnkiDatabase db = new AnkiDatabase(dbPath);
                        db.checkpointWal();
                        db.prepareForUpload(1, System.currentTimeMillis() / 1000L);
                        db.close();
                    } catch (Exception e) {
                        Log.e(TAG, "prepareForUpload error in forceUpload", e);
                    }

                    if (listener != null) listener.onProgress("Uploading collection to AnkiWeb...");
                    client.uploadCollection(dbPath);
                    prefs.edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).commit();
                    if (listener != null) listener.onSuccess("Upload complete! Your study progress is now on AnkiWeb.\nNow sync on your PC and click 'Download from AnkiWeb'.");
                } catch (Exception e) {
                    Log.e(TAG, "Force upload failed", e);
                    String msg = e.getMessage();
                    if (msg != null && msg.contains("SESSION_EXPIRED")) {
                        if (listener != null) listener.onError("SESSION_EXPIRED: " + msg);
                    } else {
                        if (listener != null) listener.onError("Upload failed: " + (msg != null ? msg : e.toString()));
                    }
                } finally {
                    sIsSyncing.set(false);
                }
            }
        }).start();
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

    public String getDbPath() {
        return new File(mContext.getFilesDir(), "collection.anki2").getAbsolutePath();
    }

    public boolean isLoggedIn() {
        return !mContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .getString(KEY_HOSTKEY, "").isEmpty()
                || !mContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .getString(KEY_USER, "").isEmpty();
    }

    public boolean hasLocalCollection() { return new File(getDbPath()).exists(); }

    public String getUsername() {
        return mContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_USER, "");
    }

    public void logout() {
        mContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().clear().commit();
        new File(getDbPath()).delete();
        File wal = new File(getDbPath() + "-wal");
        if (wal.exists()) wal.delete();
        File shm = new File(getDbPath() + "-shm");
        if (shm.exists()) shm.delete();
    }
}