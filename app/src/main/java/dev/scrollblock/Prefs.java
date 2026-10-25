package dev.scrollblock;

import android.content.Context;
import android.content.SharedPreferences;

/** Preference keys and small helpers shared by the service and the UI. */
final class Prefs {
    static final String RULES = "rules";
    static final String FREE_SWIPES = "free_swipes";
    static final String GO_HOME = "go_home";
    static final String TOAST = "toast";
    static final String PAUSED_UNTIL = "paused_until";
    static final String STATS_DAY = "stats_day";
    static final String STATS_COUNT = "stats_count";
    static final String CAPTURE_REQ = "capture_req";

    static final String CAPTURE_FILE = "capture.txt";
    static final int CAPTURE_DELAY_MS = 6000;

    static SharedPreferences get(Context c) {
        return c.getSharedPreferences("cfg", Context.MODE_PRIVATE);
    }

    static String rulesText(SharedPreferences p) {
        String s = p.getString(RULES, null);
        return s != null ? s : Rules.DEFAULTS;
    }

    static String appKey(String target) {
        return "app_" + target;
    }

    static String webKey(String target) {
        return "web_" + target;
    }

    /** Days since epoch in local time; used to reset the daily counter. */
    static int today() {
        long now = System.currentTimeMillis();
        return (int) ((now + java.util.TimeZone.getDefault().getOffset(now)) / 86400000L);
    }

    static int blockedToday(SharedPreferences p) {
        return p.getInt(STATS_DAY, -1) == today() ? p.getInt(STATS_COUNT, 0) : 0;
    }
}
