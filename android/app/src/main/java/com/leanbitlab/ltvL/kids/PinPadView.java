package com.leanbitlab.ltvL.kids;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Four-digit PIN pad that works with the remote's arrows/OK and its number keys. */
final class PinPadView extends LinearLayout {

    interface Listener { void onPinEntered(String pin); }

    private static final int LENGTH = 4;

    private final StringBuilder typed = new StringBuilder();
    private final LinearLayout dots;
    private final TextView title;
    private final TextView subtitle;
    private final TextView error;
    private Button firstKey;
    private Listener listener;

    PinPadView(Context c, String titleText, String subtitleText) {
        super(c);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = Ui.dp(c, 24);
        setPadding(pad, pad, pad, pad);
        setBackground(Ui.rounded(c, Ui.SURFACE, 20, Ui.BORDER, 1));

        title = Ui.text(c, titleText, 22, Ui.TEXT, true);
        subtitle = Ui.text(c, subtitleText, 14, Ui.MUTED, false);
        addView(title, Ui.wrap());
        LayoutParams sl = Ui.wrap();
        sl.topMargin = Ui.dp(c, 4);
        addView(subtitle, sl);

        dots = Ui.hbox(c, 14);
        dots.setGravity(Gravity.CENTER);
        LayoutParams dl = Ui.wrap();
        dl.topMargin = Ui.dp(c, 18);
        dl.bottomMargin = Ui.dp(c, 6);
        addView(dots, dl);

        error = Ui.text(c, "", 14, Ui.BAD, false);
        error.setGravity(Gravity.CENTER);
        addView(error, Ui.wrap());

        String[] keys = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "C", "0", "⌫"};
        LinearLayout grid = Ui.vbox(c, 8);
        for (int r = 0; r < 4; r++) {
            LinearLayout row = Ui.hbox(c, 8);
            for (int k = 0; k < 3; k++) {
                final String key = keys[r * 3 + k];
                Button b = Ui.button(c, key, false);
                b.setTextSize(20);
                b.setContentDescription(key.equals("⌫") ? "Delete" : key.equals("C") ? "Clear" : key);
                b.setOnClickListener(v -> press(key));
                row.addView(b, new LayoutParams(Ui.dp(c, 76), Ui.dp(c, 52)));
                if (firstKey == null) firstKey = b;
            }
            grid.addView(row, Ui.wrap());
        }
        LayoutParams gl = Ui.wrap();
        gl.topMargin = Ui.dp(c, 12);
        addView(grid, gl);
        refreshDots();
    }

    void setListener(Listener l) { listener = l; }

    void setTexts(String t, String s) {
        title.setText(t);
        subtitle.setText(s);
    }

    void showError(String msg) { error.setText(msg); }

    void reset() {
        typed.setLength(0);
        refreshDots();
    }

    void focusFirst() {
        if (firstKey != null) firstKey.requestFocus();
    }

    private void press(String key) {
        error.setText("");
        switch (key) {
            case "C" -> typed.setLength(0);
            case "⌫" -> { if (typed.length() > 0) typed.setLength(typed.length() - 1); }
            default -> { if (typed.length() < LENGTH) typed.append(key); }
        }
        refreshDots();
        if (typed.length() == LENGTH) {
            String pin = typed.toString();
            typed.setLength(0);
            postDelayed(this::refreshDots, 150);
            if (listener != null) listener.onPinEntered(pin);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) press(String.valueOf(code - KeyEvent.KEYCODE_0));
            return true;
        }
        if (code == KeyEvent.KEYCODE_DEL) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) press("⌫");
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private void refreshDots() {
        dots.removeAllViews();
        Context c = getContext();
        for (int i = 0; i < LENGTH; i++) {
            View d = new View(c);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            if (i < typed.length()) g.setColor(Ui.ACCENT);
            else {
                g.setColor(0x00000000);
                g.setStroke(Ui.dp(c, 2), 0xFF5A6677);
            }
            d.setBackground(g);
            dots.addView(d, new LayoutParams(Ui.dp(c, 16), Ui.dp(c, 16)));
        }
    }
}
