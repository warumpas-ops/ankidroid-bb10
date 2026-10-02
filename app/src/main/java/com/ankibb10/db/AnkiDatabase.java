package com.ankibb10.db;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import com.ankibb10.model.Card;
import com.ankibb10.model.Deck;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AnkiDatabase {
    private static final String TAG = "AnkiDatabase";

    private SQLiteDatabase mDb;
    private long mColCrt;
    private JSONObject mModels;
    private JSONObject mDecks;
    private JSONObject mDconf;
    private JSONObject mConf;

    public AnkiDatabase(String path) {
        mDb = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READWRITE);
        try {
            mDb.execSQL("PRAGMA journal_mode = DELETE");
            mDb.execSQL("PRAGMA synchronous = NORMAL");
        } catch (Exception ignored) {}
        loadCol();
    }

    private void loadCol() {
        Cursor c = mDb.rawQuery("SELECT crt, conf, models, decks, dconf FROM col LIMIT 1", null);
        if (c.moveToFirst()) {
            mColCrt = c.getLong(0);
            String confStr   = c.getString(1);
            String modelsStr = c.getString(2);
            String decksStr  = c.getString(3);
            String dconfStr  = c.getString(4);
            try { if (confStr   != null && !confStr.isEmpty())   mConf   = new JSONObject(confStr);   } catch (JSONException ignored) {}
            try { if (modelsStr != null && !modelsStr.isEmpty()) mModels = new JSONObject(modelsStr); } catch (JSONException ignored) {}
            try { if (decksStr  != null && !decksStr.isEmpty())  mDecks  = new JSONObject(decksStr);  } catch (JSONException ignored) {}
            try { if (dconfStr  != null && !dconfStr.isEmpty())  mDconf  = new JSONObject(dconfStr);  } catch (JSONException ignored) {}
        }
        c.close();

        // Schema 18 fallback: if JSON blobs are empty, read from dedicated tables
        if (mModels == null || mModels.length() == 0) loadModelsFromTable();
        if (mDecks  == null || mDecks.length()  == 0) loadDecksFromTable();
        if (mDconf  == null || mDconf.length()   == 0) loadDconfFromTable();

        if (mColCrt <= 0) mColCrt = System.currentTimeMillis() / 1000 - 86400;
    }

    // ---- Schema 18 table-based fallbacks ----

    private void loadDecksFromTable() {
        try {
            mDecks = new JSONObject();
            Cursor dc = mDb.rawQuery("SELECT id, name FROM decks", null);
            while (dc.moveToNext()) {
                long did = dc.getLong(0);
                String name = dc.getString(1);
                JSONObject d = new JSONObject();
                d.put("id", did); d.put("name", name); d.put("conf", 1L); d.put("collapsed", false);
                mDecks.put(String.valueOf(did), d);
            }
            dc.close();
            Log.i(TAG, "Schema 18: loaded " + mDecks.length() + " decks from decks table");
        } catch (Exception e) {
            Log.e(TAG, "Schema 18 loadDecksFromTable failed", e);
            mDecks = new JSONObject();
        }
    }

    private void loadDconfFromTable() {
        try {
            mDconf = new JSONObject();
            try {
                Cursor dc = mDb.rawQuery("SELECT id, name, config FROM deck_config", null);
                while (dc.moveToNext()) {
                    long cid  = dc.getLong(0);
                    String nm = dc.getString(1);
                    byte[] blob = dc.getBlob(2);
                    int newPerDay = 20, maxReviews = 200;
                    if (blob != null) {
                        int[] vals = parseProtoVarints(blob, new int[]{9, 10});
                        if (vals[0] > 0) newPerDay  = vals[0];
                        if (vals[1] > 0) maxReviews = vals[1];
                    }
                    JSONObject cfg = new JSONObject();
                    cfg.put("id", cid); cfg.put("name", nm);
                    JSONObject n = new JSONObject(); n.put("perDay", newPerDay);
                    JSONObject r = new JSONObject(); r.put("perDay", maxReviews);
                    cfg.put("new", n); cfg.put("rev", r);
                    mDconf.put(String.valueOf(cid), cfg);
                }
                dc.close();
            } catch (Exception e) { Log.w(TAG, "Schema 18 deck_config read warning", e); }
            if (!mDconf.has("1")) {
                JSONObject def = new JSONObject(); def.put("id", 1); def.put("name", "Default");
                JSONObject n = new JSONObject(); n.put("perDay", 20);
                JSONObject r = new JSONObject(); r.put("perDay", 200);
                def.put("new", n); def.put("rev", r);
                mDconf.put("1", def);
            }
            Log.i(TAG, "Schema 18: loaded dconf entries=" + mDconf.length());
        } catch (Exception e) { Log.e(TAG, "Schema 18 loadDconfFromTable failed", e); }
    }

    private void loadModelsFromTable() {
        try {
            mModels = new JSONObject();
            Cursor nc = mDb.rawQuery("SELECT id, name, config FROM notetypes", null);
            while (nc.moveToNext()) {
                long mid = nc.getLong(0); String mn = nc.getString(1); byte[] cfg = nc.getBlob(2);
                JSONObject model = new JSONObject();
                model.put("id", mid); model.put("name", mn);
                model.put("css", (cfg != null) ? extractProtoString(cfg, 4) : "");
                mModels.put(String.valueOf(mid), model);
            }
            nc.close();

            java.util.Map<Long, JSONArray> fieldMap = new java.util.LinkedHashMap<Long, JSONArray>();
            Cursor fc = mDb.rawQuery("SELECT ntid, ord, name FROM fields ORDER BY ntid, ord", null);
            while (fc.moveToNext()) {
                long ntid = fc.getLong(0);
                if (!fieldMap.containsKey(ntid)) fieldMap.put(ntid, new JSONArray());
                JSONObject fld = new JSONObject(); fld.put("name", fc.getString(2)); fld.put("ord", fc.getInt(1));
                fieldMap.get(ntid).put(fld);
            }
            fc.close();

            java.util.Map<Long, JSONArray> tmplMap = new java.util.LinkedHashMap<Long, JSONArray>();
            Cursor tc = mDb.rawQuery("SELECT ntid, ord, name, config FROM templates ORDER BY ntid, ord", null);
            while (tc.moveToNext()) {
                long ntid = tc.getLong(0); byte[] tcfg = tc.getBlob(3);
                if (!tmplMap.containsKey(ntid)) tmplMap.put(ntid, new JSONArray());
                JSONObject tmpl = new JSONObject();
                tmpl.put("ord", tc.getInt(1)); tmpl.put("name", tc.getString(2));
                tmpl.put("qfmt", tcfg != null ? extractProtoString(tcfg, 1) : "");
                tmpl.put("afmt", tcfg != null ? extractProtoString(tcfg, 2) : "");
                tmplMap.get(ntid).put(tmpl);
            }
            tc.close();

            java.util.Iterator<String> keys = mModels.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONObject model = mModels.optJSONObject(key);
                if (model == null) continue;
                long mid = model.optLong("id");
                if (fieldMap.containsKey(mid)) model.put("flds", fieldMap.get(mid));
                if (tmplMap.containsKey(mid)) model.put("tmpls", tmplMap.get(mid));
            }
            Log.i(TAG, "Schema 18: loaded " + mModels.length() + " models from notetypes table");
        } catch (Exception e) {
            Log.e(TAG, "Schema 18 loadModelsFromTable failed", e);
            mModels = new JSONObject();
        }
    }

    private int[] parseProtoVarints(byte[] proto, int[] targetFields) {
        int[] result = new int[targetFields.length];
        try {
            int pos = 0;
            while (pos < proto.length) {
                int tag = proto[pos++] & 0xff; int fn = tag >> 3; int wt = tag & 7;
                if (wt == 0) {
                    long val = 0; int shift = 0;
                    while (pos < proto.length) { int b = proto[pos++] & 0xff; val |= ((long)(b & 0x7f)) << shift; shift += 7; if ((b & 0x80) == 0) break; }
                    for (int i = 0; i < targetFields.length; i++) { if (fn == targetFields[i]) result[i] = (int) val; }
                } else if (wt == 2) {
                    int len = 0; int shift = 0;
                    while (pos < proto.length) { int b = proto[pos++] & 0xff; len |= (b & 0x7f) << shift; shift += 7; if ((b & 0x80) == 0) break; }
                    pos += len;
                } else if (wt == 1) { pos += 8; } else if (wt == 5) { pos += 4; } else break;
            }
        } catch (Exception e) { Log.w(TAG, "parseProtoVarints error", e); }
        return result;
    }

    private String extractProtoString(byte[] proto, int targetField) {
        try {
            int pos = 0;
            while (pos < proto.length) {
                int tag = proto[pos++] & 0xff; int fn = tag >> 3; int wt = tag & 7;
                if (wt == 0) { while (pos < proto.length && (proto[pos] & 0x80) != 0) pos++; if (pos < proto.length) pos++; }
                else if (wt == 2) {
                    int len = 0; int shift = 0;
                    while (pos < proto.length) { int b = proto[pos++] & 0xff; len |= (b & 0x7f) << shift; shift += 7; if ((b & 0x80) == 0) break; }
                    if (fn == targetField) { try { return new String(proto, pos, len, "UTF-8"); } catch (Exception ex) { return ""; } }
                    pos += len;
                } else if (wt == 1) { pos += 8; } else if (wt == 5) { pos += 4; } else break;
            }
        } catch (Exception e) { Log.w(TAG, "extractProtoString error", e); }
        return "";
    }

    public long getColCrt() { return mColCrt; }

    // ---- Deck Configuration ----

    /** Config values read from AnkiWeb's dconf JSON blob for a specific deck. */
    public static class DeckConfig {
        public int newPerDay     = 20;   // new cards per day
        public int maxReviews    = 200;  // max review cards per day
        public int maxInterval   = 36500;// max scheduling interval in days
        public int[] learnSteps  = {60, 600};   // learning steps in seconds
        public int[] lapseSteps  = {600};        // relearn steps in seconds
        public int graduatingIvl = 1;   // days after graduating learning
        public int easyIvl       = 4;   // days for easy on new card
    }

    /** Returns the deck config preset that applies to the given deckId. */
    public DeckConfig getDeckConf(long deckId) {
        DeckConfig cfg = new DeckConfig();
        if (mDecks == null || mDconf == null) return cfg;

        try {
            JSONObject deck = mDecks.optJSONObject(String.valueOf(deckId));
            if (deck == null) return cfg;

            long confId = deck.optLong("conf", 1);
            JSONObject dconf = mDconf.optJSONObject(String.valueOf(confId));
            if (dconf != null) {
                // New card limits
                JSONObject newObj = dconf.optJSONObject("new");
                if (newObj != null) {
                    cfg.newPerDay = newObj.optInt("perDay", cfg.newPerDay);
                    JSONArray intsArr = newObj.optJSONArray("ints");
                    if (intsArr != null && intsArr.length() >= 2) {
                        cfg.graduatingIvl = intsArr.optInt(0, 1);
                        cfg.easyIvl       = intsArr.optInt(1, 4);
                    }
                    JSONArray delays  = newObj.optJSONArray("delays");
                    if (delays != null && delays.length() > 0) {
                        cfg.learnSteps = new int[delays.length()];
                        for (int i = 0; i < delays.length(); i++) {
                            // Anki stores steps in minutes, convert to seconds
                            cfg.learnSteps[i] = (int)(delays.getDouble(i) * 60);
                        }
                    }
                }

                // Review limits
                JSONObject revObj = dconf.optJSONObject("rev");
                if (revObj != null) {
                    cfg.maxReviews  = revObj.optInt("perDay", cfg.maxReviews);
                    cfg.maxInterval = revObj.optInt("maxIvl", cfg.maxInterval);
                }

                // Lapse steps
                JSONObject lapObj = dconf.optJSONObject("lapse");
                if (lapObj != null) {
                    JSONArray lapDelays = lapObj.optJSONArray("delays");
                    if (lapDelays != null && lapDelays.length() > 0) {
                        cfg.lapseSteps = new int[lapDelays.length()];
                        for (int i = 0; i < lapDelays.length(); i++) {
                            cfg.lapseSteps[i] = (int)(lapDelays.getDouble(i) * 60);
                        }
                    }
                }
            }

            // Check for per-deck overrides ("This deck" setting in Anki Desktop)
            JSONObject deckNew = deck.optJSONObject("new");
            if (deckNew != null) {
                cfg.newPerDay = deckNew.optInt("perDay", cfg.newPerDay);
            }
            if (deck.has("newPerDay")) {
                cfg.newPerDay = deck.optInt("newPerDay", cfg.newPerDay);
            }
            if (deck.has("perDay")) {
                cfg.newPerDay = deck.optInt("perDay", cfg.newPerDay);
            }
            // Check for "Today only" temporary extra limit
            int extendNew = deck.optInt("extendNew", 0);
            if (extendNew > 0) {
                cfg.newPerDay += extendNew;
            }

            // Check for per-deck review overrides
            JSONObject deckRev = deck.optJSONObject("rev");
            if (deckRev != null) {
                cfg.maxReviews = deckRev.optInt("perDay", cfg.maxReviews);
            }
            if (deck.has("revPerDay")) {
                cfg.maxReviews = deck.optInt("revPerDay", cfg.maxReviews);
            }
            int extendRev = deck.optInt("extendRev", 0);
            if (extendRev > 0) {
                cfg.maxReviews += extendRev;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error reading deck conf", e);
        }
        return cfg;
    }

    public List<Long> getDeckAndChildrenIds(long did) {
        List<Long> ids = new ArrayList<Long>();
        ids.add(did);
        if (mDecks == null) return ids;

        String parentName = null;
        try {
            JSONObject parent = mDecks.optJSONObject(String.valueOf(did));
            if (parent != null) {
                parentName = parent.getString("name");
            }
        } catch (JSONException e) {}

        if (parentName != null) {
            String prefix = parentName + "::";
            Iterator<String> it = mDecks.keys();
            while (it.hasNext()) {
                String k = it.next();
                try {
                    JSONObject d = mDecks.getJSONObject(k);
                    String name = d.getString("name");
                    if (name.startsWith(prefix)) {
                        ids.add(d.getLong("id"));
                    }
                } catch (JSONException e) {}
            }
        }
        return ids;
    }

    private String makeInClause(List<Long> ids) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(ids.get(i));
        }
        return sb.toString();
    }

    public List<Deck> getDecks() {
        List<Deck> result = new ArrayList<Deck>();
        if (mDecks == null) return result;

        int today = todayDays();
        Iterator<String> keys = mDecks.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            try {
                JSONObject d = mDecks.getJSONObject(key);
                long did = d.getLong("id");
                String name = d.getString("name");
                if (name.equals("Default") && did == 1) {
                    int total = countCards(did);
                    if (total == 0) continue;
                }

                Deck deck = new Deck(did, name);
                deck.newCount    = countNew(did);
                deck.learnCount  = countLearning(did);
                deck.reviewCount = countReview(did, today);
                result.add(deck);
            } catch (JSONException e) {
                Log.e(TAG, "Deck parse error", e);
            }
        }
        return result;
    }

    public int countCards(long did) {
        List<Long> ids = getDeckAndChildrenIds(did);
        Cursor c = mDb.rawQuery("SELECT COUNT(*) FROM cards WHERE did IN (" + makeInClause(ids) + ")", null);
        int n = 0;
        if (c.moveToFirst()) n = c.getInt(0);
        c.close();
        return n;
    }

    public int countNewStudiedToday(long did) {
        try {
            long dayStartMs = (mColCrt + (long) todayDays() * 86400L) * 1000L;
            List<Long> ids = getDeckAndChildrenIds(did);
            String inClause = makeInClause(ids);
            // Count ALL new-card reviews today (including from PC after sync).
            // This mirrors Anki's behaviour: if PC studied 20 cards and you synced,
            // the phone shows 0 remaining because those 20 reviews are in revlog.
            Cursor c = mDb.rawQuery(
                    "SELECT COUNT(DISTINCT cid) FROM revlog WHERE id >= ? AND type = 0 AND cid IN (SELECT id FROM cards WHERE did IN (" + inClause + "))",
                    new String[]{String.valueOf(dayStartMs)});
            int n = 0;
            if (c.moveToFirst()) n = c.getInt(0);
            c.close();
            return n;
        } catch (Exception e) {
            return 0;
        }
    }

    public int countReviewStudiedToday(long did) {
        try {
            long dayStartMs = (mColCrt + (long) todayDays() * 86400L) * 1000L;
            List<Long> ids = getDeckAndChildrenIds(did);
            String inClause = makeInClause(ids);
            Cursor c = mDb.rawQuery(
                    "SELECT COUNT(DISTINCT cid) FROM revlog WHERE id >= ? AND type IN (1, 2) AND cid IN (SELECT id FROM cards WHERE did IN (" + inClause + "))",
                    new String[]{String.valueOf(dayStartMs)});
            int n = 0;
            if (c.moveToFirst()) n = c.getInt(0);
            c.close();
            return n;
        } catch (Exception e) {
            return 0;
        }
    }

    public int countNew(long did) {
        DeckConfig cfg = getDeckConf(did);
        int studiedToday = countNewStudiedToday(did);
        int remainingAllowance = Math.max(0, cfg.newPerDay - studiedToday);
        if (remainingAllowance <= 0) return 0;

        List<Long> ids = getDeckAndChildrenIds(did);
        Cursor c = mDb.rawQuery(
                "SELECT COUNT(*) FROM cards WHERE did IN (" + makeInClause(ids) + ") AND queue=0", null);
        int n = 0;
        if (c.moveToFirst()) n = c.getInt(0);
        c.close();
        return Math.min(n, remainingAllowance);
    }

    public int countLearning(long did) {
        List<Long> ids = getDeckAndChildrenIds(did);
        long now = System.currentTimeMillis() / 1000;
        Cursor c = mDb.rawQuery(
                "SELECT COUNT(*) FROM cards WHERE did IN (" + makeInClause(ids) + ") AND queue=1 AND due<=?",
                new String[]{String.valueOf(now)});
        int n = 0;
        if (c.moveToFirst()) n = c.getInt(0);
        c.close();
        return n;
    }

    public int countReview(long did, int today) {
        DeckConfig cfg = getDeckConf(did);
        int studiedToday = countReviewStudiedToday(did);
        int remainingAllowance = Math.max(0, cfg.maxReviews - studiedToday);
        if (remainingAllowance <= 0) return 0;

        List<Long> ids = getDeckAndChildrenIds(did);
        Cursor c = mDb.rawQuery(
                "SELECT COUNT(*) FROM cards WHERE did IN (" + makeInClause(ids) + ") AND queue=2 AND due<=?",
                new String[]{String.valueOf(today)});
        int n = 0;
        if (c.moveToFirst()) n = c.getInt(0);
        c.close();
        return Math.min(n, remainingAllowance);
    }

    public List<Card> getDueCards(long deckId) {
        DeckConfig cfg = getDeckConf(deckId);
        int newAllowance = Math.max(0, cfg.newPerDay - countNewStudiedToday(deckId));
        int revAllowance = Math.max(0, cfg.maxReviews - countReviewStudiedToday(deckId));
        return getDueCards(deckId, newAllowance, revAllowance);
    }

    public List<Card> getDueCards(long deckId, int newLimit, int revLimit) {
        List<Card> cards = new ArrayList<Card>();
        int today = todayDays();
        long nowSec = System.currentTimeMillis() / 1000;

        List<Long> didList = getDeckAndChildrenIds(deckId);
        String inClause = makeInClause(didList);

        // 1. Learning cards (always fetch due learning cards)
        Cursor c = mDb.rawQuery(
                "SELECT id,nid,did,ord,mod,usn,type,queue,due,ivl,factor,reps,lapses,left,odue,odid,flags " +
                "FROM cards WHERE did IN (" + inClause + ") AND queue=1 AND due<=? ORDER BY due",
                new String[]{String.valueOf(nowSec)});
        cards.addAll(rowsToCards(c));
        c.close();

        // 2. Review cards (up to revLimit configured by PC/deck config)
        if (revLimit > 0) {
            c = mDb.rawQuery(
                    "SELECT id,nid,did,ord,mod,usn,type,queue,due,ivl,factor,reps,lapses,left,odue,odid,flags " +
                    "FROM cards WHERE did IN (" + inClause + ") AND queue=2 AND due<=? ORDER BY due LIMIT ?",
                    new String[]{String.valueOf(today), String.valueOf(revLimit)});
            cards.addAll(rowsToCards(c));
            c.close();
        }

        // 3. New cards (up to newLimit configured by PC/deck config)
        if (newLimit > 0) {
            c = mDb.rawQuery(
                    "SELECT id,nid,did,ord,mod,usn,type,queue,due,ivl,factor,reps,lapses,left,odue,odid,flags " +
                    "FROM cards WHERE did IN (" + inClause + ") AND queue=0 ORDER BY due LIMIT ?",
                    new String[]{String.valueOf(newLimit)});
            cards.addAll(rowsToCards(c));
            c.close();
        }

        for (Card card : cards) {
            try {
                renderCard(card);
            } catch (Throwable t) {
                Log.e(TAG, "renderCard failed for card " + card.id, t);
                card.frontHtml = wrapHtml("<p>Card #" + card.id + "</p>");
                card.backHtml = wrapHtml("<p>Card #" + card.id + "</p>");
            }
        }

        return cards;
    }

    public List<Card> getDueCards(long deckId, int totalLimit) {
        DeckConfig cfg = getDeckConf(deckId);
        int newAllowance = Math.min(totalLimit, Math.max(0, cfg.newPerDay - countNewStudiedToday(deckId)));
        int revAllowance = Math.min(totalLimit, Math.max(0, cfg.maxReviews - countReviewStudiedToday(deckId)));
        return getDueCards(deckId, newAllowance, revAllowance);
    }

    private List<Card> rowsToCards(Cursor c) {
        List<Card> list = new ArrayList<Card>();
        while (c.moveToNext()) {
            Card card = new Card();
            card.id      = c.getLong(0);
            card.nid     = c.getLong(1);
            card.did     = c.getLong(2);
            card.ord     = c.getInt(3);
            card.mod     = c.getLong(4);
            card.usn     = c.getInt(5);
            card.type    = c.getInt(6);
            card.queue   = c.getInt(7);
            card.due     = c.getInt(8);
            card.ivl     = c.getInt(9);
            card.factor  = c.getInt(10);
            card.reps    = c.getInt(11);
            card.lapses  = c.getInt(12);
            card.left    = c.getInt(13);
            card.odue    = c.getInt(14);
            card.odid    = c.getLong(15);
            card.flags   = c.getInt(16);
            list.add(card);
        }
        return list;
    }

    private static final String HTML_PREFIX =
            "<!DOCTYPE html><html><head>" +
            "<meta charset='UTF-8'/>" +
            "<meta name='viewport' content='width=device-width, initial-scale=1, user-scalable=no'/>" +
            "<style>" +
            "* { -webkit-box-sizing: border-box; box-sizing: border-box; }" +
            "html, body {" +
            "  margin: 0; padding: 0;" +
            "  width: 100%; height: 100%;" +
            "  background-color: #ECEFF1;" +
            "  font-family: -apple-system, 'Helvetica Neue', Helvetica, Arial, sans-serif;" +
            "}" +
            ".card {" +
            "  background-color: #FFFFFF;" +
            "  border-radius: 8px;" +
            "  box-shadow: 0 1px 3px rgba(0,0,0,0.12), 0 1px 2px rgba(0,0,0,0.16);" +
            "  margin: 8px;" +
            "  padding: 16px 14px;" +
            "  min-height: 80%;" +
            "  text-align: center;" +
            "  font-size: 19px;" +
            "  color: #212121;" +
            "  line-height: 1.45;" +
            "  word-wrap: break-word;" +
            "}" +
            "hr#answer {" +
            "  border: 0;" +
            "  border-top: 1px dashed #B0BEC5;" +
            "  margin: 16px 0;" +
            "}" +
            "img { max-width: 100%; height: auto; border-radius: 4px; }" +
            "table { margin: 0 auto; border-collapse: collapse; }" +
            "th, td { padding: 6px 10px; border: 1px solid #CFD8DC; }" +
            ".cloze { font-weight: bold; color: #0084FF; }" +
            ".anki-sound-container { margin: 8px auto; text-align: center; }" +
            ".anki-sound-btn { display: inline-block; background-color: #007AFF; color: #FFFFFF; border: none; border-radius: 18px; padding: 6px 14px; font-size: 13px; font-weight: bold; cursor: pointer; box-shadow: 0 1px 3px rgba(0,0,0,0.18); }" +
            ".anki-sound-btn:active { background-color: #0056B3; }" +
            "</style></head><body><div class='card'>";

    private static final String HTML_SUFFIX = "</div></body></html>";

    private String wrapHtml(String content) {
        return wrapHtml(content, "");
    }

    private String wrapHtml(String content, String modelCss) {
        if (content == null) content = "";
        String customStyle = "";
        if (modelCss != null && !modelCss.trim().isEmpty()) {
            customStyle = "<style>" + modelCss + "</style>";
        }
        return "<!DOCTYPE html><html><head>" +
                "<meta charset='UTF-8'/>" +
                "<meta name='viewport' content='width=device-width, initial-scale=1, user-scalable=no'/>" +
                "<style>" +
                "* { -webkit-box-sizing: border-box; box-sizing: border-box; }" +
                "html, body {" +
                "  margin: 0; padding: 0;" +
                "  width: 100%; height: 100%;" +
                "  background-color: #ECEFF1;" +
                "  font-family: -apple-system, 'Helvetica Neue', Helvetica, Arial, sans-serif;" +
                "}" +
                ".card {" +
                "  background-color: #FFFFFF;" +
                "  border-radius: 8px;" +
                "  box-shadow: 0 1px 3px rgba(0,0,0,0.12), 0 1px 2px rgba(0,0,0,0.16);" +
                "  margin: 8px;" +
                "  padding: 16px 14px;" +
                "  min-height: 80%;" +
                "  font-size: 19px;" +
                "  color: #212121;" +
                "  line-height: 1.45;" +
                "  word-wrap: break-word;" +
                "}" +
                "hr#answer {" +
                "  border: 0;" +
                "  border-top: 1px dashed #B0BEC5;" +
                "  margin: 16px 0;" +
                "}" +
                "img { max-width: 100%; height: auto; border-radius: 4px; }" +
                "table { margin: 0 auto; border-collapse: collapse; }" +
                "th, td { padding: 6px 10px; border: 1px solid #CFD8DC; }" +
                ".cloze { font-weight: bold; color: #0084FF; }" +
                ".anki-sound-container { margin: 8px auto; text-align: center; }" +
                ".anki-sound-btn { display: inline-block; background-color: #007AFF; color: #FFFFFF; border: none; border-radius: 18px; padding: 6px 14px; font-size: 13px; font-weight: bold; cursor: pointer; box-shadow: 0 1px 3px rgba(0,0,0,0.18); }" +
                ".anki-sound-btn:active { background-color: #0056B3; }" +
                "</style>" +
                customStyle +
                "</head><body><div class='card'>" +
                content +
                HTML_SUFFIX;
    }

    private void renderCard(Card card) {
        try {
            Cursor nc = mDb.rawQuery(
                    "SELECT mid, flds FROM notes WHERE id=?",
                    new String[]{String.valueOf(card.nid)});
            if (!nc.moveToFirst()) {
                nc.close();
                card.frontHtml = wrapHtml("(Note not found)");
                card.backHtml = card.frontHtml;
                return;
            }
            long mid  = nc.getLong(0);
            String flds = nc.getString(1);
            nc.close();

            JSONObject model = mModels != null ? mModels.optJSONObject(String.valueOf(mid)) : null;
            String modelCss = model != null ? model.optString("css", "") : "";

            if (model == null) {
                String[] parts = flds.split("\u001f", -1);
                String q = parts.length > 0 ? parts[0] : "";
                String a = parts.length > 1 ? parts[1] : "";
                card.frontHtml = wrapHtml(q, "");
                card.backHtml  = wrapHtml(q + "<hr id='answer'>" + a, "");
                return;
            }

            JSONArray fldDefs = model.optJSONArray("flds");
            String[] fieldValues = flds.split("\u001f", -1);
            Map<String, String> fieldMap = new HashMap<String, String>();
            if (fldDefs != null) {
                for (int i = 0; i < fldDefs.length() && i < fieldValues.length; i++) {
                    String fname = fldDefs.getJSONObject(i).getString("name");
                    fieldMap.put(fname, fieldValues[i]);
                }
            } else {
                for (int i = 0; i < fieldValues.length; i++) {
                    fieldMap.put("Field" + i, fieldValues[i]);
                }
            }

            JSONArray tmpls = model.optJSONArray("tmpls");
            String qfmt = null;
            String afmt = null;
            if (tmpls != null && tmpls.length() > 0) {
                JSONObject tmpl = tmpls.getJSONObject(card.ord % tmpls.length());
                qfmt = tmpl.optString("qfmt", "");
                afmt = tmpl.optString("afmt", "");
            }

            if (qfmt == null || qfmt.isEmpty()) {
                String[] parts = flds.split("\u001f", -1);
                String q = parts.length > 0 ? parts[0] : "";
                String a = parts.length > 1 ? parts[1] : "";
                card.frontHtml = wrapHtml(q, modelCss);
                card.backHtml  = wrapHtml(q + "<hr id='answer'>" + a, modelCss);
                return;
            }

            String front = applyTemplate(qfmt, fieldMap, card.ord + 1, false);
            // Crucial: replace {{FrontSide}} before applyTemplate strips unrecognized brackets
            String afmtWithFront = (afmt != null) ? afmt.replace("{{FrontSide}}", front) : "";
            String back  = applyTemplate(afmtWithFront, fieldMap, card.ord + 1, true);

            card.frontHtml = wrapHtml(front, modelCss);
            card.backHtml  = wrapHtml(back, modelCss);

        } catch (Throwable e) {
            Log.e(TAG, "renderCard error", e);
            card.frontHtml = wrapHtml("<p>(card render error)</p>");
            card.backHtml  = card.frontHtml;
        }
    }

    private String applyTemplate(String tmpl, Map<String, String> fields, int clozeOrd, boolean isBack) {
        String result = tmpl;
        for (Map.Entry<String, String> e : fields.entrySet()) {
            String val = e.getValue() == null ? "" : e.getValue();
            String processed = renderCloze(val, clozeOrd, isBack);
            result = result.replace("{{" + e.getKey() + "}}", processed);
            result = result.replace("{{cloze:" + e.getKey() + "}}", processed);
            result = result.replace("{{text:" + e.getKey() + "}}", stripHtml(processed));
        }
        result = result.replaceAll("\\{\\{[^}]+\\}\\}", "");
        result = renderSoundTags(result);
        return result;
    }

    public static final Pattern SOUND_PATTERN = Pattern.compile("\\[sound:([^\\]]+)\\]");

    public static String renderSoundTags(String html) {
        if (html == null || !html.contains("[sound:")) return html;
        Matcher m = SOUND_PATTERN.matcher(html);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String soundFile = m.group(1);
            String cleanName = soundFile;
            int slash = cleanName.lastIndexOf('/');
            if (slash >= 0) cleanName = cleanName.substring(slash + 1);
            String replacement = "<div class='anki-sound-container'>" +
                    "<button type='button' class='anki-sound-btn' onclick='if(window.AnkiAudio){window.AnkiAudio.playAudio(\"" + Matcher.quoteReplacement(soundFile) + "\");} return false;'>" +
                    "▶ 🔊 " + Matcher.quoteReplacement(cleanName) + "</button></div>";
            m.appendReplacement(sb, replacement);
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String renderCloze(String text, int clozeOrd, boolean isBack) {
        if (!text.contains("{{c")) return text;
        try {
            Pattern p = Pattern.compile("\\{\\{c(\\d+)::([^:]+?)(?:::([^}]+?))?\\}\\}");
            Matcher m = p.matcher(text);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                int num = Integer.parseInt(m.group(1));
                String answer = m.group(2);
                String hint = m.group(3);

                if (num == clozeOrd) {
                    if (isBack) {
                        m.appendReplacement(sb, "<span class='cloze'>" + Matcher.quoteReplacement(answer) + "</span>");
                    } else {
                        String prompt = (hint != null && !hint.isEmpty()) ? "[" + hint + "]" : "[...]";
                        m.appendReplacement(sb, "<span class='cloze'>" + Matcher.quoteReplacement(prompt) + "</span>");
                    }
                } else {
                    m.appendReplacement(sb, Matcher.quoteReplacement(answer));
                }
            }
            m.appendTail(sb);
            return sb.toString();
        } catch (Exception e) {
            Log.w(TAG, "renderCloze error: " + e.getMessage());
            return text;
        }
    }

    private String stripHtml(String html) {
        return html.replaceAll("<[^>]+>", "");
    }

    public void updateCard(Card card) {
        mDb.execSQL(
                "UPDATE cards SET mod=?,usn=?,type=?,queue=?,due=?,ivl=?,factor=?,reps=?,lapses=?,left=?,odue=?,odid=?,flags=? WHERE id=?",
                new Object[]{
                        System.currentTimeMillis() / 1000, -1,
                        card.type, card.queue, card.due, card.ivl,
                        card.factor, card.reps, card.lapses,
                        card.left, card.odue, card.odid, card.flags,
                        card.id
                }
        );
        touchCol();
    }

    public void addRevlog(long cardId, int ease, int ivl, int lastIvl, int factor, long timeTakenMs, int type) {
        long id = System.currentTimeMillis();
        try {
            Cursor c = mDb.rawQuery("SELECT MAX(id) FROM revlog", null);
            if (c.moveToFirst()) {
                long maxId = c.getLong(0);
                if (id <= maxId) id = maxId + 1;
            }
            c.close();
        } catch (Exception ignored) {}

        long clampedTime = Math.max(1000, Math.min(timeTakenMs, 60000));
        mDb.execSQL(
                "INSERT INTO revlog (id, cid, usn, ease, ivl, lastIvl, factor, time, type) VALUES(?,?,?,?,?,?,?,?,?)",
                new Object[]{id, cardId, -1, ease, ivl, lastIvl, factor, clampedTime, type}
        );
        touchCol();
    }

    public void addNote(long did, String front, String back) {
        long nowMs = System.currentTimeMillis();
        long nowSec = nowMs / 1000;
        long mid = getFirstModelId();

        long nid = nowMs;
        String guid = UUID.randomUUID().toString().substring(0, 10);
        String flds = front + "\u001f" + back;
        int csum = front.hashCode() & 0xFFFFFFFF;

        mDb.execSQL(
                "INSERT INTO notes (id, guid, mid, mod, usn, tags, flds, sfld, csum, flags, data) " +
                "VALUES (?, ?, ?, ?, -1, '', ?, ?, ?, 0, '')",
                new Object[]{nid, guid, mid, nowSec, flds, front, csum});

        long cid = nowMs + 1;
        int maxDue = getMaxDue(did);
        mDb.execSQL(
                "INSERT INTO cards (id, nid, did, ord, mod, usn, type, queue, due, ivl, factor, reps, lapses, left, odue, odid, flags, data) " +
                "VALUES (?, ?, ?, 0, ?, -1, 0, 0, ?, 0, 2500, 0, 0, 0, 0, 0, 0, '')",
                new Object[]{cid, nid, did, nowSec, maxDue + 1});

        touchCol();
    }

    private int getMaxDue(long did) {
        Cursor c = mDb.rawQuery("SELECT MAX(due) FROM cards WHERE did=?", new String[]{String.valueOf(did)});
        int d = 0;
        if (c.moveToFirst()) d = c.getInt(0);
        c.close();
        return d;
    }

    private long getFirstModelId() {
        if (mModels != null && mModels.length() > 0) {
            String firstKey = mModels.keys().next();
            try {
                return Long.parseLong(firstKey);
            } catch (Exception e) {}
        }
        return 1;
    }

    public void createDeck(String name) {
        if (mDecks == null) return;
        long newId = System.currentTimeMillis();
        try {
            JSONObject deckObj = new JSONObject();
            deckObj.put("id", newId);
            deckObj.put("name", name);
            deckObj.put("mod", System.currentTimeMillis() / 1000);
            deckObj.put("usn", -1);
            deckObj.put("desc", "");
            deckObj.put("collapsed", false);
            mDecks.put(String.valueOf(newId), deckObj);

            mDb.execSQL("UPDATE col SET decks=?, mod=?, usn=-1",
                    new Object[]{mDecks.toString(), System.currentTimeMillis() / 1000});
        } catch (JSONException e) {
            Log.e(TAG, "createDeck failed", e);
        }
    }

    private void touchCol() {
        long nowSec = System.currentTimeMillis() / 1000L;
        mDb.execSQL("UPDATE col SET mod=?, usn=-1", new Object[]{nowSec});
    }

    public boolean hasUnsyncedChanges() {
        if (mDb == null || !mDb.isOpen()) return false;
        try {
            Cursor c = mDb.rawQuery("SELECT COUNT(*) FROM revlog WHERE usn=-1", null);
            int revlogDirty = 0;
            if (c.moveToFirst()) revlogDirty = c.getInt(0);
            c.close();
            if (revlogDirty > 0) return true;

            c = mDb.rawQuery("SELECT COUNT(*) FROM cards WHERE usn=-1", null);
            int cardDirty = 0;
            if (c.moveToFirst()) cardDirty = c.getInt(0);
            c.close();
            if (cardDirty > 0) return true;

            c = mDb.rawQuery("SELECT COUNT(*) FROM notes WHERE usn=-1", null);
            int noteDirty = 0;
            if (c.moveToFirst()) noteDirty = c.getInt(0);
            c.close();
            if (noteDirty > 0) return true;

            c = mDb.rawQuery("SELECT usn FROM col", null);
            int colUsn = 0;
            if (c.moveToFirst()) colUsn = c.getInt(0);
            c.close();
            return colUsn == -1;
        } catch (Exception e) {
            Log.e(TAG, "hasUnsyncedChanges check error", e);
            return false;
        }
    }

    public void checkpointWal() {
        if (mDb == null || !mDb.isOpen()) return;
        try {
            Cursor c = mDb.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null);
            if (c != null) {
                c.moveToFirst();
                c.close();
            }
        } catch (Exception e) {
            Log.w(TAG, "checkpointWal warning", e);
        }
    }

    public void prepareForUpload(int targetUsn, long serverMod) {
        if (mDb == null || !mDb.isOpen()) return;
        int usn = targetUsn > 0 ? targetUsn : 1;
        long nowSec = System.currentTimeMillis() / 1000L;
        long uploadMod = Math.max(nowSec, serverMod + 1);
        try {
            mDb.beginTransaction();
            mDb.execSQL("UPDATE cards SET usn=? WHERE usn=-1", new Object[]{usn});
            mDb.execSQL("UPDATE notes SET usn=? WHERE usn=-1", new Object[]{usn});
            mDb.execSQL("UPDATE revlog SET usn=? WHERE usn=-1", new Object[]{usn});
            mDb.execSQL("UPDATE col SET usn=?, mod=?, ls=?", new Object[]{usn, uploadMod, uploadMod});
            mDb.setTransactionSuccessful();
        } catch (Exception e) {
            Log.e(TAG, "prepareForUpload error", e);
        } finally {
            try { mDb.endTransaction(); } catch (Exception ignored) {}
        }
        checkpointWal();
    }

    public void prepareForUpload(int targetUsn) {
        prepareForUpload(targetUsn, 0);
    }

    public void markAllSynced() {
        prepareForUpload(1);
    }

    public void markAllSynced(int targetUsn) {
        prepareForUpload(targetUsn);
    }

    // -----------------------------------------------------------------------
    // Two-Way Offline Review Merging (for multi-device sync with PC)
    // -----------------------------------------------------------------------
    public static class PendingReview {
        public long id, cid;
        public int ease, ivl, lastIvl, factor;
        public long time;
        public int type;
    }

    public static class PendingCardUpdate {
        public long id, mod;
        public int type, queue, due, ivl, factor, reps, lapses, left, odue;
        public long odid;
        public int flags;
    }

    public static class PendingNote {
        public long id;
        public String guid;
        public long mid, mod;
        public String tags, flds, sfld;
        public long csum;
        public int flags;
        public String data;
    }

    public static class PendingChanges {
        public List<PendingReview> revlogs = new ArrayList<PendingReview>();
        public List<PendingCardUpdate> cards = new ArrayList<PendingCardUpdate>();
        public List<PendingNote> notes = new ArrayList<PendingNote>();
        public boolean isEmpty() {
            return revlogs.isEmpty() && cards.isEmpty() && notes.isEmpty();
        }
    }

    public PendingChanges getPendingChanges() {
        PendingChanges pc = new PendingChanges();
        if (mDb == null || !mDb.isOpen()) return pc;
        try {
            // Revlog
            Cursor c = mDb.rawQuery("SELECT id, cid, ease, ivl, lastIvl, factor, time, type FROM revlog WHERE usn=-1", null);
            while (c.moveToNext()) {
                PendingReview r = new PendingReview();
                r.id = c.getLong(0);
                r.cid = c.getLong(1);
                r.ease = c.getInt(2);
                r.ivl = c.getInt(3);
                r.lastIvl = c.getInt(4);
                r.factor = c.getInt(5);
                r.time = c.getLong(6);
                r.type = c.getInt(7);
                pc.revlogs.add(r);
            }
            c.close();

            // Notes
            c = mDb.rawQuery("SELECT id, guid, mid, mod, tags, flds, sfld, csum, flags, data FROM notes WHERE usn=-1", null);
            while (c.moveToNext()) {
                PendingNote n = new PendingNote();
                n.id = c.getLong(0);
                n.guid = c.getString(1);
                n.mid = c.getLong(2);
                n.mod = c.getLong(3);
                n.tags = c.getString(4);
                n.flds = c.getString(5);
                n.sfld = c.getString(6);
                n.csum = c.getLong(7);
                n.flags = c.getInt(8);
                n.data = c.getString(9);
                pc.notes.add(n);
            }
            c.close();

            // Cards
            c = mDb.rawQuery("SELECT id, mod, type, queue, due, ivl, factor, reps, lapses, left, odue, odid, flags FROM cards WHERE usn=-1", null);
            while (c.moveToNext()) {
                PendingCardUpdate cu = new PendingCardUpdate();
                cu.id = c.getLong(0);
                cu.mod = c.getLong(1);
                cu.type = c.getInt(2);
                cu.queue = c.getInt(3);
                cu.due = c.getInt(4);
                cu.ivl = c.getInt(5);
                cu.factor = c.getInt(6);
                cu.reps = c.getInt(7);
                cu.lapses = c.getInt(8);
                cu.left = c.getInt(9);
                cu.odue = c.getInt(10);
                cu.odid = c.getLong(11);
                cu.flags = c.getInt(12);
                pc.cards.add(cu);
            }
            c.close();
        } catch (Exception e) {
            Log.e(TAG, "getPendingChanges error", e);
        }
        return pc;
    }

    public void applyPendingChanges(PendingChanges pc, int targetUsn, long serverMod) {
        if (mDb == null || !mDb.isOpen() || pc == null || pc.isEmpty()) return;
        int usn = targetUsn > 0 ? targetUsn : 1;
        long nowSec = System.currentTimeMillis() / 1000;
        long uploadMod = Math.max(nowSec, serverMod + 1);

        mDb.beginTransaction();
        try {
            // Apply notes
            for (PendingNote n : pc.notes) {
                mDb.execSQL(
                    "INSERT OR REPLACE INTO notes (id, guid, mid, mod, usn, tags, flds, sfld, csum, flags, data) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    new Object[]{n.id, n.guid, n.mid, nowSec, usn, n.tags, n.flds, n.sfld, n.csum, n.flags, n.data}
                );
            }

            // Apply cards
            for (PendingCardUpdate cu : pc.cards) {
                mDb.execSQL(
                    "UPDATE cards SET mod=?, usn=?, type=?, queue=?, due=?, ivl=?, factor=?, reps=?, lapses=?, left=?, odue=?, odid=?, flags=? WHERE id=?",
                    new Object[]{nowSec, usn, cu.type, cu.queue, cu.due, cu.ivl, cu.factor, cu.reps, cu.lapses, cu.left, cu.odue, cu.odid, cu.flags, cu.id}
                );
            }

            // Apply revlogs
            for (PendingReview r : pc.revlogs) {
                mDb.execSQL(
                    "INSERT OR REPLACE INTO revlog (id, cid, usn, ease, ivl, lastIvl, factor, time, type) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    new Object[]{r.id, r.cid, usn, r.ease, r.ivl, r.lastIvl, r.factor, r.time, r.type}
                );
            }

            // Update collection timestamp & sequence number
            mDb.execSQL("UPDATE col SET mod=?, usn=?, ls=?", new Object[]{uploadMod, usn, uploadMod});
            mDb.setTransactionSuccessful();
        } catch (Exception e) {
            Log.e(TAG, "applyPendingChanges error", e);
        } finally {
            try { mDb.endTransaction(); } catch (Exception ignored) {}
        }
        checkpointWal();
    }

    public void applyPendingChanges(PendingChanges pc, int targetUsn) {
        applyPendingChanges(pc, targetUsn, 0);
    }

    public long getColMod() {
        if (mDb == null || !mDb.isOpen()) return 0;
        try {
            Cursor c = mDb.rawQuery("SELECT mod FROM col", null);
            long mod = 0;
            if (c.moveToFirst()) mod = c.getLong(0);
            c.close();
            return mod;
        } catch (Exception e) {
            return 0;
        }
    }

    public long getColScm() {
        if (mDb == null || !mDb.isOpen()) return 0;
        try {
            Cursor c = mDb.rawQuery("SELECT scm FROM col", null);
            long scm = 0;
            if (c.moveToFirst()) scm = c.getLong(0);
            c.close();
            return scm;
        } catch (Exception e) {
            return 0;
        }
    }

    public int getColUsn() {
        if (mDb == null || !mDb.isOpen()) return 0;
        try {
            Cursor c = mDb.rawQuery("SELECT usn FROM col", null);
            int usn = 0;
            if (c.moveToFirst()) usn = c.getInt(0);
            c.close();
            return usn;
        } catch (Exception e) {
            return 0;
        }
    }

    public int todayDays() {
        return (int)((System.currentTimeMillis() / 1000 - mColCrt) / 86400);
    }

    public void close() {
        checkpointWal();
        if (mDb != null && mDb.isOpen()) mDb.close();
    }

    public SQLiteDatabase getDb() { return mDb; }
}