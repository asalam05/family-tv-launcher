package com.leanbitlab.ltvL.kids;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * All Kids mode settings and history, kept on the TV.
 * Settings live in SharedPreferences; viewing history lives in one small JSON file per day.
 */
public final class KidsStore {

    public static final String RULE_ALLOW = "allow";
    public static final String RULE_PIN = "pin";
    public static final String RULE_BLOCK = "block";

    public static final String END_LOCK = "lock";
    public static final String END_OFF = "off";

    public static final String FLAG_BLOCK_SETTINGS = "block_settings";
    public static final String FLAG_BLOCK_STORE = "block_store";
    public static final String FLAG_BLOCK_VOICE = "block_voice";
    public static final String FLAG_BLOCK_OTHER_HOMES = "block_other_homes";

    /** Minutes a parent PIN unlock lasts. */
    public static final int PARENT_SESSION_MINUTES = 15;
    private static final int LOG_DAYS_KEPT = 30;

    private static KidsStore sInstance;

    private final Context ctx;
    private final SharedPreferences p;

    public static synchronized KidsStore get(Context c) {
        if (sInstance == null) sInstance = new KidsStore(c.getApplicationContext());
        return sInstance;
    }

    private KidsStore(Context c) {
        ctx = c;
        p = c.getSharedPreferences("kids_mode", Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- day rules

    /** Screen time rule for one weekday. Times are minutes after midnight. */
    public static final class DayRule {
        public boolean on;
        public int from;
        public int to;
        public int limit;

        public DayRule(boolean on, int from, int to, int limit) {
            this.on = on;
            this.from = from;
            this.to = to;
            this.limit = limit;
        }

        String encode() {
            return (on ? 1 : 0) + "," + from + "," + to + "," + limit;
        }

        static DayRule decode(String s, DayRule def) {
            if (s == null) return def;
            try {
                String[] a = s.split(",");
                return new DayRule("1".equals(a[0]), Integer.parseInt(a[1]), Integer.parseInt(a[2]), Integer.parseInt(a[3]));
            } catch (Exception e) {
                return def;
            }
        }
    }

    /** @param isoDay 1 = Monday ... 7 = Sunday */
    public DayRule getRule(int isoDay) {
        DayRule def = isoDay >= 6
                ? new DayRule(true, 9 * 60, 20 * 60 + 30, 180)
                : new DayRule(true, 16 * 60, 20 * 60, 120);
        return DayRule.decode(p.getString("rule_" + isoDay, null), def);
    }

    public void setRule(int isoDay, DayRule r) {
        p.edit().putString("rule_" + isoDay, r.encode()).apply();
    }

    public static int isoDay(Calendar c) {
        int d = c.get(Calendar.DAY_OF_WEEK); // Sunday = 1
        return d == Calendar.SUNDAY ? 7 : d - 1;
    }

    // ---------------------------------------------------------------- simple settings

    public boolean isEnabled() { return p.getBoolean("enabled", false); }
    public void setEnabled(boolean v) { p.edit().putBoolean("enabled", v).apply(); }

    public int getWarnMinutes() { return p.getInt("warn_minutes", 5); }
    public void setWarnMinutes(int v) { p.edit().putInt("warn_minutes", Math.max(1, Math.min(15, v))).apply(); }

    public String getEndMode() { return p.getString("end_mode", END_LOCK); }
    public void setEndMode(String v) { p.edit().putString("end_mode", v).apply(); }

    public long getGrantUntil() { return p.getLong("grant_until", 0); }
    public void setGrantUntil(long v) { p.edit().putLong("grant_until", v).apply(); }

    public boolean isPausedToday() { return today().equals(p.getString("paused_date", "")); }
    public void setPausedToday(boolean v) { p.edit().putString("paused_date", v ? today() : "").apply(); }

    public boolean isForceLockedToday() { return today().equals(p.getString("force_lock_date", "")); }
    public void setForceLockedToday(boolean v) { p.edit().putString("force_lock_date", v ? today() : "").apply(); }

    public boolean flag(String name, boolean def) { return p.getBoolean("flag_" + name, def); }
    public void setFlag(String name, boolean v) { p.edit().putBoolean("flag_" + name, v).apply(); }

    // ---------------------------------------------------------------- parent PIN & session

    public boolean hasPin() { return p.contains("pin_hash"); }

    public void setPin(String pin) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        String s = hex(salt);
        p.edit().putString("pin_salt", s).putString("pin_hash", hash(s, pin)).apply();
    }

    public boolean checkPin(String pin) {
        String s = p.getString("pin_salt", "");
        return hasPin() && hash(s, pin).equals(p.getString("pin_hash", ""));
    }

    public boolean isParentActive() { return p.getLong("parent_until", 0) > System.currentTimeMillis(); }
    public void startParentSession() {
        p.edit().putLong("parent_until", System.currentTimeMillis() + PARENT_SESSION_MINUTES * 60_000L).apply();
    }
    public void endParentSession() { p.edit().putLong("parent_until", 0).apply(); }

    private static String hash(String salt, String pin) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return hex(md.digest((salt + ":" + pin).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "";
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.US, "%02x", x));
        return sb.toString();
    }

    // ---------------------------------------------------------------- usage counter

    public long getUsedSeconds(String date) { return p.getLong("used_" + date, 0); }

    public void addUsedSeconds(String date, long s) {
        p.edit().putLong("used_" + date, getUsedSeconds(date) + s).apply();
    }

    // ---------------------------------------------------------------- remote button rules

    public String getKeyRule(int keyCode) { return p.getString("key_" + keyCode, RULE_ALLOW); }
    public void setKeyRule(int keyCode, String rule) {
        p.edit().putString("key_" + keyCode, rule).apply();
        rememberKey(keyCode);
    }

    public Set<Integer> getSeenKeys() {
        Set<Integer> out = new TreeSet<>();
        for (String s : p.getStringSet("seen_keys", new HashSet<>())) {
            try { out.add(Integer.parseInt(s)); } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    public void rememberKey(int keyCode) {
        Set<String> cur = p.getStringSet("seen_keys", new HashSet<>());
        if (cur.contains(String.valueOf(keyCode))) return;
        Set<String> next = new HashSet<>(cur);
        next.add(String.valueOf(keyCode));
        p.edit().putStringSet("seen_keys", next).apply();
    }

    // ---------------------------------------------------------------- blocked apps

    public Set<String> getBlockedApps() { return new HashSet<>(p.getStringSet("blocked_apps", new HashSet<>())); }

    public void setAppBlocked(String pkg, boolean blocked) {
        Set<String> s = getBlockedApps();
        if (blocked) s.add(pkg); else s.remove(pkg);
        p.edit().putStringSet("blocked_apps", s).apply();
    }

    // ---------------------------------------------------------------- viewing history

    public synchronized void appendSession(String date, JSONObject session) {
        try {
            JSONArray arr = readLog(date);
            arr.put(session);
            File f = logFile(date);
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(arr.toString().getBytes(StandardCharsets.UTF_8));
            }
            pruneLogs();
        } catch (Exception ignored) {
        }
    }

    public synchronized List<JSONObject> getSessions(String date) {
        List<JSONObject> out = new ArrayList<>();
        JSONArray arr = readLog(date);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) out.add(o);
        }
        return out;
    }

    private JSONArray readLog(String date) {
        File f = logFile(date);
        if (!f.exists()) return new JSONArray();
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int read = 0;
            while (read < buf.length) {
                int n = in.read(buf, read, buf.length - read);
                if (n < 0) break;
                read += n;
            }
            return new JSONArray(new String(buf, 0, read, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private File logDir() {
        File d = new File(ctx.getFilesDir(), "kids_log");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private File logFile(String date) { return new File(logDir(), date + ".json"); }

    private void pruneLogs() {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_YEAR, -LOG_DAYS_KEPT);
        String cutoff = dateKey(c.getTime());
        File[] files = logDir().listFiles();
        if (files == null) return;
        for (File f : files) {
            String name = f.getName().replace(".json", "");
            if (name.compareTo(cutoff) < 0) f.delete();
        }
    }

    // ---------------------------------------------------------------- dates

    public static String today() { return dateKey(new Date()); }

    public static String dateKey(Date d) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(d);
    }
}
