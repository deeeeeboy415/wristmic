#!/usr/bin/env python3
"""
WristMic receiver: use a Galaxy Watch as a push-to-talk microphone for Wispr Flow.

The WristMic watch app streams the watch microphone to this PC over your home
network. While you hold the watch button, this program:

  1. holds Wispr Flow's push-to-talk shortcut (read from Wispr Flow's own
     settings, Left Ctrl + Left Win by default),
  2. plays your voice into a virtual audio cable that Wispr Flow uses as its
     microphone (VB-CABLE, or Steam Streaming Microphone),
  3. lets go of the shortcut only after the last of your audio has played,
     so Wispr Flow types the text into whatever text box has focus.

Windows 10/11, Python 3.8 or newer, no extra packages.

    python wristmic_receiver.py                 run the receiver
    python wristmic_receiver.py --pair          print the command that pairs the watch
    python wristmic_receiver.py --doctor        check the setup
    python wristmic_receiver.py --devices       list audio devices
    python wristmic_receiver.py --test-tone     play a tone into the cable for 4 s
    python wristmic_receiver.py --test-shortcut hold Wispr Flow's shortcut for 3 s
"""

import argparse
import atexit
import hashlib
import hmac
import json
import math
import os
import queue
import secrets
import socket
import struct
import subprocess
import sys
import threading
import time
import warnings
from array import array
from collections import deque

with warnings.catch_warnings():
    warnings.simplefilter("ignore", DeprecationWarning)
    try:
        import audioop  # fast gain; removed in Python 3.13, so it is optional
    except ImportError:
        audioop = None

VERSION = "1.0"
HERE = os.path.dirname(os.path.abspath(__file__))
CONFIG_PATH = os.environ.get("WRISTMIC_CONFIG") or os.path.join(HERE, "wristmic_config.json")

DEFAULT_CONFIG = {
    "port": 47800,             # TCP for audio; discovery listens on port + 1 (UDP)
    "token": "",               # pairing key; made on first run, sent to the watch with --pair
    "shortcut": "auto",        # "auto" = Wispr Flow's push-to-talk shortcut, or e.g. "LCTRL+LWIN"
    "audio_device": "auto",    # "auto" = VB-CABLE, then Steam Streaming Microphone; or part of a name
    "gain_db": 0.0,            # raise if Wispr Flow says your audio is too quiet (try 6)
    "start_delay_ms": 350,     # time Wispr Flow gets to open its mic before your audio starts
    "end_delay_ms": 400,       # extra time after your last word before letting go of the shortcut
    "max_talk_seconds": 300,   # safety limit for one dictation
}

RATE = 16000
BYTES_PER_SEC = RATE * 2

HELLO = b"WMIC"
PROTO = 1
AUTH_LABEL = b"wristmic-v1"
T_START, T_AUDIO, T_STOP, T_PING = 0x01, 0x02, 0x03, 0x05
T_PONG, T_DONE = 0x11, 0x13
MAX_FRAME = 16384

IS_WINDOWS = os.name == "nt"

try:
    sys.stdout.reconfigure(errors="replace")
except (AttributeError, ValueError):
    pass

_print_lock = threading.Lock()


def log(msg):
    with _print_lock:
        print(time.strftime("[%H:%M:%S] ") + msg, flush=True)


def say(msg=""):
    with _print_lock:
        print(msg, flush=True)


# --------------------------------------------------------------------------- keys

KEY_NAMES = {
    "LCTRL": 0xA2, "RCTRL": 0xA3, "CTRL": 0xA2, "LCONTROL": 0xA2, "RCONTROL": 0xA3,
    "LWIN": 0x5B, "RWIN": 0x5C, "WIN": 0x5B,
    "LALT": 0xA4, "RALT": 0xA5, "ALT": 0xA4,
    "LSHIFT": 0xA0, "RSHIFT": 0xA1, "SHIFT": 0xA0,
    "SPACE": 0x20, "CAPSLOCK": 0x14, "TAB": 0x09, "ESC": 0x1B, "ENTER": 0x0D,
}
for _i in range(1, 25):
    KEY_NAMES["F%d" % _i] = 0x6F + _i
for _c in "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789":
    KEY_NAMES[_c] = ord(_c)

_KEY_LABELS = {
    0xA2: "Left Ctrl", 0xA3: "Right Ctrl", 0x11: "Ctrl", 0x5B: "Left Win", 0x5C: "Right Win",
    0xA4: "Left Alt", 0xA5: "Right Alt", 0x12: "Alt", 0xA0: "Left Shift", 0xA1: "Right Shift",
    0x10: "Shift", 0x20: "Space", 0x14: "Caps Lock", 0x09: "Tab", 0x1B: "Esc", 0x0D: "Enter",
}
EXTENDED_KEYS = {0xA3, 0xA5, 0x5B, 0x5C, 0x5D, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28,
                 0x2D, 0x2E, 0x6F, 0x90}
WIN_KEYS = {0x5B, 0x5C}


def key_label(vk):
    if vk in _KEY_LABELS:
        return _KEY_LABELS[vk]
    if 0x70 <= vk <= 0x87:
        return "F%d" % (vk - 0x6F)
    if 0x30 <= vk <= 0x5A:
        return chr(vk)
    return "key %d" % vk


def shortcut_label(vks):
    return " + ".join(key_label(v) for v in vks)


def parse_shortcut(text):
    vks = []
    for part in str(text).replace(" ", "").upper().split("+"):
        if not part:
            continue
        if part in KEY_NAMES:
            vks.append(KEY_NAMES[part])
        elif part.startswith("VK") and part[2:].isdigit():
            vks.append(int(part[2:]))
        elif part.isdigit():
            vks.append(int(part))
        else:
            raise ValueError("unknown key name %r" % part)
    if not vks or not all(0 < v < 256 for v in vks):
        raise ValueError("no usable keys in %r" % text)
    return vks


def wispr_config_file():
    base = os.environ.get("APPDATA")
    return os.path.join(base, "Wispr Flow", "config.json") if base else None


def wispr_shortcut(action):
    """The key codes Wispr Flow uses for an action ("ptt" = push to talk), or None."""
    path = wispr_config_file()
    if not path or not os.path.isfile(path):
        return None
    try:
        with open(path, "r", encoding="utf-8") as f:
            shortcuts = json.load(f)["prefs"]["user"]["shortcuts"]
    except (OSError, ValueError, KeyError, TypeError):
        return None
    for combo, act in shortcuts.items():
        if act != action:
            continue
        try:
            vks = [int(p) for p in str(combo).split("+") if p]
        except ValueError:
            continue  # a mouse button or something else we can't press
        if vks and all(0 < v < 256 for v in vks):
            return vks
    return None


def resolve_shortcut(cfg):
    setting = str(cfg.get("shortcut", "auto")).strip()
    if setting.lower() == "auto":
        vks = wispr_shortcut("ptt")
        if vks:
            return vks, "read from Wispr Flow's settings"
        return [0xA2, 0x5B], "Wispr Flow's default"
    return parse_shortcut(setting), "set in wristmic_config.json"


# ----------------------------------------------------------------- Windows bindings

class AudioError(Exception):
    pass


if IS_WINDOWS:
    import ctypes
    from ctypes import wintypes

    user32 = ctypes.WinDLL("user32", use_last_error=True)
    kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    winmm = ctypes.WinDLL("winmm")

    ULONG_PTR = ctypes.c_size_t
    INPUT_KEYBOARD = 1
    KEYEVENTF_EXTENDEDKEY = 0x0001
    KEYEVENTF_KEYUP = 0x0002
    MAPVK_VK_TO_VSC = 0
    INJECT_TAG = 0x574D4943  # "WMIC", lets other tools recognise our key presses

    class MOUSEINPUT(ctypes.Structure):
        _fields_ = (("dx", wintypes.LONG), ("dy", wintypes.LONG), ("mouseData", wintypes.DWORD),
                    ("dwFlags", wintypes.DWORD), ("time", wintypes.DWORD), ("dwExtraInfo", ULONG_PTR))

    class KEYBDINPUT(ctypes.Structure):
        _fields_ = (("wVk", wintypes.WORD), ("wScan", wintypes.WORD), ("dwFlags", wintypes.DWORD),
                    ("time", wintypes.DWORD), ("dwExtraInfo", ULONG_PTR))

    class HARDWAREINPUT(ctypes.Structure):
        _fields_ = (("uMsg", wintypes.DWORD), ("wParamL", wintypes.WORD), ("wParamH", wintypes.WORD))

    class _INPUTUNION(ctypes.Union):
        _fields_ = (("mi", MOUSEINPUT), ("ki", KEYBDINPUT), ("hi", HARDWAREINPUT))

    class INPUT(ctypes.Structure):
        _anonymous_ = ("u",)
        _fields_ = (("type", wintypes.DWORD), ("u", _INPUTUNION))

    user32.SendInput.argtypes = (wintypes.UINT, ctypes.POINTER(INPUT), ctypes.c_int)
    user32.SendInput.restype = wintypes.UINT
    user32.MapVirtualKeyW.argtypes = (wintypes.UINT, wintypes.UINT)
    user32.MapVirtualKeyW.restype = wintypes.UINT
    user32.GetAsyncKeyState.argtypes = (ctypes.c_int,)
    user32.GetAsyncKeyState.restype = ctypes.c_short

    def send_key(vk, up):
        flags = KEYEVENTF_KEYUP if up else 0
        if vk in EXTENDED_KEYS:
            flags |= KEYEVENTF_EXTENDEDKEY
        inp = INPUT()
        inp.type = INPUT_KEYBOARD
        inp.ki = KEYBDINPUT(vk, user32.MapVirtualKeyW(vk, MAPVK_VK_TO_VSC) & 0xFF, flags, 0, INJECT_TAG)
        if user32.SendInput(1, ctypes.byref(inp), ctypes.sizeof(INPUT)) != 1:
            raise ctypes.WinError(ctypes.get_last_error())

    def key_is_down(vk):
        return bool(user32.GetAsyncKeyState(vk) & 0x8000)

    # --- waveOut / waveIn (winmm) ---
    WAVE_FORMAT_PCM = 1
    WHDR_DONE = 0x00000001
    MAXPNAMELEN = 32
    HWAVE = wintypes.HANDLE

    class WAVEFORMATEX(ctypes.Structure):
        _fields_ = (("wFormatTag", wintypes.WORD), ("nChannels", wintypes.WORD),
                    ("nSamplesPerSec", wintypes.DWORD), ("nAvgBytesPerSec", wintypes.DWORD),
                    ("nBlockAlign", wintypes.WORD), ("wBitsPerSample", wintypes.WORD),
                    ("cbSize", wintypes.WORD))

    class WAVEHDR(ctypes.Structure):
        pass

    WAVEHDR._fields_ = (("lpData", ctypes.c_void_p), ("dwBufferLength", wintypes.DWORD),
                        ("dwBytesRecorded", wintypes.DWORD), ("dwUser", ULONG_PTR),
                        ("dwFlags", wintypes.DWORD), ("dwLoops", wintypes.DWORD),
                        ("lpNext", ctypes.POINTER(WAVEHDR)), ("reserved", ULONG_PTR))

    class WAVEOUTCAPSW(ctypes.Structure):
        _fields_ = (("wMid", wintypes.WORD), ("wPid", wintypes.WORD), ("vDriverVersion", wintypes.UINT),
                    ("szPname", wintypes.WCHAR * MAXPNAMELEN), ("dwFormats", wintypes.DWORD),
                    ("wChannels", wintypes.WORD), ("wReserved1", wintypes.WORD),
                    ("dwSupport", wintypes.DWORD))

    class WAVEINCAPSW(ctypes.Structure):
        _fields_ = (("wMid", wintypes.WORD), ("wPid", wintypes.WORD), ("vDriverVersion", wintypes.UINT),
                    ("szPname", wintypes.WCHAR * MAXPNAMELEN), ("dwFormats", wintypes.DWORD),
                    ("wChannels", wintypes.WORD), ("wReserved1", wintypes.WORD))

    _HDR = ctypes.POINTER(WAVEHDR)
    for _fn, _args in (
        ("waveOutGetNumDevs", ()),
        ("waveOutGetDevCapsW", (ULONG_PTR, ctypes.POINTER(WAVEOUTCAPSW), wintypes.UINT)),
        ("waveOutOpen", (ctypes.POINTER(HWAVE), wintypes.UINT, ctypes.POINTER(WAVEFORMATEX),
                         ULONG_PTR, ULONG_PTR, wintypes.DWORD)),
        ("waveOutPrepareHeader", (HWAVE, _HDR, wintypes.UINT)),
        ("waveOutUnprepareHeader", (HWAVE, _HDR, wintypes.UINT)),
        ("waveOutWrite", (HWAVE, _HDR, wintypes.UINT)),
        ("waveOutReset", (HWAVE,)),
        ("waveOutClose", (HWAVE,)),
        ("waveInGetNumDevs", ()),
        ("waveInGetDevCapsW", (ULONG_PTR, ctypes.POINTER(WAVEINCAPSW), wintypes.UINT)),
        ("waveInOpen", (ctypes.POINTER(HWAVE), wintypes.UINT, ctypes.POINTER(WAVEFORMATEX),
                        ULONG_PTR, ULONG_PTR, wintypes.DWORD)),
        ("waveInPrepareHeader", (HWAVE, _HDR, wintypes.UINT)),
        ("waveInUnprepareHeader", (HWAVE, _HDR, wintypes.UINT)),
        ("waveInAddBuffer", (HWAVE, _HDR, wintypes.UINT)),
        ("waveInStart", (HWAVE,)),
        ("waveInStop", (HWAVE,)),
        ("waveInReset", (HWAVE,)),
        ("waveInClose", (HWAVE,)),
    ):
        getattr(winmm, _fn).argtypes = _args
        getattr(winmm, _fn).restype = wintypes.UINT

    def _pcm_format():
        return WAVEFORMATEX(WAVE_FORMAT_PCM, 1, RATE, BYTES_PER_SEC, 2, 16, 0)

    def list_outputs():
        names, caps = [], WAVEOUTCAPSW()
        for i in range(winmm.waveOutGetNumDevs()):
            ok = winmm.waveOutGetDevCapsW(i, ctypes.byref(caps), ctypes.sizeof(caps)) == 0
            names.append(caps.szPname if ok else "")
        return names

    def list_inputs():
        names, caps = [], WAVEINCAPSW()
        for i in range(winmm.waveInGetNumDevs()):
            ok = winmm.waveInGetDevCapsW(i, ctypes.byref(caps), ctypes.sizeof(caps)) == 0
            names.append(caps.szPname if ok else "")
        return names

    class WinAudioOut:
        """Plays 16 kHz mono PCM into one output device, queue-style."""

        def __init__(self, index, name):
            self.index, self.name = index, name
            self.h = None
            self.blocks = deque()
            self.lock = threading.Lock()

        def open(self):
            with self.lock:
                if self.h is not None:
                    return
                h = HWAVE()
                fmt = _pcm_format()
                r = winmm.waveOutOpen(ctypes.byref(h), self.index, ctypes.byref(fmt), 0, 0, 0)
                if r:
                    raise AudioError("could not open %s (error %d)" % (self.name, r))
                self.h = h

        def write(self, data):
            if not data:
                return
            with self.lock:
                if self.h is None:
                    raise AudioError("audio device is closed")
                self._reap()
                buf = ctypes.create_string_buffer(data, len(data))
                hdr = WAVEHDR()
                hdr.lpData = ctypes.cast(buf, ctypes.c_void_p)
                hdr.dwBufferLength = len(data)
                size = ctypes.sizeof(WAVEHDR)
                r = winmm.waveOutPrepareHeader(self.h, ctypes.byref(hdr), size)
                if r == 0:
                    r = winmm.waveOutWrite(self.h, ctypes.byref(hdr), size)
                    if r:
                        winmm.waveOutUnprepareHeader(self.h, ctypes.byref(hdr), size)
                if r:
                    raise AudioError("playback failed on %s (error %d)" % (self.name, r))
                self.blocks.append((hdr, buf, len(data)))

        def _reap(self):
            size = ctypes.sizeof(WAVEHDR)
            while self.blocks and (self.blocks[0][0].dwFlags & WHDR_DONE):
                hdr = self.blocks.popleft()[0]
                winmm.waveOutUnprepareHeader(self.h, ctypes.byref(hdr), size)

        def queued_seconds(self):
            with self.lock:
                if self.h is None:
                    return 0.0
                self._reap()
                return sum(b[2] for b in self.blocks) / float(BYTES_PER_SEC)

        def wait_drained(self, timeout):
            end = time.monotonic() + timeout
            while self.queued_seconds() > 0 and time.monotonic() < end:
                time.sleep(0.01)

        def close(self):
            with self.lock:
                if self.h is None:
                    return
                winmm.waveOutReset(self.h)
                self._reap()
                size = ctypes.sizeof(WAVEHDR)
                for hdr, _, _ in self.blocks:
                    winmm.waveOutUnprepareHeader(self.h, ctypes.byref(hdr), size)
                self.blocks.clear()
                winmm.waveOutClose(self.h)
                self.h = None

    def record_input(index, seconds):
        """Record from an input device; returns raw 16 kHz mono PCM."""
        h = HWAVE()
        fmt = _pcm_format()
        r = winmm.waveInOpen(ctypes.byref(h), index, ctypes.byref(fmt), 0, 0, 0)
        if r:
            raise AudioError("could not open the input (error %d)" % r)
        n = int(BYTES_PER_SEC * seconds) & ~1
        buf = ctypes.create_string_buffer(n)
        hdr = WAVEHDR()
        hdr.lpData = ctypes.cast(buf, ctypes.c_void_p)
        hdr.dwBufferLength = n
        size = ctypes.sizeof(WAVEHDR)
        try:
            winmm.waveInPrepareHeader(h, ctypes.byref(hdr), size)
            winmm.waveInAddBuffer(h, ctypes.byref(hdr), size)
            winmm.waveInStart(h)
            end = time.monotonic() + seconds + 2.0
            while not (hdr.dwFlags & WHDR_DONE) and time.monotonic() < end:
                time.sleep(0.02)
            winmm.waveInStop(h)
            winmm.waveInReset(h)
            winmm.waveInUnprepareHeader(h, ctypes.byref(hdr), size)
            return buf.raw[:hdr.dwBytesRecorded]
        finally:
            winmm.waveInClose(h)

    HandlerRoutine = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.DWORD)
    kernel32.SetConsoleCtrlHandler.argtypes = (HandlerRoutine, wintypes.BOOL)
    kernel32.SetConsoleCtrlHandler.restype = wintypes.BOOL


class Keyboard:
    """Holds and releases a key chord. Releases Win keys last so Start never pops up."""

    def __init__(self, vks, sender=None):
        self.vks = list(vks)
        self._send = sender or send_key
        self._held = False
        self._lock = threading.Lock()
        self.held_since = None

    @property
    def held(self):
        return self._held

    def press(self):
        with self._lock:
            if self._held:
                return
            pressed = []
            try:
                for i, vk in enumerate(self.vks):
                    self._send(vk, False)
                    pressed.append(vk)
                    if i < len(self.vks) - 1:
                        time.sleep(0.012)
            except OSError:
                for vk in reversed(pressed):  # never leave half a chord held
                    try:
                        self._send(vk, True)
                    except OSError:
                        pass
                raise
            self._held = True
            self.held_since = time.monotonic()

    def release(self):
        with self._lock:
            if not self._held:
                return
            order = [v for v in self.vks if v not in WIN_KEYS] + [v for v in self.vks if v in WIN_KEYS]
            for i, vk in enumerate(order):
                try:
                    self._send(vk, True)
                except OSError:
                    pass
                if i < len(order) - 1:
                    time.sleep(0.012)
            self._held = False
            self.held_since = None


# ------------------------------------------------------------ audio device choice

CABLE_KINDS = (
    # (text in the output's name, text in the matching input's name, what Wispr Flow lists)
    ("cable input", "cable output", "CABLE Output (VB-Audio Virtual Cable)"),
    ("steam streaming mic", "steam streaming mic", "Microphone (Steam Streaming Microphone)"),
)


def choose_output(setting, outputs):
    if setting and str(setting).lower() != "auto":
        want = str(setting).lower()
        for i, n in enumerate(outputs):
            if want in n.lower():
                return i, n
        return None, None
    for out_hint, _, _ in CABLE_KINDS:
        for i, n in enumerate(outputs):
            if out_hint in n.lower():
                return i, n
    return None, None


def wispr_mic_name(out_name):
    o = out_name.lower()
    for out_hint, _, listed in CABLE_KINDS:
        if out_hint in o:
            return listed
    return "the input that matches %s" % out_name


def matching_input(out_name, inputs):
    o = out_name.lower()
    for out_hint, in_hint, _ in CABLE_KINDS:
        if out_hint in o:
            for i, n in enumerate(inputs):
                if in_hint in n.lower():
                    return i, n
    return None, None


# --------------------------------------------------------------- audio helpers

def make_tone(freq, seconds, amp=0.25):
    n = int(RATE * seconds)
    fade = int(RATE * 0.01)
    a = array("h", [0] * n)
    for i in range(n):
        g = min(1.0, i / fade, (n - 1 - i) / fade) if fade else 1.0
        a[i] = int(amp * g * 32767 * math.sin(2 * math.pi * freq * i / RATE))
    return a.tobytes()


def tone_level(pcm, freq):
    """(amplitude of `freq` in the signal, overall RMS), both on a 0..32767 scale."""
    a = array("h")
    a.frombytes(pcm[: len(pcm) // 2 * 2])
    n = len(a)
    if n == 0:
        return 0.0, 0.0
    k = 2 * math.cos(2 * math.pi * freq / RATE)
    s1 = s2 = 0.0
    total = 0.0
    for x in a:
        s0 = x + k * s1 - s2
        s2, s1 = s1, s0
        total += x * x
    power = s1 * s1 + s2 * s2 - k * s1 * s2
    return 2 * math.sqrt(max(power, 0.0)) / n, math.sqrt(total / n)


def apply_gain(data, gain):
    if abs(gain - 1.0) < 1e-3:
        return data
    if len(data) % 2:
        data = data[:-1]
    if audioop is not None:
        return audioop.mul(data, 2, gain)
    a = array("h")
    a.frombytes(data)
    for i, v in enumerate(a):
        v = int(v * gain)
        a[i] = 32767 if v > 32767 else (-32768 if v < -32768 else v)
    return a.tobytes()


# ---------------------------------------------------------- Wispr Flow mic watch

class WisprMicWatch:
    """Tells when Wispr Flow has its microphone open, from Windows' privacy records."""

    KEY = (r"Software\Microsoft\Windows\CurrentVersion\CapabilityAccessManager"
           r"\ConsentStore\microphone\NonPackaged")

    def __init__(self):
        self.ok = False
        self.subkeys = []
        if not IS_WINDOWS:
            return
        try:
            import winreg
            self.winreg = winreg
            self.rescan()
        except Exception:
            self.ok = False

    def rescan(self):
        if not IS_WINDOWS:
            return
        winreg = self.winreg
        found = []
        try:
            with winreg.OpenKey(winreg.HKEY_CURRENT_USER, self.KEY) as k:
                i = 0
                while True:
                    try:
                        name = winreg.EnumKey(k, i)
                    except OSError:
                        break
                    i += 1
                    if name.lower().endswith("#wispr flow.exe") and os.path.isfile(name.replace("#", "\\")):
                        found.append(name)
        except OSError:
            pass
        self.subkeys = found
        self.ok = bool(found)

    def read(self):
        """(latest start time, mic in use right now)."""
        latest, in_use = 0, False
        for name in self.subkeys:
            try:
                with self.winreg.OpenKey(self.winreg.HKEY_CURRENT_USER, self.KEY + "\\" + name) as k:
                    start = int(self.winreg.QueryValueEx(k, "LastUsedTimeStart")[0])
                    stop = int(self.winreg.QueryValueEx(k, "LastUsedTimeStop")[0])
            except (OSError, ValueError, TypeError):
                continue
            latest = max(latest, start)
            if start and stop == 0:
                in_use = True
        return latest, in_use


def wispr_running():
    if not IS_WINDOWS:
        return None
    try:
        out = subprocess.run(["tasklist", "/FI", "IMAGENAME eq Wispr Flow.exe", "/NH"],
                             capture_output=True, text=True, timeout=10,
                             creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0)).stdout
        return "wispr flow.exe" in out.lower()
    except (OSError, subprocess.SubprocessError):
        return None


# ---------------------------------------------------------------------- talker

class Talker(threading.Thread):
    """Runs one dictation at a time: hold the shortcut, play audio, drain, let go."""

    STALL_SECONDS = 4.0  # the watch sends audio every 20 ms while talking; silence this long = gone

    def __init__(self, cfg, keyboard, audio, mic, notify):
        super().__init__(name="talker", daemon=True)
        self.q = queue.Queue()
        self.kb = keyboard
        self.audio = audio
        self.mic = mic
        self.notify = notify
        self.start_delay = max(0.12, cfg["start_delay_ms"] / 1000.0)
        self.end_delay = max(0.0, cfg["end_delay_ms"] / 1000.0)
        self.max_talk = max(5.0, float(cfg["max_talk_seconds"]))
        self.gain = 10 ** (float(cfg.get("gain_db", 0.0)) / 20.0)
        self.idle_since = time.monotonic()
        self.warned_mic = False
        self.dictations = 0

    def post(self, kind, conn, data=None):
        self.q.put((kind, conn, data))

    def run(self):
        nxt = None
        while True:
            if nxt is None:
                try:
                    ev = self.q.get(timeout=1.0)
                except queue.Empty:
                    if time.monotonic() - self.idle_since > 15:
                        self.audio.close()
                    continue
                if ev[0] != "start":
                    continue  # stray audio/stop outside a dictation
                nxt = ev[1]
            owner, nxt = nxt, None
            try:
                nxt = self._dictation(owner)
            except Exception as e:  # never leave keys held
                log("Error during dictation: %s" % e)
                self.kb.release()
            self.idle_since = time.monotonic()

    def _dictation(self, owner):
        """Returns the id of a newer connection that started talking, if any."""
        t0 = time.monotonic()
        try:
            self.audio.open()
        except AudioError as e:
            log("Can't open the audio device: %s" % e)
            self.notify(owner, T_DONE, b"error:audio")
            self._discard(owner)
            return None

        if self.mic.ok:
            before, _ = self.mic.read()
        else:
            self.mic.rescan()
            before = self.mic.read()[0] if self.mic.ok else 0
        mic_seen = False
        last_mic_poll = 0.0

        self.kb.press()
        log("Listening...")
        pending, playing, received = [], False, 0
        reason, superseded_by = "done", None
        soft_start = t0 + self.start_delay
        hard_start = soft_start + 0.45
        last_rx = t0

        while True:
            now = time.monotonic()
            if now - last_rx > self.STALL_SECONDS:
                reason = "watch stopped sending"  # e.g. Wi-Fi dropped without closing the link
                break
            if self.mic.ok and not mic_seen and now - last_mic_poll >= (0.015 if not playing else 0.2):
                last_mic_poll = now
                latest, in_use = self.mic.read()
                mic_seen = in_use or latest != before
            if not playing and now >= soft_start and (mic_seen or not self.mic.ok or now >= hard_start):
                for chunk in pending:
                    self.audio.write(chunk)
                pending, playing = [], True
            if now - t0 > self.max_talk:
                reason = "time limit"
                break
            try:
                kind, conn, data = self.q.get(timeout=0.015 if not playing else 0.2)
            except queue.Empty:
                continue
            if conn != owner:
                if kind == "start":  # the watch reconnected and is talking on a new link
                    superseded_by, reason = conn, "reconnected"
                    break
                continue
            if kind == "audio":
                last_rx = time.monotonic()
                data = apply_gain(data, self.gain)
                received += len(data)
                if playing:
                    self.audio.write(data)
                else:
                    pending.append(data)
            elif kind == "stop":
                break
            elif kind == "lost":
                reason = "connection lost"
                break

        if not playing:  # stopped before Wispr Flow had its full head start
            while time.monotonic() < soft_start:
                time.sleep(0.01)
            for chunk in pending:
                self.audio.write(chunk)
        self.audio.wait_drained(self.audio.queued_seconds() + 3.0)
        time.sleep(self.end_delay)
        self.kb.release()

        if self.mic.ok and not mic_seen:
            latest, in_use = self.mic.read()
            mic_seen = in_use or latest != before
        secs = received / float(BYTES_PER_SEC)
        self.dictations += 1
        note = "" if reason == "done" else " (%s)" % reason
        log("Sent %.1f s of audio to Wispr Flow%s" % (secs, note))
        if self.mic.ok and not mic_seen and not self.warned_mic:
            self.warned_mic = True
            log("  Heads up: Wispr Flow didn't open its microphone that time. Make sure Wispr Flow is")
            log("  running and its push-to-talk shortcut is %s." % shortcut_label(self.kb.vks))
        self.notify(owner, T_DONE, ("%.1f" % secs).encode())
        return superseded_by

    def _discard(self, owner):
        end = time.monotonic() + self.max_talk
        while time.monotonic() < end:
            try:
                kind, conn, _ = self.q.get(timeout=1.0)
            except queue.Empty:
                continue
            if conn == owner and kind in ("stop", "lost"):
                return


# ---------------------------------------------------------------------- network

def recv_exact(sock, n):
    buf = bytearray()
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("closed")
        buf += chunk
    return bytes(buf)


class Conn:
    def __init__(self, cid, sock, name, addr):
        self.id, self.sock, self.name, self.addr = cid, sock, name, addr
        self.lock = threading.Lock()

    def send(self, ftype, payload=b""):
        with self.lock:
            self.sock.sendall(struct.pack(">BH", ftype, len(payload)) + payload)

    def close(self):
        try:
            self.sock.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        try:
            self.sock.close()
        except OSError:
            pass


class Server:
    def __init__(self, port, token, talker_factory):
        self.port = port
        self.token = token
        self.talker = talker_factory(self.send_to)
        self.active = None
        self.lock = threading.Lock()
        self.ids = 0

    def start(self, host="0.0.0.0"):
        # Windows' default rules already refuse a second copy and allow a quick restart;
        # elsewhere SO_REUSEADDR is needed for the quick restart.
        tcp = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        if not IS_WINDOWS:
            tcp.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        tcp.bind((host, self.port))
        tcp.listen(4)
        udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        udp.bind((host, self.port + 1))
        self.tcp, self.udp = tcp, udp
        self.talker.start()
        threading.Thread(target=self._accept_loop, name="accept", daemon=True).start()
        threading.Thread(target=self._discovery_loop, name="discovery", daemon=True).start()

    def send_to(self, cid, ftype, payload):
        with self.lock:
            conn = self.active if self.active and self.active.id == cid else None
        if conn:
            try:
                conn.send(ftype, payload)
            except OSError:
                pass

    def _accept_loop(self):
        while True:
            try:
                sock, addr = self.tcp.accept()
            except OSError:
                time.sleep(0.5)
                continue
            threading.Thread(target=self._handle, args=(sock, addr), name="conn", daemon=True).start()

    def _handle(self, sock, addr):
        conn = None
        try:
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            sock.settimeout(8)
            nonce = secrets.token_bytes(16)
            sock.sendall(HELLO + bytes([PROTO]) + nonce)
            head = recv_exact(sock, 37)
            if head[:4] != b"AUTH":
                return
            name = recv_exact(sock, head[36]).decode("utf-8", "replace") if head[36] else "watch"
            want = hmac.new(self.token, AUTH_LABEL + nonce, hashlib.sha256).digest()
            if not hmac.compare_digest(head[4:36], want):
                log("Refused a connection from %s: wrong pairing key. Run --pair to re-pair the watch." % addr[0])
                time.sleep(1.0)
                return
            sock.sendall(b"OKAY")
            sock.settimeout(12)
            with self.lock:
                self.ids += 1
                conn = Conn(self.ids, sock, name, addr)
                old, self.active = self.active, conn
            if old:
                old.close()
            log("Watch connected: %s (%s)" % (name, addr[0]))
            while True:
                h = recv_exact(sock, 3)
                ftype, n = h[0], (h[1] << 8) | h[2]
                if n > MAX_FRAME:
                    raise ValueError("frame too large")
                payload = recv_exact(sock, n) if n else b""
                if ftype == T_AUDIO:
                    self.talker.post("audio", conn.id, payload)
                elif ftype == T_PING:
                    conn.send(T_PONG)
                elif ftype == T_START:
                    self.talker.post("start", conn.id)
                elif ftype == T_STOP:
                    self.talker.post("stop", conn.id)
        except (OSError, ValueError, ConnectionError):
            pass
        finally:
            if conn is not None:
                self.talker.post("lost", conn.id)
                with self.lock:
                    still_active = self.active is conn
                    if still_active:
                        self.active = None
                if still_active:
                    log("Watch disconnected")
            try:
                sock.close()
            except OSError:
                pass

    def _discovery_loop(self):
        port_bytes = struct.pack(">H", self.port)
        while True:
            try:
                data, addr = self.udp.recvfrom(256)
            except OSError:
                time.sleep(0.5)
                continue
            if len(data) != 29 or data[:5] != b"WMIC?":
                continue
            nonce, tag = data[5:13], data[13:29]
            want = hmac.new(self.token, b"probe" + nonce, hashlib.sha256).digest()[:16]
            if not hmac.compare_digest(tag, want):
                continue
            reply_tag = hmac.new(self.token, b"here" + nonce + port_bytes, hashlib.sha256).digest()[:16]
            try:
                self.udp.sendto(b"WMIC!" + nonce + port_bytes + reply_tag, addr)
            except OSError:
                pass


def local_ipv4s():
    ips = set()
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ips.add(info[4][0])
    except OSError:
        pass
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))
        ips.add(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    keep = [ip for ip in ips if not ip.startswith(("127.", "169.254."))]
    # home networks first, Hyper-V/WSL switches (172.16-31) last
    return sorted(keep, key=lambda ip: (ip.startswith("172."), ip))


# ---------------------------------------------------------------------- config

CONFIG_HELP = ("token is the pairing key; run --pair to send it to the watch. shortcut 'auto' reads "
               "Wispr Flow's push-to-talk keys. Raise gain_db (try 6) if Wispr Flow says the audio is too "
               "quiet. Raise end_delay_ms if your last word gets cut off.")


def valid_token(tok):
    try:
        return len(bytes.fromhex(str(tok).strip())) >= 16
    except ValueError:
        return False


def load_config():
    """Reads wristmic_config.json, creating it (with a new pairing key) when needed."""
    cfg = dict(DEFAULT_CONFIG)
    user = {}
    if os.path.isfile(CONFIG_PATH):
        try:
            with open(CONFIG_PATH, "r", encoding="utf-8-sig") as f:
                user = json.load(f)
            cfg.update({k: v for k, v in user.items() if not k.startswith("_")})
        except (OSError, ValueError) as e:
            raise SystemExit("Couldn't read %s: %s" % (CONFIG_PATH, e))
    cfg["_new_token"] = False
    if not valid_token(cfg.get("token")):
        cfg["token"] = secrets.token_hex(16)
        cfg["_new_token"] = True
        saved = dict(user)
        saved.update({k: v for k, v in cfg.items() if not k.startswith("_")})
        saved["_help"] = CONFIG_HELP
        try:
            with open(CONFIG_PATH, "w", encoding="utf-8") as f:
                json.dump(saved, f, indent=2)
                f.write("\n")
        except OSError as e:
            raise SystemExit("Couldn't save the new pairing key to %s: %s" % (CONFIG_PATH, e))
    return cfg


def token_bytes(cfg):
    return bytes.fromhex(str(cfg["token"]).strip())


def pair_command(cfg):
    """The adb command that gives the watch this PC's pairing key and address."""
    ips = local_ipv4s()
    host = ips[0] if ips else ""
    cmd = ("adb shell am broadcast --include-stopped-packages -n com.wristmic.app/.PairReceiver "
           "--es token %s" % str(cfg["token"]).strip().lower())
    if host:
        cmd += " --es host %s" % host
    return cmd + " --ei port %d" % int(cfg["port"])


def say_pairing(cfg):
    say("To pair your watch, connect adb to it (see the README), then run this on this PC:")
    say("")
    say("  " + pair_command(cfg))
    say("")
    say("adb should print: result=1, data=\"WristMic paired with ...\". With more than one device")
    say("connected, add -s <watch address> right after adb.")


def pick_output(cfg):
    outputs = list_outputs()
    idx, name = choose_output(cfg.get("audio_device", "auto"), outputs)
    return idx, name, outputs


# ---------------------------------------------------------------------- commands

def cmd_devices(cfg):
    outs, ins = list_outputs(), list_inputs()
    idx, name = choose_output(cfg.get("audio_device", "auto"), outs)
    say("Audio outputs (WristMic plays your voice into one of these):")
    for i, n in enumerate(outs):
        say("  %s %d: %s" % ("->" if i == idx else "  ", i, n))
    say("Audio inputs:")
    for i, n in enumerate(ins):
        say("     %d: %s" % (i, n))
    say("(Windows shortens device names to 31 characters here.)")


def loopback_check(out_idx, out_name):
    """Play a tone into the cable and listen on its other end."""
    ins = list_inputs()
    in_idx, in_name = matching_input(out_name, ins)
    if in_idx is None:
        return None, "no matching input found for %s" % out_name
    result = {}

    def rec():
        try:
            result["pcm"] = record_input(in_idx, 1.6)
        except AudioError as e:
            result["err"] = str(e)

    t = threading.Thread(target=rec)
    t.start()
    time.sleep(0.25)
    out = WinAudioOut(out_idx, out_name)
    try:
        out.open()
        out.write(make_tone(1000, 1.0))
        out.wait_drained(3.0)
    finally:
        out.close()
    t.join(5)
    if "err" in result:
        return False, result["err"]
    amp, rms = tone_level(result.get("pcm", b""), 1000)
    return amp > 400 and amp > rms * 0.5, "test tone came back at %.0f%% strength from %s" % (
        min(100.0, amp / (0.25 * 32767) * 100), in_name)


def cmd_doctor(cfg):
    say("WristMic doctor %s" % VERSION)
    say("  Python %s on %s" % (sys.version.split()[0], sys.platform))
    ok = True

    vks, src = resolve_shortcut(cfg)
    say("\n[Shortcut] %s (%s)" % (shortcut_label(vks), src))
    running = wispr_running()
    if running is False:
        ok = False
        say("  ! Wispr Flow isn't running. Start it before using the watch.")
    elif running:
        say("  Wispr Flow is running.")
    mic = WisprMicWatch()
    say("  Can tell when Wispr Flow opens its mic: %s" % ("yes" if mic.ok else "no (that's fine)"))

    say("\n[Keyboard] checking that this PC accepts simulated key presses...")
    try:
        send_key(0x87, False)  # F24: no app uses it
        time.sleep(0.05)
        down = key_is_down(0x87)
        send_key(0x87, True)
        say("  %s" % ("OK" if down else "Sent, but Windows didn't report it as pressed (may still work)."))
    except OSError as e:
        ok = False
        say("  ! Simulated keys failed: %s" % e)

    idx, name, outs = pick_output(cfg)
    say("\n[Audio]")
    if idx is None:
        ok = False
        say("  ! No virtual cable found. Install VB-CABLE (vb-audio.com/Cable), then run this again.")
    else:
        say("  Plays into: %s" % name)
        say("  In Wispr Flow, pick microphone: %s" % wispr_mic_name(name))
        passed, detail = loopback_check(idx, name)
        if passed is None:
            say("  ? Couldn't test the cable: %s" % detail)
        elif passed:
            say("  OK, %s" % detail)
        else:
            ok = False
            say("  ! The cable didn't pass audio through (%s)." % detail)
            if "steam" in name.lower():
                say("    Steam's device doesn't work as a cable on this PC; install VB-CABLE instead.")

    say("\n[Network]")
    ips = local_ipv4s()
    say("  This PC: %s, port %d" % (", ".join(ips) or "no network address found", cfg["port"]))
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.bind(("0.0.0.0", cfg["port"]))
        s.close()
        say("  Port %d is free." % cfg["port"])
    except OSError:
        say("  Port %d is in use (is the receiver already running?)." % cfg["port"])
    say("\n[Pairing] To pair a watch, run: python wristmic_receiver.py --pair")
    say("\n%s" % ("Everything looks good." if ok else "Fix the items marked ! above."))
    return 0 if ok else 1


def cmd_test_tone(cfg):
    idx, name, _ = pick_output(cfg)
    if idx is None:
        raise SystemExit("No virtual cable found. Install VB-CABLE first.")
    say("Playing a 4-second tone into %s." % name)
    say("Watch the input level for %s in Wispr Flow or Windows Sound settings." % wispr_mic_name(name))
    out = WinAudioOut(idx, name)
    out.open()
    try:
        out.write(make_tone(440, 4.0, 0.3))
        out.wait_drained(6.0)
    finally:
        out.close()


def cmd_test_shortcut(cfg):
    vks, src = resolve_shortcut(cfg)
    kb = Keyboard(vks)
    say("Holding %s for 3 seconds (%s)." % (shortcut_label(vks), src))
    say("Wispr Flow's bar should switch to listening. Stay quiet so nothing gets typed.")
    _install_release_guards(kb)
    for n in (3, 2, 1):
        say("  %d..." % n)
        if n == 3:
            kb.press()
        time.sleep(1.0)
    kb.release()
    say("Released.")


def _install_release_guards(kb):
    atexit.register(kb.release)
    if IS_WINDOWS:
        def on_console_event(_ctrl):
            kb.release()
            return False
        handler = HandlerRoutine(on_console_event)
        kernel32.SetConsoleCtrlHandler(handler, True)
        _install_release_guards.handler = handler  # keep the callback alive


def run_receiver(cfg, simulate=False):
    token = token_bytes(cfg)
    vks, src = resolve_shortcut(cfg)
    if simulate:
        kb = Keyboard(vks, sender=lambda vk, up: log("[sim] %s %s" % (key_label(vk), "up" if up else "down")))
        audio = SimAudio()
        mic = WisprMicWatch()
        out_name = "simulated cable"
    else:
        idx, out_name, _ = pick_output(cfg)
        if idx is None:
            raise SystemExit("No virtual cable found. Install VB-CABLE (vb-audio.com/Cable), "
                             "then start WristMic again.")
        kb = Keyboard(vks)
        audio = WinAudioOut(idx, out_name)
        mic = WisprMicWatch()
    _install_release_guards(kb)

    server = Server(int(cfg["port"]), token,
                    lambda notify: Talker(cfg, kb, audio, mic, notify))
    try:
        server.start()
    except OSError as e:
        raise SystemExit("Can't listen on port %s (%s). Is WristMic already running?" % (cfg["port"], e))

    say("WristMic receiver %s" % VERSION)
    say("  Wispr Flow shortcut : %s (%s)" % (shortcut_label(vks), src))
    say("  Voice goes into     : %s" % out_name)
    if not simulate:
        say("  Wispr Flow mic      : %s" % wispr_mic_name(out_name))
    say("  This PC             : %s, port %s" % (", ".join(local_ipv4s()) or "?", cfg["port"]))
    if not simulate and wispr_running() is False:
        say("  ! Wispr Flow isn't running yet. Start it so your words get typed.")
    say("")
    if cfg.get("_new_token"):
        say("This is a new pairing key, so the watch doesn't know it yet.")
        say_pairing(cfg)
        say("")
    log("Waiting for the watch... (close this window or press Ctrl+C to quit)")

    try:
        hint_at = time.monotonic() + 45
        while True:
            time.sleep(0.5)
            if kb.held and kb.held_since and time.monotonic() - kb.held_since > float(cfg["max_talk_seconds"]) + 15:
                kb.release()  # last-resort guard; the talker should never get here
            if hint_at and time.monotonic() > hint_at:
                hint_at = None
                if server.active is None and server.talker.dictations == 0:
                    log("No watch yet. If the watch says it can't reach the PC, allow Python through")
                    log("Windows Firewall (see the setup guide).")
    except KeyboardInterrupt:
        pass
    finally:
        kb.release()
        audio.close()
        say("WristMic stopped.")


class SimAudio:
    """Stands in for the cable in --simulate mode: plays in real time, silently."""

    def __init__(self):
        self.until = 0.0
        self.written = 0
        self.lock = threading.Lock()
        self.is_open = False

    def open(self):
        self.is_open = True

    def write(self, data):
        with self.lock:
            now = time.monotonic()
            self.until = max(now, self.until) + len(data) / float(BYTES_PER_SEC)
            self.written += len(data)

    def queued_seconds(self):
        with self.lock:
            return max(0.0, self.until - time.monotonic())

    def wait_drained(self, timeout):
        end = time.monotonic() + timeout
        while self.queued_seconds() > 0 and time.monotonic() < end:
            time.sleep(0.01)

    def close(self):
        self.is_open = False


def main():
    ap = argparse.ArgumentParser(description="WristMic receiver: Galaxy Watch push-to-talk for Wispr Flow.")
    ap.add_argument("--pair", action="store_true", help="print the command that pairs the watch")
    ap.add_argument("--doctor", action="store_true", help="check the setup")
    ap.add_argument("--devices", action="store_true", help="list audio devices")
    ap.add_argument("--test-tone", action="store_true", help="play a tone into the cable")
    ap.add_argument("--test-shortcut", action="store_true", help="hold Wispr Flow's shortcut for 3 s")
    ap.add_argument("--simulate", action="store_true", help=argparse.SUPPRESS)
    args = ap.parse_args()
    cfg = load_config()

    if args.pair:
        return say_pairing(cfg)
    if args.simulate:
        return run_receiver(cfg, simulate=True)
    if not IS_WINDOWS:
        raise SystemExit("WristMic receiver runs on Windows.")
    if args.devices:
        return cmd_devices(cfg)
    if args.doctor:
        return cmd_doctor(cfg)
    if args.test_tone:
        return cmd_test_tone(cfg)
    if args.test_shortcut:
        return cmd_test_shortcut(cfg)
    return run_receiver(cfg)


if __name__ == "__main__":
    sys.exit(main())
