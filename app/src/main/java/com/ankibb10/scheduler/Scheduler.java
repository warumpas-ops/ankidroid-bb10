package com.ankibb10.scheduler;

import com.ankibb10.db.AnkiDatabase;
import com.ankibb10.model.Card;

public class Scheduler {
    public static final int EASE_AGAIN = 1;
    public static final int EASE_HARD  = 2;
    public static final int EASE_GOOD  = 3;
    public static final int EASE_EASY  = 4;

    private static final int MIN_EASE      = 1300;
    private static final int STARTING_EASE = 2500;

    private final AnkiDatabase mDb;
    private final FsrsScheduler mFsrs;

    public Scheduler(AnkiDatabase db) {
        mDb = db;
        mFsrs = new FsrsScheduler();
    }

    public boolean isFsrsActive() {
        return mDb != null && mDb.isFsrsEnabled();
    }

    public String[] getNextIntervals(Card card) {
        String[] ivls = new String[4];
        if (card == null) {
            ivls[0] = "<1m"; ivls[1] = "10m"; ivls[2] = "1d"; ivls[3] = "4d";
            return ivls;
        }

        if (isFsrsActive()) {
            return getNextIntervalsFsrs(card);
        }

        AnkiDatabase.DeckConfig cfg = mDb.getDeckConf(card.did);
        int[] learnSteps = (cfg.learnSteps != null && cfg.learnSteps.length > 0) ? cfg.learnSteps : new int[]{60, 600};
        int[] lapseSteps = (cfg.lapseSteps != null && cfg.lapseSteps.length > 0) ? cfg.lapseSteps : new int[]{600};
        int maxIvl = cfg.maxInterval > 0 ? cfg.maxInterval : 36500;

        if (card.type == Card.TYPE_REVIEW) {
            ivls[0] = "<" + formatSeconds(lapseSteps[0]);
            int hardIvl = Math.min(maxIvl, Math.max(card.ivl + 1, (int)(card.ivl * 1.2)));
            ivls[1] = formatDays(hardIvl);

            int factor = card.factor > 0 ? card.factor : STARTING_EASE;
            int goodIvl = Math.min(maxIvl, Math.max(card.ivl + 1, (int)(card.ivl * (factor / 1000.0))));
            ivls[2] = formatDays(goodIvl);

            int easyIvl = Math.min(maxIvl, Math.max(card.ivl + 1, (int)(card.ivl * (factor / 1000.0) * 1.3)));
            ivls[3] = formatDays(easyIvl);
        } else {
            ivls[0] = "<" + formatSeconds(learnSteps[0]);
            if (learnSteps.length > 1) {
                ivls[1] = formatSeconds(learnSteps[1]);
            } else {
                ivls[1] = formatSeconds(learnSteps[0] * 2);
            }
            if (card.left <= 1) {
                ivls[2] = formatDays(cfg.graduatingIvl);
            } else {
                int nextIdx = learnSteps.length - (card.left - 1);
                if (nextIdx >= 0 && nextIdx < learnSteps.length) {
                    ivls[2] = formatSeconds(learnSteps[nextIdx]);
                } else {
                    ivls[2] = formatDays(cfg.graduatingIvl);
                }
            }
            ivls[3] = formatDays(cfg.easyIvl);
        }
        return ivls;
    }

    private String[] getNextIntervalsFsrs(Card card) {
        String[] ivls = new String[4];
        AnkiDatabase.DeckConfig cfg = mDb.getDeckConf(card.did);
        int[] lapseSteps = (cfg.lapseSteps != null && cfg.lapseSteps.length > 0) ? cfg.lapseSteps : new int[]{600};
        int maxIvl = cfg.maxInterval > 0 ? cfg.maxInterval : 36500;
        int today = mDb.todayDays();

        // Again
        ivls[0] = "<" + formatSeconds(lapseSteps[0]);

        // Hard
        FsrsScheduler.MemoryState msHard = mFsrs.step(card, EASE_HARD, today);
        int hardIvl = Math.min(maxIvl, mFsrs.intervalFromStability(msHard.stability));

        // Good
        FsrsScheduler.MemoryState msGood = mFsrs.step(card, EASE_GOOD, today);
        int goodIvl = Math.min(maxIvl, mFsrs.intervalFromStability(msGood.stability));

        // Easy
        FsrsScheduler.MemoryState msEasy = mFsrs.step(card, EASE_EASY, today);
        int easyIvl = Math.min(maxIvl, Math.max(goodIvl + 1, mFsrs.intervalFromStability(msEasy.stability)));

        // Ensure monotonicity
        if (hardIvl >= goodIvl && goodIvl > 1) {
            hardIvl = Math.max(1, goodIvl - 1);
        }

        ivls[1] = formatDays(hardIvl);
        ivls[2] = formatDays(goodIvl);
        ivls[3] = formatDays(easyIvl);

        return ivls;
    }

    private String formatSeconds(int sec) {
        if (sec < 60) return "<1m";
        if (sec < 3600) return (sec / 60) + "m";
        if (sec < 86400) return (sec / 3600) + "h";
        return (sec / 86400) + "d";
    }

    private String formatDays(int days) {
        if (days <= 0) return "<1d";
        if (days == 1) return "1d";
        if (days < 30) return days + "d";
        if (days < 365) return (days / 30) + "m";
        return (days / 365) + "y";
    }

    public void answerCard(Card card, int ease, long timeTakenMs) {
        int today   = mDb.todayDays();
        int lastIvl = card.ivl;
        int lastFac = card.factor;
        int lastTyp = card.type;
        if (card.factor == 0) card.factor = STARTING_EASE;

        if (isFsrsActive()) {
            answerFsrs(card, ease, today, lastTyp);
        } else {
            if (card.type == Card.TYPE_REVIEW) {
                answerReview(card, ease, today);
            } else {
                answerLearning(card, ease, today);
            }
        }
        card.reps++;

        int revType = (lastTyp == Card.TYPE_REVIEW) ? 1 : (lastTyp == Card.TYPE_RELEARN ? 2 : 0);

        int revlogIvl;
        if (card.type == Card.TYPE_REVIEW) {
            revlogIvl = card.ivl; // positive days
        } else {
            revlogIvl = (ease == EASE_HARD) ? -120 : -60; // negative seconds for learn/relearn
        }

        int revlogLastIvl;
        if (lastTyp == Card.TYPE_REVIEW) {
            revlogLastIvl = lastIvl; // positive days
        } else if (lastTyp == Card.TYPE_NEW) {
            revlogLastIvl = 0; // 0 = standard Anki marker for card introduced from New
        } else {
            revlogLastIvl = lastIvl < 0 ? lastIvl : -60; // negative seconds for learn/relearn
        }

        mDb.updateCard(card);
        mDb.addRevlog(card.id, ease, revlogIvl, revlogLastIvl, card.factor, timeTakenMs, revType);
    }

    private void answerFsrs(Card card, int ease, int today, int lastTyp) {
        AnkiDatabase.DeckConfig cfg = mDb.getDeckConf(card.did);
        int maxIvl = cfg.maxInterval > 0 ? cfg.maxInterval : 36500;
        int[] lapseSteps = (cfg.lapseSteps != null && cfg.lapseSteps.length > 0) ? cfg.lapseSteps : new int[]{600};

        FsrsScheduler.MemoryState nextState = mFsrs.step(card, ease, today);
        card.data = FsrsScheduler.formatCardData(nextState);
        card.fsrsStability = nextState.stability;
        card.fsrsDifficulty = nextState.difficulty;

        if (ease == EASE_AGAIN) {
            card.lapses++;
            card.type  = Card.TYPE_RELEARN;
            card.queue = Card.QUEUE_LEARN;
            card.left  = lapseSteps.length;
            card.due   = (int)(System.currentTimeMillis() / 1000 + lapseSteps[0]);
            card.ivl   = 0;
        } else {
            int ivl = mFsrs.intervalFromStability(nextState.stability);
            if (ease == EASE_EASY) {
                FsrsScheduler.MemoryState goodState = mFsrs.step(card, EASE_GOOD, today);
                int goodIvl = mFsrs.intervalFromStability(goodState.stability);
                ivl = Math.max(goodIvl + 1, ivl);
            }
            card.ivl   = Math.min(maxIvl, Math.max(1, ivl));
            card.due   = today + card.ivl;
            card.type  = Card.TYPE_REVIEW;
            card.queue = Card.QUEUE_REVIEW;
            card.left  = 0;
        }
        card.factor = (int) Math.round(nextState.stability * 100);
    }

    private void answerLearning(Card card, int ease, int today) {
        AnkiDatabase.DeckConfig cfg = mDb.getDeckConf(card.did);
        int[] learnSteps = (cfg.learnSteps != null && cfg.learnSteps.length > 0) ? cfg.learnSteps : new int[]{60, 600};
        long nowSec = System.currentTimeMillis() / 1000;
        if (card.left == 0) card.left = learnSteps.length;

        switch (ease) {
            case EASE_AGAIN:
                card.type  = Card.TYPE_LEARN;
                card.queue = Card.QUEUE_LEARN;
                card.left  = learnSteps.length;
                card.due   = (int)(nowSec + learnSteps[0]);
                break;
            case EASE_HARD:
                card.type  = Card.TYPE_LEARN;
                card.queue = Card.QUEUE_LEARN;
                int si = Math.max(0, learnSteps.length - card.left);
                card.due   = (int)(nowSec + (learnSteps[si] * 3 / 2));
                break;
            case EASE_GOOD:
                if (card.left <= 1) {
                    graduate(card, today, false, cfg);
                } else {
                    card.left--;
                    card.type  = Card.TYPE_LEARN;
                    card.queue = Card.QUEUE_LEARN;
                    int stepIdx = learnSteps.length - card.left;
                    if (stepIdx < 0) stepIdx = 0;
                    if (stepIdx >= learnSteps.length) stepIdx = learnSteps.length - 1;
                    card.due   = (int)(nowSec + learnSteps[stepIdx]);
                }
                break;
            case EASE_EASY:
                graduate(card, today, true, cfg);
                break;
        }
    }

    private void graduate(Card card, int today, boolean easy, AnkiDatabase.DeckConfig cfg) {
        card.ivl   = easy ? cfg.easyIvl : cfg.graduatingIvl;
        card.due   = today + card.ivl;
        card.type  = Card.TYPE_REVIEW;
        card.queue = Card.QUEUE_REVIEW;
        card.left  = 0;
        if (card.factor == 0) card.factor = STARTING_EASE;
    }

    private void answerReview(Card card, int ease, int today) {
        AnkiDatabase.DeckConfig cfg = mDb.getDeckConf(card.did);
        int[] lapseSteps = (cfg.lapseSteps != null && cfg.lapseSteps.length > 0) ? cfg.lapseSteps : new int[]{600};
        int maxIvl = cfg.maxInterval > 0 ? cfg.maxInterval : 36500;

        switch (ease) {
            case EASE_AGAIN:
                card.lapses++;
                card.factor = Math.max(MIN_EASE, card.factor - 200);
                card.type   = Card.TYPE_RELEARN;
                card.queue  = Card.QUEUE_LEARN;
                card.left   = lapseSteps.length;
                card.due    = (int)(System.currentTimeMillis() / 1000 + lapseSteps[0]);
                break;
            case EASE_HARD:
                card.factor = Math.max(MIN_EASE, card.factor - 150);
                card.ivl    = Math.min(maxIvl, Math.max(card.ivl + 1, (int)(card.ivl * 1.2)));
                card.due    = today + card.ivl;
                break;
            case EASE_GOOD:
                card.ivl   = Math.min(maxIvl, Math.max(card.ivl + 1, (int)(card.ivl * card.factor / 1000.0)));
                card.due   = today + card.ivl;
                break;
            case EASE_EASY:
                card.factor = card.factor + 150;
                card.ivl    = Math.min(maxIvl, Math.max(card.ivl + 1, (int)(card.ivl * card.factor / 1000.0 * 1.3)));
                card.due    = today + card.ivl;
                break;
        }
    }
}
