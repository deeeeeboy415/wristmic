package com.wristmic.app;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Lets the Quick button open WristMic from any screen.
 *
 * Android only lets an app bring itself to the front from the background in a few
 * cases; having an active accessibility service is one. This service asks for no
 * events and no key presses, and never reads the screen: it exists so that
 * QuickButtonService can open WristMic when the Quick button is pressed.
 */
public class ButtonService extends AccessibilityService {
    static final String TAG = "WristMicKeys";
    static volatile boolean connected;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        connected = true;
        Log.i(TAG, "button service connected");
        Engine.get(this).render();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        connected = false;
        Engine.get(this).render();
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // not used
    }

    @Override
    public void onInterrupt() {
        // nothing to stop
    }

    static String describe(KeyEvent e) {
        String act = e.getAction() == KeyEvent.ACTION_DOWN ? "down" : e.getAction() == KeyEvent.ACTION_UP ? "up" : "other";
        return act + " " + keyName(e.getKeyCode(), e.getScanCode())
                + (e.getRepeatCount() > 0 ? " repeat " + e.getRepeatCount() : "")
                + " scan " + e.getScanCode();
    }

    /** "STEM_1 (265)", or "unnamed key, scan 249" for a key Android has no name for. */
    static String keyName(int code, int scan) {
        if (code == KeyEvent.KEYCODE_UNKNOWN) return "unnamed key, scan " + scan;
        String n = KeyEvent.keyCodeToString(code);
        if (n.startsWith("KEYCODE_")) n = n.substring(8);
        return n + " (" + code + ")";
    }
}
