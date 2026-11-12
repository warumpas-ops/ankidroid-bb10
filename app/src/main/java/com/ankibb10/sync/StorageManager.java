package com.ankibb10.sync;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class StorageManager {
    private static final String TAG = "StorageManager";
    public static final String KEY_STORAGE_DIR = "storage_dir";

    public static class StorageLocation {
        public final String id;
        public final String title;
        public final String path;
        public final String description;
        public final boolean isRemovable;

        public StorageLocation(String id, String title, String path, String description, boolean isRemovable) {
            this.id = id;
            this.title = title;
            this.path = path;
            this.description = description;
            this.isRemovable = isRemovable;
        }
    }

    public static File getStorageDir(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(SyncManager.PREFS, Context.MODE_PRIVATE);
        String customPath = prefs.getString(KEY_STORAGE_DIR, null);
        if (customPath != null && !customPath.trim().isEmpty()) {
            File dir = new File(customPath.trim());
            if (!dir.exists()) {
                dir.mkdirs();
            }
            if (dir.exists() && dir.isDirectory() && dir.canWrite()) {
                return dir;
            }
        }

        // Auto-detect existing collection on SD card if user hasn't explicitly set a preference yet
        for (String p : getSdCardCandidates()) {
            try {
                File dir = new File(p);
                if (dir.exists() && dir.isDirectory()) {
                    File ankiDir = new File(dir, "AnkiDroid");
                    if (new File(ankiDir, "collection.anki2").exists() || new File(ankiDir, "collection.media").exists()) {
                        Log.i(TAG, "Auto-detected collection in " + ankiDir.getAbsolutePath());
                        return ankiDir;
                    }
                    if (new File(dir, "collection.anki2").exists() || new File(dir, "collection.media").exists()) {
                        Log.i(TAG, "Auto-detected collection in " + dir.getAbsolutePath());
                        return dir;
                    }
                }
            } catch (Exception ignored) {}
        }

        return context.getFilesDir();
    }

    public static void setStorageDir(Context context, String path) {
        context.getSharedPreferences(SyncManager.PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_STORAGE_DIR, path)
                .commit();
    }

    public static String getDbPath(Context context) {
        return new File(getStorageDir(context), "collection.anki2").getAbsolutePath();
    }

    public static File getMediaDir(Context context) {
        File mediaDir = new File(getStorageDir(context), "collection.media");
        if (!mediaDir.exists()) {
            mediaDir.mkdirs();
        }
        return mediaDir;
    }

    public static List<String> getSdCardCandidates() {
        List<String> sdCandidates = new ArrayList<String>();
        String secStorage = System.getenv("SECONDARY_STORAGE");
        if (secStorage != null && !secStorage.isEmpty()) {
            for (String part : secStorage.split(":")) {
                if (!part.trim().isEmpty()) sdCandidates.add(part.trim());
            }
        }

        // BlackBerry 10 native mount points & Android 4.x microSD paths
        sdCandidates.add("/accounts/1000/removable/sdcard");
        sdCandidates.add("/mnt/removable_sdcard");
        sdCandidates.add("/storage/sdcard1");
        sdCandidates.add("/mnt/sdcard/removable_sdcard");
        sdCandidates.add("/removable/sdcard");
        sdCandidates.add("/mnt/extSdCard");
        sdCandidates.add("/storage/extSdCard");
        sdCandidates.add("/mnt/external_sd");
        sdCandidates.add("/accounts/1000/shared");
        return sdCandidates;
    }

    public static List<StorageLocation> getAvailableLocations(Context context) {
        List<StorageLocation> list = new ArrayList<StorageLocation>();
        Set<String> seenPaths = new HashSet<String>();

        // 1. Internal App Sandbox
        File appFiles = context.getFilesDir();
        list.add(new StorageLocation(
                "internal_app",
                "Internal App Storage",
                appFiles.getAbsolutePath(),
                formatSpace(appFiles) + " (App Sandbox)",
                false
        ));
        seenPaths.add(appFiles.getAbsolutePath());

        // 2. Shared Internal Storage (usually /storage/emulated/0 or /sdcard)
        try {
            File ext = Environment.getExternalStorageDirectory();
            if (ext != null && ext.exists()) {
                File ankiDir = new File(ext, "AnkiDroid");
                String canonical = ankiDir.getCanonicalPath();
                if (!seenPaths.contains(canonical)) {
                    seenPaths.add(canonical);
                    list.add(new StorageLocation(
                            "internal_shared",
                            "Internal Shared Storage",
                            ankiDir.getAbsolutePath(),
                            formatSpace(ext) + " (/sdcard/AnkiDroid)",
                            false
                    ));
                }
            }
        } catch (Exception ignored) {}

        // 3. Removable MicroSD Cards (BlackBerry 10 & Android 4.3 mount points)
        int sdIndex = 1;
        for (String p : getSdCardCandidates()) {
            try {
                File dir = new File(p);
                if (dir.exists() && dir.isDirectory()) {
                    File target = new File(dir, "AnkiDroid");
                    String canonical = target.getCanonicalPath();
                    if (!seenPaths.contains(canonical)) {
                        seenPaths.add(canonical);
                        String label = "MicroSD Card" + (sdIndex > 1 ? " " + sdIndex : "");
                        list.add(new StorageLocation(
                                "sdcard_" + sdIndex,
                                label,
                                target.getAbsolutePath(),
                                formatSpace(dir) + " (" + p + ")",
                                true
                        ));
                        sdIndex++;
                    }
                }
            } catch (Exception ignored) {}
        }

        return list;
    }

    public static String formatSpace(File dir) {
        try {
            long free = dir.getFreeSpace();
            long total = dir.getTotalSpace();
            if (total > 0) {
                double freeGb = free / (1024.0 * 1024.0 * 1024.0);
                double totalGb = total / (1024.0 * 1024.0 * 1024.0);
                if (freeGb >= 1.0) {
                    return String.format(Locale.US, "%.1f GB free of %.1f GB", freeGb, totalGb);
                } else {
                    double freeMb = free / (1024.0 * 1024.0);
                    return String.format(Locale.US, "%.0f MB free of %.1f GB", freeMb, totalGb);
                }
            }
        } catch (Exception ignored) {}
        return "Available";
    }

    public interface CopyProgressListener {
        void onProgress(String message, int percent);
    }

    public static void copyCollectionData(File srcDir, File dstDir, CopyProgressListener listener) throws IOException {
        if (!dstDir.exists()) {
            if (!dstDir.mkdirs()) {
                throw new IOException("Could not create destination directory: " + dstDir.getAbsolutePath());
            }
        }

        File srcDb = new File(srcDir, "collection.anki2");
        if (srcDb.exists()) {
            if (listener != null) listener.onProgress("Copying database...", 10);
            copyFile(srcDb, new File(dstDir, "collection.anki2"));
        }
        File srcWal = new File(srcDir, "collection.anki2-wal");
        if (srcWal.exists()) copyFile(srcWal, new File(dstDir, "collection.anki2-wal"));
        File srcShm = new File(srcDir, "collection.anki2-shm");
        if (srcShm.exists()) copyFile(srcShm, new File(dstDir, "collection.anki2-shm"));

        File srcMedia = new File(srcDir, "collection.media");
        if (srcMedia.exists() && srcMedia.isDirectory()) {
            File dstMedia = new File(dstDir, "collection.media");
            if (!dstMedia.exists()) dstMedia.mkdirs();

            File[] files = srcMedia.listFiles();
            if (files != null && files.length > 0) {
                int total = files.length;
                for (int i = 0; i < total; i++) {
                    File f = files[i];
                    if (f.isFile()) {
                        copyFile(f, new File(dstMedia, f.getName()));
                    }
                    if (listener != null && i % 25 == 0) {
                        int pct = 10 + (int) (((i + 1) / (float) total) * 90);
                        listener.onProgress("Copying media (" + (i + 1) + "/" + total + ")...", pct);
                    }
                }
            }
        }
        if (listener != null) listener.onProgress("Done", 100);
    }

    public static void copyFile(File src, File dst) throws IOException {
        FileInputStream in = new FileInputStream(src);
        FileOutputStream out = new FileOutputStream(dst);
        byte[] buf = new byte[65536];
        int len;
        while ((len = in.read(buf)) > 0) {
            out.write(buf, 0, len);
        }
        in.close();
        out.close();
    }
}
