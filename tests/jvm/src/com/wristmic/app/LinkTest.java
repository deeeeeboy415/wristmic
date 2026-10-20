package com.wristmic.app;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Runs the watch app's real Link/Proto code against the real Python receiver (--simulate).
 * Start it with tests/jvm/run.sh from anywhere.
 */
public class LinkTest {
    static final Path ROOT = Paths.get(System.getProperty("wristmic.root", "../.."));
    static final Path LOG;
    static final Path CONFIG;
    static final String TOKEN = "00112233445566778899aabbccddeeff";
    static final String OTHER_TOKEN = "ffeeddccbbaa99887766554433221100";
    static {
        try {
            Path tmp = Files.createTempDirectory("wristmic-test");
            LOG = tmp.resolve("receiver.log");
            CONFIG = tmp.resolve("wristmic_config.json");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
    static Process receiver;
    static final BlockingQueue<String> events = new LinkedBlockingQueue<>();
    static int failures = 0;

    static class Ctx extends Context {
        final ConnectivityManager cm = new ConnectivityManager();
        final Map<String, Object> store = new ConcurrentHashMap<>();
        final SharedPreferences prefs = new SharedPreferences() {
            public String getString(String k, String d) { Object v = store.get(k); return v == null ? d : (String) v; }
            public int getInt(String k, int d) { Object v = store.get(k); return v == null ? d : (Integer) v; }
            public boolean getBoolean(String k, boolean d) { Object v = store.get(k); return v == null ? d : (Boolean) v; }
            public Editor edit() {
                return new Editor() {
                    public Editor putString(String k, String v) { store.put(k, v); return this; }
                    public Editor putInt(String k, int v) { store.put(k, v); return this; }
                    public Editor putBoolean(String k, boolean v) { store.put(k, v); return this; }
                    public void apply() {}
                };
            }
        };
        public Context getApplicationContext() { return this; }
        public Object getSystemService(String n) { return cm; }
        public ContentResolver getContentResolver() { return new ContentResolver(); }
        public SharedPreferences getSharedPreferences(String name, int mode) { return prefs; }
    }

    static void startReceiver() throws IOException, InterruptedException {
        startReceiver(TOKEN);
    }

    static void startReceiver(String token) throws IOException, InterruptedException {
        Files.write(CONFIG, ("{\"token\": \"" + token + "\"}").getBytes(StandardCharsets.UTF_8));
        ProcessBuilder pb = new ProcessBuilder(System.getProperty("wristmic.python", "python3"), "-u",
                ROOT.resolve("pc/wristmic_receiver.py").toString(), "--simulate");
        pb.environment().put("WRISTMIC_CONFIG", CONFIG.toString());
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(LOG.toFile()));
        receiver = pb.start();
        Thread.sleep(1200);
    }

    static void stopReceiver() throws InterruptedException {
        receiver.destroy();
        receiver.waitFor(5, TimeUnit.SECONDS);
    }

    static int count(String needle) throws IOException {
        int n = 0;
        for (String line : Files.readAllLines(LOG, StandardCharsets.UTF_8)) if (line.contains(needle)) n++;
        return n;
    }

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + what);
        if (!ok) failures++;
    }

    static boolean waitState(Link link, int want, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (link.state() == want) return true;
            Thread.sleep(50);
        }
        return false;
    }

    static boolean waitNotState(Link link, int not, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (link.state() != not) return true;
            Thread.sleep(50);
        }
        return false;
    }

    static String waitEvent(String prefix, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            String e = events.poll(100, TimeUnit.MILLISECONDS);
            if (e != null && e.startsWith(prefix)) return e;
        }
        return null;
    }

    static Utterance talk(Link link, int id, int preroll, int frames, boolean realtime) throws InterruptedException {
        Utterance u = new Utterance(id);
        byte[] f = new byte[Config.FRAME_BYTES];
        for (int i = 0; i < preroll; i++) link.addFrame(u, f);
        link.enqueue(u);
        for (int i = 0; i < frames; i++) {
            link.addFrame(u, f);
            if (realtime) Thread.sleep(20);
        }
        return u;
    }

    public static void main(String[] args) throws Exception {
        Files.deleteIfExists(LOG);
        startReceiver();
        Ctx ctx = new Ctx();
        Prefs prefs = new Prefs(ctx);
        prefs.setPc("127.0.0.1", Config.PORT);
        Link link = new Link(ctx, prefs, new Link.Listener() {
            public void onLinkState() { events.add("state"); }
            public void onDictationDone(String info) { events.add("done:" + info); }
            public void onDictationDropped() { events.add("dropped"); }
        });

        System.out.println("-- not paired yet");
        Proto.setKey("");
        link.start();
        check(waitState(link, Link.UNPAIRED, 5000), "without a pairing key the watch waits instead of dialing");
        check(count("Watch connected") == 0, "nothing connects before pairing");

        System.out.println("-- pairing");
        ctx.store.put("token", TOKEN);   // what PairReceiver stores
        Proto.setKey(TOKEN);
        link.reconnect();
        check(waitState(link, Link.CONNECTED, 15000), "watch connects and passes the pairing check");
        check("via phone".equals(link.route()), "route shows 'via phone' without Wi-Fi (" + link.route() + ")");
        check(ctx.cm.requests == 1, "asked Wear OS for Wi-Fi once");

        System.out.println("-- hold to talk");
        events.clear();
        Utterance u = talk(link, 1, Config.PREROLL_FRAMES, 100, true);
        link.finish(u);
        String done = waitEvent("done:", 10000);
        check("done:2.2".equals(done), "2 s + pre-roll delivered; PC reports " + done);

        System.out.println("-- a tap sends nothing");
        Utterance tap = new Utterance(2);
        for (int i = 0; i < 10; i++) link.addFrame(tap, new byte[Config.FRAME_BYTES]);   // never enqueued
        check(waitEvent("done:", 2500) == null, "no dictation for a tap");

        System.out.println("-- idle keepalive");
        Thread.sleep(11000);
        check(link.state() == Link.CONNECTED, "link stays up while idle (pings answered)");

        System.out.println("-- PC goes away; dictation is dropped, never typed later");
        stopReceiver();
        check(waitNotState(link, Link.CONNECTED, 15000), "notices the PC went away");
        events.clear();
        Utterance late = talk(link, 3, 0, 50, false);
        link.finish(late);
        check(waitEvent("dropped", Config.STALE_MS + 12000) != null, "unsent dictation dropped after the stale limit");
        int before = count("Listening...");
        startReceiver();
        check(waitState(link, Link.CONNECTED, 25000), "reconnects when the PC comes back");
        Thread.sleep(2500);
        check(count("Listening...") == before, "the stale dictation was never typed");

        System.out.println("-- talking while the PC reconnects");
        stopReceiver();
        check(waitNotState(link, Link.CONNECTED, 15000), "disconnected again");
        events.clear();
        Utterance gap = talk(link, 4, 0, 40, true);   // recorded while offline
        startReceiver();
        byte[] f = new byte[Config.FRAME_BYTES];
        for (int i = 0; i < 60; i++) { link.addFrame(gap, f); Thread.sleep(20); }
        link.finish(gap);
        String gapDone = waitEvent("done:", 30000);
        check("done:2.0".equals(gapDone), "audio buffered while offline is delivered in full (" + gapDone + ")");

        System.out.println("-- Wi-Fi route with discovery");
        link.stop();
        check(ctx.cm.releases >= 1, "stop() hands Wi-Fi back to Wear OS");
        ctx.store.clear();   // forget the PC; built-in addresses aren't reachable here
        ctx.cm.offerWifi = true;
        link.start();
        check(waitState(link, Link.CONNECTED, 25000), "finds the PC by broadcast and connects over Wi-Fi");
        check("Wi-Fi".equals(link.route()), "route shows Wi-Fi (" + link.route() + ")");
        check("127.0.0.1".equals(prefs.host()), "remembers the address it found (" + prefs.host() + ")");
        events.clear();
        Utterance w = talk(link, 5, 0, 50, true);
        link.finish(w);
        check("done:1.0".equals(waitEvent("done:", 10000)), "dictation over the Wi-Fi route");

        System.out.println("-- wrong pairing key is refused");
        link.stop();
        stopReceiver();
        startReceiver(OTHER_TOKEN);
        prefs.setPc("127.0.0.1", Config.PORT);
        ctx.cm.offerWifi = false;
        link.start();
        check(!waitState(link, Link.CONNECTED, 6000), "watch can't connect to a receiver with a different key");
        check(count("wrong pairing key") > 0, "receiver logs the refusal");
        link.stop();
        stopReceiver();
        check(count("[sim] Left Ctrl down") == count("[sim] Left Ctrl up"), "every key press was released");
        System.out.println(failures == 0 ? "\nALL PASSED" : "\n" + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }
}
