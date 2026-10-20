package com.wristmic.app;

import android.content.Context;
import android.content.SharedPreferences;

final class Prefs {
    private final SharedPreferences sp;

    Prefs(Context c) {
        sp = c.getSharedPreferences("wristmic", Context.MODE_PRIVATE);
    }

    /** Pairing key shared with the PC receiver, as hex; empty until paired. */
    String token() {
        return sp.getString("token", "");
    }

    void setToken(String hex) {
        sp.edit().putString("token", hex).apply();
    }

    /** PC address from pairing, discovery or Settings; empty until then. */
    String host() {
        return sp.getString("host", "");
    }

    int port() {
        return sp.getInt("port", Config.PORT);
    }

    void setPc(String host, int port) {
        sp.edit().putString("host", host).putInt("port", port).apply();
    }

    boolean autoStart() {
        return sp.getBoolean("autoStart", false);
    }

    void setAutoStart(boolean on) {
        sp.edit().putBoolean("autoStart", on).apply();
    }
}
