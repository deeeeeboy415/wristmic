package com.wristmic.app;

/** Fixed settings. The pairing key and the PC's address come from pairing (see PairReceiver). */
final class Config {
    private Config() {}

    /** TCP port of the receiver; discovery is UDP on PORT + 1. */
    static final int PORT = 47800;

    static final int SAMPLE_RATE = 16000;
    static final int FRAME_BYTES = 640;       // 20 ms of 16-bit mono audio
    static final int PREROLL_FRAMES = 12;     // 240 ms kept from just before a press
    static final long HOLD_MS = 220;          // a press longer than this is hold-to-talk
    static final int MAX_FRAMES = 15000;      // 5 minutes per dictation
    static final long STALE_MS = 15000;       // give up on an unsent dictation after this
    static final long PING_MS = 2000;         // keepalive while idle
    static final long LINGER_MS = 60000;      // stay connected this long after the app is hidden
}
