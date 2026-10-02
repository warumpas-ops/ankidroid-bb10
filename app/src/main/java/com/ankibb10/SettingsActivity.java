package com.ankibb10;

import android.app.Activity;
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
}