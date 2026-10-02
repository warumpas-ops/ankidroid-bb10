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
            prefs.edit()
                    .putString(KEY_HOSTKEY, hkey)
                    .putString(KEY_USER, username)
                    .putString(KEY_PASS, password)
                    .commit();

            listener.onProgress("Downloading collection...");
            client.downloadCollection(getDbPath());
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
            client.setHostKey(hkey);

            // 1. Check metadata and refresh session if needed
            JSONObject meta = null;
            try {
                listener.onProgress("Checking AnkiWeb status...");
                meta = client.getMeta();
            } catch (Exception e) {
                if (username != null && password != null && !password.isEmpty()) {
                    listener.onProgress("Refreshing session...");
                    hkey = client.authenticate(username, password);
                    prefs.edit().putString(KEY_HOSTKEY, hkey).commit();
                    client.setHostKey(hkey);
                    try {
                        meta = client.getMeta();
                    } catch (Exception e2) {
                        Log.w(TAG, "Meta check after re-auth warning", e2);
                    }
                } else {
                    throw new Exception("SESSION_EXPIRED: AnkiWeb session expired after PC sync. Please reconnect your account.");
                }
            }

            // 2. Initial download if no local collection exists
            File dbFile = new File(dbPath);
            if (!dbFile.exists()) {
                listener.onProgress("Downloading full collection from AnkiWeb...");
                client.downloadCollection(dbPath);
                prefs.edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).commit();
                listener.onSuccess("Downloaded latest collection!");
                return;
            }

            // 3. Inspect local database status & extract any offline pending reviews
            boolean hasLocalChanges = false;
            long localMod = 0;
            AnkiDatabase.PendingChanges pending = null;

            int localUsn = 0;
            long localScm = 0;
            try {
                AnkiDatabase localDb = new AnkiDatabase(dbPath);
                hasLocalChanges = localDb.hasUnsyncedChanges();
                localMod = localDb.getColMod();
                localScm = localDb.getColScm();
                localUsn = localDb.getColUsn();
                if (hasLocalChanges) {
                    pending = localDb.getPendingChanges();
                }
                localDb.checkpointWal();
                localDb.close();
            } catch (Exception e) {
                Log.e(TAG, "Error checking local DB status", e);
            }

            long serverMod = (meta != null && meta.has("mod")) ? meta.optLong("mod", 0) : 0;
            int serverUsn  = (meta != null && meta.has("usn")) ? meta.optInt("usn", 0) : 0;
            long serverScm = (meta != null && meta.has("scm")) ? meta.optLong("scm", 0) : 0;
            long normLocalMod  = localMod > 100000000000L ? (localMod / 1000L) : localMod;
            long normServerMod = serverMod > 100000000000L ? (serverMod / 1000L) : serverMod;
            boolean serverNewer = (normServerMod > normLocalMod)
                    || (serverUsn > localUsn)
                    || (serverScm > 0 && localScm > 0 && serverScm != localScm)
                    || (!hasLocalChanges && (normServerMod != normLocalMod || serverUsn != localUsn));
            Log.i(TAG, "Sync check: hasLocalChanges=" + hasLocalChanges + ", localUsn=" + localUsn + ", serverUsn=" + serverUsn + ", normLocalMod=" + normLocalMod + ", normServerMod=" + normServerMod + ", serverNewer=" + serverNewer);

            if (hasLocalChanges && serverNewer && pending != null && !pending.isEmpty()) {
                // MERGE SCENARIO: User studied on PC (server is newer) AND has offline reviews on BlackBerry!
                listener.onProgress("Merging offline reviews with PC changes...");

                // Step 1: Download PC/Server version to a temp file
                String tempPath = dbPath + ".server";
                client.downloadCollection(tempPath);

                // Step 2: Merge the local offline reviews into the freshly downloaded collection
                try {
                    AnkiDatabase serverDb = new AnkiDatabase(tempPath);
                    serverDb.applyPendingChanges(pending, serverUsn + 1, normServerMod);
                    serverDb.close();
                } catch (Exception e) {
                    Log.e(TAG, "Error applying pending changes to downloaded collection", e);
                }

                // Step 3: Replace local database with the merged collection
                File serverFile = new File(tempPath);
                File localFile = new File(dbPath);
                if (localFile.exists()) localFile.delete();
                File wal = new File(dbPath + "-wal");
                if (wal.exists()) wal.delete();
                File shm = new File(dbPath + "-shm");
                if (shm.exists()) shm.delete();

                if (!serverFile.renameTo(localFile)) {
                    copyFile(serverFile, localFile);
                    serverFile.delete();
                }

                // Step 4: Upload the merged collection back to AnkiWeb
                listener.onProgress("Uploading merged progress to AnkiWeb...");
                client.uploadCollection(dbPath);

                prefs.edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).commit();
                listener.onSuccess("Synced: offline reviews merged with PC!");

            } else if (hasLocalChanges) {
                // NORMAL UPLOAD: User studied on BlackBerry, PC has no new changes
                listener.onProgress("Preparing collection for AnkiWeb...");
                try {
                    AnkiDatabase localDb = new AnkiDatabase(dbPath);
                    localDb.prepareForUpload(serverUsn > 0 ? serverUsn : 1, normServerMod);
                    localDb.close();
                } catch (Exception e) {
                    Log.e(TAG, "Error preparing upload", e);
                }

                listener.onProgress("Uploading study activity to AnkiWeb...");
                client.uploadCollection(dbPath);

                prefs.edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).commit();
                listener.onSuccess("Synced: all study activity uploaded to AnkiWeb!");

            } else if (serverNewer) {
                // NORMAL DOWNLOAD: User studied on PC, no local changes on BlackBerry
                listener.onProgress("Downloading newer collection from AnkiWeb...");

                // Clean stale WAL/SHM before writing new DB
                File wal = new File(dbPath + "-wal");
                if (wal.exists()) wal.delete();
                File shm = new File(dbPath + "-shm");
                if (shm.exists()) shm.delete();

                client.downloadCollection(dbPath);

                // Checkpoint newly downloaded database
                try {
                    AnkiDatabase localDb = new AnkiDatabase(dbPath);
                    localDb.checkpointWal();
                    localDb.close();
                } catch (Exception ignored) {}

                prefs.edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).commit();
                listener.onSuccess("Synced: latest collection downloaded from PC!");

            } else {
                // In sync!
                prefs.edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).commit();
                listener.onSuccess("Collection is already up-to-date.");
            }

        } catch (Exception e) {
            Log.e(TAG, "Sync failed", e);
            String msg = e.getMessage();
            if (msg == null || msg.isEmpty()) msg = e.toString();
            listener.onError("Sync failed: " + msg);
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
                    if (listener != null) listener.onProgress("Connecting to AnkiWeb...");
                    SharedPreferences prefs = mContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                    String hkey     = prefs.getString(KEY_HOSTKEY, null);
                    String username = prefs.getString(KEY_USER, null);
                    String password = prefs.getString(KEY_PASS, null);
                    String syncUrl  = prefs.getString(KEY_SYNCURL, DEFAULT_URL);
                    String dbPath   = getDbPath();

                    AnkiWebSyncClient client = new AnkiWebSyncClient(syncUrl);
                    client.setHostKey(hkey);

                    try {
                        client.getMeta();
                    } catch (Exception e) {
                        if (username != null && password != null && !password.isEmpty()) {
                            hkey = client.authenticate(username, password);
                            prefs.edit().putString(KEY_HOSTKEY, hkey).commit();
                            client.setHostKey(hkey);
                        } else {
                            throw e;
                        }
                    }

                    if (listener != null) listener.onProgress("Downloading full collection from AnkiWeb...");
                    File wal = new File(dbPath + "-wal");
                    if (wal.exists()) wal.delete();
                    File shm = new File(dbPath + "-shm");
                    if (shm.exists()) shm.delete();

                    client.downloadCollection(dbPath);
                    prefs.edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).commit();
                    if (listener != null) listener.onSuccess("Successfully downloaded latest collection from AnkiWeb!");
                } catch (Exception e) {
                    Log.e(TAG, "Force download failed", e);
                    if (listener != null) listener.onError("Download failed: " + e.getMessage());
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