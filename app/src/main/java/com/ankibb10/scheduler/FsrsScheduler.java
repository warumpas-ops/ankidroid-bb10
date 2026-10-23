package com.ankibb10.scheduler;

import com.ankibb10.model.Card;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FsrsScheduler {

    // Default 17 weights for FSRS-4.5 (matching Anki's native default weights)
    public static final double[] DEFAULT_WEIGHTS = new double[]{
            0.4872, 1.4003, 3.7145, 13.8206, // w0-w3: Initial stability for Again(1), Hard(2), Good(3), Easy(4)
            5.1618, 1.2298,                   // w4-w5: Initial difficulty parameters
            0.8975,                           // w6: Difficulty step
            0.031,                            // w7: Difficulty mean reversion
            1.6474, 0.1367,                   // w8-w9: Stability review base and power
            1.0461,                           // w10: Retrievability factor
            2.1072, 0.0793, 0.3246, 1.587,   // w11-w14: Lapse stability parameters
            0.2272,                           // w15: Hard penalty multiplier
            2.8755                            // w16: Easy bonus multiplier
    };

    public static final double DEFAULT_DESIRED_RETENTION = 0.90;

    private static final Pattern PATTERN_S = Pattern.compile("\"s\"\\s*:\\s*([0-9.]+)");
    private static final Pattern PATTERN_D = Pattern.compile("\"d\"\\s*:\\s*([0-9.]+)");

    private final double[] w;
    private final double desiredRetention;

    public FsrsScheduler() {
        this(DEFAULT_WEIGHTS, DEFAULT_DESIRED_RETENTION);
    }

    public FsrsScheduler(double[] customWeights, double desiredRetention) {
        this.w = (customWeights != null && customWeights.length >= 17) ? customWeights : DEFAULT_WEIGHTS;
        this.desiredRetention = (desiredRetention > 0.7 && desiredRetention < 0.99) ? desiredRetention : DEFAULT_DESIRED_RETENTION;
    }

    public static class MemoryState {
        public double stability;
        public double difficulty;

        public MemoryState(double s, double d) {
            this.stability = s;
            this.difficulty = d;
        }
    }

    /**
     * Extracts existing FSRS state from Card.
     * Returns null if the card has never been scheduled with FSRS.
     */
    public MemoryState getMemoryState(Card card) {
        if (card == null) return null;
        if (card.fsrsStability > 0 && card.fsrsDifficulty > 0) {
            return new MemoryState(card.fsrsStability, card.fsrsDifficulty);
        }
        if (card.data != null && !card.data.isEmpty()) {
            try {
                Matcher ms = PATTERN_S.matcher(card.data);
                Matcher md = PATTERN_D.matcher(card.data);
                if (ms.find() && md.find()) {
                    double s = Double.parseDouble(ms.group(1));
                    double d = Double.parseDouble(md.group(1));
                    card.fsrsStability = s;
                    card.fsrsDifficulty = d;
                    return new MemoryState(s, d);
                }
            } catch (Exception ignored) {}
        }
        // If it's an existing review card migrated from SM-2 without FSRS data,
        // estimate stability from its existing interval and mid difficulty
        if (card.type == Card.TYPE_REVIEW && card.ivl > 0) {
            double s = Math.max(1.0, (double) card.ivl);
            double d = 5.0;
            card.fsrsStability = s;
            card.fsrsDifficulty = d;
            return new MemoryState(s, d);
        }
        return null;
    }

    public double initDifficulty(int rating) {
        double d = w[4] - Math.exp(w[5] * (rating - 1)) + 1.0;
        return Math.min(10.0, Math.max(1.0, d));
    }

    public double initStability(int rating) {
        int idx = Math.min(3, Math.max(0, rating - 1));
        return Math.max(0.1, w[idx]);
    }

    public double nextDifficulty(double d, int rating) {
        double deltaD = -w[6] * (rating - 3);
        double dPrime = w[7] * initDifficulty(3) + (1.0 - w[7]) * (d + deltaD);
        return Math.min(10.0, Math.max(1.0, dPrime));
    }

    public double retrievability(double stability, double elapsedDays) {
        if (stability <= 0) return 0.0;
        double t = Math.max(0.0, elapsedDays);
        double r = Math.pow(1.0 + t / (9.0 * stability), -1.0);
        return Math.min(1.0, Math.max(0.0, r));
    }

    public double nextRecallStability(double d, double s, double r, int rating) {
        double hardPenalty = (rating == Scheduler.EASE_HARD) ? w[15] : 1.0;
        double easyBonus   = (rating == Scheduler.EASE_EASY) ? w[16] : 1.0;
        double mult = hardPenalty * easyBonus;

        double sInc = Math.exp(w[8])
                * (11.0 - d)
                * Math.pow(s, -w[9])
                * (Math.exp(w[10] * (1.0 - r)) - 1.0)
                * mult;

        double newS = s * (1.0 + sInc);
        return Math.max(s, Math.max(0.1, newS));
    }

    public double nextForgetStability(double d, double s, double r) {
        double sPrime = w[11]
                * Math.pow(d, -w[12])
                * (Math.pow(s + 1.0, w[13]) - 1.0)
                * Math.exp(w[14] * (1.0 - r));

        return Math.min(s, Math.max(0.1, sPrime));
    }

    public int intervalFromStability(double stability) {
        // I = (S / 9) * (1/DR - 1). For DR = 0.90, this equals S.
        double factor = (1.0 / desiredRetention - 1.0) / (1.0 / 0.90 - 1.0);
        double ivl = stability * factor;
        return Math.max(1, (int) Math.round(ivl));
    }

    public MemoryState step(Card card, int rating, int todayDays) {
        MemoryState current = getMemoryState(card);

        if (current == null) {
            // First rating (New card)
            double initS = initStability(rating);
            double initD = initDifficulty(rating);
            return new MemoryState(initS, initD);
        }

        // Review rating
        int elapsedDays = (card.ivl > 0) ? Math.max(0, todayDays - (card.due - card.ivl)) : 0;
        double r = retrievability(current.stability, elapsedDays);

        double newD = nextDifficulty(current.difficulty, rating);
        double newS;

        if (rating == Scheduler.EASE_AGAIN) {
            newS = nextForgetStability(current.difficulty, current.stability, r);
        } else {
            newS = nextRecallStability(current.difficulty, current.stability, r, rating);
        }

        return new MemoryState(newS, newD);
    }

    public static String formatCardData(MemoryState ms) {
        if (ms == null) return "";
        return String.format(Locale.US, "{\"s\":%.2f,\"d\":%.2f}", ms.stability, ms.difficulty);
    }
}
