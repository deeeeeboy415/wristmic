package com.wristmic.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManager;

/**
 * Main screen. Hold the Back button (or the circle) to talk; let go to send.
 * A short tap on the circle talks hands-free until the next tap or Back press.
 * A short Back press while idle closes the app as usual.
 */
public class MainActivity extends Activity implements Engine.Ui {
    private static final int REQ_MIC = 1;
    /** Set on launches from the talk button, which starts talking itself. */
    static final String EXTRA_FROM_BUTTON = "com.wristmic.app.FROM_BUTTON";
    /** True while the main screen is in front (read by ButtonService). */
    static volatile boolean onScreen;

    private Engine engine;
    private MicView view;
    private boolean backDown, keyDown, touchDown, settingsDown;
    private boolean freshLaunch, pendingAutoStart, askedForMic;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        engine = Engine.get(this);
        view = new MicView(this);
        view.setOnTouchListener((v, e) -> onTouch(e));
        setContentView(view);
        freshLaunch = saved == null && !getIntent().getBooleanExtra(EXTRA_FROM_BUTTON, false);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getBooleanExtra(EXTRA_FROM_BUTTON, false)) return;   // the talk button handles itself
        if (!engine.prefs.autoStart()) return;
        // Opened again while talking (e.g. a second double-press of Home): that press means "stop",
        // and hiding the screen for the relaunch already sent the dictation.
        if (engine.justStoppedByHiding() || engine.isTalking()) {
            engine.stopTalking();
        } else {
            pendingAutoStart = true;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        onScreen = true;
        engine.attach(this);
        if (hasMic()) {
            engine.resume(this);
            if (freshLaunch || pendingAutoStart) {
                boolean auto = engine.prefs.autoStart();
                freshLaunch = false;
                pendingAutoStart = false;
                if (auto) engine.startHandsFree();
            }
        } else if (!askedForMic) {
            askedForMic = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        }
        render();
    }

    @Override
    protected void onPause() {
        onScreen = false;
        backDown = keyDown = touchDown = settingsDown = false;
        engine.pause();
        engine.detach(this);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        render();   // onResume follows and starts the mic if it was allowed
    }

    @Override
    public void render() {
        Engine.Snapshot s = engine.snapshot();
        if (s.talk != Engine.IDLE || s.typing || s.waitingForPc) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        view.show(s, hasMic());
    }

    // --------------------------------------------------------------- buttons

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        boolean up = e.getAction() == KeyEvent.ACTION_UP;
        String line = ButtonService.describe(e);
        android.util.Log.i(ButtonService.TAG, "app " + line);
        if (code != KeyEvent.KEYCODE_BACK) engine.noteKey("app: " + line);

        if (code == KeyEvent.KEYCODE_BACK) {
            if (down && e.getRepeatCount() == 0) {
                if (!hasMic()) {
                    finish();
                    return true;
                }
                backDown = true;
                engine.pressDown();
            } else if (up && backDown) {
                backDown = false;
                if (engine.pressUp(false) == Engine.RELEASE_TAP) finish();
            }
            return true;
        }
        if (isTalkKey(code)) {
            if (down && e.getRepeatCount() == 0) {
                keyDown = true;
                engine.holdDown();
            } else if (up && keyDown) {
                keyDown = false;
                engine.pressUp(false);
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    /** Extra hardware buttons (stem keys, or a Quick button if Samsung passes it to apps). */
    static boolean isTalkKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_STEM_PRIMARY:
            case KeyEvent.KEYCODE_STEM_1:
            case KeyEvent.KEYCODE_STEM_2:
            case KeyEvent.KEYCODE_STEM_3:
            case KeyEvent.KEYCODE_UNKNOWN:
                return true;
            default:
                return code >= 285;   // vendor keys beyond the standard keyboard set
        }
    }

    // ---------------------------------------------------------------- screen

    private boolean onTouch(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (view.isSettingsHit(x, y)) {
                    settingsDown = true;
                    return true;
                }
                if (!view.isButtonHit(x, y)) return false;
                if (!hasMic()) {
                    askForMicAgain();
                    return true;
                }
                touchDown = true;
                engine.pressDown();
                return true;
            case MotionEvent.ACTION_UP:
                if (settingsDown) {
                    settingsDown = false;
                    if (view.isSettingsHit(x, y)) startActivity(new Intent(this, SettingsActivity.class));
                    return true;
                }
                if (touchDown) {
                    touchDown = false;
                    engine.pressUp(true);
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                settingsDown = false;
                if (touchDown) {
                    touchDown = false;
                    engine.pressUp(false);
                }
                return true;
            default:
                return touchDown || settingsDown;
        }
    }

    private boolean hasMic() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void askForMicAgain() {
        if (askedForMic && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            // Android won't ask again; open the app's settings page instead.
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
                return;
            } catch (RuntimeException ignored) {
                // fall through and try the prompt
            }
        }
        askedForMic = true;
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
    }
}
