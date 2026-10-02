package com.ankibb10;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import android.media.AudioManager;
import android.media.MediaPlayer;
import android.webkit.JavascriptInterface;

import com.ankibb10.db.AnkiDatabase;
import com.ankibb10.model.Card;
import com.ankibb10.scheduler.Scheduler;
import com.ankibb10.sync.SyncManager;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.regex.Matcher;

public class StudyActivity extends Activity {
    private static final String TAG = "StudyActivity";

    public static final String EXTRA_DECK_ID   = "deck_id";
    public static final String EXTRA_DECK_NAME = "deck_name";

    private ImageButton  btnBack;
    private ImageButton  btnAudio;
    private TextView     tvDeckName;
    private TextView     tvBadgeNew, tvBadgeLearn, tvBadgeReview;
    private WebView      wvCard;
    private View         layoutDone;
    private Button       btnDoneBack;

    private MediaPlayer  mediaPlayer;
    private List<String> currentCardSounds = new ArrayList<String>();

    private LinearLayout llFrontAnswer;
    private Button       btnShowAnswer;

    private LinearLayout llBackRatings;
    private View         btnAgain, btnHard, btnGood, btnEasy;
    private TextView     tvIvlAgain, tvIvlHard, tvIvlGood, tvIvlEasy;
    private TextView     tvLblAgain, tvLblHard, tvLblGood, tvLblEasy;

    private AnkiDatabase db;
    private Scheduler    scheduler;
    private Queue<Card>  cardQueue = new LinkedList<Card>();
    private Card         currentCard;
    private boolean      showingBack = false;
    private long         deckId;

    // Configurable Key Mappings
    private String mapShowAnswer, mapAgain, mapHard, mapGood, mapEasy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_study);

        try {
            deckId = getIntent().getLongExtra(EXTRA_DECK_ID, -1);
            String deckName = getIntent().getStringExtra(EXTRA_DECK_NAME);
            if (deckName != null) setTitle(deckName);

            btnBack          = (ImageButton) findViewById(R.id.btn_study_back);
            tvDeckName       = (TextView) findViewById(R.id.tv_deck_study_name);
            tvBadgeNew       = (TextView) findViewById(R.id.tv_badge_new);
            tvBadgeLearn     = (TextView) findViewById(R.id.tv_badge_learn);
            tvBadgeReview    = (TextView) findViewById(R.id.tv_badge_review);
            wvCard           = (WebView)  findViewById(R.id.wv_card);
            layoutDone       = findViewById(R.id.tv_done);
            btnDoneBack      = (Button)   findViewById(R.id.btn_done_back);

            llFrontAnswer    = (LinearLayout) findViewById(R.id.ll_front_answer);
            btnShowAnswer    = (Button)       findViewById(R.id.btn_show_answer);

            llBackRatings    = (LinearLayout) findViewById(R.id.ll_back_ratings);
            btnAgain         = findViewById(R.id.btn_again);
            btnHard          = findViewById(R.id.btn_hard);
            btnGood          = findViewById(R.id.btn_good);
            btnEasy          = findViewById(R.id.btn_easy);

            tvIvlAgain       = (TextView) findViewById(R.id.tv_ivl_again);
            tvIvlHard        = (TextView) findViewById(R.id.tv_ivl_hard);
            tvIvlGood        = (TextView) findViewById(R.id.tv_ivl_good);
            tvIvlEasy        = (TextView) findViewById(R.id.tv_ivl_easy);

            tvLblAgain       = (TextView) findViewById(R.id.tv_lbl_again);
            tvLblHard        = (TextView) findViewById(R.id.tv_lbl_hard);
            tvLblGood        = (TextView) findViewById(R.id.tv_lbl_good);
            tvLblEasy        = (TextView) findViewById(R.id.tv_lbl_easy);

            if (deckName != null && tvDeckName != null) {
                // If it's a subdeck, show leaf name in title bar
                String[] parts = deckName.split("::");
                tvDeckName.setText(parts[parts.length - 1]);
            }

            btnAudio = (ImageButton) findViewById(R.id.btn_study_audio);
            if (btnAudio != null) {
                btnAudio.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) { playCurrentAudio(); }
                });
            }

            if (btnBack != null) {
                btnBack.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) { finish(); }
                });
            }

            if (btnDoneBack != null) {
                btnDoneBack.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) { finish(); }
                });
            }

            // Configure WebView
            if (wvCard != null) {
                WebSettings ws = wvCard.getSettings();
                ws.setJavaScriptEnabled(true);
                ws.setDefaultTextEncodingName("UTF-8");
                ws.setBuiltInZoomControls(false);
                ws.setAllowFileAccess(true);
                ws.setAllowContentAccess(true);
                if (android.os.Build.VERSION.SDK_INT >= 16) {
                    ws.setAllowFileAccessFromFileURLs(true);
                    ws.setAllowUniversalAccessFromFileURLs(true);
                }
                wvCard.addJavascriptInterface(new AudioBridge(), "AnkiAudio");

                // Tap card to flip
                wvCard.setOnTouchListener(new View.OnTouchListener() {
                    private float startY;
                    public boolean onTouch(View v, MotionEvent event) {
                        if (event.getAction() == MotionEvent.ACTION_DOWN) {
                            startY = event.getY();
                        } else if (event.getAction() == MotionEvent.ACTION_UP) {
                            if (Math.abs(event.getY() - startY) < 20 && !showingBack) {
                                showBack();
                            }
                        }
                        return false;
                    }
                });
            }

            // Open DB & Scheduler
            SyncManager sm = new SyncManager(this);
            try {
                db        = new AnkiDatabase(sm.getDbPath());
                scheduler = new Scheduler(db);
            } catch (Exception e) {
                Log.e(TAG, "DB open error", e);
                Toast.makeText(this, "Could not open collection: " + e.getMessage(), Toast.LENGTH_LONG).show();
                finish();
                return;
            }

            List<Card> due = db.getDueCards(deckId);
            if (due != null) {
                cardQueue.addAll(due);
            }

            loadKeyMappings();
            setupButtons();
            showNextCard();

        } catch (Throwable t) {
            Log.e(TAG, "Fatal in StudyActivity.onCreate", t);
            Toast.makeText(this, "Error opening deck: " + t.getMessage(), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void loadKeyMappings() {
        mapShowAnswer = KeyMapper.getMapping(this, KeyMapper.KEY_SHOW_ANSWER, KeyMapper.DEFAULT_SHOW_ANSWER);
        mapAgain      = KeyMapper.getMapping(this, KeyMapper.KEY_AGAIN,       KeyMapper.DEFAULT_AGAIN);
        mapHard       = KeyMapper.getMapping(this, KeyMapper.KEY_HARD,        KeyMapper.DEFAULT_HARD);
        mapGood       = KeyMapper.getMapping(this, KeyMapper.KEY_GOOD,        KeyMapper.DEFAULT_GOOD);
        mapEasy       = KeyMapper.getMapping(this, KeyMapper.KEY_EASY,        KeyMapper.DEFAULT_EASY);

        if (btnShowAnswer != null) {
            btnShowAnswer.setText("SHOW ANSWER  [" + mapShowAnswer + "]");
        }

        if (tvLblAgain != null) tvLblAgain.setText("Again (" + mapAgain + ")");
        if (tvLblHard  != null) tvLblHard.setText("Hard ("  + mapHard  + ")");
        if (tvLblGood  != null) tvLblGood.setText("Good ("  + mapGood  + ")");
        if (tvLblEasy  != null) tvLblEasy.setText("Easy ("  + mapEasy  + ")");
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadKeyMappings();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) mediaPlayer.stop();
                mediaPlayer.release();
            } catch (Exception ignored) {}
            mediaPlayer = null;
        }
        if (db != null) {
            try { db.close(); } catch (Exception ignored) {}
            db = null;
        }
        SyncManager sm = new SyncManager(this);
        if (sm.isLoggedIn()) {
            sm.autoSync(null);
        }
    }

    private void updateLiveCounts() {
        int newCount = 0;
        int lrnCount = 0;
        int revCount = 0;

        for (Card c : cardQueue) {
            if (c.queue == Card.QUEUE_NEW) newCount++;
            else if (c.queue == Card.QUEUE_LEARN) lrnCount++;
            else revCount++;
        }

        if (currentCard != null) {
            if (currentCard.queue == Card.QUEUE_NEW) newCount++;
            else if (currentCard.queue == Card.QUEUE_LEARN) lrnCount++;
            else revCount++;
        }

        if (tvBadgeNew != null) {
            tvBadgeNew.setText(String.valueOf(newCount));
            tvBadgeNew.setAlpha(newCount > 0 ? 1.0f : 0.4f);
        }
        if (tvBadgeLearn != null) {
            tvBadgeLearn.setText(String.valueOf(lrnCount));
            tvBadgeLearn.setAlpha(lrnCount > 0 ? 1.0f : 0.4f);
        }
        if (tvBadgeReview != null) {
            tvBadgeReview.setText(String.valueOf(revCount));
            tvBadgeReview.setAlpha(revCount > 0 ? 1.0f : 0.4f);
        }
    }

    private void showNextCard() {
        try {
            if (cardQueue.isEmpty()) {
                if (wvCard != null) wvCard.setVisibility(View.GONE);
                if (llFrontAnswer != null) llFrontAnswer.setVisibility(View.GONE);
                if (llBackRatings != null) llBackRatings.setVisibility(View.GONE);
                if (tvBadgeNew != null) tvBadgeNew.setText("0");
                if (tvBadgeLearn != null) tvBadgeLearn.setText("0");
                if (layoutDone != null) layoutDone.setVisibility(View.VISIBLE);
                if (btnAudio != null) btnAudio.setVisibility(View.GONE);
                if (db != null) {
                    try { db.close(); } catch (Exception ignored) {}
                    db = null;
                }

                SyncManager sm = new SyncManager(this);
                if (sm.isLoggedIn()) {
                    sm.autoSync(null);
                }
                return;
            }

            currentCard = cardQueue.poll();
            currentCard.startedAt = System.currentTimeMillis();
            showingBack = false;

            updateLiveCounts();
            showFront();
        } catch (Throwable t) {
            Log.e(TAG, "showNextCard error", t);
        }
    }

    private void showFront() {
        if (wvCard != null) wvCard.setVisibility(View.VISIBLE);
        if (layoutDone != null) layoutDone.setVisibility(View.GONE);
        if (llFrontAnswer != null) llFrontAnswer.setVisibility(View.VISIBLE);
        if (llBackRatings != null) llBackRatings.setVisibility(View.GONE);

        String html = currentCard != null && currentCard.frontHtml != null ? currentCard.frontHtml : "<html><body><div class='card'>No Content</div></body></html>";
        if (wvCard != null) {
            File mediaDir = new File(getFilesDir(), "collection.media");
            wvCard.loadDataWithBaseURL("file://" + mediaDir.getAbsolutePath() + "/", html, "text/html", "UTF-8", null);
        }

        currentCardSounds = extractSoundFiles(html);
        if (btnAudio != null) {
            btnAudio.setVisibility(!currentCardSounds.isEmpty() ? View.VISIBLE : View.GONE);
        }
        if (!currentCardSounds.isEmpty()) {
            playMedia(currentCardSounds.get(0));
        }
    }

    private void showBack() {
        showingBack = true;
        if (llFrontAnswer != null) llFrontAnswer.setVisibility(View.GONE);
        if (llBackRatings != null) llBackRatings.setVisibility(View.VISIBLE);

        if (scheduler != null && currentCard != null) {
            String[] intervals = scheduler.getNextIntervals(currentCard);
            if (tvIvlAgain != null) tvIvlAgain.setText(intervals[0]);
            if (tvIvlHard != null) tvIvlHard.setText(intervals[1]);
            if (tvIvlGood != null) tvIvlGood.setText(intervals[2]);
            if (tvIvlEasy != null) tvIvlEasy.setText(intervals[3]);
        }

        String html = currentCard != null && currentCard.backHtml != null ? currentCard.backHtml : "<html><body><div class='card'>No Content</div></body></html>";
        if (wvCard != null) {
            File mediaDir = new File(getFilesDir(), "collection.media");
            wvCard.loadDataWithBaseURL("file://" + mediaDir.getAbsolutePath() + "/", html, "text/html", "UTF-8", null);
        }

        List<String> backSounds = extractSoundFiles(html);
        if (!backSounds.isEmpty()) {
            currentCardSounds = backSounds;
            if (btnAudio != null) btnAudio.setVisibility(View.VISIBLE);
            playMedia(backSounds.get(0));
        }
    }

    private void setupButtons() {
        View.OnClickListener showAnswerClick = new View.OnClickListener() {
            public void onClick(View v) { showBack(); }
        };
        if (btnShowAnswer != null) btnShowAnswer.setOnClickListener(showAnswerClick);
        if (llFrontAnswer != null) llFrontAnswer.setOnClickListener(showAnswerClick);

        View.OnClickListener againClick = new View.OnClickListener() {
            public void onClick(View v) { answerCard(Scheduler.EASE_AGAIN); }
        };
        if (btnAgain != null) btnAgain.setOnClickListener(againClick);
        if (tvIvlAgain != null) tvIvlAgain.setOnClickListener(againClick);
        if (tvLblAgain != null) tvLblAgain.setOnClickListener(againClick);

        View.OnClickListener hardClick = new View.OnClickListener() {
            public void onClick(View v) { answerCard(Scheduler.EASE_HARD); }
        };
        if (btnHard != null) btnHard.setOnClickListener(hardClick);
        if (tvIvlHard != null) tvIvlHard.setOnClickListener(hardClick);
        if (tvLblHard != null) tvLblHard.setOnClickListener(hardClick);

        View.OnClickListener goodClick = new View.OnClickListener() {
            public void onClick(View v) { answerCard(Scheduler.EASE_GOOD); }
        };
        if (btnGood != null) btnGood.setOnClickListener(goodClick);
        if (tvIvlGood != null) tvIvlGood.setOnClickListener(goodClick);
        if (tvLblGood != null) tvLblGood.setOnClickListener(goodClick);

        View.OnClickListener easyClick = new View.OnClickListener() {
            public void onClick(View v) { answerCard(Scheduler.EASE_EASY); }
        };
        if (btnEasy != null) btnEasy.setOnClickListener(easyClick);
        if (tvIvlEasy != null) tvIvlEasy.setOnClickListener(easyClick);
        if (tvLblEasy != null) tvLblEasy.setOnClickListener(easyClick);
    }

    private void answerCard(int ease) {
        if (currentCard == null) return;

        try {
            long timeTaken = System.currentTimeMillis() - currentCard.startedAt;
            if (scheduler != null) {
                scheduler.answerCard(currentCard, ease, timeTaken);
            }

            if (ease == Scheduler.EASE_AGAIN || currentCard.queue == Card.QUEUE_LEARN) {
                cardQueue.add(currentCard);
            }

            showNextCard();
        } catch (Throwable t) {
            Log.e(TAG, "answerCard error", t);
            showNextCard();
        }
    }

    // -----------------------------------------------------------------------
    // BlackBerry Physical Keyboard
    // -----------------------------------------------------------------------

    private boolean isHandledKey(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_BACK) return false;

        // Custom-mapped keys
        if (KeyMapper.matches(event, mapShowAnswer)
                || KeyMapper.matches(event, mapAgain)
                || KeyMapper.matches(event, mapHard)
                || KeyMapper.matches(event, mapGood)
                || KeyMapper.matches(event, mapEasy)) {
            return true;
        }

        // Audio replay: P or O
        if (keyCode == KeyEvent.KEYCODE_P || isChar(event, 'p')
                || keyCode == KeyEvent.KEYCODE_O || isChar(event, 'o')) return true;

        // BB Q5 built-in keyboard fallbacks
        if (keyCode == KeyEvent.KEYCODE_SPACE || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) return true;
        if (keyCode >= KeyEvent.KEYCODE_1 && keyCode <= KeyEvent.KEYCODE_4) return true;
        if (keyCode == KeyEvent.KEYCODE_W || keyCode == KeyEvent.KEYCODE_E
                || keyCode == KeyEvent.KEYCODE_R || keyCode == KeyEvent.KEYCODE_S
                || keyCode == KeyEvent.KEYCODE_D || keyCode == KeyEvent.KEYCODE_F) return true;
        if (isChar(event, '1') || isChar(event, '2') || isChar(event, '3') || isChar(event, '4')) return true;
        if (isChar(event, 'w') || isChar(event, 'e') || isChar(event, 'r') || isChar(event, 's')
                || isChar(event, 'd') || isChar(event, 'f')) return true;

        return false;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (handlePhysicalKey(event)) return true;
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            if (isHandledKey(event)) return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (handlePhysicalKey(event)) return true;
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (isHandledKey(event)) return true;
        return super.onKeyUp(keyCode, event);
    }

    private boolean handlePhysicalKey(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_BACK) return false;

        // Audio replay: P or O
        if (keyCode == KeyEvent.KEYCODE_P || isChar(event, 'p')
                || keyCode == KeyEvent.KEYCODE_O || isChar(event, 'o')) {
            playCurrentAudio();
            return true;
        }

        // -- Front Side: Show Answer ------------------------------------------
        if (!showingBack) {
            if (KeyMapper.matches(event, mapShowAnswer)
                    || KeyMapper.matches(event, mapAgain)
                    || KeyMapper.matches(event, mapHard)
                    || KeyMapper.matches(event, mapGood)
                    || KeyMapper.matches(event, mapEasy)
                    || keyCode == KeyEvent.KEYCODE_SPACE
                    || keyCode == KeyEvent.KEYCODE_ENTER
                    || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                    || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                    || (keyCode >= KeyEvent.KEYCODE_1 && keyCode <= KeyEvent.KEYCODE_4)
                    || keyCode == KeyEvent.KEYCODE_W || keyCode == KeyEvent.KEYCODE_E
                    || keyCode == KeyEvent.KEYCODE_R || keyCode == KeyEvent.KEYCODE_S
                    || keyCode == KeyEvent.KEYCODE_D || keyCode == KeyEvent.KEYCODE_F
                    || isChar(event, '1') || isChar(event, '2') || isChar(event, '3') || isChar(event, '4')
                    || isChar(event, 'w') || isChar(event, 'e') || isChar(event, 'r')
                    || isChar(event, 's') || isChar(event, 'd') || isChar(event, 'f')) {
                showBack();
                return true;
            }
        } else {
            // -- Back Side: Rating --------------------------------------------

            // Again
            if (KeyMapper.matches(event, mapAgain)
                    || keyCode == KeyEvent.KEYCODE_1 || isChar(event, '1')
                    || keyCode == KeyEvent.KEYCODE_W || isChar(event, 'w')) {
                answerCard(Scheduler.EASE_AGAIN);
                return true;
            }

            // Hard
            if (KeyMapper.matches(event, mapHard)
                    || keyCode == KeyEvent.KEYCODE_2 || isChar(event, '2')
                    || keyCode == KeyEvent.KEYCODE_E || isChar(event, 'e')) {
                answerCard(Scheduler.EASE_HARD);
                return true;
            }

            // Good (Space / Enter / 3 / R / D / custom)
            if (KeyMapper.matches(event, mapGood)
                    || KeyMapper.matches(event, mapShowAnswer)
                    || keyCode == KeyEvent.KEYCODE_3 || isChar(event, '3')
                    || keyCode == KeyEvent.KEYCODE_R || isChar(event, 'r')
                    || keyCode == KeyEvent.KEYCODE_D || isChar(event, 'd')
                    || keyCode == KeyEvent.KEYCODE_SPACE || keyCode == KeyEvent.KEYCODE_ENTER
                    || keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                answerCard(Scheduler.EASE_GOOD);
                return true;
            }

            // Easy
            if (KeyMapper.matches(event, mapEasy)
                    || keyCode == KeyEvent.KEYCODE_4 || isChar(event, '4')
                    || keyCode == KeyEvent.KEYCODE_S || isChar(event, 's')
                    || keyCode == KeyEvent.KEYCODE_F || isChar(event, 'f')) {
                answerCard(Scheduler.EASE_EASY);
                return true;
            }
        }

        return false;
    }

    private boolean isChar(KeyEvent event, char target) {
        char c = (char) event.getUnicodeChar();
        return Character.toLowerCase(c) == Character.toLowerCase(target);
    }

    private void playCurrentAudio() {
        if (currentCardSounds != null && !currentCardSounds.isEmpty()) {
            playMedia(currentCardSounds.get(0));
        } else {
            Toast.makeText(this, "No audio on this card", Toast.LENGTH_SHORT).show();
        }
    }

    private void playMedia(String filename) {
        if (filename == null || filename.isEmpty()) return;
        try {
            File targetFile = null;
            // 1. App filesDir/collection.media/
            File f1 = new File(new File(getFilesDir(), "collection.media"), filename);
            if (f1.exists()) targetFile = f1;

            // 2. External /sdcard/AnkiDroid/collection.media/
            if (targetFile == null) {
                File f2 = new File(android.os.Environment.getExternalStorageDirectory(), "AnkiDroid/collection.media/" + filename);
                if (f2.exists()) targetFile = f2;
            }

            // 3. External /sdcard/collection.media/
            if (targetFile == null) {
                File f3 = new File(android.os.Environment.getExternalStorageDirectory(), "collection.media/" + filename);
                if (f3.exists()) targetFile = f3;
            }

            // 4. App filesDir/
            if (targetFile == null) {
                File f4 = new File(getFilesDir(), filename);
                if (f4.exists()) targetFile = f4;
            }

            if (targetFile != null) {
                if (mediaPlayer != null) {
                    try {
                        if (mediaPlayer.isPlaying()) mediaPlayer.stop();
                        mediaPlayer.release();
                    } catch (Exception ignored) {}
                    mediaPlayer = null;
                }
                mediaPlayer = new MediaPlayer();
                mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
                mediaPlayer.setDataSource(targetFile.getAbsolutePath());
                mediaPlayer.prepare();
                mediaPlayer.start();
                Log.i(TAG, "Playing audio: " + targetFile.getAbsolutePath());
            } else if (filename.startsWith("http://") || filename.startsWith("https://")) {
                if (mediaPlayer != null) {
                    try {
                        if (mediaPlayer.isPlaying()) mediaPlayer.stop();
                        mediaPlayer.release();
                    } catch (Exception ignored) {}
                    mediaPlayer = null;
                }
                mediaPlayer = new MediaPlayer();
                mediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
                mediaPlayer.setDataSource(filename);
                mediaPlayer.prepareAsync();
                mediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                    public void onPrepared(MediaPlayer mp) {
                        mp.start();
                    }
                });
            } else {
                Toast.makeText(this, "Audio file not found: " + filename + "\n(Place in collection.media)", Toast.LENGTH_SHORT).show();
                Log.w(TAG, "Audio file not found: " + filename);
            }
        } catch (Throwable t) {
            Log.e(TAG, "playMedia error", t);
            Toast.makeText(this, "Audio playback error: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private List<String> extractSoundFiles(String html) {
        List<String> list = new ArrayList<String>();
        if (html == null) return list;
        Matcher m = AnkiDatabase.SOUND_PATTERN.matcher(html);
        while (m.find()) {
            list.add(m.group(1));
        }
        return list;
    }

    public class AudioBridge {
        @JavascriptInterface
        public void playAudio(final String filename) {
            runOnUiThread(new Runnable() {
                public void run() {
                    playMedia(filename);
                }
            });
        }
    }
}