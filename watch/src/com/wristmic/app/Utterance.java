package com.wristmic.app;

import java.util.ArrayList;

/** One dictation: its audio frames and how far it has been sent. Guarded by Link.lock. */
final class Utterance {
    final int id;
    final ArrayList<byte[]> frames = new ArrayList<>();
    boolean queued;      // handed to the link (held long enough, or hands-free)
    boolean stopped;     // the user let go
    long stopTime;
    int sent;            // frames written so far
    boolean startSent;   // START written on the current connection
    boolean everSent;    // START reached some connection

    Utterance(int id) {
        this.id = id;
    }
}
