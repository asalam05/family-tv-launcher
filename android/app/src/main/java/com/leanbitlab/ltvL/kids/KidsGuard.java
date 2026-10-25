package com.leanbitlab.ltvL.kids;

import android.accessibilityservice.AccessibilityService;
import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.leanbitlab.ltvL.LauncherNotificationListenerService;
import com.leanbitlab.ltvL.MainActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The part of Kids mode that runs all the time, hosted by the launcher's accessibility service.
 * It counts viewing time, logs what was watched, shows the warning and lock screens over any app,
 * sends the kids home from blocked apps, and applies the remote button rules.
 */
public final class KidsGuard {

    public interface KeyTestListener { void onKey(int keyCode); }

    private static volatile KidsGuard sInstance;
    private static volatile KeyTestListener sKeyTest;

    static final Set<String> SETTINGS_PKGS = new HashSet<>(Arrays.asList(
            "com.android.tv.settings", "com.android.settings", "com.google.android.tv.settings"));
    static final Set<String> STORE_PKGS = new HashSet<>(Arrays.asList(
            "com.android.vending", "com.google.android.packageinstaller", "com.android.packageinstaller"));
    static final Set<String> VOICE_PKGS = new HashSet<>(Arrays.asList(
            "com.google.android.katniss", "com.google.android.googlequicksearchbox",
            "com.google.android.apps.mediashell.voice"));
    private static final Set<String> NEVER_COUNT = new HashSet<>(Arrays.asList(
            "android", "com.android.systemui"));

    private static final long TICK_MS = 3000;
    private static final int MAX_WRONG_PINS = 3;

    private final AccessibilityService svc;
    private final String own;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final WindowManager wm;
    private final PowerManager pm;
    private final KidsStore store;

    private String fgPkg;
    private long lastTick;
    private long usedRemainderMs;
    private long lastUsageQuery;
    private long ignoreOwnEventsUntil;
    private boolean lastScreenOn = true;
    private Set<String> homePkgs = new HashSet<>();
    private long homePkgsAt;

    // lock screen
    private FrameLayout lockView;
    private TextView lockTitle;
    private TextView lockBody;
    private PinPadView lockPin;
    private LinearLayout lockChoices;
    private long lockShownAt;
    private boolean offAttempted;
    private int wrongPins;
    private long pinCooldownUntil;

    // warning + toast
    private View warnView;
    private long lastWarnShownAt;
    private View toastView;

    // viewing log
    private String sesPkg;
    private long sesStart;
    private LinkedHashSet<String> sesTitles = new LinkedHashSet<>();
    private long lastTitleCheck;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            tick();
            h.postDelayed(this, TICK_MS);
        }
    };
    private final Runnable quickTick = this::tick;

    public KidsGuard(AccessibilityService service) {
        svc = service;
        own = service.getPackageName();
        wm = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
        pm = (PowerManager) service.getSystemService(Context.POWER_SERVICE);
        store = KidsStore.get(service);
    }

    // ------------------------------------------------------------------ lifecycle & statics

    public void start() {
        sInstance = this;
        lastUsageQuery = System.currentTimeMillis() - 6 * 3600_000L;
        h.post(ticker);
    }

    public void stop() {
        h.removeCallbacksAndMessages(null);
        endSession(System.currentTimeMillis());
        hideLock();
        hideWarning();
        hideToast();
        if (sInstance == this) sInstance = null;
    }

    public static boolean isRunning() { return sInstance != null; }

    /** Re-check the rules right away (called after a parent changes something). */
    public static void refresh() {
        KidsGuard g = sInstance;
        if (g != null) g.h.post(g.quickTick);
    }

    /** While set, every remote button is reported to the listener (for the "test your remote" screen). */
    public static void setKeyTestListener(KeyTestListener l) { sKeyTest = l; }

    // ------------------------------------------------------------------ accessibility input

    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence p = e.getPackageName();
        if (p == null) return;
        if (!hasUsageAccess()) {
            String pkg = p.toString();
            boolean ownOverlay = pkg.equals(own) && SystemClock.uptimeMillis() < ignoreOwnEventsUntil;
            if (!ownOverlay && !NEVER_COUNT.contains(pkg)) fgPkg = pkg;
        }
        h.removeCallbacks(quickTick);
        h.postDelayed(quickTick, 300);
    }

    /** @return true when the key should be swallowed. */
    public boolean onKey(KeyEvent e) {
        int code = e.getKeyCode();
        boolean firstDown = e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0;

        KeyTestListener test = sKeyTest;
        if (test != null) {
            if (firstDown) {
                store.rememberKey(code);
                h.post(() -> test.onKey(code));
            }
            return code == KeyEvent.KEYCODE_HOME || !isNavigation(code);
        }

        if (firstDown && code != KeyEvent.KEYCODE_HOME && !isNavigation(code)) store.rememberKey(code);

        if (lockView != null) {
            // Lock screen up: arrows/OK/numbers reach the PIN pad, everything else is ignored.
            return code == KeyEvent.KEYCODE_HOME || !isNavigation(code);
        }

        boolean kidsActive = store.isEnabled() && !store.isParentActive();
        if (!kidsActive || code == KeyEvent.KEYCODE_HOME || isNavigation(code)) return false;

        String rule = store.getKeyRule(code);
        if (isVoiceKey(code) && store.flag(KidsStore.FLAG_BLOCK_VOICE, false)) rule = KidsStore.RULE_BLOCK;
        if (KidsStore.RULE_BLOCK.equals(rule)) {
            if (firstDown) h.post(() -> showToast("That button is turned off in Kids mode"));
            return true;
        }
        if (KidsStore.RULE_PIN.equals(rule)) {
            if (firstDown) h.post(this::askForPin);
            return true;
        }
        return false;
    }

    static boolean isNavigation(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_DEL:
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
            case KeyEvent.KEYCODE_MUTE:
            case KeyEvent.KEYCODE_POWER:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_STOP:
                return true;
            default:
                return code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9;
        }
    }

    static boolean isVoiceKey(int code) {
        return code == KeyEvent.KEYCODE_SEARCH || code == KeyEvent.KEYCODE_ASSIST
                || code == KeyEvent.KEYCODE_VOICE_ASSIST;
    }

    // ------------------------------------------------------------------ main loop

    private void tick() {
        long now = System.currentTimeMillis();
        refreshForegroundFromUsage(now);
        boolean screenOn = pm.isInteractive();
        if (screenOn && !lastScreenOn) {
            offAttempted = false;
            lockShownAt = now;
        }
        lastScreenOn = screenOn;

        KidsEngine.Status st = KidsEngine.compute(store, now);

        // 1. count viewing time
        long dt = lastTick == 0 ? 0 : Math.min(now - lastTick, 10_000);
        lastTick = now;
        boolean watching = screenOn && st.enabled && lockView == null && isCountable(fgPkg)
                && !KidsEngine.R_PARENT.equals(st.reason);
        if (watching && dt > 0) {
            usedRemainderMs += dt;
            long secs = usedRemainderMs / 1000;
            usedRemainderMs -= secs * 1000;
            if (secs > 0) store.addUsedSeconds(KidsStore.today(), secs);
        }

        // 2. viewing log
        updateSession(screenOn && st.enabled ? fgPkg : null, now);
        if (sesPkg != null) captureTitle(now);

        if (!st.enabled) {
            hideLock();
            hideWarning();
            return;
        }

        // 3. lock when time is up
        if (!st.allowed) {
            hideWarning();
            if (screenOn) {
                showLock(st, now);
                if (fgPkg != null && !fgPkg.equals(own)) {
                    pauseMedia();
                    goHome();
                }
                if (KidsStore.END_OFF.equals(store.getEndMode()) && !offAttempted
                        && lockShownAt > 0 && now - lockShownAt > 20_000) {
                    offAttempted = true;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN);
                    }
                }
            }
            return;
        }
        hideLock();

        // 4. blocked apps
        if (!store.isParentActive() && fgPkg != null && isBlocked(fgPkg)) {
            goHome();
            showToast("Ask a parent to open this");
            fgPkg = own;
        }

        // 5. "time almost up" warning
        int left = st.minutesLeft(now);
        int warn = store.getWarnMinutes();
        if (screenOn && left >= 0 && left <= warn && now - lastWarnShownAt > (warn + 2) * 60_000L) {
            lastWarnShownAt = now;
            showWarning(left, st.endsAt);
        }
    }

    // ------------------------------------------------------------------ foreground app

    private boolean hasUsageAccess() {
        try {
            AppOpsManager ops = (AppOpsManager) svc.getSystemService(Context.APP_OPS_SERVICE);
            @SuppressWarnings("deprecation")
            int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), own);
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private void refreshForegroundFromUsage(long now) {
        if (!hasUsageAccess()) return;
        try {
            UsageStatsManager usm = (UsageStatsManager) svc.getSystemService(Context.USAGE_STATS_SERVICE);
            UsageEvents events = usm.queryEvents(lastUsageQuery, now + 1000);
            UsageEvents.Event ev = new UsageEvents.Event();
            while (events.hasNextEvent()) {
                events.getNextEvent(ev);
                if (ev.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) fgPkg = ev.getPackageName();
            }
            lastUsageQuery = now - 2000;
        } catch (Exception ignored) {
        }
    }

    private Set<String> homePackages() {
        long now = System.currentTimeMillis();
        if (now - homePkgsAt > 60_000) {
            Set<String> s = new HashSet<>();
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            for (ResolveInfo ri : svc.getPackageManager().queryIntentActivities(i, PackageManager.MATCH_ALL)) {
                if (ri.activityInfo != null) s.add(ri.activityInfo.packageName);
            }
            s.remove(own);
            homePkgs = s;
            homePkgsAt = now;
        }
        return homePkgs;
    }

    private boolean isCountable(String pkg) {
        return pkg != null && !pkg.equals(own) && !NEVER_COUNT.contains(pkg) && !homePackages().contains(pkg);
    }

    boolean isBlocked(String pkg) {
        if (pkg == null || pkg.equals(own)) return false;
        if (store.getBlockedApps().contains(pkg)) return true;
        if (store.flag(KidsStore.FLAG_BLOCK_SETTINGS, true) && SETTINGS_PKGS.contains(pkg)) return true;
        if (store.flag(KidsStore.FLAG_BLOCK_STORE, true) && STORE_PKGS.contains(pkg)) return true;
        if (store.flag(KidsStore.FLAG_BLOCK_VOICE, false) && VOICE_PKGS.contains(pkg)) return true;
        return store.flag(KidsStore.FLAG_BLOCK_OTHER_HOMES, true) && homePackages().contains(pkg)
                && !SETTINGS_PKGS.contains(pkg);
    }

    // ------------------------------------------------------------------ viewing log

    private void updateSession(String pkg, long now) {
        String loggable = isCountable(pkg) ? pkg : null;
        if (sesPkg != null && Objects.equals(loggable, sesPkg) && now - sesStart > 30 * 60_000L) {
            endSession(now); // split long sessions so nothing is lost if the TV loses power
        }
        if (Objects.equals(loggable, sesPkg)) return;
        endSession(now);
        if (loggable != null) {
            sesPkg = loggable;
            sesStart = now;
            sesTitles = new LinkedHashSet<>();
            lastTitleCheck = 0;
        }
    }

    private void endSession(long now) {
        if (sesPkg == null) return;
        long dur = now - sesStart;
        if (dur >= 10_000) {
            try {
                JSONObject o = new JSONObject();
                o.put("pkg", sesPkg);
                o.put("label", appLabel(sesPkg));
                o.put("start", sesStart);
                o.put("end", now);
                o.put("titles", new JSONArray(sesTitles));
                store.appendSession(KidsStore.dateKey(new Date(sesStart)), o);
            } catch (Exception ignored) {
            }
        }
        sesPkg = null;
    }

    private String appLabel(String pkg) {
        try {
            PackageManager p = svc.getPackageManager();
            return p.getApplicationLabel(p.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    private void captureTitle(long now) {
        if (now - lastTitleCheck < 10_000) return;
        lastTitleCheck = now;
        try {
            MediaSessionManager msm = (MediaSessionManager) svc.getSystemService(Context.MEDIA_SESSION_SERVICE);
            List<MediaController> list = msm.getActiveSessions(new ComponentName(svc, LauncherNotificationListenerService.class));
            for (MediaController mc : list) {
                if (!sesPkg.equals(mc.getPackageName())) continue;
                MediaMetadata md = mc.getMetadata();
                if (md == null) continue;
                String t = md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
                if (t == null || t.trim().isEmpty()) t = md.getString(MediaMetadata.METADATA_KEY_TITLE);
                if (t != null && !t.trim().isEmpty() && sesTitles.size() < 30) sesTitles.add(t.trim());
            }
        } catch (Exception ignored) {
            // No notification access: titles are simply not recorded.
        }
    }

    // ------------------------------------------------------------------ actions

    private void goHome() {
        try {
            Intent i = new Intent(svc, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            svc.startActivity(i);
        } catch (Exception e) {
            svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
        }
    }

    private void pauseMedia() {
        try {
            AudioManager am = (AudioManager) svc.getSystemService(Context.AUDIO_SERVICE);
            long t = SystemClock.uptimeMillis();
            am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0));
            am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE, 0));
        } catch (Exception ignored) {
        }
    }

    private void askForPin() {
        Intent i = new Intent(svc, PinActivity.class)
                .putExtra(PinActivity.EXTRA_MODE, PinActivity.MODE_GATE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        svc.startActivity(i);
    }

    // ------------------------------------------------------------------ overlays

    private WindowManager.LayoutParams overlayParams(boolean fullscreen, boolean focusable) {
        int flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        if (!focusable) {
            flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        int size = fullscreen ? WindowManager.LayoutParams.MATCH_PARENT : WindowManager.LayoutParams.WRAP_CONTENT;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags,
                fullscreen ? PixelFormat.OPAQUE : PixelFormat.TRANSLUCENT);
        ignoreOwnEventsUntil = SystemClock.uptimeMillis() + 1500;
        return lp;
    }

    private void showLock(KidsEngine.Status st, long now) {
        if (lockView == null) {
            buildLock();
            try {
                wm.addView(lockView, overlayParams(true, true));
                lockShownAt = now;
                offAttempted = false;
                showPinPad();
            } catch (Exception e) {
                lockView = null;
                return;
            }
        }
        String next = st.nextAllowedAt > 0 ? KidsEngine.whenText(st.nextAllowedAt, now) : "later";
        String used = KidsEngine.duration(st.usedSeconds);
        switch (st.reason) {
            case KidsEngine.R_EARLY -> {
                lockTitle.setText("TV time hasn't started yet");
                lockBody.setText("The TV unlocks " + next + ".");
            }
            case KidsEngine.R_LOCKED -> {
                lockTitle.setText("The TV is locked");
                lockBody.setText("A parent locked the TV. It unlocks again " + next + ".");
            }
            default -> {
                lockTitle.setText("TV time is over\nfor today");
                lockBody.setText("You watched " + used + " today. The TV unlocks again " + next + ".");
            }
        }
    }

    private void buildLock() {
        Context c = svc;
        lockView = new FrameLayout(c);
        lockView.setBackgroundColor(0xFF0B0E13);

        LinearLayout row = Ui.hbox(c, 0);
        int padH = Ui.dp(c, 64);
        int padV = Ui.dp(c, 48);
        row.setPadding(padH, padV, padH, padV);

        LinearLayout left = Ui.vbox(c, 14);
        TextView moon = Ui.text(c, "☾", 48, Ui.ACCENT, false);
        lockTitle = Ui.text(c, "", 40, Ui.TEXT, true);
        lockBody = Ui.text(c, "", 18, 0xFFC3CBD6, false);
        TextView note = Ui.text(c, "Restarting the TV won't reset the timer", 14, 0xFFD5DCE4, false);
        int np = Ui.dp(c, 10);
        note.setPadding(np + 4, np, np + 4, np);
        note.setBackground(Ui.rounded(c, Ui.SURFACE, 10, Ui.BORDER, 1));
        left.addView(moon, Ui.wrap());
        left.addView(lockTitle, Ui.wrap());
        left.addView(lockBody, Ui.matchWidth());
        LinearLayout.LayoutParams nl = Ui.wrap();
        nl.topMargin = Ui.dp(c, 10);
        left.addView(note, nl);
        row.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        FrameLayout right = new FrameLayout(c);
        lockPin = new PinPadView(c, "Parent PIN", "Enter your PIN to give more time");
        lockPin.setListener(this::onLockPin);
        right.addView(lockPin);

        lockChoices = Ui.card(c, 10);
        lockChoices.setVisibility(View.GONE);
        lockChoices.addView(Ui.text(c, "Unlocked", 22, Ui.TEXT, true));
        lockChoices.addView(Ui.text(c, "What should the kids get?", 14, Ui.MUTED, false));
        addChoice("+15 minutes", false, () -> grant(15));
        addChoice("+30 minutes", true, () -> grant(30));
        addChoice("+1 hour", false, () -> grant(60));
        addChoice("No limits for the rest of today", false, () -> {
            store.setForceLockedToday(false);
            store.setPausedToday(true);
            hideLock();
        });
        addChoice("Open parent controls", false, () -> {
            store.startParentSession();
            hideLock();
            Intent i = new Intent(svc, ParentActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            svc.startActivity(i);
        });
        addChoice("Keep the TV locked", false, this::showPinPad);
        right.addView(lockChoices, new FrameLayout.LayoutParams(Ui.dp(c, 300), FrameLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams rl = Ui.wrap();
        rl.leftMargin = Ui.dp(c, 48);
        row.addView(right, rl);

        FrameLayout.LayoutParams fl = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        fl.gravity = Gravity.CENTER;
        lockView.addView(row, fl);
    }

    private void addChoice(String label, boolean primary, Runnable action) {
        Button b = Ui.button(svc, label, primary);
        b.setOnClickListener(v -> action.run());
        lockChoices.addView(b, Ui.matchWidth());
    }

    private void showPinPad() {
        if (lockPin == null) return;
        lockChoices.setVisibility(View.GONE);
        lockPin.setVisibility(View.VISIBLE);
        lockPin.reset();
        lockPin.post(lockPin::focusFirst);
    }

    private void onLockPin(String pin) {
        long now = SystemClock.uptimeMillis();
        if (now < pinCooldownUntil) {
            lockPin.showError("Too many tries. Wait a moment.");
            return;
        }
        if (store.checkPin(pin)) {
            wrongPins = 0;
            lockPin.setVisibility(View.GONE);
            lockChoices.setVisibility(View.VISIBLE);
            lockChoices.post(() -> {
                if (lockChoices.getChildCount() > 3) lockChoices.getChildAt(3).requestFocus();
            });
        } else {
            wrongPins++;
            if (wrongPins >= MAX_WRONG_PINS) {
                wrongPins = 0;
                pinCooldownUntil = now + 30_000;
                lockPin.showError("Wrong PIN. Try again in 30 seconds.");
            } else {
                lockPin.showError("Wrong PIN — try again");
            }
        }
    }

    private void grant(int minutes) {
        KidsEngine.grant(store, minutes);
        lastWarnShownAt = 0;
        hideLock();
    }

    private void hideLock() {
        if (lockView == null) return;
        try { wm.removeView(lockView); } catch (Exception ignored) { }
        lockView = null;
        lockPin = null;
        lockChoices = null;
        lockShownAt = 0;
    }

    private void showWarning(int minutesLeft, long endsAt) {
        hideWarning();
        Context c = svc;
        LinearLayout card = Ui.vbox(c, 6);
        int pad = Ui.dp(c, 20);
        card.setPadding(pad, pad, pad, pad);
        card.setBackground(Ui.rounded(c, 0xF5171C24, 16, 0xFF3A4555, 1));
        String mins = minutesLeft <= 1 ? "1 minute" : minutesLeft + " minutes";
        card.addView(Ui.text(c, mins + " of TV time left", 22, Ui.TEXT, true));
        card.addView(Ui.text(c, "Time to finish up. The TV locks at " + KidsEngine.clock(endsAt) + ".", 15, 0xFFC3CBD6, false));
        WindowManager.LayoutParams lp = overlayParams(false, false);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = Ui.dp(c, 32);
        lp.y = Ui.dp(c, 32);
        try {
            wm.addView(card, lp);
            warnView = card;
            h.postDelayed(this::hideWarning, 20_000);
        } catch (Exception ignored) {
        }
    }

    private void hideWarning() {
        if (warnView == null) return;
        try { wm.removeView(warnView); } catch (Exception ignored) { }
        warnView = null;
    }

    private void showToast(String msg) {
        hideToast();
        Context c = svc;
        TextView t = Ui.text(c, msg, 18, Ui.TEXT, true);
        int p = Ui.dp(c, 16);
        t.setPadding(p + 8, p, p + 8, p);
        t.setBackground(Ui.rounded(c, 0xF5171C24, 14, Ui.ACCENT, 2));
        WindowManager.LayoutParams lp = overlayParams(false, false);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.y = Ui.dp(c, 48);
        try {
            wm.addView(t, lp);
            toastView = t;
            h.postDelayed(this::hideToast, 3500);
        } catch (Exception ignored) {
        }
    }

    private void hideToast() {
        if (toastView == null) return;
        try { wm.removeView(toastView); } catch (Exception ignored) { }
        toastView = null;
    }
}
