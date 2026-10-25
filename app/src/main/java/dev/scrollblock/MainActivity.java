package dev.scrollblock;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.text.DateFormat;
import java.util.Date;

/** Settings screen. Built in code to avoid layout inflation and any libraries. */
public final class MainActivity extends Activity {

    private static final long PAUSE_MS = 15 * 60 * 1000L;

    private SharedPreferences prefs;
    private TextView status;
    private Button enableBtn;
    private TextView stats;
    private Button pauseBtn;
    private LinearLayout targetsBox;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = Prefs.get(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        status = text(root, "", 18, true);
        enableBtn = button(root, "Open accessibility settings", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        });
        if (Build.VERSION.SDK_INT >= 33) {
            text(root, "Toggle greyed out? Open App info, tap ⋮ and choose "
                    + "\"Allow restricted settings\", then try again.", 13, false);
            button(root, "Open app info", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", getPackageName(), null)));
                }
            });
        }
        stats = text(root, "", 15, false);
        pauseBtn = button(root, "", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean paused = isPaused();
                prefs.edit().putLong(Prefs.PAUSED_UNTIL,
                        paused ? 0 : System.currentTimeMillis() + PAUSE_MS).apply();
                refresh();
            }
        });

        header(root, "Block");
        targetsBox = new LinearLayout(this);
        targetsBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(targetsBox);

        header(root, "When blocked");
        RadioGroup action = new RadioGroup(this);
        final RadioButton back = new RadioButton(this);
        back.setText("Go back");
        RadioButton home = new RadioButton(this);
        home.setText("Go to home screen");
        action.addView(back);
        action.addView(home);
        (prefs.getBoolean(Prefs.GO_HOME, false) ? home : back).setChecked(true);
        action.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup g, int id) {
                prefs.edit().putBoolean(Prefs.GO_HOME, !back.isChecked()).apply();
            }
        });
        root.addView(action);
        check(root, "Show a \"blocked\" message", Prefs.TOAST, true);

        header(root, "Advanced");
        text(root, "Apps change their screens over time. If something stops being "
                + "blocked, capture the screen and add its view id to the rules.", 13, false);
        button(root, "Edit detection rules", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                editRules();
            }
        });
        button(root, "Capture a screen (in 6 s)", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!serviceEnabled()) {
                    toast("Enable the accessibility service first");
                    return;
                }
                long now = System.currentTimeMillis();
                // Pause blocking until just after the capture, so the feed being
                // inspected isn't closed first. Keeps a longer pause if one is set.
                long until = Math.max(prefs.getLong(Prefs.PAUSED_UNTIL, 0), now + Prefs.CAPTURE_DELAY_MS + 2000);
                prefs.edit().putLong(Prefs.CAPTURE_REQ, now).putLong(Prefs.PAUSED_UNTIL, until).apply();
                toast("Open the screen to inspect. Capturing in 6 seconds…");
                moveTaskToBack(true);
            }
        });
        button(root, "View last capture", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showCapture();
            }
        });
        if (Build.VERSION.SDK_INT >= 23) {
            button(root, "Battery optimization settings", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                }
            });
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
        buildTargets();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        boolean on = serviceEnabled();
        status.setText(on ? "Blocking is ON" : "Blocking is OFF – enable the ScrollBlock service");
        status.setTextColor(on ? 0xFF2E7D32 : 0xFFC62828);
        enableBtn.setText(on ? "Accessibility settings" : "Enable in accessibility settings");
        stats.setText("Blocked today: " + Prefs.blockedToday(prefs));
        if (isPaused()) {
            String until = DateFormat.getTimeInstance(DateFormat.SHORT)
                    .format(new Date(prefs.getLong(Prefs.PAUSED_UNTIL, 0)));
            pauseBtn.setText("Paused until " + until + " – resume now");
        } else {
            pauseBtn.setText("Pause for 15 minutes");
        }
    }

    private void buildTargets() {
        targetsBox.removeAllViews();
        Rules rules = Rules.parse(Prefs.rulesText(prefs));
        for (String key : rules.targets.keySet()) {
            boolean hasApp = false;
            for (Rules.AppRule[] list : rules.apps.values()) {
                for (Rules.AppRule a : list) hasApp |= a.target.equals(key);
            }
            boolean hasWeb = false;
            for (String[] u : rules.urls) hasWeb |= u[0].equals(key);

            final String target = key;
            final String title = rules.targets.get(key);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView name = new TextView(this);
            name.setText(rules.targets.get(key));
            name.setTextSize(16);
            row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            if (hasApp) check(row, "App", Prefs.appKey(key), true);
            if (hasWeb) check(row, "Browser", Prefs.webKey(key), true);
            targetsBox.addView(row);

            TextView limit = new TextView(this);
            limit.setText("Limit: " + Prefs.limitSummary(Prefs.reels(prefs, key), Prefs.waitSeconds(prefs, key)) + "  ›");
            limit.setTextSize(14);
            limit.setTextColor(0xFF1565C0);
            limit.setPadding(0, dp(2), 0, dp(12));
            limit.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    editLimit(target, title);
                }
            });
            targetsBox.addView(limit);
        }
    }

    private void editLimit(final String target, String title) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        box.setPadding(pad, dp(8), pad, 0);

        final TextView explain = text(box, "", 14, false);
        final TextView reelsLabel = text(box, "", 15, true);
        final SeekBar reels = new SeekBar(this);
        reels.setMax(Prefs.MAX_REELS);
        box.addView(reels);
        final TextView waitLabel = text(box, "", 15, true);
        final SeekBar wait = new SeekBar(this);
        wait.setMax(Prefs.WAIT_STEPS.length - 1);
        box.addView(wait);

        final Runnable update = new Runnable() {
            @Override
            public void run() {
                int r = reels.getProgress();
                int w = Prefs.WAIT_STEPS[wait.getProgress()];
                reelsLabel.setText("Reels per visit: " + (r == 0 ? "none (block on open)" : r));
                waitLabel.setText("Wait after a visit: " + (w == 0 ? "none" : Prefs.duration(w)));
                wait.setEnabled(r > 0);
                waitLabel.setEnabled(r > 0);
                explain.setText(limitExplanation(r, r == 0 ? 0 : w));
            }
        };
        SeekBar.OnSeekBarChangeListener l = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int v, boolean user) {
                update.run();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        };
        reels.setOnSeekBarChangeListener(l);
        wait.setOnSeekBarChangeListener(l);
        reels.setProgress(Prefs.reels(prefs, target));
        wait.setProgress(waitStep(Prefs.waitSeconds(prefs, target)));
        update.run();

        text(box, "Presets", 13, true).setPadding(0, dp(12), 0, 0);
        LinearLayout presets = new LinearLayout(this);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        box.addView(presets);
        preset(presets, "Block", reels, wait, 0, 0);
        preset(presets, "1 reel / 1 min", reels, wait, 1, 60);
        preset(presets, "3 reels / 10 min", reels, wait, 3, 600);

        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(sv)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        int r = reels.getProgress();
                        Prefs.setLimit(prefs, target, r, r == 0 ? 0 : Prefs.WAIT_STEPS[wait.getProgress()]);
                        buildTargets();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void preset(ViewGroup parent, String label, final SeekBar reels, final SeekBar wait,
                        final int r, final int w) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(12);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                reels.setProgress(r);
                wait.setProgress(waitStep(w));
            }
        });
        parent.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
    }

    private void editRules() {
        final EditText edit = new EditText(this);
        edit.setText(Prefs.rulesText(prefs));
        edit.setTypeface(Typeface.MONOSPACE);
        edit.setTextSize(12);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        edit.setHorizontallyScrolling(true);
        edit.setGravity(Gravity.TOP);
        new AlertDialog.Builder(this)
                .setTitle("Detection rules")
                .setView(edit)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String text = edit.getText().toString();
                        Rules r = Rules.parse(text);
                        prefs.edit().putString(Prefs.RULES, text).apply();
                        buildTargets();
                        if (!r.errors.isEmpty()) {
                            new AlertDialog.Builder(MainActivity.this)
                                    .setTitle("Saved, with problems")
                                    .setMessage(join(r.errors))
                                    .setPositiveButton("OK", null)
                                    .show();
                        }
                    }
                })
                .setNeutralButton("Reset to defaults", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        prefs.edit().remove(Prefs.RULES).apply();
                        buildTargets();
                        toast("Rules reset");
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showCapture() {
        final String text;
        try {
            FileInputStream in = openFileInput(Prefs.CAPTURE_FILE);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            in.close();
            text = out.toString("UTF-8");
        } catch (Exception e) {
            toast("No capture yet");
            return;
        }
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(10);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(12), dp(8), dp(12), dp(8));
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle("Last capture")
                .setView(sv)
                .setPositiveButton("Close", null)
                .setNeutralButton("Copy", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(ClipData.newPlainText("capture", text));
                        toast("Copied");
                    }
                })
                .setNegativeButton("Share", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        Intent i = new Intent(Intent.ACTION_SEND);
                        i.setType("text/plain");
                        i.putExtra(Intent.EXTRA_TEXT, text);
                        startActivity(Intent.createChooser(i, "Share capture"));
                    }
                })
                .show();
    }

    // ---- helpers ---------------------------------------------------------

    private boolean isPaused() {
        return System.currentTimeMillis() < prefs.getLong(Prefs.PAUSED_UNTIL, 0);
    }

    private boolean serviceEnabled() {
        String list = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (list == null) return false;
        ComponentName cn = new ComponentName(this, BlockerService.class);
        for (String s : list.split(":")) {
            ComponentName c = ComponentName.unflattenFromString(s);
            if (cn.equals(c)) return true;
        }
        return false;
    }

    private static String limitExplanation(int reels, int wait) {
        if (reels == 0) return "Blocked as soon as it opens, in the app and in browsers.";
        String watch = reels == 1 ? "Watch 1 reel as long as you like. Swiping to another one blocks it."
                : "Watch up to " + reels + " reels per visit. Swiping past the last one blocks it.";
        if (wait == 0) return watch + " Leaving and coming back starts a fresh visit.";
        return watch + " When a visit ends (blocked, or you leave), new reels stay blocked for "
                + Prefs.duration(wait) + ". The wait applies in browsers too.";
    }

    private static int waitStep(int seconds) {
        int best = 0;
        for (int i = 0; i < Prefs.WAIT_STEPS.length; i++) {
            if (Math.abs(Prefs.WAIT_STEPS[i] - seconds) < Math.abs(Prefs.WAIT_STEPS[best] - seconds)) best = i;
        }
        return best;
    }

    private static String join(java.util.List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String s : lines) sb.append(s).append('\n');
        return sb.toString();
    }

    private void check(ViewGroup parent, String label, final String key, boolean def) {
        CheckBox cb = new CheckBox(this);
        cb.setText(label);
        cb.setChecked(prefs.getBoolean(key, def));
        cb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                prefs.edit().putBoolean(key, on).apply();
            }
        });
        parent.addView(cb);
    }

    private TextView text(ViewGroup parent, String s, int sp, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(sp);
        if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(0, dp(4), 0, dp(4));
        parent.addView(tv);
        return tv;
    }

    private void header(ViewGroup parent, String s) {
        TextView tv = text(parent, s, 14, true);
        tv.setTextColor(0xFF1565C0);
        tv.setPadding(0, dp(20), 0, dp(4));
    }

    private Button button(ViewGroup parent, String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        parent.addView(b);
        return b;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
