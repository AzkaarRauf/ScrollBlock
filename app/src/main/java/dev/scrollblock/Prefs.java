package dev.scrollblock;

import android.content.Context;
import android.content.SharedPreferences;

/** Preference keys and small helpers shared by the service and the UI. */
final class Prefs {
    static final String RULES = "rules";
    /** Pre-1.1 global setting; only read to seed the per-feed limits. */
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

    /** Wait choices offered in the UI, in seconds. */
    static final int[] WAIT_STEPS = {0, 30, 60, 120, 300, 600, 900, 1800, 3600, 7200, 14400};
    static final int MAX_REELS = 20;

    /** Reels allowed per visit; 0 = blocked as soon as one opens. */
    static int reels(SharedPreferences p, String target) {
        int old = p.getInt(FREE_SWIPES, 0);
        return p.getInt("reels_" + target, old == 0 ? 0 : old + 1);
    }

    /** Seconds a feed stays blocked after a visit ends. */
    static int waitSeconds(SharedPreferences p, String target) {
        return p.getInt("wait_" + target, 0);
    }

    static void setLimit(SharedPreferences p, String target, int reels, int waitSeconds) {
        p.edit().putInt("reels_" + target, reels).putInt("wait_" + target, waitSeconds).apply();
    }

    static String duration(long seconds) {
        if (seconds < 60) return seconds + " s";
        if (seconds < 3600) return (seconds + 59) / 60 + " min";
        long m = (seconds + 59) / 60;
        return m / 60 + " h" + (m % 60 == 0 ? "" : " " + m % 60 + " min");
    }

    /** One-line description of a limit, used on the main screen. */
    static String limitSummary(int reels, int waitSeconds) {
        if (reels == 0) return "Blocked on open";
        String r = reels == 1 ? "1 reel" : reels + " reels";
        return waitSeconds == 0 ? r + " per visit" : r + ", then wait " + duration(waitSeconds);
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
