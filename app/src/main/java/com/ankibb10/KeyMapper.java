package com.ankibb10;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.KeyEvent;

public class KeyMapper {
    private static final String PREFS_NAME = "ankibb10_keys";

    public static final String KEY_SHOW_ANSWER = "key_show_answer";
    public static final String KEY_AGAIN       = "key_again";
    public static final String KEY_HARD        = "key_hard";
    public static final String KEY_GOOD        = "key_good";
    public static final String KEY_EASY        = "key_easy";

    public static final String DEFAULT_SHOW_ANSWER = "SPACE";
    public static final String DEFAULT_AGAIN       = "1";
    public static final String DEFAULT_HARD        = "2";
    public static final String DEFAULT_GOOD        = "3";
    public static final String DEFAULT_EASY        = "4";

    public static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static String getMapping(Context context, String key, String defaultVal) {
        return getPrefs(context).getString(key, defaultVal);
    }

    public static void setMapping(Context context, String key, String val) {
        getPrefs(context).edit().putString(key, val).commit();
    }

    public static void resetDefaults(Context context) {
        getPrefs(context).edit()
                .putString(KEY_SHOW_ANSWER, DEFAULT_SHOW_ANSWER)
                .putString(KEY_AGAIN,       DEFAULT_AGAIN)
                .putString(KEY_HARD,        DEFAULT_HARD)
                .putString(KEY_GOOD,        DEFAULT_GOOD)
                .putString(KEY_EASY,        DEFAULT_EASY)
                .commit();
    }

    /**
     * Canonical name for any physical keyboard key event to store in settings.
     */
    public static String getKeyName(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_SPACE) return "SPACE";
        if (keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) return "ENTER";

        char c = (char) event.getUnicodeChar();
        if (c >= ' ' && c <= '~') {
            return String.valueOf(Character.toUpperCase(c));
        }

        if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) {
            return String.valueOf((char) ('A' + (keyCode - KeyEvent.KEYCODE_A)));
        }
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            return String.valueOf((char) ('0' + (keyCode - KeyEvent.KEYCODE_0)));
        }

        return "KEY_" + keyCode;
    }

    /**
     * Matches a keyboard event against a target mapping string.
     */
    public static boolean matches(KeyEvent event, String targetKey) {
        if (targetKey == null || targetKey.isEmpty()) return false;
        int keyCode = event.getKeyCode();
        char c = Character.toUpperCase((char) event.getUnicodeChar());

        if ("SPACE".equalsIgnoreCase(targetKey)) {
            return keyCode == KeyEvent.KEYCODE_SPACE || c == ' ';
        }
        if ("ENTER".equalsIgnoreCase(targetKey)) {
            return keyCode == KeyEvent.KEYCODE_ENTER
                    || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                    || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER;
        }

        if (targetKey.startsWith("KEY_")) {
            try {
                int targetCode = Integer.parseInt(targetKey.substring(4));
                if (keyCode == targetCode) return true;
            } catch (Exception ignored) {}
        }

        if (targetKey.length() == 1) {
            char targetChar = Character.toUpperCase(targetKey.charAt(0));
            if (c == targetChar) return true;
            if (targetChar >= '0' && targetChar <= '9') {
                int digit = targetChar - '0';
                if (keyCode == (KeyEvent.KEYCODE_0 + digit)) return true;
            }
            if (targetChar >= 'A' && targetChar <= 'Z') {
                int alpha = targetChar - 'A';
                if (keyCode == (KeyEvent.KEYCODE_A + alpha)) return true;
            }
        }

        return false;
    }
}