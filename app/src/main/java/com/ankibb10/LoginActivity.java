package com.ankibb10;

import android.app.Activity;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Toast;
import com.ankibb10.sync.SyncManager;
import com.ankibb10.sync.StorageManager;
import java.io.File;
import java.util.List;

public class LoginActivity extends Activity {
    private EditText etUsername, etPassword;
    private Button btnLogin, btnSkip;
    private ProgressBar progress;
    private SyncManager syncManager;
    private View rowStorage;
    private android.widget.TextView tvStorageName;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        syncManager = new SyncManager(this);

        // If user is already logged in, or already has an existing local collection (e.g. offline study / imported decks),
        // go straight to the deck list. Never prompt them again!
        if (syncManager.isLoggedIn() || syncManager.hasLocalCollection()) {
            startDeckList();
            return;
        }

        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_login);

        etUsername = (EditText) findViewById(R.id.et_username);
        etPassword = (EditText) findViewById(R.id.et_password);
        btnLogin   = (Button)   findViewById(R.id.btn_login);
        btnSkip    = (Button)   findViewById(R.id.btn_skip);
        progress   = (ProgressBar) findViewById(R.id.progress);
        rowStorage = findViewById(R.id.row_login_storage);
        tvStorageName = (android.widget.TextView) findViewById(R.id.tv_login_storage_name);
        updateStorageLabel();
        if (rowStorage != null) {
            rowStorage.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { promptStorageLocation(); }
            });
        }

        String savedUser = syncManager.getUsername();
        if (savedUser != null && !savedUser.isEmpty()) {
            etUsername.setText(savedUser);
            etPassword.requestFocus();
        }

        btnLogin.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String u = etUsername.getText().toString().trim();
                String p = etPassword.getText().toString();
                if (u.isEmpty() || p.isEmpty()) {
                    Toast.makeText(LoginActivity.this, "Enter email and password", Toast.LENGTH_SHORT).show();
                    return;
                }
                doLogin(u, p);
            }
        });

        btnSkip.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { startDeckList(); }
        });
    }

    private void doLogin(final String user, final String pass) {
        setFormEnabled(false);
        progress.setVisibility(View.VISIBLE);
        new AsyncTask<Void, String, Boolean>() {
            String errorMsg;
            protected Boolean doInBackground(Void... v) {
                syncManager.login(user, pass, new SyncManager.SyncListener() {
                    public void onProgress(String m) { publishProgress(m); }
                    public void onSuccess(String m)  { }
                    public void onError(String e)    { errorMsg = e; }
                });
                return syncManager.isLoggedIn();
            }
            protected void onProgressUpdate(String... msgs) {
                Toast.makeText(LoginActivity.this, msgs[0], Toast.LENGTH_SHORT).show();
            }
            protected void onPostExecute(Boolean ok) {
                progress.setVisibility(View.GONE);
                setFormEnabled(true);
                if (ok) {
                    startDeckList();
                } else {
                    Toast.makeText(LoginActivity.this, getString(R.string.login_failed) + "\n" + (errorMsg != null ? errorMsg : ""), Toast.LENGTH_LONG).show();
                }
            }
        }.execute();
    }

    private void setFormEnabled(boolean on) {
        etUsername.setEnabled(on); etPassword.setEnabled(on);
        btnLogin.setEnabled(on);   btnSkip.setEnabled(on);
    }

    private void startDeckList() {
        startActivity(new Intent(this, DeckListActivity.class));
        finish();
    }

    private void updateStorageLabel() {
        if (tvStorageName == null) return;
        File currentDir = StorageManager.getStorageDir(this);
        String currentPath = currentDir.getAbsolutePath();
        String label = "Internal App Storage";
        List<StorageManager.StorageLocation> locs = StorageManager.getAvailableLocations(this);
        for (StorageManager.StorageLocation loc : locs) {
            if (loc.path.equals(currentPath)) {
                label = loc.title;
                break;
            }
        }
        tvStorageName.setText(label);
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

        new android.app.AlertDialog.Builder(this)
                .setTitle("Select Sync & Storage Location")
                .setSingleChoiceItems(labels, selected, new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        dialog.dismiss();
                        if (which == labels.length - 1) {
                            promptCustomStoragePath();
                        } else {
                            StorageManager.setStorageDir(LoginActivity.this, locs.get(which).path);
                            updateStorageLabel();
                            android.widget.Toast.makeText(LoginActivity.this, "Storage set to " + locs.get(which).title, android.widget.Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void promptCustomStoragePath() {
        final EditText input = new EditText(this);
        input.setText(StorageManager.getStorageDir(this).getAbsolutePath());
        new android.app.AlertDialog.Builder(this)
                .setTitle("Custom Storage Path")
                .setMessage("Enter the directory path on your device or SD card where AnkiBB10 should store your collection:")
                .setView(input)
                .setPositiveButton("Set Path", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        String path = input.getText().toString().trim();
                        if (!path.isEmpty()) {
                            StorageManager.setStorageDir(LoginActivity.this, path);
                            updateStorageLabel();
                            android.widget.Toast.makeText(LoginActivity.this, "Storage path updated", android.widget.Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}