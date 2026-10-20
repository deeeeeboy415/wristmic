package com.wristmic.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Pairs the watch with a PC receiver. The receiver prints the exact command
 * (python wristmic_receiver.py --pair); it looks like:
 *
 *   adb shell am broadcast --include-stopped-packages -n com.wristmic.app/.PairReceiver
 *       --es token HEX --es host PC_ADDRESS --ei port 47800
 *
 * The manifest requires senders to hold android.permission.DUMP, which the adb shell
 * has and ordinary apps don't, so other apps on the watch can't re-pair it.
 */
public class PairReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String token = intent.getStringExtra("token");
        token = token == null ? "" : token.trim();
        if (!Proto.validKey(token)) {
            setResult(0, "WristMic: missing or invalid token (32 to 128 hex digits)", null);
            return;
        }
        Prefs prefs = new Prefs(context);
        prefs.setToken(token.toLowerCase(java.util.Locale.ROOT));

        String host = intent.getStringExtra("host");
        host = host == null ? "" : host.trim();
        int port = intent.getIntExtra("port", Config.PORT);
        if (!host.isEmpty()) {
            prefs.setPc(host, port);
        } else if (port != Config.PORT) {
            prefs.setPc(prefs.host(), port);
        }

        Engine.get(context).paired();
        String msg = "WristMic paired" + (host.isEmpty() ? "" : " with " + host + ":" + port);
        Log.i(ButtonService.TAG, msg);
        setResult(1, msg, null);
    }
}
