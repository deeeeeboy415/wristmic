package com.wristmic.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/** The round main screen: link status on top, a big mic button, what's happening below. */
final class MicView extends View {
    private static final int TEAL = 0xFF2DD4BF;
    private static final int RED = 0xFFFF453A;
    private static final int AMBER = 0xFFFFB020;
    private static final int GREEN = 0xFF32D74B;
    private static final int GRAY = 0xFF636366;
    private static final int FILL = 0xFF1C1C1E;
    private static final int DIM = 0xFF8E8E93;

    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint title = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line1 = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line2 = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint footer = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private float cx, cy, r;
    private Engine.Snapshot s;
    private boolean micAllowed = true;

    MicView(Context c) {
        super(c);
        setBackgroundColor(Color.BLACK);
        ring.setStyle(Paint.Style.STROKE);
        glow.setStyle(Paint.Style.FILL);
        glyph.setStrokeCap(Paint.Cap.ROUND);
        for (Paint p : new Paint[]{title, line1, line2, footer}) {
            p.setTextAlign(Paint.Align.CENTER);
        }
        line1.setColor(Color.WHITE);
        line1.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        line2.setColor(DIM);
        footer.setColor(DIM);
    }

    void show(Engine.Snapshot snap, boolean allowed) {
        s = snap;
        micAllowed = allowed;
        invalidate();
    }

    boolean isButtonHit(float x, float y) {
        float dx = x - cx, dy = y - cy, reach = r * 1.35f;
        return dx * dx + dy * dy <= reach * reach;
    }

    boolean isSettingsHit(float x, float y) {
        return y > getHeight() * 0.85f && Math.abs(x - cx) < getWidth() * 0.25f;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        float m = Math.min(w, h);
        cx = w / 2f;
        cy = h * 0.43f;
        r = m * 0.19f;
        ring.setStrokeWidth(r * 0.08f);
        title.setTextSize(m * 0.052f);
        line1.setTextSize(m * 0.068f);
        line2.setTextSize(m * 0.05f);
        footer.setTextSize(m * 0.047f);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (s == null) return;
        int h = getHeight();
        boolean talking = s.talk == Engine.TALKING;
        boolean connected = s.link == Link.CONNECTED;

        int accent;
        String main, sub;
        if (!micAllowed) {
            accent = GRAY;
            main = "Microphone is off";
            sub = "Tap the circle to allow";
        } else if (talking) {
            accent = RED;
            main = "Listening...";
            sub = s.handsFree ? "Tap or press Back to send" : "Let go to send";
        } else if (s.talk == Engine.PRESSING) {
            accent = RED;
            main = "Keep holding...";
            sub = "";
        } else if (!s.flash.isEmpty()) {
            accent = s.flashError ? RED : GREEN;
            int nl = s.flash.indexOf('\n');
            main = nl < 0 ? s.flash : s.flash.substring(0, nl);
            sub = nl < 0 ? "" : s.flash.substring(nl + 1);
        } else if (s.typing) {
            accent = AMBER;
            main = "Typing on PC...";
            sub = "";
        } else if (s.waitingForPc) {
            accent = AMBER;
            main = "Waiting for PC...";
            sub = "Sends when it connects";
        } else if (s.link == Link.UNPAIRED) {
            accent = AMBER;
            main = "Not paired yet";
            sub = "Run --pair on the PC";
        } else {
            accent = connected ? TEAL : GRAY;
            main = "Hold Back to talk";
            sub = "or hold the circle";
        }

        if (talking) {   // a halo that grows with your voice
            glow.setColor(RED);
            glow.setAlpha(60);
            c.drawCircle(cx, cy, r * 1.08f + s.level * r * 0.32f, glow);
        }
        fill.setColor(talking ? RED : FILL);
        c.drawCircle(cx, cy, r, fill);
        ring.setColor(accent);
        c.drawCircle(cx, cy, r, ring);
        drawMic(c, micAllowed ? Color.WHITE : GRAY);

        title.setColor(connected ? DIM : AMBER);
        String top = connected ? "PC  \u00b7  " + s.route
                : s.link == Link.UNPAIRED ? "Pair with your PC" : "Looking for your PC...";
        c.drawText(top, cx, h * 0.155f, title);
        float y1 = cy + r + h * 0.105f;
        c.drawText(main, cx, y1, line1);
        if (!sub.isEmpty()) c.drawText(sub, cx, y1 + h * 0.075f, line2);
        c.drawText("Settings", cx, h * 0.925f, footer);
    }

    private void drawMic(Canvas c, int color) {
        glyph.setColor(color);
        float w = r * 0.30f, h = r * 0.56f;
        float top = cy - r * 0.38f;
        glyph.setStyle(Paint.Style.FILL);
        rect.set(cx - w / 2, top, cx + w / 2, top + h);
        c.drawRoundRect(rect, w / 2, w / 2, glyph);
        glyph.setStyle(Paint.Style.STROKE);
        glyph.setStrokeWidth(r * 0.07f);
        float ar = w * 0.95f;
        float acy = top + h * 0.52f;
        rect.set(cx - ar, acy - ar, cx + ar, acy + ar);
        c.drawArc(rect, 0, 180, false, glyph);
        float stemTop = acy + ar, stemBottom = stemTop + r * 0.17f;
        c.drawLine(cx, stemTop, cx, stemBottom, glyph);
        c.drawLine(cx - w * 0.6f, stemBottom, cx + w * 0.6f, stemBottom, glyph);
    }
}
