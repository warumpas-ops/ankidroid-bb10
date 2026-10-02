package com.ankibb10.sync;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.util.Log;

public class NetworkChangeReceiver extends BroadcastReceiver {
    private static final String TAG = "NetworkChangeReceiver";

    @Override
    public void onReceive(final Context context, Intent intent) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            NetworkInfo activeNet = cm.getActiveNetworkInfo();
            boolean isConnected = (activeNet != null && activeNet.isConnected());

            if (isConnected) {
                Log.i(TAG, "Device came ONLINE. Triggering auto-sync for offline reviews...");
                final SyncManager sm = new SyncManager(context);
                if (sm.isLoggedIn()) {
                    sm.autoSync(new SyncManager.SyncListener() {
                        public void onProgress(String message) { Log.d(TAG, "Online auto-sync: " + message); }
                        public void onSuccess(String message)  { Log.i(TAG, "Online auto-sync SUCCESS: " + message); }
                        public void onError(String error)      { Log.w(TAG, "Online auto-sync: " + error); }
                    });
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "NetworkChangeReceiver error", t);
        }
    }
}
