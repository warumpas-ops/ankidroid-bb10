package com.ankibb10;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

import com.ankibb10.sync.SyncManager;

public class ThemeManager {
    public static final String PREF_THEME = "app_theme";
    public static final String THEME_BLACK = "black";
    public static final String THEME_DARK = "dark";
    public static final String THEME_LIGHT = "light";

    public static String getTheme(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(SyncManager.PREFS, Context.MODE_PRIVATE);
        return prefs.getString(PREF_THEME, THEME_BLACK); // Default to Black (OLED) for BlackBerry
    }

    public static void setTheme(Context context, String theme) {
        SharedPreferences prefs = context.getSharedPreferences(SyncManager.PREFS, Context.MODE_PRIVATE);
        prefs.edit().putString(PREF_THEME, theme).commit();
    }

    public static String getThemeDisplayName(Context context) {
        String theme = getTheme(context);
        if (THEME_DARK.equals(theme)) return "Plain Dark";
        if (THEME_LIGHT.equals(theme)) return "Light";
        return "Black (OLED)";
    }

    public static int getBackgroundColor(Context context) {
        String theme = getTheme(context);
        if (THEME_BLACK.equals(theme)) {
            return Color.BLACK; // #000000
        } else if (THEME_DARK.equals(theme)) {
            return Color.parseColor("#202124");
        } else {
            return Color.parseColor("#ECEFF1");
        }
    }

    public static int getToolbarColor(Context context) {
        String theme = getTheme(context);
        if (THEME_BLACK.equals(theme)) {
            return Color.parseColor("#121212");
        } else if (THEME_DARK.equals(theme)) {
            return Color.parseColor("#2D3238"); // Anki dark header
        } else {
            return Color.parseColor("#424647"); // Anki logo grey
        }
    }

    public static int getCardBackgroundColor(Context context) {
        String theme = getTheme(context);
        if (THEME_BLACK.equals(theme)) {
            return Color.BLACK;
        } else if (THEME_DARK.equals(theme)) {
            return Color.parseColor("#282828");
        } else {
            return Color.WHITE;
        }
    }

    public static int getPrimaryTextColor(Context context) {
        String theme = getTheme(context);
        if (THEME_LIGHT.equals(theme)) {
            return Color.parseColor("#212121");
        } else {
            return Color.parseColor("#F1F1F1");
        }
    }

    public static int getSecondaryTextColor(Context context) {
        String theme = getTheme(context);
        if (THEME_LIGHT.equals(theme)) {
            return Color.parseColor("#757575");
        } else {
            return Color.parseColor("#9E9E9E");
        }
    }

    public static int getDividerColor(Context context) {
        String theme = getTheme(context);
        if (THEME_BLACK.equals(theme)) {
            return Color.parseColor("#1E1E1E");
        } else if (THEME_DARK.equals(theme)) {
            return Color.parseColor("#383838");
        } else {
            return Color.parseColor("#CFD8DC");
        }
    }

    public static boolean isDark(Context context) {
        String theme = getTheme(context);
        return THEME_BLACK.equals(theme) || THEME_DARK.equals(theme);
    }

    public static String getCardCss(Context context) {
        String theme = getTheme(context);
        if (THEME_BLACK.equals(theme)) {
            return "html, body { background-color: #000000 !important; color: #FFFFFF !important; margin: 8px; font-family: sans-serif; }\n"
                 + ".card, .nightMode, .night_mode { background-color: #000000 !important; color: #FFFFFF !important; }\n"
                 + "p, div, span, b, i, em, strong, li { color: inherit; }\n"
                 + "hr { border: 0; height: 1px; background: #2A2A2A !important; margin: 12px 0; }\n"
                 + "a { color: #29B6F6 !important; }\n"
                 + "table, th, td { border-color: #333333 !important; }\n";
        } else if (THEME_DARK.equals(theme)) {
            return "html, body { background-color: #202124 !important; color: #E8EAED !important; margin: 8px; font-family: sans-serif; }\n"
                 + ".card, .nightMode, .night_mode { background-color: #202124 !important; color: #E8EAED !important; }\n"
                 + "p, div, span, b, i, em, strong, li { color: inherit; }\n"
                 + "hr { border: 0; height: 1px; background: #3C4043 !important; margin: 12px 0; }\n"
                 + "a { color: #8AB4F8 !important; }\n"
                 + "table, th, td { border-color: #444444 !important; }\n";
        } else {
            return "html, body { background-color: #FFFFFF !important; color: #212121 !important; margin: 8px; font-family: sans-serif; }\n"
                 + ".card { background-color: #FFFFFF !important; color: #212121 !important; }\n"
                 + "hr { border: 0; height: 1px; background: #E0E0E0 !important; margin: 12px 0; }\n"
                 + "a { color: #0084FF !important; }\n";
        }
    }
}
