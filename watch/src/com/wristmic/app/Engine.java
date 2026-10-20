package com.wristmic.app;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

import java.util.ArrayDeque;

/** App-wide state: the microphone, the link to the PC, and the dictation in progress. */
final class Engine implements Capture.Sink, Link.Listener {
    interface Ui {
        void render();
    }

    static final int IDLE = 0, PRESSING = 1, TALKING = 2;
    static final int RELEASE_NONE = 0, RELEASE_SENT = 1, RELEASE_TAP = 2, RELEASE_HANDSFREE = 3;

    /** What the screen shows, copied so drawing never holds locks. */
    static final class Snapshot {
        int link;
        String route;
        int talk;
        boolean handsFree;
        float level;
        boolean typing;
        boolean waitingForPc;
        String flash;
        boolean flashError;
    }

    private static Engine instance;

    static synchronized Engine get(Context c) {
        if (instance == null) instance = new Engine(c.getApplicationContext());
        return instance;
    }

    final Prefs prefs;
    private final Context app;
    private final Link link;
    private final Capture capture;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Vibrator vibrator;

    private Ui ui;

    // guarded by this
    private int talk = IDLE;
    private boolean handsFree;
    private boolean pressDuringTalk;
    private Utterance current;
    private int nextId = 1;
    private final ArrayDeque<byte[]> preroll = new ArrayDeque<>();

    // main thread only
    private int awaiting;          // dictations sent, waiting for the PC to type them
    private long awaitingSince;
    private String flash = "";
    private boolean flashError;
    private long flashUntil;
    private long hiddenWhileTalkingAt;

    // talk button (ButtonService), main thread only
    private boolean pendingHold;   // pressed while WristMic was still coming on screen
    private final ArrayDeque<String> keysSeen = new ArrayDeque<>();
    private final Runnable pendingHoldExpired = () -> pendingHold = false;

    private volatile float level;
    private volatile long lastLevelPost;

    private final Runnable commit = this::commitPress;
    private final Runnable lingerOver = this::lingerOver;
    private final Runnable rerender = this::render;

    private Engine(Context app) {
        this.app = app;
        prefs = new Prefs(app);
        Proto.setKey(prefs.token());
        link = new Link(app, prefs, this);
        capture = new Capture(this);
        vibrator = vibratorOf(app);
    }

    @SuppressWarnings("deprecation")
    private static Vibrator vibratorOf(Context c) {
        if (Build.VERSION.SDK_INT >= 31) {
            VibratorManager vm = (VibratorManager) c.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            if (vm != null) return vm.getDefaultVibrator();
        }
        return (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);   // Wear OS 3 (API 30)
    }

    // --------------------------------------------------------- screen lifecycle

    void attach(Ui u) {
        ui = u;
    }

    void detach(Ui u) {
        if (ui == u) ui = null;
    }

    /** The app is on screen: connect, and keep the mic warm so the first word isn't lost. */
    void resume(Context c) {
        main.removeCallbacks(lingerOver);
        link.start();
        capture.start(c);
        if (pendingHold) {   // the talk button opened WristMic and is still held
            pendingHold = false;
            main.removeCallbacks(pendingHoldExpired);
            holdDown();
        }
        render();
    }

    /** Keep the connection without the mic (Settings screen). */
    void keepLinkUp() {
        main.removeCallbacks(lingerOver);
        link.start();
    }

    /** The app left the screen: send what was said, free the mic, disconnect a little later. */
    void pause() {
        boolean sent = false;
        main.removeCallbacks(commit);
        synchronized (this) {
            if (talk == PRESSING) {
                cancelPressLocked();
            } else if (talk == TALKING) {
                endTalkLocked();
                sent = true;
            }
        }
        if (sent) {
            hiddenWhileTalkingAt = SystemClock.elapsedRealtime();
            awaitStart();
        }
        capture.stop();
        main.removeCallbacks(lingerOver);
        main.postDelayed(lingerOver, Config.LINGER_MS);
    }

    private void lingerOver() {
        link.stopWhenIdle();
    }

    /** True right after the app was hidden mid-dictation (a second launch press means "stop"). */
    boolean justStoppedByHiding() {
        return SystemClock.elapsedRealtime() - hiddenWhileTalkingAt < 1500;
    }

    // ------------------------------------------------------------ presses (main thread)

    /** Back button or screen went down. Becomes hold-to-talk after HOLD_MS. */
    void pressDown() {
        synchronized (this) {
            if (talk == TALKING) {
                pressDuringTalk = true;
                return;
            }
            if (talk == PRESSING) return;
            if (!micReadyLocked()) {
                showFlash("Allow the microphone", true);
                return;
            }
            current = newUtteranceLocked();
            talk = PRESSING;
        }
        main.postDelayed(commit, Config.HOLD_MS);
        render();
    }

    /** A dedicated talk key went down: talk right away. */
    void holdDown() {
        main.removeCallbacks(commit);
        synchronized (this) {
            if (talk == TALKING) {
                pressDuringTalk = true;
                return;
            }
            if (talk == IDLE) {
                if (!micReadyLocked()) {
                    showFlash("Allow the microphone", true);
                    return;
                }
                current = newUtteranceLocked();
            }
            talk = TALKING;
            handsFree = false;
            link.enqueue(current);
        }
        buzz(25);
        render();
    }

    private void commitPress() {
        synchronized (this) {
            if (talk != PRESSING || current == null) return;
            talk = TALKING;
            handsFree = false;
            link.enqueue(current);
        }
        buzz(25);
        render();
    }

    /**
     * The button or screen came up. A short tap on the screen starts hands-free talking
     * (tapStartsHandsFree); a short Back press is reported as RELEASE_TAP so the app can close.
     */
    int pressUp(boolean tapStartsHandsFree) {
        main.removeCallbacks(commit);
        int r = RELEASE_NONE;
        synchronized (this) {
            if (talk == TALKING) {
                if (!handsFree || pressDuringTalk) {
                    endTalkLocked();
                    r = RELEASE_SENT;
                }
                pressDuringTalk = false;
            } else if (talk == PRESSING) {
                if (tapStartsHandsFree) {
                    talk = TALKING;
                    handsFree = true;
                    link.enqueue(current);
                    r = RELEASE_HANDSFREE;
                } else {
                    cancelPressLocked();
                    r = RELEASE_TAP;
                }
            } else if (!tapStartsHandsFree) {
                r = RELEASE_TAP;   // the press never started (e.g. mic busy): Back still closes the app
            }
        }
        if (r == RELEASE_SENT) {
            awaitStart();
            buzzTwice();
        } else if (r == RELEASE_HANDSFREE) {
            buzz(25);
        }
        render();
        return r;
    }

    void startHandsFree() {
        synchronized (this) {
            if (talk != IDLE || !micReadyLocked()) return;
            current = newUtteranceLocked();
            talk = TALKING;
            handsFree = true;
            link.enqueue(current);
        }
        buzz(25);
        render();
    }

    void stopTalking() {
        boolean sent = false;
        main.removeCallbacks(commit);
        synchronized (this) {
            if (talk == TALKING) {
                endTalkLocked();
                sent = true;
            } else if (talk == PRESSING) {
                cancelPressLocked();
            }
        }
        if (sent) {
            awaitStart();
            buzzTwice();
        }
        render();
    }

    synchronized boolean isTalking() {
        return talk == TALKING;
    }

    // ------------------------------------------------- talk button (main thread)

    /** The chosen watch button went down. Off screen, talking starts once WristMic is up. */
    void talkButtonDown(boolean onScreen) {
        if (onScreen) {
            holdDown();
            return;
        }
        pendingHold = true;
        main.removeCallbacks(pendingHoldExpired);
        main.postDelayed(pendingHoldExpired, 4000);
    }

    void talkButtonUp() {
        main.removeCallbacks(pendingHoldExpired);
        if (pendingHold) {   // let go before WristMic was on screen: nothing was recorded
            pendingHold = false;
            return;
        }
        pressUp(false);
    }

    /** Recent key presses, newest first, for the Settings screen. */
    void noteKey(String line) {
        keysSeen.addFirst(line);
        while (keysSeen.size() > 6) keysSeen.removeLast();
    }

    String keysSeen() {
        return String.join("\n", keysSeen);
    }

    private long lastQuickPress;   // wall-clock time of the last Quick button press, 0 = never

    void quickButtonEvent(int event) {
        if (event == QuickButtonService.PRESSED) lastQuickPress = System.currentTimeMillis();
        String what = event == 0 ? "pressed" : event == 1 ? "released" : event == 2 ? "short press"
                : event == 3 ? "long press" : "event " + event;
        noteKey("Quick button " + what);
    }

    long lastQuickPress() {
        return lastQuickPress;
    }

    // ------------------------------------------------------------------ helpers

    private boolean micReadyLocked() {
        return capture.isRunning() || capture.start(app);
    }

    private Utterance newUtteranceLocked() {
        Utterance u = new Utterance(nextId++);
        for (byte[] f : preroll) link.addFrame(u, f);
        preroll.clear();
        return u;
    }

    private void endTalkLocked() {
        Utterance u = current;
        current = null;
        talk = IDLE;
        handsFree = false;
        pressDuringTalk = false;
        if (u != null) link.finish(u);
    }

    /** A press that turned out to be a tap: nothing is sent, the audio goes back to pre-roll. */
    private void cancelPressLocked() {
        Utterance u = current;
        current = null;
        talk = IDLE;
        if (u == null) return;
        synchronized (link.lock) {
            int from = Math.max(0, u.frames.size() - Config.PREROLL_FRAMES);
            for (int i = from; i < u.frames.size(); i++) preroll.addLast(u.frames.get(i));
        }
    }

    private void awaitStart() {
        awaiting++;
        awaitingSince = SystemClock.elapsedRealtime();
        main.postDelayed(rerender, 20500);   // so a lost reply can't leave "Typing" up
    }

    private void showFlash(String text, boolean error) {
        flash = text;
        flashError = error;
        long ms = error ? 4000 : 2500;
        flashUntil = SystemClock.elapsedRealtime() + ms;
        main.postDelayed(rerender, ms + 50);
    }

    // -------------------------------------------------------------- mic frames

    @Override
    public void onFrame(byte[] f, float lvl) {
        level = lvl;
        boolean active;
        synchronized (this) {
            active = talk != IDLE;
            if (current != null) {
                link.addFrame(current, f);
            } else {
                preroll.addLast(f);
                while (preroll.size() > Config.PREROLL_FRAMES) preroll.removeFirst();
            }
        }
        long now = SystemClock.elapsedRealtime();
        if (active && now - lastLevelPost >= 50) {
            lastLevelPost = now;
            main.post(rerender);
        }
    }

    // ------------------------------------------------------- link callbacks (any thread)

    @Override
    public void onLinkState() {
        main.post(() -> {
            if (link.state() != Link.CONNECTED) awaiting = 0;
            render();
        });
    }

    @Override
    public void onDictationDone(final String info) {
        main.post(() -> {
            if (awaiting > 0) awaiting--;
            if (info.startsWith("error")) {
                showFlash("PC audio problem", true);
            } else {
                showFlash("Sent  " + info + " s", false);
            }
            render();
        });
    }

    @Override
    public void onDictationDropped() {
        main.post(() -> {
            if (awaiting > 0) awaiting--;
            showFlash("Not sent\nPC unreachable", true);
            buzz(250);
            render();
        });
    }

    // ----------------------------------------------------------- for the screens

    Snapshot snapshot() {
        Snapshot s = new Snapshot();
        synchronized (this) {
            s.talk = talk;
            s.handsFree = handsFree;
        }
        s.link = link.state();
        s.route = link.route();
        s.level = level;
        long now = SystemClock.elapsedRealtime();
        boolean waiting = awaiting > 0 && now - awaitingSince < 20000;
        s.typing = waiting && s.link == Link.CONNECTED;
        s.waitingForPc = waiting && s.link != Link.CONNECTED;
        if (now < flashUntil) {
            s.flash = flash;
            s.flashError = flashError;
        } else {
            s.flash = "";
        }
        return s;
    }

    String linkSummary() {
        switch (link.state()) {
            case Link.CONNECTED:
                return "Connected, " + link.route();
            case Link.UNPAIRED:
                return "Not paired. On the PC run: python wristmic_receiver.py --pair";
            default:
                return "Looking for the PC...";
        }
    }

    void findPc(Link.Found cb) {
        link.findPc(cb);
    }

    void reconnect() {
        link.reconnect();
    }

    /** PairReceiver stored a new pairing key: use it and dial the PC again. */
    void paired() {
        Proto.setKey(prefs.token());
        link.reconnect();
        main.post(() -> {
            showFlash("Paired with your PC", false);
            render();
        });
    }

    void render() {
        Ui u = ui;
        if (u != null) u.render();
    }

    private void buzz(long ms) {
        try {
            if (vibrator != null && vibrator.hasVibrator()) {
                vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            }
        } catch (RuntimeException ignored) {
            // haptics are optional
        }
    }

    private void buzzTwice() {
        try {
            if (vibrator != null && vibrator.hasVibrator()) {
                vibrator.vibrate(VibrationEffect.createWaveform(new long[]{0, 18, 70, 18}, -1));
            }
        } catch (RuntimeException ignored) {
            // haptics are optional
        }
    }
}
