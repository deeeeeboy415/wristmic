package com.wristmic.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Keeps a connection to the PC receiver and sends dictations over it, oldest first.
 *
 * Tries the watch's Wi-Fi first (it asks Wear OS to bring Wi-Fi up), and falls back to
 * whatever network the watch has, usually Bluetooth through the phone. Audio recorded
 * while the link is still coming up is kept and sent once it connects.
 */
final class Link {
    interface Listener {
        void onLinkState();
        void onDictationDone(String info);
        void onDictationDropped();
    }

    interface Found {
        void onResult(String hostPort);   // null when nothing answered
    }

    static final int STOPPED = 0, SEARCHING = 1, CONNECTED = 2, UNPAIRED = 3;
    private static final String TAG = "WristMic";

    final Object lock = new Object();

    private final Context app;
    private final Prefs prefs;
    private final Listener listener;
    private final ConnectivityManager cm;
    private final SecureRandom rng = new SecureRandom();

    private final ArrayDeque<Utterance> outbox = new ArrayDeque<>();   // guarded by lock
    private int awaitingDone;                                          // guarded by lock

    private volatile boolean running;
    private volatile int generation;
    private volatile boolean stopWhenIdle;
    private volatile Network wifi;
    private ConnectivityManager.NetworkCallback wifiCallback;
    private volatile Socket socket;
    private volatile boolean viaWifi;
    private volatile int state = STOPPED;
    private volatile String route = "";
    private volatile long noWifiUpgradeUntil;

    Link(Context context, Prefs prefs, Listener listener) {
        this.app = context.getApplicationContext();
        this.prefs = prefs;
        this.listener = listener;
        this.cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    int state() {
        return state;
    }

    String route() {
        return route;
    }

    // ------------------------------------------------------------------ lifecycle

    synchronized void start() {
        stopWhenIdle = false;
        if (running) return;
        running = true;
        final int gen = ++generation;
        requestWifi();
        new Thread(() -> loop(gen), "wristmic-link").start();
    }

    synchronized void stop() {
        if (!running) return;
        running = false;
        generation++;
        releaseWifi();
        closeQuietly(socket);
        synchronized (lock) {
            lock.notifyAll();
        }
        setState(STOPPED, "");
    }

    /** Disconnect once everything queued has gone out. */
    void stopWhenIdle() {
        boolean idle;
        synchronized (lock) {
            idle = outbox.isEmpty();
            if (!idle) {
                stopWhenIdle = true;
                lock.notifyAll();
            }
        }
        if (idle) stop();
    }

    /** Drop the current connection and dial again, e.g. after the PC address changed. */
    void reconnect() {
        noWifiUpgradeUntil = 0;
        closeQuietly(socket);
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    // ------------------------------------------------------------- dictations

    void enqueue(Utterance u) {
        synchronized (lock) {
            if (!u.queued) {
                u.queued = true;
                outbox.addLast(u);
            }
            lock.notifyAll();
        }
    }

    void addFrame(Utterance u, byte[] frame) {
        synchronized (lock) {
            if (u.frames.size() < Config.MAX_FRAMES) u.frames.add(frame);
            if (u.queued) lock.notifyAll();
        }
    }

    void finish(Utterance u) {
        synchronized (lock) {
            u.stopped = true;
            u.stopTime = SystemClock.elapsedRealtime();
            lock.notifyAll();
        }
    }

    /** Look for the PC on Wi-Fi now (Settings screen). Calls back on a background thread. */
    void findPc(final Found cb) {
        new Thread(() -> {
            long until = SystemClock.elapsedRealtime() + 8000;
            Network w = wifi;
            while (w == null && SystemClock.elapsedRealtime() < until) {
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    break;
                }
                w = wifi;
            }
            InetSocketAddress hit = w == null ? null : discover(w);
            if (hit != null) {
                prefs.setPc(hit.getAddress().getHostAddress(), hit.getPort());
                reconnect();
            }
            cb.onResult(hit == null ? null : hit.getAddress().getHostAddress() + ":" + hit.getPort());
        }, "wristmic-find").start();
    }

    // ----------------------------------------------------------------- worker

    private boolean alive(int gen) {
        return running && gen == generation;
    }

    private void loop(int gen) {
        long backoff = 500;
        while (alive(gen)) {
            if (!Proto.hasKey()) {   // nothing to dial until the PC's pairing command arrives
                setState(UNPAIRED, "");
                synchronized (lock) {
                    dropStaleLocked();
                    try {
                        lock.wait(2000);   // reconnect() after pairing wakes this
                    } catch (InterruptedException e) {
                        break;
                    }
                }
                continue;
            }
            Socket s = null;
            try {
                setState(SEARCHING, "");
                s = connect();
                if (!alive(gen)) break;
                handshake(s);
                synchronized (lock) {
                    awaitingDone = 0;
                    for (Utterance u : outbox) u.startSent = false;
                }
                socket = s;
                setState(CONNECTED, viaWifi ? "Wi-Fi" : "via phone");
                backoff = 500;
                startReader(s);
                pump(s, gen);
            } catch (IOException e) {
                Log.i(TAG, "link: " + e);
            } catch (InterruptedException e) {
                break;
            } finally {
                closeQuietly(s);
                if (socket == s) socket = null;
            }
            if (!alive(gen)) break;
            setState(SEARCHING, "");
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                break;
            }
            backoff = Math.min(backoff * 2, 4000);
            synchronized (lock) {
                dropStaleLocked();
            }
        }
    }

    /** Writes queued dictations and keepalives until the connection breaks. */
    private void pump(Socket s, int gen) throws IOException, InterruptedException {
        OutputStream out = new BufferedOutputStream(s.getOutputStream(), 16 * 1024);
        long lastPing = 0;
        while (alive(gen) && !s.isClosed()) {
            boolean start = false, stop = false, upgrade = false, quit = false;
            int id = 0;
            List<byte[]> batch = null;
            synchronized (lock) {
                dropStaleLocked();
                Utterance u = outbox.peekFirst();
                if (u != null && u.everSent && !u.startSent && u.stopped && u.sent >= u.frames.size()) {
                    outbox.pollFirst();   // fully delivered before the last link dropped
                    u = outbox.peekFirst();
                }
                if (u != null) {
                    id = u.id;
                    if (!u.startSent) {
                        start = true;
                        u.startSent = true;
                        u.everSent = true;
                    }
                    if (u.sent < u.frames.size()) {
                        batch = new ArrayList<>(u.frames.subList(u.sent, u.frames.size()));
                        u.sent = u.frames.size();
                    }
                    if (u.stopped && u.sent >= u.frames.size()) {
                        stop = true;
                        outbox.pollFirst();
                        awaitingDone++;
                    }
                }
                long now = SystemClock.elapsedRealtime();
                boolean pingDue = now - lastPing >= Config.PING_MS;
                if (!start && batch == null && !stop && !pingDue) {
                    if (outbox.isEmpty()) {
                        if (stopWhenIdle) {
                            quit = true;
                        } else if (awaitingDone == 0 && !viaWifi && wifi != null && now > noWifiUpgradeUntil) {
                            upgrade = true;   // idle on Bluetooth while Wi-Fi is up: move over
                        }
                    }
                    if (!quit && !upgrade) {
                        lock.wait(Math.max(20, Config.PING_MS - (now - lastPing)));
                        continue;
                    }
                }
            }
            if (quit) {
                stop();
                return;
            }
            if (upgrade) {
                noWifiUpgradeUntil = SystemClock.elapsedRealtime() + 30000;
                return;
            }
            if (start) Proto.frame(out, Proto.START, Proto.int32(id));
            if (batch != null) {
                for (byte[] f : batch) Proto.frame(out, Proto.AUDIO, f);
            }
            if (stop) Proto.frame(out, Proto.STOP, null);
            long now = SystemClock.elapsedRealtime();
            if (now - lastPing >= Config.PING_MS) {
                Proto.frame(out, Proto.PING, null);
                lastPing = now;
            }
            out.flush();
        }
    }

    private void startReader(final Socket s) {
        Thread t = new Thread(() -> {
            try {
                s.setSoTimeout(9000);   // the PC answers every ping, so silence means it's gone
                DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
                while (true) {
                    int type = in.readUnsignedByte();
                    int len = in.readUnsignedShort();
                    byte[] p = new byte[len];
                    in.readFully(p);
                    if (type == Proto.DONE) {
                        synchronized (lock) {
                            if (awaitingDone > 0) awaitingDone--;
                            lock.notifyAll();
                        }
                        listener.onDictationDone(new String(p, StandardCharsets.UTF_8));
                    }
                }
            } catch (IOException e) {
                closeQuietly(s);
                synchronized (lock) {
                    lock.notifyAll();
                }
            }
        }, "wristmic-rx");
        t.setDaemon(true);
        t.start();
    }

    /** Never-sent dictations that waited too long are dropped so they can't type later. */
    private void dropStaleLocked() {
        long now = SystemClock.elapsedRealtime();
        Utterance u;
        while ((u = outbox.peekFirst()) != null && u.stopped && !u.startSent
                && now - u.stopTime > Config.STALE_MS) {
            outbox.pollFirst();
            listener.onDictationDropped();
        }
    }

    // ---------------------------------------------------------------- dialing

    private Socket connect() throws IOException {
        LinkedHashSet<String> hosts = new LinkedHashSet<>();
        if (!prefs.host().isEmpty()) hosts.add(prefs.host());
        int port = prefs.port();
        IOException last = null;

        Network w = wifi;
        if (w != null) {
            for (String h : hosts) {
                try {
                    return open(w, h, port, 1500);
                } catch (IOException e) {
                    last = e;
                }
            }
            InetSocketAddress hit = discover(w);
            if (hit != null) {
                String h = hit.getAddress().getHostAddress();
                prefs.setPc(h, hit.getPort());
                try {
                    return open(w, h, hit.getPort(), 2000);
                } catch (IOException e) {
                    last = e;
                }
            }
        }
        for (String h : hosts) {
            try {
                return open(null, h, port, 2500);
            } catch (IOException e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("no PC address");
    }

    private Socket open(Network net, String host, int port, int timeoutMs) throws IOException {
        Socket s = net != null ? net.getSocketFactory().createSocket() : new Socket();
        try {
            InetAddress addr = net != null ? net.getByName(host) : InetAddress.getByName(host);
            s.setTcpNoDelay(true);
            s.setKeepAlive(true);
            s.connect(new InetSocketAddress(addr, port), timeoutMs);
            s.setSoTimeout(5000);
            viaWifi = net != null || isWifi(cm.getActiveNetwork());
            return s;
        } catch (IOException e) {
            closeQuietly(s);
            throw e;
        }
    }

    private void handshake(Socket s) throws IOException {
        if (!Proto.hasKey()) throw new IOException("not paired");
        DataInputStream in = new DataInputStream(s.getInputStream());   // unbuffered: read exactly
        OutputStream out = s.getOutputStream();
        byte[] hello = new byte[21];
        in.readFully(hello);
        if (hello[0] != 'W' || hello[1] != 'M' || hello[2] != 'I' || hello[3] != 'C' || hello[4] != 1) {
            throw new IOException("not a WristMic receiver");
        }
        byte[] nonce = Arrays.copyOfRange(hello, 5, 21);
        byte[] mac = Proto.hmac(Proto.ascii("wristmic-v1"), nonce);
        byte[] name = deviceName().getBytes(StandardCharsets.UTF_8);
        if (name.length > 60) name = Arrays.copyOf(name, 60);
        out.write(Proto.concat(Proto.ascii("AUTH"), mac, new byte[]{(byte) name.length}, name));
        out.flush();
        byte[] ok = new byte[4];
        in.readFully(ok);
        if (!Arrays.equals(ok, Proto.ascii("OKAY"))) throw new IOException("receiver refused the pairing key");
    }

    /** Ask the local network where the receiver is (UDP broadcast, authenticated reply). */
    private InetSocketAddress discover(Network w) {
        if (!Proto.hasKey()) return null;   // probes and replies are signed with the pairing key
        DatagramSocket ds = null;
        try {
            ds = new DatagramSocket();
            w.bindSocket(ds);
            ds.setBroadcast(true);
            ds.setSoTimeout(300);
            byte[] nonce = new byte[8];
            rng.nextBytes(nonce);
            byte[] tag = Arrays.copyOf(Proto.hmac(Proto.ascii("probe"), nonce), 16);
            byte[] probe = Proto.concat(Proto.ascii("WMIC?"), nonce, tag);
            List<InetAddress> targets = broadcastTargets(w);
            byte[] buf = new byte[64];
            for (int round = 0; round < 3; round++) {
                for (InetAddress t : targets) {
                    try {
                        ds.send(new DatagramPacket(probe, probe.length, t, prefs.port() + 1));
                    } catch (IOException ignored) {
                        // try the next address
                    }
                }
                long until = SystemClock.elapsedRealtime() + 350;
                while (SystemClock.elapsedRealtime() < until) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    try {
                        ds.receive(p);
                    } catch (SocketTimeoutException e) {
                        break;
                    }
                    InetSocketAddress hit = parseReply(p, nonce);
                    if (hit != null) return hit;
                }
            }
        } catch (IOException e) {
            Log.i(TAG, "discovery: " + e);
        } finally {
            if (ds != null) ds.close();
        }
        return null;
    }

    private static InetSocketAddress parseReply(DatagramPacket p, byte[] nonce) {
        byte[] d = Arrays.copyOfRange(p.getData(), p.getOffset(), p.getOffset() + p.getLength());
        if (d.length != 31 || d[0] != 'W' || d[1] != 'M' || d[2] != 'I' || d[3] != 'C' || d[4] != '!') return null;
        if (!Arrays.equals(Arrays.copyOfRange(d, 5, 13), nonce)) return null;
        byte[] port = Arrays.copyOfRange(d, 13, 15);
        byte[] want = Arrays.copyOf(Proto.hmac(Proto.ascii("here"), nonce, port), 16);
        if (!MessageDigest.isEqual(want, Arrays.copyOfRange(d, 15, 31))) return null;
        return new InetSocketAddress(p.getAddress(), ((port[0] & 0xff) << 8) | (port[1] & 0xff));
    }

    private List<InetAddress> broadcastTargets(Network w) {
        List<InetAddress> out = new ArrayList<>();
        try {
            LinkProperties lp = cm.getLinkProperties(w);
            if (lp != null) {
                for (LinkAddress la : lp.getLinkAddresses()) {
                    InetAddress a = la.getAddress();
                    if (!(a instanceof Inet4Address)) continue;
                    byte[] ip = a.getAddress();
                    int v = ((ip[0] & 0xff) << 24) | ((ip[1] & 0xff) << 16) | ((ip[2] & 0xff) << 8) | (ip[3] & 0xff);
                    int prefix = la.getPrefixLength();
                    int mask = prefix <= 0 ? 0 : (int) (0xffffffffL << (32 - prefix));
                    int b = v | ~mask;
                    out.add(InetAddress.getByAddress(new byte[]{
                            (byte) (b >>> 24), (byte) (b >>> 16), (byte) (b >>> 8), (byte) b}));
                }
            }
            out.add(InetAddress.getByAddress(new byte[]{(byte) 255, (byte) 255, (byte) 255, (byte) 255}));
        } catch (IOException ignored) {
            // unknown host can't happen for literal addresses
        }
        return out;
    }

    // ------------------------------------------------------------------ Wi-Fi

    private void requestWifi() {
        if (wifiCallback != null) return;
        NetworkRequest req = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build();
        ConnectivityManager.NetworkCallback cb = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network n) {
                wifi = n;
                synchronized (lock) {
                    lock.notifyAll();
                }
            }

            @Override
            public void onLost(Network n) {
                if (n.equals(wifi)) wifi = null;
            }
        };
        try {
            cm.requestNetwork(req, cb);
            wifiCallback = cb;
        } catch (RuntimeException e) {
            Log.w(TAG, "Wi-Fi request refused", e);
        }
    }

    private void releaseWifi() {
        if (wifiCallback != null) {
            try {
                cm.unregisterNetworkCallback(wifiCallback);
            } catch (RuntimeException ignored) {
                // already gone
            }
            wifiCallback = null;
        }
        wifi = null;
    }

    private boolean isWifi(Network n) {
        if (n == null) return false;
        NetworkCapabilities nc = cm.getNetworkCapabilities(n);
        return nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    }

    // ------------------------------------------------------------------ misc

    private void setState(int st, String r) {
        if (state == st && route.equals(r)) return;
        state = st;
        route = r;
        listener.onLinkState();
    }

    private String deviceName() {
        String n = null;
        try {
            n = Settings.Global.getString(app.getContentResolver(), "device_name");
        } catch (RuntimeException ignored) {
            // fall back to the model name
        }
        if (n == null || n.trim().isEmpty()) n = Build.MODEL;
        return n;
    }

    private static void closeQuietly(Socket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (IOException ignored) {
            // closing anyway
        }
    }
}
