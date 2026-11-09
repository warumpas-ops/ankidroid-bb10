package com.ankibb10;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.os.AsyncTask;
import com.ankibb10.sync.StorageManager;
import java.io.File;
import java.util.List;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.ankibb10.sync.SyncManager;

public class SettingsActivity extends Activity {

    private ImageButton btnBack;
    private EditText etSyncUrl;
    private Button   btnSave;

    private View rowTheme;
    private TextView tvValTheme;

    private View rowStorage;
    private TextView tvValStorageType;
    private TextView tvValStoragePath;
    private TextView tvValStorageSpace;

    private View rowShowAnswer, rowAgain, rowHard, rowGood, rowEasy;
    private TextView tvShowAnswer, tvAgain, tvHard, tvGood, tvEasy;
    private Button btnResetKeys;

    private Dialog activeRemapDialog;
    private String activeActionName;
    private String activePrefKey;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_settings);

        btnBack   = (ImageButton) findViewById(R.id.btn_settings_back);
        etSyncUrl = (EditText)   findViewById(R.id.et_sync_url);
        btnSave   = (Button)     findViewById(R.id.btn_save);

        rowTheme   = findViewById(R.id.row_theme);
        tvValTheme = (TextView) findViewById(R.id.tv_val_theme);
        if (tvValTheme != null) {
            tvValTheme.setText(ThemeManager.getThemeDisplayName(this));
        }
        if (rowTheme != null) {
            rowTheme.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { promptTheme(); }
            });
        }

        rowStorage         = findViewById(R.id.row_storage);
        tvValStorageType   = (TextView) findViewById(R.id.tv_val_storage_type);
        tvValStoragePath   = (TextView) findViewById(R.id.tv_val_storage_path);
        tvValStorageSpace  = (TextView) findViewById(R.id.tv_val_storage_space);

        if (rowStorage != null) {
            rowStorage.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { promptStorageLocation(); }
            });
        }
        updateStorageLabels();

        rowShowAnswer = findViewById(R.id.row_key_show_answer);
        rowAgain      = findViewById(R.id.row_key_again);
        rowHard       = findViewById(R.id.row_key_hard);
        rowGood       = findViewById(R.id.row_key_good);
        rowEasy       = findViewById(R.id.row_key_easy);

        tvShowAnswer = (TextView) findViewById(R.id.tv_val_show_answer);
        tvAgain      = (TextView) findViewById(R.id.tv_val_again);
        tvHard       = (TextView) findViewById(R.id.tv_val_hard);
        tvGood       = (TextView) findViewById(R.id.tv_val_good);
        tvEasy       = (TextView) findViewById(R.id.tv_val_easy);

        btnResetKeys = (Button) findViewById(R.id.btn_reset_keys);

        if (btnBack != null) {
            btnBack.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { finish(); }
            });
        }

        applyTheme();

        // Sync URL
        SharedPreferences prefs = getSharedPreferences(SyncManager.PREFS, MODE_PRIVATE);
        etSyncUrl.setText(prefs.getString(SyncManager.KEY_SYNCURL, SyncManager.DEFAULT_URL));

        btnSave.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String url = etSyncUrl.getText().toString().trim();
                if (url.isEmpty()) url = SyncManager.DEFAULT_URL;
                getSharedPreferences(SyncManager.PREFS, MODE_PRIVATE)
                        .edit().putString(SyncManager.KEY_SYNCURL, url).commit();
                Toast.makeText(SettingsActivity.this, "Sync URL saved", Toast.LENGTH_SHORT).show();
            }
        });

        // Key Mapping rows
        updateKeyLabels();

        rowShowAnswer.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { promptKey("Show Answer", KeyMapper.KEY_SHOW_ANSWER); }
        });
        rowAgain.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { promptKey("Rate Again", KeyMapper.KEY_AGAIN); }
        });
        rowHard.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { promptKey("Rate Hard", KeyMapper.KEY_HARD); }
        });
        rowGood.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { promptKey("Rate Good", KeyMapper.KEY_GOOD); }
        });
        rowEasy.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { promptKey("Rate Easy", KeyMapper.KEY_EASY); }
        });

        btnResetKeys.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                KeyMapper.resetDefaults(SettingsActivity.this);
                updateKeyLabels();
                Toast.makeText(SettingsActivity.this, "Keys reset to default (Space, 1, 2, 3, 4)", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void updateKeyLabels() {
        tvShowAnswer.setText(KeyMapper.getMapping(this, KeyMapper.KEY_SHOW_ANSWER, KeyMapper.DEFAULT_SHOW_ANSWER));
        tvAgain.setText(KeyMapper.getMapping(this, KeyMapper.KEY_AGAIN, KeyMapper.DEFAULT_AGAIN));
        tvHard.setText(KeyMapper.getMapping(this, KeyMapper.KEY_HARD, KeyMapper.DEFAULT_HARD));
        tvGood.setText(KeyMapper.getMapping(this, KeyMapper.KEY_GOOD, KeyMapper.DEFAULT_GOOD));
        tvEasy.setText(KeyMapper.getMapping(this, KeyMapper.KEY_EASY, KeyMapper.DEFAULT_EASY));
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (activeRemapDialog != null && activeRemapDialog.isShowing()) {
            int keyCode = event.getKeyCode();
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    activeRemapDialog.dismiss();
                    activeRemapDialog = null;
                }
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                String keyName = KeyMapper.getKeyName(event);
                assignKey(activeRemapDialog, activeActionName, activePrefKey, keyName);
                activeRemapDialog = null;
                return true;
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    /**
     * Shows a key-capture dialog with helpful prompt text and quick buttons to tap.
     */
    private void promptKey(final String actionName, final String prefKey) {
        activeActionName = actionName;
        activePrefKey    = prefKey;

        final Dialog dialog = new Dialog(this) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent event) {
                int keyCode = event.getKeyCode();
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.getAction() == KeyEvent.ACTION_UP) dismiss();
                    return true;
                }
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    String keyName = KeyMapper.getKeyName(event);
                    assignKey(this, actionName, prefKey, keyName);
                    activeRemapDialog = null;
                    return true;
                }
                return true;
            }
        };

        activeRemapDialog = dialog;

        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        Context ctx = this;
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(ctx);
        title.setText("Remap: " + actionName);
        title.setTextSize(16f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(0xFF212121);
        root.addView(title);

        TextView prompt = new TextView(ctx);
        prompt.setText("\nPress any key on your BlackBerry physical keyboard (letters, numbers, Space, or Enter) to assign it.\n\nOr click a button below to map it:");
        prompt.setTextSize(13f);
        prompt.setTextColor(0xFF616161);
        prompt.setPadding(0, 0, 0, dp(12));
        root.addView(prompt);

        // Quick buttons row 1: Space, Enter
        root.addView(makeButtonRow(ctx, dialog, actionName, prefKey,
                new String[]{"Space", "Enter"},
                new String[]{"SPACE", "ENTER"}));

        // Quick buttons row 2: 1 (W), 2 (E), 3 (R), 4 (S)
        root.addView(makeButtonRow(ctx, dialog, actionName, prefKey,
                new String[]{"1 (W)", "2 (E)", "3 (R)", "4 (S)"},
                new String[]{"1", "2", "3", "4"}));

        // Cancel button
        Button cancel = new Button(ctx);
        cancel.setText("Cancel");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        cancel.setLayoutParams(lp);
        cancel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                dialog.dismiss();
                activeRemapDialog = null;
            }
        });
        root.addView(cancel);

        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            public void onDismiss(DialogInterface d) {
                activeRemapDialog = null;
            }
        });

        dialog.setContentView(root);
        dialog.show();
    }

    private void assignKey(Dialog dialog, String actionName, String prefKey, String keyName) {
        KeyMapper.setMapping(SettingsActivity.this, prefKey, keyName);
        Toast.makeText(SettingsActivity.this,
                actionName + " mapped to: " + keyName, Toast.LENGTH_SHORT).show();
        updateKeyLabels();
        try {
            dialog.dismiss();
        } catch (Exception ignored) {}
    }

    private LinearLayout makeButtonRow(final Context ctx, final Dialog dialog,
            final String actionName, final String prefKey,
            String[] labels, final String[] keys) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = dp(4);
        row.setLayoutParams(rowLp);

        for (int i = 0; i < labels.length; i++) {
            final String key = keys[i];
            Button b = new Button(ctx);
            b.setText(labels[i]);
            b.setTextSize(12f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMargins(dp(2), 0, dp(2), 0);
            b.setLayoutParams(lp);
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    assignKey(dialog, actionName, prefKey, key);
                }
            });
            row.addView(b);
        }
        return row;
    }

    private int dp(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    private void promptTheme() {
        final String[] themes = {"Black (OLED / Pitch Black)", "Plain Dark", "Light"};
        final String[] themeKeys = {ThemeManager.THEME_BLACK, ThemeManager.THEME_DARK, ThemeManager.THEME_LIGHT};
        String current = ThemeManager.getTheme(this);
        int selectedIndex = 0;
        for (int i = 0; i < themeKeys.length; i++) {
            if (themeKeys[i].equals(current)) {
                selectedIndex = i;
                break;
            }
        }

        new android.app.AlertDialog.Builder(this)
                .setTitle("Select Theme")
                .setSingleChoiceItems(themes, selectedIndex, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        ThemeManager.setTheme(SettingsActivity.this, themeKeys[which]);
                        if (tvValTheme != null) {
                            tvValTheme.setText(themes[which]);
                        }
                        applyTheme();
                        Toast.makeText(SettingsActivity.this, "Theme set to " + themes[which], Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void applyTheme() {
        int bg = ThemeManager.getBackgroundColor(this);
        int toolbar = ThemeManager.getToolbarColor(this);
        View root = findViewById(R.id.root_settings);
        if (root != null) root.setBackgroundColor(bg);
        View tb = findViewById(R.id.ll_settings_toolbar);
        if (tb != null) tb.setBackgroundColor(toolbar);
    }

    private void updateStorageLabels() {
        File currentDir = StorageManager.getStorageDir(this);
        String currentPath = currentDir.getAbsolutePath();
        if (tvValStoragePath != null) tvValStoragePath.setText(currentPath);
        if (tvValStorageSpace != null) tvValStorageSpace.setText(StorageManager.formatSpace(currentDir));

        List<StorageManager.StorageLocation> locs = StorageManager.getAvailableLocations(this);
        String typeName = "Custom Storage";
        for (StorageManager.StorageLocation loc : locs) {
            if (loc.path.equals(currentPath)) {
                typeName = loc.title;
                break;
            }
        }
        if (tvValStorageType != null) tvValStorageType.setText(typeName);
    }

    private void promptStorageLocation() {
        final List<StorageManager.StorageLocation> locs = StorageManager.getAvailableLocations(this);
        int count = locs.size();
        final String[] labels = new String[count + 1];
        File currentDir = StorageManager.getStorageDir(this);
        String currentPath = currentDir.getAbsolutePath();
        int selected = -1;

        for (int i = 0; i < count; i++) {
            StorageManager.StorageLocation loc = locs.get(i);
            labels[i] = loc.title + "\n" + loc.description;
            if (loc.path.equals(currentPath)) {
                selected = i;
            }
        }
        labels[count] = "Custom Storage Path...\nEnter custom folder location";
        if (selected == -1) selected = count;

        new AlertDialog.Builder(this)
                .setTitle("Choose Storage Location")
                .setSingleChoiceItems(labels, selected, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        if (which == labels.length - 1) {
                            promptCustomStoragePath();
                        } else {
                            onStorageLocationSelected(locs.get(which).path);
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void promptCustomStoragePath() {
        final EditText input = new EditText(this);
        input.setText(StorageManager.getStorageDir(this).getAbsolutePath());
        new AlertDialog.Builder(this)
                .setTitle("Custom Storage Path")
                .setMessage("Enter the directory path where AnkiBB10 should store your collection and media:")
                .setView(input)
                .setPositiveButton("Set Path", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        String path = input.getText().toString().trim();
                        if (!path.isEmpty()) {
                            onStorageLocationSelected(path);
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void onStorageLocationSelected(final String newPath) {
        final File currentDir = StorageManager.getStorageDir(this);
        final File newDir = new File(newPath);

        try {
            if (currentDir.getCanonicalPath().equals(newDir.getCanonicalPath())) {
                Toast.makeText(this, "Storage location unchanged", Toast.LENGTH_SHORT).show();
                return;
            }
        } catch (Exception ignored) {}

        final File oldDb = new File(currentDir, "collection.anki2");
        final File newDb = new File(newDir, "collection.anki2");

        if (oldDb.exists() && !newDb.exists()) {
            new AlertDialog.Builder(this)
                    .setTitle("Move Existing Collection?")
                    .setMessage("An existing collection was found in your current storage. Would you like to copy your collection and media files to the new location?\n\nDestination:\n" + newPath)
                    .setPositiveButton("Copy Data", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            migrateStorage(currentDir, newDir);
                        }
                    })
                    .setNegativeButton("Switch Only", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            StorageManager.setStorageDir(SettingsActivity.this, newPath);
                            updateStorageLabels();
                            Toast.makeText(SettingsActivity.this, "Switched storage location", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNeutralButton("Cancel", null)
                    .show();
        } else {
            StorageManager.setStorageDir(this, newPath);
            updateStorageLabels();
            Toast.makeText(this, "Storage location updated", Toast.LENGTH_SHORT).show();
        }
    }

    private void migrateStorage(final File src, final File dst) {
        final ProgressDialog pd = new ProgressDialog(this);
        pd.setTitle("Moving Collection");
        pd.setMessage("Copying files to " + dst.getAbsolutePath() + "...");
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setMax(100);
        pd.setCancelable(false);
        pd.show();

        new AsyncTask<Void, Integer, Exception>() {
            private String statusMessage = "";

            protected Exception doInBackground(Void... params) {
                try {
                    StorageManager.copyCollectionData(src, dst, new StorageManager.CopyProgressListener() {
                        public void onProgress(String message, int percent) {
                            statusMessage = message;
                            publishProgress(percent);
                        }
                    });
                    return null;
                } catch (Exception e) {
                    return e;
                }
            }

            protected void onProgressUpdate(Integer... values) {
                if (values.length > 0) {
                    pd.setProgress(values[0]);
                    if (statusMessage != null && !statusMessage.isEmpty()) {
                        pd.setMessage(statusMessage);
                    }
                }
            }

            protected void onPostExecute(Exception e) {
                try { pd.dismiss(); } catch (Exception ignored) {}
                if (e == null) {
                    StorageManager.setStorageDir(SettingsActivity.this, dst.getAbsolutePath());
                    updateStorageLabels();
                    Toast.makeText(SettingsActivity.this, "Storage moved successfully!", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(SettingsActivity.this, "Failed to copy data: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
        }.execute();
    }
}