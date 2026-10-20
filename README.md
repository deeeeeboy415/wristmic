# WristMic

Hold a button on your Galaxy Watch, talk, let go, and your words show up in whatever text box has focus on your Windows PC.

WristMic turns a Wear OS watch into a push-to-talk microphone for [Wispr Flow](https://wisprflow.ai). The watch streams its microphone to a small receiver on the PC. While you hold the button, the receiver holds Wispr Flow's push-to-talk shortcut and plays your voice into a virtual audio cable that Wispr Flow uses as its microphone. Wispr Flow then transcribes and types the text exactly as if you had used the shortcut at your desk.

On Samsung watches with a Quick button, the physical Quick button becomes the talk button, and it works from any screen.

```
watch mic ──► WristMic app ──Wi-Fi──► wristmic_receiver.py ──► virtual cable ──► Wispr Flow ──► your text box
                                       └─ holds Wispr Flow's push-to-talk keys while you talk
```

Tested on a Galaxy Watch8 Classic (Android 16) with Windows 11 and Wispr Flow 1.6. Other Wear OS 3+ watches should work with the on-screen button. The Quick button part relies on Samsung-specific behavior and has only been tried on the Watch8 Classic.

## What you need

- A Wear OS 3 or newer watch (Android 11 or later).
- A Windows 10 or 11 PC with Python 3.8 or newer. No extra packages.
- Wispr Flow for Windows.
- A virtual audio cable: [VB-CABLE](https://vb-audio.com/Cable/) (free), or the Steam Streaming Microphone that comes with Steam.
- adb (Android platform tools) on the PC, to install the app and pair it. `winget install Google.PlatformTools` is the quickest way to get it.
- The watch and the PC on the same home network.

## Setup

### 1. Start the receiver on the PC

Download `WristMic-PC.zip` from the [latest release](https://github.com/deeeeeboy415/wristmic/releases/latest) (or the `pc` folder of this repository) and unzip it anywhere.

Double-click **Start WristMic.bat**, or run `python wristmic_receiver.py`. On the first run it:

- creates `wristmic_config.json` with a new random pairing key,
- prints the command that pairs your watch (step 4),
- tells you which microphone to choose in Wispr Flow.

When Windows Firewall asks, allow Python on **private** networks. Without that, the watch can't reach the PC.

Keep the window open while you use the watch. To start it with Windows, put a shortcut to `Start WristMic.bat` in your Startup folder (press Win+R and enter `shell:startup`).

**Check WristMic setup.bat** tests the shortcut, the cable and the network whenever something seems off.

### 2. Point Wispr Flow at the cable

In Wispr Flow's settings, set the microphone to the recording side of the cable:

- VB-CABLE: **CABLE Output (VB-Audio Virtual Cable)**
- Steam: **Microphone (Steam Streaming Microphone)**

Leave Wispr Flow's push-to-talk shortcut alone. The receiver reads it from Wispr Flow's own settings when it starts (Left Ctrl + Left Win by default), so if you change the shortcut, just restart the receiver.

### 3. Install the watch app

1. Turn on Developer options on the watch. On a Galaxy Watch: Settings > About watch > Software information, then tap Software version until it says developer mode is on.
2. In Settings > Developer options, turn on **ADB debugging** and **Wireless debugging**. The watch must be on the same Wi-Fi as the PC.
3. In Wireless debugging, tap **Pair new device**. On the PC, run `adb pair` with the address and port shown under the pairing code, then type the code when asked:
   ```
   adb pair 192.168.1.50:37123
   ```
4. Connect to the address and port shown on the main Wireless debugging screen (a different port from the pairing one):
   ```
   adb connect 192.168.1.50:41235
   ```
5. Download `WristMic.apk` from the [latest release](https://github.com/deeeeeboy415/wristmic/releases/latest) (or [build it](#building)) and install it:
   ```
   adb install WristMic.apk
   ```
6. Open WristMic on the watch and allow the microphone.

The addresses above are examples. Use the ones your watch shows.

### 4. Pair the watch with the PC

```
python wristmic_receiver.py --pair
```

prints a command like this one, with your PC's key and address filled in:

```
adb shell am broadcast --include-stopped-packages -n com.wristmic.app/.PairReceiver --es token 3f9c0b7e... --es host 192.168.1.20 --ei port 47800
```

Run it while adb is connected to the watch. adb should answer `result=1, data="WristMic paired with 192.168.1.20:47800"`, and WristMic shows "Paired with your PC" if it's open. If more than one Android device is connected, add `-s` and the watch's address right after `adb`.

### 5. Talk

Start Wispr Flow and click into a text box on the PC. On the watch, open WristMic, hold the big button, talk, and let go. Wispr Flow types the text after you let go.

A quick tap does nothing. It has to be a hold of more than about a fifth of a second. WristMic keeps the quarter second of audio from just before your press, so the start of your first word isn't lost.

In WristMic's settings (tap **Settings** at the bottom of the screen) you can type the PC's address, search for the PC on Wi-Fi, and choose to start talking as soon as the app opens.

### 6. Optional: use the Quick button (Samsung)

Samsung's watches keep the Quick button away from apps, but they can start an app's service each time it's pressed and released. WristMic uses that for hold-to-talk from any screen.

1. In WristMic, tap **Settings**, then under Quick button tap **Accessibility settings** and turn on **WristMic button**. It doesn't read the screen or your button presses. Android blocks apps from bringing themselves to the front from the background, and an enabled accessibility service is one of the few exemptions. That's all this one is for.
2. Note the button's current action so you can restore it later:
   ```
   adb shell settings get global setting_action_button_press
   ```
3. Point the Quick button at WristMic:
   ```
   adb shell settings put global setting_action_button_press com.wristmic.app/com.wristmic.app.QuickButtonService
   ```
4. Hold the Quick button, talk, and let go. If WristMic isn't on screen, it opens.

To undo it, put back the value from step 2 with `adb shell settings put global setting_action_button_press` followed by that value. If step 2 printed `null`, run `adb shell settings delete global setting_action_button_press` instead. Choosing another action for the Quick button in the watch's own settings should also take it back.

## Settings

`wristmic_config.json` sits next to the receiver. Edit it with Notepad and restart the receiver.

| Setting | Default | What it does |
|---|---|---|
| `port` | `47800` | TCP port for audio. Discovery uses the next port (UDP). |
| `token` | made on first run | The pairing key. Run `--pair` again after changing it. |
| `shortcut` | `"auto"` | `auto` reads Wispr Flow's push-to-talk keys. Or set your own, like `"LCTRL+LWIN"`. |
| `audio_device` | `"auto"` | `auto` picks VB-CABLE, then Steam Streaming Microphone. Or part of a device name. |
| `gain_db` | `0` | Raise it (try `6`) if Wispr Flow says your audio is too quiet. |
| `start_delay_ms` | `350` | Head start Wispr Flow gets to open its microphone before your audio plays. |
| `end_delay_ms` | `400` | Extra time after your last word before the shortcut is released. |
| `max_talk_seconds` | `300` | Safety limit for one dictation. |

Other receiver commands: `--doctor` (the setup check), `--devices` (list audio devices), `--test-tone` (play a tone into the cable), `--test-shortcut` (hold Wispr Flow's shortcut for 3 seconds).

## Troubleshooting

- **The watch says "Not paired yet".** Run `python wristmic_receiver.py --pair` and then the command it prints.
- **The watch keeps looking for the PC.** Check that the receiver is running, that Python is allowed through Windows Firewall on private networks, and that both are on the same network. In WristMic's settings, try **Find PC on Wi-Fi** or type the PC's address. The setup check lists the PC's addresses.
- **The receiver says "wrong pairing key".** The watch has a different key than the PC, for example after `wristmic_config.json` was replaced. Pair again.
- **Nothing gets typed.** Make sure Wispr Flow is running and set to the cable microphone. `--test-shortcut` should switch Wispr Flow's bar to listening, and `--test-tone` should move the cable microphone's level.
- **The first word is missing.** Raise `start_delay_ms` (try `600`). Wispr Flow can be slow to open its microphone, especially right after it starts.
- **The last word is cut off.** Raise `end_delay_ms`.
- **adb can't connect anymore.** Wireless debugging turns itself off when the watch's Wi-Fi sleeps, and the port changes each time. Turn it back on and connect to the new port.
- **The Quick button does nothing.** Check the setting with `adb shell settings get global setting_action_button_press`. WristMic's settings show the time of the last Quick button press it received. A watch software update can change or remove this mechanism.

## How it works

**Watch app** (`watch/`, plain Java, no Gradle)

- While the app is open, it records 16 kHz mono audio in 20 ms frames and keeps the last 240 ms, so a press includes the start of your first word. A press longer than 220 ms starts a dictation.
- It sends audio over one TCP connection to port 47800. It asks Wear OS to bring Wi-Fi up and uses it when it can, and otherwise falls back to whatever network the watch has, usually Bluetooth through the phone.
- Audio recorded while the connection is coming up is sent once it connects. Anything it can't send within 15 seconds is dropped, so a late connection never types old words.
- It finds the PC at its paired address, or with a UDP broadcast on port 47801. The probe and the reply are both signed with the pairing key.

**Receiver** (`pc/wristmic_receiver.py`, Python standard library only)

- Every connection must pass an HMAC-SHA256 challenge. The receiver sends a random nonce, and the watch has to answer with HMAC(key, "wristmic-v1" + nonce).
- When you start talking, it presses Wispr Flow's shortcut with `SendInput` and buffers your audio. Playback into the cable starts once Wispr Flow has opened its microphone (it watches Windows' microphone-in-use records), after at least `start_delay_ms` and at most 0.45 seconds more.
- When you stop, it waits until the last of the audio has played, plus `end_delay_ms`, then releases the shortcut and reports back to the watch.
- It lets go of the keys if the watch disconnects, if audio stops arriving for 4 seconds, or if the program exits.

**Wire format**: `[type:1][length:2, big-endian][payload]`. Watch to PC: START `0x01`, AUDIO `0x02`, STOP `0x03`, PING `0x05`. PC to watch: PONG `0x11`, DONE `0x13`.

### The Samsung Quick button

On the Galaxy Watch8 Classic, the Quick button is `KEYCODE_STEM_1` (scan code 582). Samsung's window manager consumes it before apps or accessibility services can see it. Instead, on every press and release it starts the service named in the global setting `setting_action_button_press`, after giving that app a short exemption from power saving, and passes these int extras:

- `ACTION_KEY_EVENT`: 0 pressed, 1 released, 2 short press, 3 long press
- `ACTION_KEY_COUNT`: the press count

There's no allowlist, so it can be any app's service. `QuickButtonService` turns pressed and released into hold-to-talk. Android still blocks apps from opening their own screen from the background, and an enabled accessibility service is one of the few exemptions a regular app can get, which is why `ButtonService` exists. It asks for no accessibility events and doesn't filter keys.

None of this is documented by Samsung, so a software update could change it.

## Building

The watch app needs JDK 17 or newer, the Android SDK platform 35 and build-tools 35. No Gradle.

```
ANDROID_HOME=~/Android/Sdk ./watch/build.sh
```

It's a bash script, so on Windows run it in Git Bash or WSL. You can also point it at the tools directly with `ANDROID_JAR` and `BUILD_TOOLS`.

The first build creates `watch/wristmic.keystore` (password `wristmic`) and signs the app with it. Keep that file: Android only installs updates signed with the same key. The release APK is signed with a different key, so uninstall it before installing your own build, then pair again.

Tests:

```
python3 tests/test_receiver.py   # the receiver end to end with simulated keys and audio, on any OS
bash tests/jvm/run.sh            # the watch's network code on a desktop JVM against the receiver
```

`watch/make_icons.py` redraws the launcher icon (needs Pillow).

## Security and privacy

- The pairing key is random for each PC. It lives in `wristmic_config.json` next to the receiver and in WristMic's private storage on the watch. Don't share or commit that file; this repository's `.gitignore` excludes it.
- Only adb can pair the watch. The pairing receiver requires a permission that the adb shell has and ordinary apps don't, so other apps on the watch can't change the key.
- A device without the key is refused before any audio is accepted.
- Audio isn't encrypted on the network. Anyone who can capture traffic on your network could listen to what you dictate, so use it on a network you trust.
- The receiver only presses Wispr Flow's shortcut keys. It never types text itself.

## Not affiliated

WristMic is an independent project. It isn't affiliated with or endorsed by Samsung, Google, Wispr, Valve, or VB-Audio. Galaxy Watch, Wear OS, Wispr Flow, Steam and VB-CABLE are trademarks of their owners.

## License

[MIT](LICENSE)
