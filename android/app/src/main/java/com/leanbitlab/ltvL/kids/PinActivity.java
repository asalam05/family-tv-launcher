package com.leanbitlab.ltvL.kids;

import android.app.Activity;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Full-screen parent PIN prompt.
 * MODE_GATE checks the PIN and starts a short parent session; MODE_CREATE sets a new PIN.
 * Finishes with RESULT_OK on success.
 */
public class PinActivity extends Activity {

    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_REASON = "reason";
    public static final String MODE_GATE = "gate";
    public static final String MODE_CREATE = "create";

    private KidsStore store;
    private PinPadView pad;
    private String firstEntry;
    private int wrong;
    private long cooldownUntil;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = KidsStore.get(this);
        String mode = getIntent().getStringExtra(EXTRA_MODE);
        boolean create = MODE_CREATE.equals(mode) || !store.hasPin();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);

        LinearLayout col = Ui.vbox(this, 16);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        String reason = getIntent().getStringExtra(EXTRA_REASON);
        TextView head = Ui.text(this, reason != null ? reason : "Parent controls", 16, Ui.MUTED, false);
        col.addView(head, Ui.wrap());

        pad = new PinPadView(this,
                create ? "Create a parent PIN" : "Parent PIN",
                create ? "Choose 4 digits the kids don't know" : "Enter your PIN to continue");
        pad.setListener(create ? this::onCreatePin : this::onGatePin);
        col.addView(pad, Ui.wrap());

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        root.addView(col, lp);
        setContentView(root);
        pad.post(pad::focusFirst);
        setResult(RESULT_CANCELED);
    }

    private void onGatePin(String pin) {
        long now = SystemClock.uptimeMillis();
        if (now < cooldownUntil) {
            pad.showError("Too many tries. Wait a moment.");
            return;
        }
        if (store.checkPin(pin)) {
            store.startParentSession();
            KidsGuard.refresh();
            setResult(RESULT_OK);
            finish();
        } else if (++wrong >= 3) {
            wrong = 0;
            cooldownUntil = now + 30_000;
            pad.showError("Wrong PIN. Try again in 30 seconds.");
        } else {
            pad.showError("Wrong PIN — try again");
        }
    }

    private void onCreatePin(String pin) {
        if (firstEntry == null) {
            firstEntry = pin;
            pad.setTexts("Confirm your PIN", "Enter the same 4 digits again");
            pad.reset();
            return;
        }
        if (firstEntry.equals(pin)) {
            store.setPin(pin);
            store.startParentSession();
            setResult(RESULT_OK);
            finish();
        } else {
            firstEntry = null;
            pad.setTexts("Create a parent PIN", "Choose 4 digits the kids don't know");
            pad.showError("The PINs didn't match — start again");
            pad.reset();
        }
    }
}
