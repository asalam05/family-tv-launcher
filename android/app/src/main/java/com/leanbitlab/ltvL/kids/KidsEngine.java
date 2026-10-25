package com.leanbitlab.ltvL.kids;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** Works out, for a given moment, whether the kids may watch and for how long. */
public final class KidsEngine {

    public static final String R_OFF = "off";          // Kids mode disabled
    public static final String R_PARENT = "parent";    // parent PIN session active
    public static final String R_NO_LIMIT = "nolimit"; // day has no rules, or paused today
    public static final String R_GRANT = "grant";      // extra time given by a parent
    public static final String R_OK = "ok";            // inside the schedule
    public static final String R_EARLY = "early";      // before today's start time
    public static final String R_LATE = "late";        // after today's off time
    public static final String R_LIMIT = "limit";      // daily limit used up
    public static final String R_LOCKED = "locked";    // parent pressed "Lock now"

    public static final class Status {
        public boolean enabled;
        public boolean allowed;
        public String reason;
        /** When watching must stop (ms), or -1 when there is no end. */
        public long endsAt = -1;
        /** When the TV unlocks again (ms), or -1. */
        public long nextAllowedAt = -1;
        public long usedSeconds;
        public int limitMinutes;
        public int fromMinutes;
        public int toMinutes;

        public int minutesLeft(long now) {
            if (!allowed || endsAt < 0) return -1;
            return (int) Math.max(0, Math.ceil((endsAt - now) / 60000.0));
        }
    }

    private KidsEngine() { }

    public static Status compute(KidsStore s, long now) {
        Status st = new Status();
        st.enabled = s.isEnabled();
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        int isoDay = KidsStore.isoDay(cal);
        KidsStore.DayRule r = s.getRule(isoDay);
        String today = KidsStore.dateKey(new Date(now));
        st.usedSeconds = s.getUsedSeconds(today);
        st.limitMinutes = r.limit;
        st.fromMinutes = r.from;
        st.toMinutes = r.to;

        if (!st.enabled) { st.allowed = true; st.reason = R_OFF; return st; }
        if (s.isParentActive()) { st.allowed = true; st.reason = R_PARENT; return st; }

        long grant = s.getGrantUntil();
        if (grant > now) { st.allowed = true; st.reason = R_GRANT; st.endsAt = grant; return st; }

        if (s.isForceLockedToday()) {
            st.allowed = false;
            st.reason = R_LOCKED;
            st.nextAllowedAt = nextStart(s, cal, false);
            return st;
        }
        if (!r.on || s.isPausedToday()) { st.allowed = true; st.reason = R_NO_LIMIT; return st; }

        int mins = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        long dayStart = startOfDay(cal);
        long limitLeftMs = r.limit * 60_000L - st.usedSeconds * 1000L;

        if (mins < r.from) {
            st.allowed = false;
            st.reason = R_EARLY;
            st.nextAllowedAt = dayStart + r.from * 60_000L;
        } else if (mins >= r.to) {
            st.allowed = false;
            st.reason = R_LATE;
            st.nextAllowedAt = nextStart(s, cal, false);
        } else if (limitLeftMs <= 0) {
            st.allowed = false;
            st.reason = R_LIMIT;
            st.nextAllowedAt = nextStart(s, cal, false);
        } else {
            st.allowed = true;
            st.reason = R_OK;
            st.endsAt = Math.min(dayStart + r.to * 60_000L, now + limitLeftMs);
        }
        return st;
    }

    /** When the TV next unlocks on its own: tomorrow's start time (or midnight if tomorrow has no rules). */
    private static long nextStart(KidsStore s, Calendar now, boolean includeToday) {
        Calendar d = (Calendar) now.clone();
        if (!includeToday) d.add(Calendar.DAY_OF_YEAR, 1);
        KidsStore.DayRule r = s.getRule(KidsStore.isoDay(d));
        long start = startOfDay(d);
        return r.on ? start + r.from * 60_000L : start;
    }

    /** Give extra minutes on top of whatever time is currently left. */
    public static void grant(KidsStore s, int minutes) {
        long now = System.currentTimeMillis();
        Status st = compute(s, now);
        long base = st.allowed && st.endsAt > now ? st.endsAt : now;
        s.setGrantUntil(base + minutes * 60_000L);
        s.setForceLockedToday(false);
    }

    static long startOfDay(Calendar c) {
        Calendar d = (Calendar) c.clone();
        d.set(Calendar.HOUR_OF_DAY, 0);
        d.set(Calendar.MINUTE, 0);
        d.set(Calendar.SECOND, 0);
        d.set(Calendar.MILLISECOND, 0);
        return d.getTimeInMillis();
    }

    // ---------------------------------------------------------------- text helpers

    public static String clock(long ms) {
        return DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(ms));
    }

    public static String clockMinutes(int minutesAfterMidnight) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, minutesAfterMidnight / 60);
        c.set(Calendar.MINUTE, minutesAfterMidnight % 60);
        return clock(c.getTimeInMillis());
    }

    /** "today at 4:00 PM", "tomorrow at 4:00 PM" or "Saturday at 9:00 AM". */
    public static String whenText(long ms, long now) {
        Calendar a = Calendar.getInstance();
        a.setTimeInMillis(now);
        Calendar b = Calendar.getInstance();
        b.setTimeInMillis(ms);
        long days = (startOfDay(b) - startOfDay(a)) / (24 * 3600_000L);
        String day = days == 0 ? "today" : days == 1 ? "tomorrow"
                : new SimpleDateFormat("EEEE", Locale.getDefault()).format(new Date(ms));
        return day + " at " + clock(ms);
    }

    public static String duration(long seconds) {
        long m = Math.round(seconds / 60.0);
        long h = m / 60;
        m = m % 60;
        if (h == 0) return m + " min";
        if (m == 0) return h + " h";
        return h + " h " + m + " min";
    }
}
