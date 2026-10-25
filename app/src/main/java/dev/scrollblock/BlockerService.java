package dev.scrollblock;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/**
 * Watches only the enabled apps/browsers (the system filters everything else
 * out before it reaches us) and backs out of short-video feeds.
 *
 * Cost model: content-change events are coalesced by the system
 * (notificationTimeout) and then throttled here to one tree query per
 * CHECK_INTERVAL_MS per app, with a trailing check so the final state of a
 * burst is never missed. The queries themselves (findByViewId) execute inside
 * the target app's process; we only receive the few matching nodes.
 */
public final class BlockerService extends AccessibilityService
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    private static final long CHECK_INTERVAL_MS = 400;
    private static final long ACTION_COOLDOWN_MS = 900;
    private static final long SWIPE_GAP_MS = 700;
    /** After a block, landing on the same feed again within this window blocks at once. */
    private static final long REBLOCK_WINDOW_MS = 4000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    // Effective configuration (only enabled targets).
    private String rulesSource;
    private Rules rules;
    private final HashMap<String, Rules.AppRule[]> appRules = new HashMap<>();
    private final HashMap<String, String> browserBars = new HashMap<>();
    private final ArrayList<String[]> urlRules = new ArrayList<>();
    private int freeSwipes;
    private boolean goHome;
    private boolean showToast;
    private long pausedUntil;

    // Per-foreground-app state.
    private String curPkg;
    private String zone;          // target key of the feed we are in, or null
    private int swipes;
    private long lastScroll;
    private long lastCheck;
    private long lastAction;
    private String lastBlocked;
    private String lastUrl;
    private boolean checkPending;

    private final Runnable deferredCheck = new Runnable() {
        @Override
        public void run() {
            checkPending = false;
            if (curPkg != null) check(curPkg);
        }
    };

    private final Runnable capture = new Runnable() {
        @Override
        public void run() {
            captureScreen();
        }
    };

    @Override
    protected void onServiceConnected() {
        prefs = Prefs.get(this);
        prefs.registerOnSharedPreferenceChangeListener(this);
        reload();
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        if (prefs != null) prefs.unregisterOnSharedPreferenceChangeListener(this);
        handler.removeCallbacksAndMessages(null);
        return super.onUnbind(intent);
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences p, String key) {
        if (Prefs.STATS_DAY.equals(key) || Prefs.STATS_COUNT.equals(key)) return;
        if (Prefs.CAPTURE_REQ.equals(key)) {
            handler.removeCallbacks(capture);
            handler.postDelayed(capture, Prefs.CAPTURE_DELAY_MS);
            return;
        }
        reload();
    }

    private void reload() {
        String src = Prefs.rulesText(prefs);
        if (!src.equals(rulesSource)) {
            rulesSource = src;
            rules = Rules.parse(src);
        }
        appRules.clear();
        browserBars.clear();
        urlRules.clear();

        for (String pkg : rules.apps.keySet()) {
            ArrayList<Rules.AppRule> on = new ArrayList<>();
            for (Rules.AppRule a : rules.apps.get(pkg)) {
                if (prefs.getBoolean(Prefs.appKey(a.target), true)) on.add(a);
            }
            if (!on.isEmpty()) appRules.put(pkg, on.toArray(new Rules.AppRule[0]));
        }
        for (String[] u : rules.urls) {
            if (prefs.getBoolean(Prefs.webKey(u[0]), true)) urlRules.add(u);
        }
        if (!urlRules.isEmpty()) {
            for (String pkg : rules.browsers.keySet()) {
                // A package with native rules is treated as an app, not a browser.
                if (!appRules.containsKey(pkg)) browserBars.put(pkg, rules.browsers.get(pkg));
            }
        }

        freeSwipes = prefs.getInt(Prefs.FREE_SWIPES, 0);
        goHome = prefs.getBoolean(Prefs.GO_HOME, false);
        showToast = prefs.getBoolean(Prefs.TOAST, true);
        pausedUntil = prefs.getLong(Prefs.PAUSED_UNTIL, 0);

        HashSet<String> pkgs = new HashSet<>(appRules.keySet());
        pkgs.addAll(browserBars.keySet());
        // An empty/null list would mean "every app", so fall back to ourselves.
        if (pkgs.isEmpty()) pkgs.add(getPackageName());
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.packageNames = pkgs.toArray(new String[0]);
            setServiceInfo(info);
        }
        resetZone();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        CharSequence p = e.getPackageName();
        if (p == null) return;
        if (pausedUntil != 0 && System.currentTimeMillis() < pausedUntil) return;
        String pkg = p.toString();
        if (!pkg.equals(curPkg)) {
            curPkg = pkg;
            resetZone();
        }

        long now = SystemClock.uptimeMillis();
        int type = e.getEventType();
        boolean urgent = type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;

        if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED && zone != null && appRules.containsKey(pkg)) {
            // A single swipe produces a burst of scroll events; count bursts.
            if (now - lastScroll > SWIPE_GAP_MS) {
                swipes++;
                urgent = true;
            }
            lastScroll = now;
        }

        if (urgent || now - lastCheck >= CHECK_INTERVAL_MS) {
            if (checkPending) {
                handler.removeCallbacks(deferredCheck);
                checkPending = false;
            }
            check(pkg);
        } else if (!checkPending) {
            checkPending = true;
            handler.postAtTime(deferredCheck, lastCheck + CHECK_INTERVAL_MS);
        }
    }

    private void resetZone() {
        zone = null;
        swipes = 0;
        lastUrl = null;
    }

    private void check(String pkg) {
        long now = SystemClock.uptimeMillis();
        lastCheck = now;
        if (now - lastAction < ACTION_COOLDOWN_MS) {
            // Re-check once the previous back/home has had time to land.
            if (!checkPending) {
                checkPending = true;
                handler.postAtTime(deferredCheck, lastAction + ACTION_COOLDOWN_MS);
            }
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        String target = null;
        CharSequence rootPkg = root.getPackageName();
        if (rootPkg != null && pkg.contentEquals(rootPkg)) {
            Rules.AppRule[] ar = appRules.get(pkg);
            if (ar != null) {
                target = matchApp(root, ar);
            } else {
                String bar = browserBars.get(pkg);
                if (bar != null) target = matchBrowser(root, bar);
            }
        }
        recycle(root);

        if (target == null) {
            if (zone != null) resetZone();
            return;
        }
        if (!target.equals(zone)) {
            zone = target;
            swipes = 0;
        }
        if (freeSwipes == 0 || swipes > freeSwipes
                || (target.equals(lastBlocked) && now - lastAction < REBLOCK_WINDOW_MS)) {
            block(target);
        }
    }

    private String matchApp(AccessibilityNodeInfo root, Rules.AppRule[] list) {
        for (Rules.AppRule a : list) {
            boolean ok = true;
            for (int i = 0; ok && i < a.ids.length; i++) ok = hasVisibleId(root, a.ids[i]);
            for (int i = 0; ok && i < a.texts.length; i++) ok = hasVisibleText(root, a.texts[i]);
            if (ok) return a.target;
        }
        return null;
    }

    /**
     * Returns the matching target, or null. If the URL can't be read (bar
     * missing, or focused because the user is typing) the current zone is
     * kept so the swipe count survives.
     */
    private String matchBrowser(AccessibilityNodeInfo root, String barId) {
        List<AccessibilityNodeInfo> bars = root.findAccessibilityNodeInfosByViewId(barId);
        String url = null;
        for (AccessibilityNodeInfo n : bars) {
            if (url == null && !n.isFocused()) {
                CharSequence t = n.getText();
                if (t != null && t.length() > 0) url = normalize(t.toString());
            }
            recycle(n);
        }
        if (url == null) return zone;
        String target = null;
        for (String[] u : urlRules) {
            if (url.contains(u[1])) {
                target = u[0];
                break;
            }
        }
        if (target != null && target.equals(zone) && lastUrl != null && !url.equals(lastUrl)) {
            swipes++; // each new short in the web player has its own URL
        }
        lastUrl = url;
        return target;
    }

    private static String normalize(String s) {
        s = s.trim().toLowerCase();
        if (s.startsWith("https://")) s = s.substring(8);
        else if (s.startsWith("http://")) s = s.substring(7);
        if (s.startsWith("www.")) s = s.substring(4);
        else if (s.startsWith("m.")) s = s.substring(2);
        return s;
    }

    private static boolean hasVisibleId(AccessibilityNodeInfo root, String id) {
        List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByViewId(id);
        boolean hit = false;
        for (AccessibilityNodeInfo n : found) {
            if (!hit && n.isVisibleToUser()) hit = true;
            recycle(n);
        }
        return hit;
    }

    private static boolean hasVisibleText(AccessibilityNodeInfo root, String text) {
        List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByText(text);
        boolean hit = false;
        for (AccessibilityNodeInfo n : found) {
            if (!hit && n.isVisibleToUser() && (eq(n.getText(), text) || eq(n.getContentDescription(), text))) {
                hit = true;
            }
            recycle(n);
        }
        return hit;
    }

    private static boolean eq(CharSequence a, String b) {
        return a != null && a.length() == b.length() && a.toString().equalsIgnoreCase(b);
    }

    private void block(String target) {
        lastAction = SystemClock.uptimeMillis();
        lastBlocked = target;
        resetZone();
        performGlobalAction(goHome ? GLOBAL_ACTION_HOME : GLOBAL_ACTION_BACK);
        if (showToast) {
            String name = rules.targets.get(target);
            Toast.makeText(this, (name != null ? name : target) + " blocked", Toast.LENGTH_SHORT).show();
        }
        int day = Prefs.today();
        int count = prefs.getInt(Prefs.STATS_DAY, -1) == day ? prefs.getInt(Prefs.STATS_COUNT, 0) : 0;
        prefs.edit().putInt(Prefs.STATS_DAY, day).putInt(Prefs.STATS_COUNT, count + 1).apply();
    }

    // ---- Screen capture for writing rules -------------------------------

    private void captureScreen() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            Toast.makeText(this, "Capture failed: no active window", Toast.LENGTH_SHORT).show();
            return;
        }
        StringBuilder sb = new StringBuilder(16 * 1024);
        sb.append("package: ").append(root.getPackageName()).append('\n');
        sb.append("legend: Class #view-id \"text\" [description] (v = visible)\n\n");
        int[] budget = {3000};
        dump(root, 0, sb, budget);
        recycle(root);
        try {
            FileOutputStream out = openFileOutput(Prefs.CAPTURE_FILE, MODE_PRIVATE);
            out.write(sb.toString().getBytes("UTF-8"));
            out.close();
            Toast.makeText(this, "ScrollBlock: screen captured", Toast.LENGTH_SHORT).show();
        } catch (Exception ex) {
            Toast.makeText(this, "Capture failed: " + ex.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private static void dump(AccessibilityNodeInfo n, int depth, StringBuilder sb, int[] budget) {
        if (budget[0]-- <= 0 || depth > 60) return;
        for (int i = 0; i < depth; i++) sb.append("  ");
        CharSequence cls = n.getClassName();
        if (cls != null) {
            String c = cls.toString();
            sb.append(c.substring(c.lastIndexOf('.') + 1));
        }
        String id = n.getViewIdResourceName();
        if (id != null) sb.append(" #").append(id.substring(id.indexOf('/') + 1));
        CharSequence t = n.getText();
        if (t != null && t.length() > 0) sb.append(" \"").append(clip(t)).append('"');
        CharSequence d = n.getContentDescription();
        if (d != null && d.length() > 0) sb.append(" [").append(clip(d)).append(']');
        if (n.isVisibleToUser()) sb.append(" (v)");
        sb.append('\n');
        int count = n.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            if (c == null) continue;
            dump(c, depth + 1, sb, budget);
            recycle(c);
        }
    }

    private static CharSequence clip(CharSequence s) {
        return s.length() > 80 ? s.subSequence(0, 80) + "…" : s;
    }

    @SuppressWarnings("deprecation")
    private static void recycle(AccessibilityNodeInfo n) {
        // Pooled before API 33; a no-op afterwards.
        if (Build.VERSION.SDK_INT < 33) n.recycle();
    }
}
