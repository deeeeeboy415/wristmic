package com.wristmic.app;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

/**
 * The Galaxy Watch Quick button, as push-to-talk.
 *
 * When the watch's Quick button setting (Settings.Global "setting_action_button_press")
 * names this service, Samsung's system starts it on every press with ACTION_KEY_EVENT:
 * 0 = pressed, 1 = released, 2 = short press finished, 3 = long press (still held).
 * Pressed opens WristMic if needed and starts listening; released sends.
 *
 * Not exported: only WristMic itself and the system can start it.
 */
public class QuickButtonService extends Service {
    static final String EXTRA_EVENT = "ACTION_KEY_EVENT";
    static final String EXTRA_COUNT = "ACTION_KEY_COUNT";
    static final int PRESSED = 0, RELEASED = 1;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        int event = intent == null ? -1 : intent.getIntExtra(EXTRA_EVENT, -1);
        int count = intent == null ? -1 : intent.getIntExtra(EXTRA_COUNT, -1);
        Log.i(ButtonService.TAG, "quick button event " + event + " count " + count);
        Engine engine = Engine.get(this);
        engine.quickButtonEvent(event);

        if (event == PRESSED) {
            boolean onScreen = MainActivity.onScreen;
            if (!onScreen) {
                try {
                    startActivity(new Intent(this, MainActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                            .putExtra(MainActivity.EXTRA_FROM_BUTTON, true));
                } catch (RuntimeException e) {
                    Log.w(ButtonService.TAG, "couldn't open WristMic: " + e);
                }
            }
            engine.talkButtonDown(onScreen);
        } else if (event == RELEASED) {
            engine.talkButtonUp();
        }
        stopSelf(startId);
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
