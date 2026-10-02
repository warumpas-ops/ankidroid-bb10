package com.ankibb10.db;

import android.util.Log;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class ApkgImporter {
    private static final String TAG = "ApkgImporter";

    public static void importApkg(String apkgPath, String destDbPath) throws IOException {
        File apkg = new File(apkgPath);
        if (!apkg.exists()) throw new IOException("File not found: " + apkgPath);

        File destDb = new File(destDbPath);
        File mediaDir = new File(destDb.getParentFile(), "collection.media");
        mediaDir.mkdirs();

        ZipFile zip = new ZipFile(apkg);
        try {
            // 1. Extract collection.anki2 (or 2.1 collection.anki21)
            ZipEntry colEntry = zip.getEntry("collection.anki2");
            if (colEntry == null) {
                colEntry = zip.getEntry("collection.anki21");
            }
            if (colEntry != null) {
                destDb.getParentFile().mkdirs();
                copyEntry(zip.getInputStream(colEntry), destDb);
            } else {
                throw new IOException("collection.anki2 not found inside " + apkgPath);
            }

            // 2. Read media JSON mapping
            Map<String, String> mediaMap = new HashMap<String, String>();
            ZipEntry mediaEntry = zip.getEntry("media");
            if (mediaEntry != null) {
                InputStream is = zip.getInputStream(mediaEntry);
                String jsonStr = readFully(is);
                is.close();
                try {
                    JSONObject obj = new JSONObject(jsonStr);
                    Iterator<?> keys = obj.keys();
                    while (keys.hasNext()) {
                        String key = (String) keys.next();
                        String realName = obj.getString(key);
                        mediaMap.put(key, realName);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Error parsing media JSON", e);
                }
            }

            // 3. Extract all numbered media files to their actual filenames in collection.media
            int count = 0;
            for (Map.Entry<String, String> entry : mediaMap.entrySet()) {
                String zipNum = entry.getKey();
                String realFilename = entry.getValue();
                ZipEntry fileEntry = zip.getEntry(zipNum);
                if (fileEntry != null) {
                    File outFile = new File(mediaDir, realFilename);
                    copyEntry(zip.getInputStream(fileEntry), outFile);
                    count++;
                }
            }
            Log.i(TAG, "Extracted collection.anki2 and " + count + " media files into " + mediaDir.getAbsolutePath());

        } finally {
            zip.close();
        }
    }

    private static void copyEntry(InputStream in, File outFile) throws IOException {
        outFile.getParentFile().mkdirs();
        FileOutputStream fos = new FileOutputStream(outFile);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            fos.write(buf, 0, n);
        }
        fos.flush();
        fos.close();
        in.close();
    }

    private static String readFully(InputStream is) throws IOException {
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) {
            sb.append(new String(buf, 0, n, "UTF-8"));
        }
        return sb.toString();
    }

    public static void copyStream(InputStream is, String destPath) throws IOException {
        File out = new File(destPath);
        copyEntry(is, out);
    }
}
