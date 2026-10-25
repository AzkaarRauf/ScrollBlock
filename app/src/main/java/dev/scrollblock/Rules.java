package dev.scrollblock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;

/**
 * Parsed form of the user-editable rules text. Parsing happens only when the
 * rules change, so the hot path in BlockerService does plain map lookups.
 */
final class Rules {

    static final String DEFAULTS =
            "# Lines starting with # are comments.\n"
            + "#\n"
            + "# target <key> <display name>\n"
            + "# app <key> <package> <matcher> [& <matcher> ...]\n"
            + "#     all matchers on one line must match; several app lines = any of them.\n"
            + "#     id:<view id>     a visible view with this resource id\n"
            + "#     text:<text>      a visible view whose text/description equals this\n"
            + "# url <key> <text>     URL (no scheme, www. or m.) contains this\n"
            + "# browser <package> <url bar view id>\n"
            + "\n"
            + "target yt YouTube Shorts\n"
            + "target ig Instagram Reels\n"
            + "target fb Facebook Reels\n"
            + "\n"
            + "app yt com.google.android.youtube id:reel_recycler\n"
            + "app yt com.google.android.youtube id:reel_player_page_container\n"
            + "app yt com.google.android.youtube id:reel_watch_player\n"
            + "app ig com.instagram.android id:clips_viewer_view_pager\n"
            + "app ig com.instagram.android id:clips_viewer_pager\n"
            + "app ig com.instagram.android id:root_clips_layout\n"
            + "app fb com.facebook.katana id:reels_viewer_container\n"
            + "app fb com.facebook.katana text:Reels & text:Create\n"
            + "app fb com.facebook.lite text:Reels & text:Create\n"
            + "\n"
            + "url yt youtube.com/shorts\n"
            + "url ig instagram.com/reel\n"
            + "url fb facebook.com/reel\n"
            + "url fb facebook.com/watch/reels\n"
            + "\n"
            + "browser com.android.chrome url_bar\n"
            + "browser com.chrome.beta url_bar\n"
            + "browser com.chrome.dev url_bar\n"
            + "browser com.chrome.canary url_bar\n"
            + "browser com.brave.browser url_bar\n"
            + "browser com.brave.browser_beta url_bar\n"
            + "browser com.brave.browser_nightly url_bar\n"
            + "browser com.microsoft.emmx url_bar\n"
            + "browser com.kiwibrowser.browser url_bar\n"
            + "browser com.vivaldi.browser url_bar\n"
            + "browser org.mozilla.firefox mozac_browser_toolbar_url_view\n"
            + "browser org.mozilla.firefox_beta mozac_browser_toolbar_url_view\n"
            + "browser org.mozilla.fenix mozac_browser_toolbar_url_view\n"
            + "browser com.sec.android.app.sbrowser location_bar_edit_text\n"
            + "browser com.opera.browser url_field\n"
            + "browser com.duckduckgo.mobile.android omnibarTextInput\n";

    static final class AppRule {
        final String target;
        final String[] ids;    // fully qualified "pkg:id/name"
        final String[] texts;

        AppRule(String target, String[] ids, String[] texts) {
            this.target = target;
            this.ids = ids;
            this.texts = texts;
        }
    }

    /** key -> display name, in file order (drives the settings UI). */
    final LinkedHashMap<String, String> targets = new LinkedHashMap<>();
    final HashMap<String, AppRule[]> apps = new HashMap<>();
    /** pairs of {target, substring} */
    final ArrayList<String[]> urls = new ArrayList<>();
    /** browser package -> fully qualified url bar id */
    final HashMap<String, String> browsers = new HashMap<>();
    /** human readable problems, shown when saving */
    final ArrayList<String> errors = new ArrayList<>();

    static Rules parse(String text) {
        Rules r = new Rules();
        HashMap<String, ArrayList<AppRule>> apps = new HashMap<>();
        String[] lines = text.split("\n");
        for (int n = 0; n < lines.length; n++) {
            String line = lines[n].trim();
            if (line.isEmpty() || line.charAt(0) == '#') continue;
            String kind = line.split("\\s+", 2)[0];
            String[] p = line.split("\\s+", kind.equals("app") ? 4 : 3);
            if (kind.equals("target") && p.length == 3) {
                r.targets.put(p[1], p[2]);
            } else if (kind.equals("url") && p.length == 3) {
                r.urls.add(new String[]{p[1], p[2].toLowerCase()});
            } else if (kind.equals("browser") && p.length == 3) {
                r.browsers.put(p[1], qualify(p[1], p[2]));
            } else if (kind.equals("app") && p.length == 4) {
                AppRule a = parseApp(p[1], p[2], p[3]);
                if (a == null) {
                    r.errors.add("line " + (n + 1) + ": bad matcher");
                    continue;
                }
                ArrayList<AppRule> list = apps.get(p[2]);
                if (list == null) apps.put(p[2], list = new ArrayList<>());
                list.add(a);
            } else {
                r.errors.add("line " + (n + 1) + ": not understood");
            }
        }
        for (String pkg : apps.keySet()) {
            ArrayList<AppRule> list = apps.get(pkg);
            r.apps.put(pkg, list.toArray(new AppRule[0]));
        }
        for (AppRule[] list : r.apps.values()) {
            for (AppRule a : list) checkTarget(r, a.target);
        }
        for (String[] u : r.urls) checkTarget(r, u[0]);
        return r;
    }

    private static void checkTarget(Rules r, String key) {
        if (!r.targets.containsKey(key)) {
            r.targets.put(key, key);
            r.errors.add("target '" + key + "' used but not declared");
        }
    }

    private static AppRule parseApp(String target, String pkg, String rest) {
        ArrayList<String> ids = new ArrayList<>();
        ArrayList<String> texts = new ArrayList<>();
        for (String m : rest.split("&")) {
            m = m.trim();
            if (m.startsWith("id:") && m.length() > 3) {
                ids.add(qualify(pkg, m.substring(3).trim()));
            } else if (m.startsWith("text:") && m.length() > 5) {
                texts.add(m.substring(5).trim());
            } else {
                return null;
            }
        }
        return new AppRule(target, ids.toArray(new String[0]), texts.toArray(new String[0]));
    }

    private static String qualify(String pkg, String id) {
        return id.indexOf(':') >= 0 ? id : pkg + ":id/" + id;
    }
}
