package com.leanbitlab.ltvL.kids;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small view-building helpers so the Kids mode screens share one look (dark, amber focus). */
final class Ui {
    static final int BG = 0xFF0E1116;
    static final int SURFACE = 0xFF171C24;
    static final int SURFACE2 = 0xFF212833;
    static final int BORDER = 0xFF2C3542;
    static final int TEXT = 0xFFF2F4F7;
    static final int MUTED = 0xFFA3ADBB;
    static final int ACCENT = 0xFFF2B347;
    static final int ACCENT_LIGHT = 0xFFFFD08A;
    static final int ON_ACCENT = 0xFF1A1300;
    static final int GOOD_BG = 0xFF1E2A1F;
    static final int GOOD = 0xFFCDEBCF;
    static final int BAD = 0xFFFFB4A8;

    private Ui() { }

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    static GradientDrawable rounded(Context c, int color, float radiusDp, int strokeColor, float strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        if (strokeDp > 0) d.setStroke(dp(c, strokeDp), strokeColor);
        return d;
    }

    static TextView text(Context c, CharSequence s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    /** A remote-friendly button: amber fill when focused. */
    static Button button(Context c, CharSequence label, boolean primary) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setFocusable(true);
        b.setStateListAnimator(null);
        int padH = dp(c, 16);
        int padV = dp(c, 10);
        b.setPadding(padH, padV, padH, padV);
        b.setMinHeight(dp(c, 44));
        b.setMinimumHeight(dp(c, 44));
        b.setBackground(buttonBackground(c, primary));
        int[][] states = {{android.R.attr.state_focused}, {android.R.attr.state_pressed}, {}};
        int[] colors = {ON_ACCENT, ON_ACCENT, primary ? ON_ACCENT : TEXT};
        b.setTextColor(new ColorStateList(states, colors));
        return b;
    }

    static Drawable buttonBackground(Context c, boolean primary) {
        StateListDrawable s = new StateListDrawable();
        GradientDrawable focused = rounded(c, primary ? ACCENT_LIGHT : ACCENT, 12, 0xFFFFFFFF, primary ? 3 : 0);
        s.addState(new int[]{android.R.attr.state_focused}, focused);
        s.addState(new int[]{android.R.attr.state_pressed}, focused);
        s.addState(new int[]{}, primary ? rounded(c, ACCENT, 12, 0, 0) : rounded(c, SURFACE2, 12, BORDER, 1));
        return s;
    }

    static LinearLayout vbox(Context c, float gapDp) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        if (gapDp > 0) l.setDividerDrawable(spacer(c, gapDp));
        l.setShowDividers(gapDp > 0 ? LinearLayout.SHOW_DIVIDER_MIDDLE : LinearLayout.SHOW_DIVIDER_NONE);
        return l;
    }

    static LinearLayout hbox(Context c, float gapDp) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        if (gapDp > 0) l.setDividerDrawable(spacer(c, gapDp));
        l.setShowDividers(gapDp > 0 ? LinearLayout.SHOW_DIVIDER_MIDDLE : LinearLayout.SHOW_DIVIDER_NONE);
        return l;
    }

    private static Drawable spacer(Context c, float gapDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(0x00000000);
        d.setSize(dp(c, gapDp), dp(c, gapDp));
        return d;
    }

    static LinearLayout card(Context c, float gapDp) {
        LinearLayout l = vbox(c, gapDp);
        int pad = dp(c, 20);
        l.setPadding(pad, pad, pad, pad);
        l.setBackground(rounded(c, SURFACE, 16, BORDER, 1));
        return l;
    }

    static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout.LayoutParams matchWidth() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w);
    }

    static LinearLayout.LayoutParams fixed(Context c, float wDp) {
        return new LinearLayout.LayoutParams(dp(c, wDp), LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    static View gap(Context c, float dp) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c, dp), dp(c, dp)));
        return v;
    }
}
