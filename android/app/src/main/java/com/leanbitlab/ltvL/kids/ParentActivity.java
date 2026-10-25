package com.leanbitlab.ltvL.kids;

import android.app.Activity;
import android.app.AppOpsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.leanbitlab.ltvL.LauncherAccessibilityService;
import com.leanbitlab.ltvL.LauncherNotificationListenerService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Parent controls: screen time rules, viewing history, blocked apps, remote buttons, setup. */
public class ParentActivity extends Activity {

    private static final int REQ_PIN = 1;
    private static final int REQ_CHANGE_PIN = 2;
    private static final String[] DAY_NAMES = {"", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"};

    private KidsStore store;
    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean built;
    private boolean leavingForPin;
    private LinearLayout nav;
    private LinearLayout content;
    private ScrollView scroll;
    private String section = "rules";
    private int activityDayOffset;
    private TextView testResult;
    private final Runnable stopTest = this::stopKeyTest;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = KidsStore.get(this);
        if (!store.hasPin()) {
            openPin(PinActivity.MODE_CREATE, REQ_PIN);
        } else if (!store.isParentActive()) {
            openPin(PinActivity.MODE_GATE, REQ_PIN);
        } else {
            build();
        }
    }

    private void openPin(String mode, int req) {
        leavingForPin = true;
        startActivityForResult(new Intent(this, PinActivity.class).putExtra(PinActivity.EXTRA_MODE, mode), req);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        leavingForPin = false;
        if (requestCode == REQ_PIN) {
            if (resultCode == RESULT_OK) build();
            else finish();
        } else if (requestCode == REQ_CHANGE_PIN && built) {
            show("setup");
        }
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        if (built) store.startParentSession(); // stay unlocked while a parent is using this screen
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopKeyTest();
        if (built && !leavingForPin && !isChangingConfigurations()) {
            store.endParentSession();
            KidsGuard.refresh();
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        exit();
    }

    private void exit() {
        store.endParentSession();
        KidsGuard.refresh();
        finish();
    }

    // ------------------------------------------------------------------ frame

    private void build() {
        built = true;
        LinearLayout root = Ui.hbox(this, 0);
        root.setBackgroundColor(Ui.BG);
        root.setGravity(Gravity.TOP);

        nav = Ui.vbox(this, 8);
        int np = Ui.dp(this, 24);
        nav.setPadding(np, np + 8, np, np);
        nav.setBackgroundColor(0xFF12161D);
        root.addView(nav, new LinearLayout.LayoutParams(Ui.dp(this, 230), LinearLayout.LayoutParams.MATCH_PARENT));

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        content = Ui.vbox(this, 18);
        int cp = Ui.dp(this, 36);
        content.setPadding(cp, cp, cp, cp);
        scroll.addView(content, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        setContentView(root);
        show(section);
    }

    private void buildNav() {
        nav.removeAllViews();
        nav.addView(Ui.text(this, "Parent controls", 18, Ui.TEXT, true));
        nav.addView(Ui.gap(this, 12));
        addNav("rules", "Screen time");
        addNav("activity", "What they watched");
        addNav("apps", "Apps");
        addNav("remote", "Remote buttons");
        addNav("setup", "Setup & PIN");
        View spacer = new View(this);
        nav.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));
        Button exit = Ui.button(this, "Exit", false);
        exit.setOnClickListener(v -> exit());
        nav.addView(exit, Ui.matchWidth());
    }

    private void addNav(String id, String label) {
        Button b = Ui.button(this, label, id.equals(section));
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setOnClickListener(v -> show(id));
        b.setTag("nav_" + id);
        nav.addView(b, Ui.matchWidth());
    }

    private void show(String id) {
        if (!"remote".equals(id)) stopKeyTest();
        section = id;
        buildNav();
        content.removeAllViews();
        switch (id) {
            case "activity" -> buildActivity();
            case "apps" -> buildApps();
            case "remote" -> buildRemote();
            case "setup" -> buildSetup();
            default -> buildRules();
        }
        scroll.scrollTo(0, 0);
        View sel = nav.findViewWithTag("nav_" + id);
        if (sel != null) sel.requestFocus();
    }

    private void header(String title, String sub) {
        content.addView(Ui.text(this, title, 30, Ui.TEXT, true));
        if (sub != null) content.addView(Ui.text(this, sub, 16, Ui.MUTED, false));
    }

    private Button smallButton(String label, View.OnClickListener l) {
        Button b = Ui.button(this, label, false);
        b.setOnClickListener(l);
        return b;
    }

    /** A row that can take focus, so the remote can scroll through long lists. */
    private LinearLayout focusRow() {
        LinearLayout row = Ui.vbox(this, 4);
        int p = Ui.dp(this, 12);
        row.setPadding(p, p, p, p);
        row.setFocusable(true);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, Ui.rounded(this, Ui.SURFACE2, 10, Ui.ACCENT, 2));
        bg.addState(new int[]{}, Ui.rounded(this, 0x00000000, 10, 0, 0));
        row.setBackground(bg);
        return row;
    }

    private void toast(String msg) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ screen time rules

    private void buildRules() {
        header("Screen time rules", "Change these any time — new rules apply straight away.");

        long now = System.currentTimeMillis();
        KidsEngine.Status st = KidsEngine.compute(store, now);

        LinearLayout status = Ui.card(this, 10);
        status.addView(Ui.text(this, store.isEnabled() ? "Kids mode is ON" : "Kids mode is OFF", 20, store.isEnabled() ? Ui.ACCENT : Ui.MUTED, true));
        KidsStore.DayRule today = store.getRule(KidsStore.isoDay(Calendar.getInstance()));
        String line = "Watched today: " + KidsEngine.duration(st.usedSeconds);
        if (today.on && !store.isPausedToday()) {
            line += " of " + KidsEngine.duration(today.limit * 60L) + " · allowed "
                    + KidsEngine.clockMinutes(today.from) + " – " + KidsEngine.clockMinutes(today.to);
        }
        status.addView(Ui.text(this, line, 16, Ui.TEXT, false));
        status.addView(Ui.text(this, statusLine(st, now), 15, Ui.MUTED, false));

        LinearLayout actions = Ui.hbox(this, 10);
        actions.addView(smallButton(store.isEnabled() ? "Turn Kids mode off" : "Turn Kids mode on", v -> {
            store.setEnabled(!store.isEnabled());
            KidsGuard.refresh();
            show("rules");
        }));
        if (store.isEnabled()) {
            actions.addView(smallButton("Lock TV now", v -> {
                store.setGrantUntil(0);
                store.setPausedToday(false);
                store.setForceLockedToday(true);
                toast("The TV locks when you leave parent controls");
                show("rules");
            }));
            actions.addView(smallButton("+15 min", v -> { KidsEngine.grant(store, 15); show("rules"); }));
            actions.addView(smallButton("+30 min", v -> { KidsEngine.grant(store, 30); show("rules"); }));
            actions.addView(smallButton("+1 hour", v -> { KidsEngine.grant(store, 60); show("rules"); }));
            actions.addView(smallButton(store.isPausedToday() ? "Resume rules" : "No limits today", v -> {
                store.setPausedToday(!store.isPausedToday());
                if (store.isPausedToday()) store.setForceLockedToday(false);
                show("rules");
            }));
        }
        LinearLayout.LayoutParams al = Ui.wrap();
        al.topMargin = Ui.dp(this, 6);
        status.addView(actions, al);
        content.addView(status, Ui.matchWidth());

        // weekly schedule
        LinearLayout sched = Ui.card(this, 6);
        sched.addView(Ui.text(this, "Weekly schedule", 20, Ui.TEXT, true));
        LinearLayout head = Ui.hbox(this, 12);
        head.addView(Ui.text(this, "Day", 14, Ui.MUTED, false), Ui.fixed(this, 110));
        head.addView(Ui.text(this, "Rules", 14, Ui.MUTED, false), Ui.fixed(this, 70));
        head.addView(Ui.text(this, "TV allowed from", 14, Ui.MUTED, false), Ui.fixed(this, 190));
        head.addView(Ui.text(this, "TV off at", 14, Ui.MUTED, false), Ui.fixed(this, 190));
        head.addView(Ui.text(this, "Daily limit", 14, Ui.MUTED, false), Ui.fixed(this, 190));
        sched.addView(head);
        for (int d = 1; d <= 7; d++) sched.addView(dayRow(d));
        content.addView(sched, Ui.matchWidth());

        // what happens when time runs out
        LinearLayout opts = Ui.card(this, 12);
        opts.addView(Ui.text(this, "When time runs out", 20, Ui.TEXT, true));
        LinearLayout warnRow = Ui.hbox(this, 10);
        warnRow.addView(Ui.text(this, "Warn the kids before", 16, Ui.TEXT, false), Ui.fixed(this, 220));
        TextView warnVal = Ui.text(this, store.getWarnMinutes() + " min", 16, Ui.TEXT, true);
        warnVal.setGravity(Gravity.CENTER);
        warnRow.addView(smallButton("−", v -> { store.setWarnMinutes(store.getWarnMinutes() - 1); warnVal.setText(store.getWarnMinutes() + " min"); }));
        warnRow.addView(warnVal, Ui.fixed(this, 80));
        warnRow.addView(smallButton("+", v -> { store.setWarnMinutes(store.getWarnMinutes() + 1); warnVal.setText(store.getWarnMinutes() + " min"); }));
        opts.addView(warnRow);

        LinearLayout endRow = Ui.hbox(this, 10);
        endRow.addView(Ui.text(this, "Then", 16, Ui.TEXT, false), Ui.fixed(this, 220));
        Button endMode = Ui.button(this, endModeLabel(), false);
        endMode.setOnClickListener(v -> {
            store.setEndMode(KidsStore.END_LOCK.equals(store.getEndMode()) ? KidsStore.END_OFF : KidsStore.END_LOCK);
            endMode.setText(endModeLabel());
        });
        endRow.addView(endMode);
        opts.addView(endRow);
        opts.addView(Ui.text(this, "The TV stays locked after a restart. Only the parent PIN unlocks it.", 14, Ui.MUTED, false));
        content.addView(opts, Ui.matchWidth());
    }

    private String endModeLabel() {
        return KidsStore.END_LOCK.equals(store.getEndMode())
                ? "Show the lock screen"
                : "Show the lock screen, then turn the screen off";
    }

    private String statusLine(KidsEngine.Status st, long now) {
        if (!st.enabled) return "Turn Kids mode on to start the limits.";
        switch (st.reason) {
            case KidsEngine.R_NO_LIMIT: return "No limits right now.";
            case KidsEngine.R_PARENT:
                return "You're in parent mode. The rules apply again when you leave.";
            case KidsEngine.R_GRANT: return "Extra time given — TV locks at " + KidsEngine.clock(st.endsAt) + ".";
            case KidsEngine.R_OK: return st.minutesLeft(now) + " min left · TV locks by " + KidsEngine.clock(st.endsAt) + ".";
            case KidsEngine.R_LOCKED: return "Locked by you. Unlocks " + KidsEngine.whenText(st.nextAllowedAt, now) + ".";
            default:
                return "TV is locked now. Unlocks " + (st.nextAllowedAt > 0 ? KidsEngine.whenText(st.nextAllowedAt, now) : "later") + ".";
        }
    }

    private View dayRow(int day) {
        LinearLayout row = Ui.hbox(this, 12);
        row.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        row.addView(Ui.text(this, DAY_NAMES[day], 16, Ui.TEXT, true), Ui.fixed(this, 110));

        TextView from = Ui.text(this, "", 16, Ui.TEXT, false);
        TextView to = Ui.text(this, "", 16, Ui.TEXT, false);
        TextView lim = Ui.text(this, "", 16, Ui.TEXT, false);
        Button onOff = Ui.button(this, "", false);

        Runnable refresh = () -> {
            KidsStore.DayRule r = store.getRule(day);
            onOff.setText(r.on ? "On" : "Off");
            from.setText(KidsEngine.clockMinutes(r.from));
            to.setText(KidsEngine.clockMinutes(r.to));
            lim.setText(KidsEngine.duration(r.limit * 60L));
            float alpha = r.on ? 1f : 0.45f;
            from.setAlpha(alpha);
            to.setAlpha(alpha);
            lim.setAlpha(alpha);
        };
        onOff.setOnClickListener(v -> {
            KidsStore.DayRule r = store.getRule(day);
            r.on = !r.on;
            store.setRule(day, r);
            refresh.run();
        });
        row.addView(onOff, Ui.fixed(this, 70));
        row.addView(stepper(from, () -> change(day, 0, -30), () -> change(day, 0, 30), refresh), Ui.fixed(this, 190));
        row.addView(stepper(to, () -> change(day, 1, -30), () -> change(day, 1, 30), refresh), Ui.fixed(this, 190));
        row.addView(stepper(lim, () -> change(day, 2, -15), () -> change(day, 2, 15), refresh), Ui.fixed(this, 190));
        refresh.run();
        return row;
    }

    /** field: 0 = from, 1 = to, 2 = limit */
    private void change(int day, int field, int delta) {
        KidsStore.DayRule r = store.getRule(day);
        if (field == 0) r.from = Math.max(5 * 60, Math.min(r.to - 30, r.from + delta));
        if (field == 1) r.to = Math.max(r.from + 30, Math.min(24 * 60 - 1, r.to + delta));
        if (field == 2) r.limit = Math.max(15, Math.min(12 * 60, r.limit + delta));
        store.setRule(day, r);
        KidsGuard.refresh();
    }

    private View stepper(TextView value, Runnable down, Runnable up, Runnable refresh) {
        LinearLayout l = Ui.hbox(this, 6);
        Button minus = smallButton("−", v -> { down.run(); refresh.run(); });
        Button plus = smallButton("+", v -> { up.run(); refresh.run(); });
        value.setGravity(Gravity.CENTER);
        l.addView(minus, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        l.addView(value, Ui.fixed(this, 86));
        l.addView(plus, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        return l;
    }

    // ------------------------------------------------------------------ viewing history

    private void buildActivity() {
        header("What they watched", "Apps opened in Kids mode, with the show or video name when the app shares it.");

        Calendar day = Calendar.getInstance();
        day.add(Calendar.DAY_OF_YEAR, activityDayOffset);
        String key = KidsStore.dateKey(day.getTime());
        String dayName = activityDayOffset == 0 ? "Today"
                : activityDayOffset == -1 ? "Yesterday"
                : new SimpleDateFormat("EEEE, d MMM", Locale.getDefault()).format(day.getTime());

        LinearLayout dayNav = Ui.hbox(this, 12);
        dayNav.addView(smallButton("‹ Earlier", v -> { if (activityDayOffset > -29) activityDayOffset--; show("activity"); }));
        TextView dn = Ui.text(this, dayName, 20, Ui.TEXT, true);
        dn.setGravity(Gravity.CENTER);
        dayNav.addView(dn, Ui.fixed(this, 260));
        Button later = smallButton("Later ›", v -> { if (activityDayOffset < 0) activityDayOffset++; show("activity"); });
        later.setEnabled(activityDayOffset < 0);
        dayNav.addView(later);
        content.addView(dayNav);

        List<JSONObject> sessions = store.getSessions(key);
        Map<String, Long> byApp = new LinkedHashMap<>();
        for (JSONObject s : sessions) {
            long dur = (s.optLong("end") - s.optLong("start")) / 1000;
            String label = s.optString("label", s.optString("pkg"));
            byApp.put(label, (byApp.containsKey(label) ? byApp.get(label) : 0L) + dur);
        }

        LinearLayout summary = Ui.card(this, 8);
        summary.addView(Ui.text(this, "Screen time counted: " + KidsEngine.duration(store.getUsedSeconds(key)), 20, Ui.TEXT, true));
        if (byApp.isEmpty()) {
            summary.addView(Ui.text(this, "Nothing watched in Kids mode on this day.", 16, Ui.MUTED, false));
        } else {
            List<Map.Entry<String, Long>> entries = new ArrayList<>(byApp.entrySet());
            Collections.sort(entries, (a, b) -> Long.compare(b.getValue(), a.getValue()));
            for (Map.Entry<String, Long> e : entries) {
                summary.addView(Ui.text(this, e.getKey() + " — " + KidsEngine.duration(e.getValue()), 16, Ui.TEXT, false));
            }
        }
        content.addView(summary, Ui.matchWidth());

        if (!sessions.isEmpty()) {
            LinearLayout list = Ui.card(this, 2);
            list.addView(Ui.text(this, "Timeline", 20, Ui.TEXT, true));
            SimpleDateFormat tf = new SimpleDateFormat("h:mm a", Locale.getDefault());
            for (JSONObject s : sessions) {
                LinearLayout row = focusRow();
                long dur = (s.optLong("end") - s.optLong("start")) / 1000;
                String top = tf.format(new Date(s.optLong("start"))) + "   " + s.optString("label") + "   ·   " + KidsEngine.duration(dur);
                row.addView(Ui.text(this, top, 16, Ui.TEXT, true));
                JSONArray titles = s.optJSONArray("titles");
                if (titles != null && titles.length() > 0) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < titles.length(); i++) {
                        if (i > 0) sb.append("\n");
                        sb.append("• ").append(titles.optString(i));
                    }
                    row.addView(Ui.text(this, sb.toString(), 15, 0xFFD5DCE4, false));
                } else {
                    row.addView(Ui.text(this, "This app didn't share what was playing", 14, Ui.MUTED, false));
                }
                list.addView(row, Ui.matchWidth());
            }
            content.addView(list, Ui.matchWidth());
        }
    }

    // ------------------------------------------------------------------ apps

    private void buildApps() {
        header("Apps", "Blocked apps send the kids back home with a message. Parents can open anything after the PIN.");

        LinearLayout sys = Ui.card(this, 10);
        sys.addView(Ui.text(this, "Protection", 20, Ui.TEXT, true));
        sys.addView(flagRow("Lock TV Settings", "So Kids mode can't be switched off", KidsStore.FLAG_BLOCK_SETTINGS, true));
        sys.addView(flagRow("Lock Play Store and app installs", "New apps need a parent", KidsStore.FLAG_BLOCK_STORE, true));
        sys.addView(flagRow("Block other home screens", "Stops the Mi button opening PatchWall", KidsStore.FLAG_BLOCK_OTHER_HOMES, true));
        sys.addView(flagRow("Turn off voice search", "Stops searching around the rules", KidsStore.FLAG_BLOCK_VOICE, false));
        content.addView(sys, Ui.matchWidth());

        LinearLayout apps = Ui.card(this, 6);
        apps.addView(Ui.text(this, "Installed apps", 20, Ui.TEXT, true));
        PackageManager pm = getPackageManager();
        Map<String, String> found = new LinkedHashMap<>();
        for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            for (ResolveInfo ri : pm.queryIntentActivities(i, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || found.containsKey(pkg)) continue;
                found.put(pkg, ri.loadLabel(pm).toString());
            }
        }
        List<Map.Entry<String, String>> sorted = new ArrayList<>(found.entrySet());
        Collections.sort(sorted, (a, b) -> a.getValue().compareToIgnoreCase(b.getValue()));
        Set<String> blocked = store.getBlockedApps();
        for (Map.Entry<String, String> e : sorted) {
            String pkg = e.getKey();
            LinearLayout row = Ui.hbox(this, 12);
            row.addView(Ui.text(this, e.getValue(), 16, Ui.TEXT, false), Ui.weight(1));
            Button b = Ui.button(this, blocked.contains(pkg) ? "Blocked" : "Allowed", false);
            b.setOnClickListener(v -> {
                boolean nowBlocked = !store.getBlockedApps().contains(pkg);
                store.setAppBlocked(pkg, nowBlocked);
                b.setText(nowBlocked ? "Blocked" : "Allowed");
                KidsGuard.refresh();
            });
            row.addView(b, Ui.fixed(this, 130));
            apps.addView(row, Ui.matchWidth());
        }
        content.addView(apps, Ui.matchWidth());
    }

    private View flagRow(String title, String sub, String flag, boolean def) {
        LinearLayout row = Ui.hbox(this, 12);
        LinearLayout texts = Ui.vbox(this, 2);
        texts.addView(Ui.text(this, title, 16, Ui.TEXT, false));
        texts.addView(Ui.text(this, sub, 14, Ui.MUTED, false));
        row.addView(texts, Ui.weight(1));
        Button b = Ui.button(this, store.flag(flag, def) ? "On" : "Off", false);
        b.setOnClickListener(v -> {
            boolean next = !store.flag(flag, def);
            store.setFlag(flag, next);
            b.setText(next ? "On" : "Off");
            KidsGuard.refresh();
        });
        row.addView(b, Ui.fixed(this, 100));
        return row;
    }

    // ------------------------------------------------------------------ remote buttons

    private void buildRemote() {
        header("Remote buttons", "Choose what each button does in Kids mode. Home always opens this launcher.");

        LinearLayout test = Ui.card(this, 10);
        test.addView(Ui.text(this, "Test your remote", 20, Ui.TEXT, true));
        test.addView(Ui.text(this, "Start the test, then press each button once. Buttons that show up here can be controlled. "
                + "Buttons that never show up are handled by the TV itself — if they open something blocked, the kids are sent home.", 15, 0xFFC3CBD6, false));
        testResult = Ui.text(this, KidsGuard.isRunning() ? "Not testing" : "Turn on the accessibility service first (see Setup)", 18, Ui.ACCENT, true);
        test.addView(testResult);
        LinearLayout tb = Ui.hbox(this, 10);
        tb.addView(smallButton("Start test (30 s)", v -> startKeyTest()));
        tb.addView(smallButton("Stop test", v -> { stopKeyTest(); show("remote"); }));
        test.addView(tb);
        content.addView(test, Ui.matchWidth());

        LinearLayout rules = Ui.card(this, 8);
        rules.addView(Ui.text(this, "Button rules", 20, Ui.TEXT, true));
        Set<Integer> keys = new TreeSet<>();
        keys.add(KeyEvent.KEYCODE_MENU);
        keys.add(KeyEvent.KEYCODE_SETTINGS);
        keys.add(KeyEvent.KEYCODE_TV_INPUT);
        keys.add(KeyEvent.KEYCODE_VOICE_ASSIST);
        keys.add(KeyEvent.KEYCODE_SEARCH);
        for (int k : store.getSeenKeys()) {
            if (!KidsGuard.isNavigation(k) && k != KeyEvent.KEYCODE_HOME) keys.add(k);
        }
        for (int code : keys) {
            LinearLayout row = Ui.hbox(this, 12);
            row.addView(Ui.text(this, keyName(code), 16, Ui.TEXT, false), Ui.weight(1));
            Button b = Ui.button(this, ruleLabel(store.getKeyRule(code)), false);
            b.setOnClickListener(v -> {
                String cur = store.getKeyRule(code);
                String next = KidsStore.RULE_ALLOW.equals(cur) ? KidsStore.RULE_PIN
                        : KidsStore.RULE_PIN.equals(cur) ? KidsStore.RULE_BLOCK : KidsStore.RULE_ALLOW;
                store.setKeyRule(code, next);
                b.setText(ruleLabel(next));
            });
            row.addView(b, Ui.fixed(this, 150));
            rules.addView(row, Ui.matchWidth());
        }
        rules.addView(Ui.text(this, "Power, volume, arrows, OK, Back and number keys always work.", 14, Ui.MUTED, false));
        content.addView(rules, Ui.matchWidth());
    }

    private static String ruleLabel(String rule) {
        if (KidsStore.RULE_PIN.equals(rule)) return "Needs PIN";
        if (KidsStore.RULE_BLOCK.equals(rule)) return "Blocked";
        return "Allowed";
    }

    static String keyName(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_MENU: return "Menu button";
            case KeyEvent.KEYCODE_SETTINGS: return "Settings button";
            case KeyEvent.KEYCODE_TV_INPUT: return "Input / source button";
            case KeyEvent.KEYCODE_VOICE_ASSIST:
            case KeyEvent.KEYCODE_ASSIST: return "Assistant / mic button";
            case KeyEvent.KEYCODE_SEARCH: return "Search button";
            default:
                String s = KeyEvent.keyCodeToString(code).replace("KEYCODE_", "").replace('_', ' ').toLowerCase(Locale.US);
                return Character.toUpperCase(s.charAt(0)) + s.substring(1) + " (code " + code + ")";
        }
    }

    private void startKeyTest() {
        if (!KidsGuard.isRunning()) {
            testResult.setText("Turn on the accessibility service first (see Setup)");
            return;
        }
        testResult.setText("Press a button on the remote…");
        KidsGuard.setKeyTestListener(code -> {
            if (testResult == null) return;
            String caught = KidsGuard.isNavigation(code) ? " — always works" : " — can be controlled ✓";
            testResult.setText(keyName(code) + caught);
        });
        h.removeCallbacks(stopTest);
        h.postDelayed(stopTest, 30_000);
    }

    private void stopKeyTest() {
        KidsGuard.setKeyTestListener(null);
        h.removeCallbacks(stopTest);
        if (testResult != null && "remote".equals(section)) testResult.setText("Not testing");
    }

    // ------------------------------------------------------------------ setup

    private void buildSetup() {
        header("Setup & PIN", "Everything below is set once with ADB from a laptop. See the install guide in the repo.");
        String pkg = getPackageName();

        LinearLayout checks = Ui.card(this, 12);
        checks.addView(Ui.text(this, "Checklist", 20, Ui.TEXT, true));
        checks.addView(check("Accessibility service (lock screen, buttons)", KidsGuard.isRunning(),
                "adb shell settings put secure enabled_accessibility_services " + pkg + "/" + LauncherAccessibilityService.class.getName()
                        + "\nadb shell settings put secure accessibility_enabled 1"));
        checks.addView(check("Usage access (counts screen time)", hasUsageAccess(),
                "adb shell appops set " + pkg + " GET_USAGE_STATS allow"));
        checks.addView(check("Notification access (records video titles)", hasNotificationAccess(),
                "adb shell cmd notification allow_listener " + pkg + "/" + LauncherNotificationListenerService.class.getName()));
        checks.addView(check("This launcher is the home screen", isDefaultHome(),
                "Press Home and choose this launcher, or disable the old one:\nadb shell pm disable-user --user 0 <old launcher package>"));
        content.addView(checks, Ui.matchWidth());

        LinearLayout pin = Ui.card(this, 10);
        pin.addView(Ui.text(this, "Parent PIN", 20, Ui.TEXT, true));
        pin.addView(Ui.text(this, "Needed to unlock the TV, give extra time and open these controls.", 15, Ui.MUTED, false));
        LinearLayout pb = Ui.hbox(this, 10);
        pb.addView(smallButton("Change PIN", v -> openPin(PinActivity.MODE_CREATE, REQ_CHANGE_PIN)));
        pin.addView(pb);
        content.addView(pin, Ui.matchWidth());
    }

    private View check(String title, boolean ok, String fix) {
        LinearLayout row = focusRow();
        row.addView(Ui.text(this, (ok ? "✓  " : "✗  ") + title, 17, ok ? Ui.GOOD : Ui.BAD, true));
        if (!ok) {
            TextView cmd = Ui.text(this, fix, 13, 0xFFD5DCE4, false);
            cmd.setTypeface(android.graphics.Typeface.MONOSPACE);
            row.addView(cmd);
        }
        return row;
    }

    private boolean hasUsageAccess() {
        try {
            AppOpsManager ops = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            @SuppressWarnings("deprecation")
            int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean hasNotificationAccess() {
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        ComponentName cn = new ComponentName(this, LauncherNotificationListenerService.class);
        return flat != null && flat.contains(cn.flattenToString());
    }

    private boolean isDefaultHome() {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo ri = getPackageManager().resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
        return ri != null && ri.activityInfo != null && getPackageName().equals(ri.activityInfo.packageName);
    }
}
