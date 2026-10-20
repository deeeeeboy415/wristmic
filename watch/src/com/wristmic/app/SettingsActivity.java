package com.wristmic.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** PC address, "Find PC on Wi-Fi", the start-talking-on-open option, and Quick button status. */
public class SettingsActivity extends Activity {
    private Engine engine;
    private TextView status, talkStatus, keysLog;
    private Button setTalkButton;
    private EditText address;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean searching;
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            if (!searching) status.setText(engine.linkSummary());
            refreshTalkButton();
            handler.postDelayed(this, 400);
        }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        engine = Engine.get(this);
        int side = dp(26);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.BLACK);
        scroll.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(side, dp(34), side, dp(60));
        scroll.addView(col, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        col.addView(label("Settings", 17, Color.WHITE, true));
        status = label("", 12, 0xFF8E8E93, false);
        col.addView(status);

        col.addView(label("PC address", 12, 0xFF8E8E93, false), topMargin(12));
        address = new EditText(this);
        address.setSingleLine(true);
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setGravity(Gravity.CENTER);
        address.setTextColor(Color.WHITE);
        address.setText(currentAddress());
        col.addView(address, matchWidth(0));

        col.addView(button("Save address", v -> saveAddress()), matchWidth(4));
        col.addView(button("Find PC on Wi-Fi", v -> findPc()), matchWidth(4));

        CheckBox auto = new CheckBox(this);
        auto.setText("Start talking when opened");
        auto.setTextColor(Color.WHITE);
        auto.setChecked(engine.prefs.autoStart());
        auto.setOnCheckedChangeListener((b, on) -> engine.prefs.setAutoStart(on));
        col.addView(auto, topMargin(10));

        col.addView(label("Quick button", 13, Color.WHITE, true), topMargin(16));
        talkStatus = label("", 12, 0xFF8E8E93, false);
        col.addView(talkStatus);
        setTalkButton = button("Accessibility settings", v -> openAccessibility());
        col.addView(setTalkButton, matchWidth(4));
        keysLog = label("", 11, 0xFF8E8E93, false);
        col.addView(keysLog, topMargin(8));

        col.addView(label("WristMic 1.4", 11, 0xFF636366, false), topMargin(8));

        setContentView(scroll);
        scroll.requestFocus();   // lets the bezel scroll the page
    }

    @Override
    protected void onResume() {
        super.onResume();
        engine.keepLinkUp();
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        engine.pause();
        super.onPause();
    }

    private void refreshTalkButton() {
        long last = engine.lastQuickPress();
        String quick = last == 0 ? "No Quick button press received yet."
                : "Working. Last press " + android.text.format.DateFormat.getTimeFormat(this).format(new java.util.Date(last)) + ".";
        if (!ButtonService.connected) {
            talkStatus.setText(quick + "\nTurn on WristMic button in Accessibility so the Quick button can open WristMic from any screen.");
            setTalkButton.setVisibility(View.VISIBLE);
        } else {
            talkStatus.setText(quick + "\nHold the Quick button to talk, from any screen.");
            setTalkButton.setVisibility(View.GONE);
        }
        String seen = engine.keysSeen();
        keysLog.setText(seen.isEmpty() ? "" : "Buttons seen, newest first:\n" + seen);
    }

    private void openAccessibility() {
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (RuntimeException e) {
            toast("Open Settings > Accessibility on the watch");
        }
    }

    private String currentAddress() {
        String host = engine.prefs.host();
        int port = engine.prefs.port();
        return host.isEmpty() || port == Config.PORT ? host : host + ":" + port;
    }

    private void saveAddress() {
        String t = address.getText().toString().trim();
        if (t.isEmpty()) {
            toast("Enter the PC's address");
            return;
        }
        String host = t;
        int port = Config.PORT;
        int colon = t.lastIndexOf(':');
        if (colon > 0) {
            host = t.substring(0, colon);
            try {
                port = Integer.parseInt(t.substring(colon + 1));
            } catch (NumberFormatException e) {
                toast("The port must be a number");
                return;
            }
        }
        engine.prefs.setPc(host, port);
        engine.reconnect();
        toast("Saved");
    }

    private void findPc() {
        if (!Proto.hasKey()) {
            status.setText("Pair first: on the PC run python wristmic_receiver.py --pair");
            return;
        }
        searching = true;
        status.setText("Looking on Wi-Fi...");
        engine.findPc(hostPort -> runOnUiThread(() -> {
            searching = false;
            if (isFinishing()) return;
            if (hostPort == null) {
                status.setText("Not found. Is the watch on the PC's Wi-Fi, and WristMic running on the PC?");
            } else {
                String suffix = ":" + Config.PORT;
                address.setText(hostPort.endsWith(suffix)
                        ? hostPort.substring(0, hostPort.length() - suffix.length()) : hostPort);
                status.setText("Found the PC");
            }
        }));
    }

    private TextView label(String text, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    private Button button(String text, View.OnClickListener onClick) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(onClick);
        return b;
    }

    private LinearLayout.LayoutParams matchWidth(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp);
        return lp;
    }

    private LinearLayout.LayoutParams topMargin(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp);
        return lp;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }
}
