"""Simulated end-to-end tests for wristmic_receiver.py. Runs anywhere: python tests/test_receiver.py"""
import hashlib, hmac, importlib.util, os, socket, struct, sys, threading, time

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("wr", os.path.join(HERE, "..", "pc", "wristmic_receiver.py"))
wr = importlib.util.module_from_spec(spec)
spec.loader.exec_module(wr)

TOKEN = bytes.fromhex("00112233445566778899aabbccddeeff")  # test key, not a real one
FRAME = 640


def free_port():
    s = socket.socket(); s.bind(("127.0.0.1", 0)); p = s.getsockname()[1]; s.close()
    return p


class Rec:
    def __init__(self):
        self.events = []
    def __call__(self, vk, up):
        self.events.append((time.monotonic(), vk, up))


def make_server(cfg_over=None):
    cfg = dict(wr.DEFAULT_CONFIG)
    cfg.update(cfg_over or {})
    port = free_port()
    while True:  # need port+1 free too
        try:
            u = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); u.bind(("127.0.0.1", port + 1)); u.close(); break
        except OSError:
            port = free_port()
    rec = Rec()
    kb = wr.Keyboard([0xA2, 0x5B], sender=rec)
    audio = wr.SimAudio()
    mic = wr.WisprMicWatch()
    srv = wr.Server(port, TOKEN, lambda notify: wr.Talker(cfg, kb, audio, mic, notify))
    srv.start("127.0.0.1")
    return srv, kb, audio, rec, port, cfg


class Watch:
    def __init__(self, port, token=TOKEN, name="Galaxy Watch8 Classic"):
        self.s = socket.create_connection(("127.0.0.1", port), timeout=5)
        hello = wr.recv_exact(self.s, 21)
        assert hello[:4] == b"WMIC" and hello[4] == 1
        nonce = hello[5:]
        mac = hmac.new(token, b"wristmic-v1" + nonce, hashlib.sha256).digest()
        nb = name.encode()
        self.s.sendall(b"AUTH" + mac + bytes([len(nb)]) + nb)
        self.ok = self.s.recv(4) == b"OKAY"
        self.s.settimeout(None)
        self.rx = []
        if self.ok:
            threading.Thread(target=self._reader, daemon=True).start()

    def _reader(self):
        try:
            while True:
                h = wr.recv_exact(self.s, 3)
                n = (h[1] << 8) | h[2]
                self.rx.append((h[0], wr.recv_exact(self.s, n) if n else b""))
        except Exception:
            pass

    def frame(self, t, payload=b""):
        self.s.sendall(struct.pack(">BH", t, len(payload)) + payload)

    def talk(self, seconds, realtime=True, stop=True):
        self.frame(wr.T_START, struct.pack(">I", 1))
        n = int(seconds * 50)
        for i in range(n):
            self.frame(wr.T_AUDIO, bytes([i % 256]) * FRAME)
            if realtime:
                time.sleep(0.02)
        if stop:
            self.frame(wr.T_STOP)
        return n * FRAME

    def wait_done(self, timeout=8):
        end = time.time() + timeout
        while time.time() < end:
            for t, p in self.rx:
                if t == wr.T_DONE:
                    return p.decode()
            time.sleep(0.02)
        return None


def check(cond, msg):
    print(("PASS  " if cond else "FAIL  ") + msg)
    if not cond:
        check.failed = True
check.failed = False


def test_basic():
    srv, kb, audio, rec, port, cfg = make_server()
    w = Watch(port)
    check(w.ok, "auth with the right key is accepted")
    t_send = time.monotonic()
    sent = w.talk(2.0)
    t_stop = time.monotonic()
    done = w.wait_done()
    check(done is not None and abs(float(done) - 2.0) < 0.05, "DONE reports 2.0 s (got %r)" % done)
    check(audio.written == sent, "every audio byte was played (%d/%d)" % (audio.written, sent))
    downs = [e for e in rec.events if not e[2]]
    ups = [e for e in rec.events if e[2]]
    check([e[1] for e in downs] == [0xA2, 0x5B], "pressed Left Ctrl then Left Win")
    check([e[1] for e in ups] == [0xA2, 0x5B], "released Ctrl first, Win last (no Start menu)")
    # playback can't end before start_delay + 2 s of audio; release must come after playback + end delay
    release_t = ups[-1][0]
    press_t = downs[0][0]
    min_release = press_t + cfg["start_delay_ms"] / 1000 + 2.0 + cfg["end_delay_ms"] / 1000 - 0.05
    check(release_t >= min_release, "released only after all audio played + end delay (%.2fs after press)" % (release_t - press_t))
    check(release_t - t_stop < 1.3, "released promptly after the watch stopped (%.2fs)" % (release_t - t_stop))
    check(not kb.held, "keys not held after dictation")
    w.s.close()


def test_wrong_key():
    srv, kb, audio, rec, port, cfg = make_server()
    w = Watch(port, token=bytes(16))
    check(not w.ok, "auth with a wrong key is refused")
    check(not rec.events, "no keys pressed for a refused connection")


def test_unauthenticated_frames():
    srv, kb, audio, rec, port, cfg = make_server()
    s = socket.create_connection(("127.0.0.1", port), timeout=5)
    wr.recv_exact(s, 21)
    s.sendall(struct.pack(">BH", wr.T_START, 0) * 20)  # junk instead of AUTH
    time.sleep(0.5)
    check(not rec.events, "frames without auth never press keys")
    s.close()


def test_lost_midway():
    srv, kb, audio, rec, port, cfg = make_server()
    w = Watch(port)
    w.talk(1.0, stop=False)
    w.s.shutdown(socket.SHUT_RDWR); w.s.close()  # watch vanished mid-dictation
    time.sleep(cfg["start_delay_ms"] / 1000 + 1.0 + cfg["end_delay_ms"] / 1000 + 0.6)
    check(not kb.held, "keys released after the watch drops mid-dictation")
    check(audio.written == 50 * FRAME, "audio received before the drop still played")


def test_silent_stall():
    srv, kb, audio, rec, port, cfg = make_server()
    w = Watch(port)
    w.talk(1.0, stop=False)  # link stays open but nothing more arrives (Wi-Fi died quietly)
    t_last = time.monotonic()
    while kb.held and time.monotonic() - t_last < 10:
        time.sleep(0.05)
    waited = time.monotonic() - t_last
    check(not kb.held and waited < wr.Talker.STALL_SECONDS + 1.5, "keys released %.1fs after audio stopped arriving" % waited)
    done = w.wait_done(2)
    check(done is not None, "watch still gets DONE after a stall")
    w.s.close()


def test_short_press():
    srv, kb, audio, rec, port, cfg = make_server()
    w = Watch(port)
    sent = w.talk(0.1, realtime=False)
    done = w.wait_done()
    check(done is not None, "a very short dictation still completes")
    check(audio.written == sent, "short dictation played fully")
    downs = [e for e in rec.events if not e[2]]
    ups = [e for e in rec.events if e[2]]
    check(ups and ups[-1][0] - downs[0][0] >= cfg["start_delay_ms"] / 1000, "short dictation still gave Wispr its head start")


def test_backlog():
    # audio arrives in a burst (watch was buffering while Wi-Fi came up)
    srv, kb, audio, rec, port, cfg = make_server()
    w = Watch(port)
    sent = w.talk(3.0, realtime=False)
    done = w.wait_done(10)
    ups = [e for e in rec.events if e[2]]
    downs = [e for e in rec.events if not e[2]]
    check(audio.written == sent, "burst of buffered audio fully played")
    check(ups[-1][0] - downs[0][0] >= 3.0 + cfg["start_delay_ms"] / 1000, "held the shortcut for the whole backlog (%.2fs)" % (ups[-1][0] - downs[0][0]))


def test_supersede():
    srv, kb, audio, rec, port, cfg = make_server()
    a = Watch(port, name="A")
    a.talk(0.5, stop=False)
    b = Watch(port, name="B")          # reconnect on a new link
    sent_b = b.talk(1.0)
    done_b = b.wait_done(10)
    check(done_b is not None and abs(float(done_b) - 1.0) < 0.05, "new link's dictation completes after a reconnect (%r)" % done_b)
    check(not kb.held, "no keys left held after reconnect")
    presses = sum(1 for e in rec.events if not e[2] and e[1] == 0xA2)
    check(presses == 2, "two separate dictations (got %d)" % presses)


def test_discovery():
    srv, kb, audio, rec, port, cfg = make_server()
    u = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); u.settimeout(1.0)
    nonce = os.urandom(8)
    tag = hmac.new(TOKEN, b"probe" + nonce, hashlib.sha256).digest()[:16]
    u.sendto(b"WMIC?" + nonce + tag, ("127.0.0.1", port + 1))
    data, _ = u.recvfrom(64)
    pb = struct.pack(">H", port)
    want = b"WMIC!" + nonce + pb + hmac.new(TOKEN, b"here" + nonce + pb, hashlib.sha256).digest()[:16]
    check(data == want, "discovery answers a valid probe with an authenticated reply")
    u.sendto(b"WMIC?" + nonce + bytes(16), ("127.0.0.1", port + 1))
    try:
        u.recvfrom(64); got = True
    except socket.timeout:
        got = False
    check(not got, "discovery ignores probes with a bad tag")


def test_gain_and_shortcut_parsing():
    check(wr.parse_shortcut("LCTRL+LWIN") == [0xA2, 0x5B], "parses LCTRL+LWIN")
    check(wr.parse_shortcut("162+91") == [162, 91], "parses Wispr-style 162+91")
    check(wr.shortcut_label([162, 32, 91]) == "Left Ctrl + Space + Left Win", "labels keys")
    loud = wr.apply_gain(struct.pack("<hh", 20000, -20000), 2.0)
    check(struct.unpack("<hh", loud) == (32767, -32768), "gain saturates instead of wrapping")
    amp, rms = wr.tone_level(wr.make_tone(1000, 0.5), 1000)
    check(amp > 7000, "tone detector finds the test tone (amp %.0f)" % amp)


def test_pairing_config():
    import json, tempfile
    d = tempfile.mkdtemp()
    old_path = wr.CONFIG_PATH
    try:
        wr.CONFIG_PATH = os.path.join(d, "wristmic_config.json")
        cfg = wr.load_config()
        check(cfg["_new_token"] and wr.valid_token(cfg["token"]), "first run makes a pairing key")
        saved = json.load(open(wr.CONFIG_PATH))
        check(saved["token"] == cfg["token"], "the new key is saved to the config file")
        again = wr.load_config()
        check(again["token"] == cfg["token"] and not again["_new_token"], "the key is kept on the next run")
        cmd = wr.pair_command(cfg)
        check(cfg["token"] in cmd and "--ei port 47800" in cmd and ".PairReceiver" in cmd,
              "pairing command carries the key and port")
        with open(wr.CONFIG_PATH, "w") as f:
            json.dump({"token": "not-hex", "gain_db": 6}, f)
        fixed = wr.load_config()
        kept = json.load(open(wr.CONFIG_PATH))
        check(wr.valid_token(fixed["token"]) and kept.get("gain_db") == 6, "a bad key is replaced, other settings kept")
    finally:
        wr.CONFIG_PATH = old_path


for t in (test_gain_and_shortcut_parsing, test_pairing_config, test_basic, test_wrong_key, test_unauthenticated_frames,
          test_lost_midway, test_silent_stall, test_short_press, test_backlog, test_supersede, test_discovery):
    print("--", t.__name__)
    t()
print("\nALL PASSED" if not check.failed else "\nSOME FAILED")
sys.exit(1 if check.failed else 0)
