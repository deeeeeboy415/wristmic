package com.wristmic.app;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Process;
import android.util.Log;

/** Reads the watch microphone in 20 ms frames on its own thread. */
final class Capture {
    interface Sink {
        void onFrame(byte[] frame, float level);
    }

    private final Sink sink;
    private volatile boolean running;
    private Thread thread;
    private AudioRecord rec;

    Capture(Sink sink) {
        this.sink = sink;
    }

    boolean isRunning() {
        return running;
    }

    synchronized boolean start(Context c) {
        if (running) return true;
        if (c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        int min = AudioRecord.getMinBufferSize(Config.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        int size = Math.max(min, Config.FRAME_BYTES * 16);
        AudioRecord r = create(MediaRecorder.AudioSource.VOICE_RECOGNITION, size);
        if (r == null) r = create(MediaRecorder.AudioSource.MIC, size);
        if (r == null) return false;
        try {
            r.startRecording();
        } catch (IllegalStateException e) {
            r.release();
            return false;
        }
        rec = r;
        running = true;
        final AudioRecord fr = r;
        thread = new Thread(() -> readLoop(fr), "wristmic-mic");
        thread.start();
        return true;
    }

    private static AudioRecord create(int source, int size) {
        try {
            AudioRecord r = new AudioRecord(source, Config.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, size);
            if (r.getState() == AudioRecord.STATE_INITIALIZED) return r;
            r.release();
        } catch (IllegalArgumentException | SecurityException e) {
            Log.w("WristMic", "mic source " + source + ": " + e);
        }
        return null;
    }

    private void readLoop(AudioRecord r) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        while (running) {
            byte[] f = new byte[Config.FRAME_BYTES];
            int got = 0;
            while (got < f.length && running) {
                int n = r.read(f, got, f.length - got);
                if (n < 0) {
                    running = false;   // the mic went away
                    break;
                }
                if (n == 0) {
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException e) {
                        break;
                    }
                    continue;
                }
                got += n;
            }
            if (got == f.length) sink.onFrame(f, level(f));
        }
    }

    synchronized void stop() {
        running = false;
        AudioRecord r = rec;
        rec = null;
        if (r != null) {
            try {
                r.stop();
            } catch (IllegalStateException ignored) {
                // not recording
            }
        }
        if (thread != null) {
            try {
                thread.join(500);
            } catch (InterruptedException ignored) {
                // shutting down
            }
            thread = null;
        }
        if (r != null) r.release();
    }

    /** Loudness of a frame on a 0..1 scale (-60 dBFS .. 0 dBFS). */
    static float level(byte[] f) {
        long sum = 0;
        for (int i = 0; i + 1 < f.length; i += 2) {
            int s = (short) ((f[i] & 0xff) | (f[i + 1] << 8));
            sum += (long) s * s;
        }
        double rms = Math.sqrt(sum / (f.length / 2.0));
        double db = 20 * Math.log10(Math.max(rms, 1.0) / 32768.0);
        return (float) Math.max(0, Math.min(1, (db + 60) / 60));
    }
}
