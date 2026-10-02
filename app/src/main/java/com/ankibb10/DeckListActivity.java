package com.ankibb10;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.ankibb10.db.AnkiDatabase;
import com.ankibb10.db.ApkgImporter;
import com.ankibb10.model.Deck;
import com.ankibb10.model.DeckNode;
import com.ankibb10.sync.SyncManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DeckListActivity extends Activity {
    private static final String TAG = "DeckListActivity";
    private static final int REQ_PICK_APKG = 1001;

    private ListView lvDecks;
    private TextView tvEmpty;
    private ImageButton fabAdd;
    private ImageButton btnSync, btnOverflow;
    private DeckTreeAdapter adapter;

    private List<Deck> rawDecks = new ArrayList<Deck>();
    private List<DeckNode> rootNodes = new ArrayList<DeckNode>();
    private List<DeckNode> visibleNodes = new ArrayList<DeckNode>();
    private Map<String, Boolean> collapseState = new HashMap<String, Boolean>();

    private SyncManager syncManager;
    private AnkiDatabase ankiDb;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_deck_list);

        syncManager = new SyncManager(this);
        lvDecks     = (ListView) findViewById(R.id.lv_decks);
        tvEmpty     = (TextView) findViewById(R.id.tv_empty);
        fabAdd      = (ImageButton) findViewById(R.id.fab_add);
        btnSync     = (ImageButton) findViewById(R.id.btn_action_sync);
        btnOverflow = (ImageButton) findViewById(R.id.btn_action_overflow);

        adapter = new DeckTreeAdapter();
        lvDecks.setAdapter(adapter);

        lvDecks.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View view, int pos, long id) {
                if (pos >= 0 && pos < visibleNodes.size()) {
                    DeckNode node = visibleNodes.get(pos);
                    if (node.deck != null) {
                        startStudy(node.deck);
                    }
                }
            }
        });

        fabAdd.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showAddActionDialog();
            }
        });

        if (btnSync != null) {
            btnSync.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    doSync();
                }
            });
        }

        if (btnOverflow != null) {
            btnOverflow.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    showOverflowMenu();
                }
            });
        }
    }

    private void showOverflowMenu() {
        final CharSequence[] items = {"Add Note", "Create Deck", "Import .apkg", "Settings", "Log Out"};
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("AnkiDroid");
        builder.setItems(items, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                if (which == 0) {
                    showAddNoteDialog(null);
                } else if (which == 1) {
                    showCreateDeckDialog();
                } else if (which == 2) {
                    pickApkg();
                } else if (which == 3) {
                    startActivity(new Intent(DeckListActivity.this, SettingsActivity.class));
                } else if (which == 4) {
                    confirmLogout();
                }
            }
        });
        builder.show();
    }

    private void startStudy(Deck deck) {
        Intent i = new Intent(DeckListActivity.this, StudyActivity.class);
        i.putExtra(StudyActivity.EXTRA_DECK_ID, deck.id);
        i.putExtra(StudyActivity.EXTRA_DECK_NAME, deck.name);
        startActivity(i);
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadDecks();

        if (syncManager != null && syncManager.isLoggedIn() && syncManager.isNetworkAvailable()) {
            syncManager.autoSync(new SyncManager.SyncListener() {
                public void onProgress(String message) {}
                public void onSuccess(String message) {
                    runOnUiThread(new Runnable() {
                        public void run() { loadDecks(); }
                    });
                }
                public void onError(String error) {}
            });
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (ankiDb != null) ankiDb.close();
    }

    private void loadDecks() {
        if (!syncManager.hasLocalCollection()) {
            tvEmpty.setVisibility(View.VISIBLE);
            lvDecks.setVisibility(View.GONE);
            return;
        }
        if (ankiDb != null) ankiDb.close();
        try {
            ankiDb = new AnkiDatabase(syncManager.getDbPath());
            rawDecks = ankiDb.getDecks();
        } catch (Exception e) {
            Log.e(TAG, "Failed to open collection", e);
            Toast.makeText(this, "Could not open collection: " + e.getMessage(), Toast.LENGTH_LONG).show();
            rawDecks = new ArrayList<Deck>();
        }

        buildTree();

        if (visibleNodes.isEmpty()) {
            tvEmpty.setVisibility(View.VISIBLE);
            lvDecks.setVisibility(View.GONE);
        } else {
            tvEmpty.setVisibility(View.GONE);
            lvDecks.setVisibility(View.VISIBLE);
        }
        adapter.notifyDataSetChanged();
    }

    // -----------------------------------------------------------------------
    // Hierarchical Tree Construction & Flattening
    // -----------------------------------------------------------------------
    private void buildTree() {
        Collections.sort(rawDecks, new Comparator<Deck>() {
            public int compare(Deck a, Deck b) {
                return a.name.compareToIgnoreCase(b.name);
            }
        });

        Map<String, DeckNode> nodeMap = new LinkedHashMap<String, DeckNode>();
        rootNodes = new ArrayList<DeckNode>();

        for (Deck d : rawDecks) {
            String fullName = d.name;
            String[] parts = fullName.split("::");
            String leaf = parts[parts.length - 1];
            int lvl = parts.length - 1;

            DeckNode node = new DeckNode(d, fullName, leaf, lvl);
            if (collapseState.containsKey(fullName)) {
                node.isCollapsed = collapseState.get(fullName);
            } else {
                // Default expanded so user sees their structure
                node.isCollapsed = false;
            }
            nodeMap.put(fullName, node);
        }

        for (DeckNode node : nodeMap.values()) {
            int idx = node.fullName.lastIndexOf("::");
            if (idx > 0) {
                String parentName = node.fullName.substring(0, idx);
                DeckNode parent = nodeMap.get(parentName);
                if (parent != null) {
                    parent.children.add(node);
                    parent.hasChildren = true;
                } else {
                    rootNodes.add(node);
                }
            } else {
                rootNodes.add(node);
            }
        }

        for (DeckNode root : rootNodes) {
            computeSums(root);
        }

        rebuildVisibleList();
    }

    private void computeSums(DeckNode node) {
        node.sumNew = (node.deck != null) ? node.deck.newCount : 0;
        node.sumLearn = (node.deck != null) ? node.deck.learnCount : 0;
        node.sumReview = (node.deck != null) ? node.deck.reviewCount : 0;

        for (DeckNode child : node.children) {
            computeSums(child);
            node.sumNew += child.sumNew;
            node.sumLearn += child.sumLearn;
            node.sumReview += child.sumReview;
        }
    }

    private void rebuildVisibleList() {
        visibleNodes = new ArrayList<DeckNode>();
        flatten(rootNodes, visibleNodes);
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private void flatten(List<DeckNode> nodes, List<DeckNode> out) {
        for (DeckNode n : nodes) {
            out.add(n);
            if (n.hasChildren && !n.isCollapsed) {
                flatten(n.children, out);
            }
        }
    }

    // -----------------------------------------------------------------------
    // BlackBerry Q5 Hardware Keyboard Shortcuts
    // -----------------------------------------------------------------------
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int keyCode = event.getKeyCode();
            char keyChar = (char) event.getUnicodeChar();
            if (keyChar == 's' || keyChar == 'S' || keyCode == KeyEvent.KEYCODE_S) {
                doSync();
                return true;
            } else if (keyChar == 'a' || keyChar == 'A' || keyCode == KeyEvent.KEYCODE_A) {
                showAddNoteDialog(null);
                return true;
            } else if (keyChar == 'd' || keyChar == 'D' || keyCode == KeyEvent.KEYCODE_D) {
                showCreateDeckDialog();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            char keyChar = (char) event.getUnicodeChar();
            if (keyChar == 's' || keyChar == 'S' || keyCode == KeyEvent.KEYCODE_S) {
                doSync();
                return true;
            } else if (keyChar == 'a' || keyChar == 'A' || keyCode == KeyEvent.KEYCODE_A) {
                showAddNoteDialog(null);
                return true;
            } else if (keyChar == 'd' || keyChar == 'D' || keyCode == KeyEvent.KEYCODE_D) {
                showCreateDeckDialog();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    private void showAddActionDialog() {
        final CharSequence[] items = {"Add Note", "Create Deck", "Import .apkg file"};
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Add to Anki");
        builder.setItems(items, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                if (which == 0) {
                    showAddNoteDialog(null);
                } else if (which == 1) {
                    showCreateDeckDialog();
                } else if (which == 2) {
                    pickApkg();
                }
            }
        });
        builder.show();
    }

    private void showAddNoteDialog(Deck selectedDeck) {
        if (rawDecks.isEmpty()) {
            Toast.makeText(this, "Please create a deck first", Toast.LENGTH_SHORT).show();
            showCreateDeckDialog();
            return;
        }

        final Deck targetDeck = (selectedDeck != null) ? selectedDeck : rawDecks.get(0);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Add Note to " + targetDeck.name);

        final EditText etFront = new EditText(this);
        etFront.setHint("Front (Question)");
        etFront.setMinLines(2);

        final EditText etBack = new EditText(this);
        etBack.setHint("Back (Answer)");
        etBack.setMinLines(2);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(24, 16, 24, 16);
        layout.addView(etFront);
        layout.addView(etBack);
        builder.setView(layout);

        builder.setPositiveButton("Add", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                String front = etFront.getText().toString().trim();
                String back = etBack.getText().toString().trim();
                if (!front.isEmpty()) {
                    if (ankiDb != null) {
                        ankiDb.addNote(targetDeck.id, front, back);
                        Toast.makeText(DeckListActivity.this, "Note added", Toast.LENGTH_SHORT).show();
                        loadDecks();
                        if (syncManager != null && syncManager.isLoggedIn() && syncManager.isNetworkAvailable()) {
                            syncManager.autoSync(null);
                        }
                    }
                }
            }
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void showCreateDeckDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Create New Deck");

        final EditText etName = new EditText(this);
        etName.setHint("Deck Name (use Parent::Child for subdecks)");
        etName.setSingleLine(true);

        LinearLayout layout = new LinearLayout(this);
        layout.setPadding(24, 16, 24, 16);
        layout.addView(etName);
        builder.setView(layout);

        builder.setPositiveButton("Create", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                String name = etName.getText().toString().trim();
                if (!name.isEmpty()) {
                    if (ankiDb != null) {
                        ankiDb.createDeck(name);
                        Toast.makeText(DeckListActivity.this, "Deck created", Toast.LENGTH_SHORT).show();
                        loadDecks();
                        if (syncManager != null && syncManager.isLoggedIn() && syncManager.isNetworkAvailable()) {
                            syncManager.autoSync(null);
                        }
                    }
                }
            }
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void doSync() {
        if (!syncManager.isLoggedIn()) {
            Toast.makeText(this, "Not logged in - log in first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!syncManager.isNetworkAvailable()) {
            Toast.makeText(this, "Offline: changes are saved locally and will auto-sync when connected", Toast.LENGTH_LONG).show();
            return;
        }

        // Close local database handle before network sync so file operations are completely clean
        if (ankiDb != null) {
            ankiDb.close();
            ankiDb = null;
        }

        final android.app.ProgressDialog pd = new android.app.ProgressDialog(this);
        pd.setMessage("Connecting to AnkiWeb...");
        pd.setIndeterminate(true);
        pd.setCancelable(false);
        pd.show();

        new AsyncTask<Void, String, String>() {
            boolean success = false;
            protected String doInBackground(Void... v) {
                final String[] result = {null};
                syncManager.sync(new SyncManager.SyncListener() {
                    public void onProgress(String msg) { publishProgress(msg); }
                    public void onSuccess(String msg)  { result[0] = msg; success = true; }
                    public void onError(String err)    { result[0] = err; success = false; }
                });
                return result[0];
            }
            protected void onProgressUpdate(String... values) {
                if (values.length > 0 && pd.isShowing()) {
                    pd.setMessage(values[0]);
                }
            }
            protected void onPostExecute(String msg) {
                if (pd.isShowing()) {
                    try { pd.dismiss(); } catch (Exception ignored) {}
                }
                if (success) {
                    Toast.makeText(DeckListActivity.this, msg != null ? msg : "Sync complete!", Toast.LENGTH_SHORT).show();
                    loadDecks();
                } else {
                    Toast.makeText(DeckListActivity.this,
                            msg != null ? msg : "Sync failed", Toast.LENGTH_LONG).show();
                    try {
                        new AlertDialog.Builder(DeckListActivity.this)
                                .setTitle("Sync Notice")
                                .setMessage(msg != null ? msg : "Sync failed. Check connection or AnkiWeb credentials.")
                                .setPositiveButton("OK", null)
                                .show();
                    } catch (Exception ignored) {}
                }
            }
        }.execute();
    }

    private void pickApkg() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("*/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(i, "Select .apkg file"), REQ_PICK_APKG);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_PICK_APKG && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            importApkg(uri);
        }
    }

    private void importApkg(final Uri uri) {
        Toast.makeText(this, R.string.loading, Toast.LENGTH_SHORT).show();
        final String destPath = syncManager.getDbPath();
        new AsyncTask<Void, Void, Exception>() {
            protected Exception doInBackground(Void... v) {
                try {
                    String tmp = getCacheDir() + "/import_tmp.apkg";
                    ApkgImporter.copyStream(
                            getContentResolver().openInputStream(uri), tmp);
                    ApkgImporter.importApkg(tmp, destPath);
                    return null;
                } catch (Exception e) {
                    return e;
                }
            }
            protected void onPostExecute(Exception e) {
                if (e == null) {
                    Toast.makeText(DeckListActivity.this, R.string.import_success, Toast.LENGTH_SHORT).show();
                    loadDecks();
                } else {
                    Toast.makeText(DeckListActivity.this,
                            getString(R.string.import_failed, e.getMessage()), Toast.LENGTH_LONG).show();
                }
            }
        }.execute();
    }

    private void confirmLogout() {
        new AlertDialog.Builder(this)
                .setMessage(R.string.logout)
                .setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        syncManager.logout();
                        startActivity(new Intent(DeckListActivity.this, LoginActivity.class));
                        finish();
                    }
                })
                .setNegativeButton(R.string.no, null)
                .show();
    }

    // -----------------------------------------------------------------------
    // Expandable Hierarchical Deck Adapter
    // -----------------------------------------------------------------------
    private class DeckTreeAdapter extends BaseAdapter {
        private final float density = getResources().getDisplayMetrics().density;

        public int     getCount()         { return visibleNodes.size(); }
        public Object  getItem(int pos)   { return visibleNodes.get(pos); }
        public long    getItemId(int pos) {
            Deck d = visibleNodes.get(pos).deck;
            return d != null ? d.id : pos;
        }

        public View getView(int pos, View cv, ViewGroup parent) {
            if (cv == null) {
                cv = getLayoutInflater().inflate(R.layout.item_deck, parent, false);
            }
            final DeckNode node = visibleNodes.get(pos);

            // 1. Indentation
            View vIndent = cv.findViewById(R.id.view_indent);
            int indentPx = (int)(node.level * 16 * density);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(indentPx, 1);
            vIndent.setLayoutParams(lp);

            // 2. Chevron toggle button
            ImageButton btnToggle = (ImageButton) cv.findViewById(R.id.btn_toggle_expand);
            if (node.hasChildren) {
                btnToggle.setVisibility(View.VISIBLE);
                btnToggle.setClickable(true);
                btnToggle.setFocusable(false);
                btnToggle.setImageResource(node.isCollapsed ? R.drawable.ic_chevron_right : R.drawable.ic_chevron_down);
                btnToggle.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        node.isCollapsed = !node.isCollapsed;
                        collapseState.put(node.fullName, node.isCollapsed);
                        rebuildVisibleList();
                    }
                });
            } else {
                btnToggle.setVisibility(View.INVISIBLE);
                btnToggle.setClickable(false);
                btnToggle.setFocusable(false);
                btnToggle.setOnClickListener(null);
            }

            // 3. Name (Shows leaf name, e.g. "Grammar")
            TextView tvName = (TextView) cv.findViewById(R.id.tv_deck_name);
            tvName.setText(node.displayName);
            tvName.setTypeface(null, node.hasChildren ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);

            // 4. Badges (Shows sums if collapsed, deck values if expanded)
            TextView tvNew = (TextView) cv.findViewById(R.id.tv_count_new);
            TextView tvLrn = (TextView) cv.findViewById(R.id.tv_count_learn);
            TextView tvRev = (TextView) cv.findViewById(R.id.tv_count_review);

            int n = (node.hasChildren && node.isCollapsed) ? node.sumNew : (node.deck != null ? node.deck.newCount : 0);
            int l = (node.hasChildren && node.isCollapsed) ? node.sumLearn : (node.deck != null ? node.deck.learnCount : 0);
            int r = (node.hasChildren && node.isCollapsed) ? node.sumReview : (node.deck != null ? node.deck.reviewCount : 0);

            tvNew.setText(String.valueOf(n));
            tvLrn.setText(String.valueOf(l));
            tvRev.setText(String.valueOf(r));

            tvNew.setAlpha(n > 0 ? 1.0f : 0.35f);
            tvLrn.setAlpha(l > 0 ? 1.0f : 0.35f);
            tvRev.setAlpha(r > 0 ? 1.0f : 0.35f);

            return cv;
        }
    }
}