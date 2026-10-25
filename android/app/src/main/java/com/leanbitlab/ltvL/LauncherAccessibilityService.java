package com.leanbitlab.ltvL;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import com.leanbitlab.ltvL.kids.KidsGuard;

public class LauncherAccessibilityService extends AccessibilityService {

    private KidsGuard guard;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        guard = new KidsGuard(this);
        guard.start();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (guard != null) guard.onAccessibilityEvent(event);
    }

    @Override
    public void onInterrupt() {
        // Not used
    }

    @Override
    public void onDestroy() {
        if (guard != null) guard.stop();
        guard = null;
        super.onDestroy();
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (guard != null && guard.onKey(event)) return true;
        if (event.getKeyCode() == KeyEvent.KEYCODE_HOME) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                Intent intent = new Intent(this, MainActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(intent);
            }
            return true; // Consume Home key event
        }
        return super.onKeyEvent(event);
    }
}
