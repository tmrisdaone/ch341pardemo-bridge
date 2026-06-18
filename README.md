# CH341PAR Demo Bridge

An Android UI around WCH's official `CH341PARV1.1.jar` library, plus a foreground
service that exposes the connected CH341/CH347 chip directly to Termux over an
AF_UNIX local socket.

This is meant for the Termux + USB-OTG + dev-board workflow: plug a CH341A
programmer (or CH347 eval board) into your phone, tap "Open Device" in the app,
and then `cat /dev/urandom | ./termux/ch341-bridge` from Termux to talk to the
chip without leaving the terminal.

> **No Shizuku / root required.** The app uses the standard `UsbManager` host API
> and `LocalServerSocket`, both available since API 19 (Android 4.4).

## How it works

```
┌───────────────────────────────────────┐
│  Android app                          │
│                                       │
│  MainActivity       TermuxBridge      │
│  ───────────────   ─────────────────  │
│  CH341PARV1.1.jar  UsbManager.open    │
│        │                 │            │
│        ▼                 ▼            │
│  EPP/MEM/SPI/I2C  bulkTransfer (async)│
│   UI buttons            │             │
│                          ▼             │
│                 LocalServerSocket       │
│                 ("ch341_bridge")        │
└──────────────────────┬────────────────┘
                       │ AF_UNIX
                       ▼  (same UID: Termux)
/data/data/cn.wch.ch341pardemo/ch341_bridge
                       │
                       ▼
       ~/ch341-bridge (Python/soctat wrapper)
```

The Android service runs as a foreground notification so Android won't kill
it when Termux isn't visible. The local socket namespace means only same-package
processes (Termux) can connect — no TCP port, no permission prompts.

## Install

1. Build and install the APK:

   ```bash
   ./gradlew :app:assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

   Or download a release APK from the **Actions** tab GitHub workflow.

2. Open the app once. Grant USB permission when prompted. The chip info shows in
   the status banner.

3. From the app's title → tap **Open Device**. The foreground notification now
   shows `Connected: 0x1A86:0x7523`. `TermuxBridge` is now running.

## Use from Termux

The included wrapper `termux/ch341-bridge` opens the AF_UNIX socket and pipes
stdin/stdout:

```bash
sudo apt install socat                       # optional, faster proxy
cp termux/ch341-bridge ~/ && chmod +x ~/ch341-bridge

# one-shot write
printf '\xA1\x00\x00\x00' | ~/ch341-bridge
# interactive (stty raw helps)
./ch341-bridge
# check status
./ch341-bridge --status
```

For ergonomics you can symlink it into PATH:

```bash
ln -s "$PWD/termux/ch341-bridge" /data/data/com.termux/files/usr/bin/ch341
ch341 --status
```

## Build

Stack:

- Android Gradle Plugin 8.6+
- `compileSdk 34`, `minSdk 19`
- Java 17 (toolchain), `source/target 1.8`
- Material Components 1.8, AppCompat 1.6
- **WCH `CH341PARV1.1.jar`** (vendored in `app/libs/`, not open source)

Recommended:

```bash
./gradlew assembleDebug
./gradlew lint
./gradlew test
```

CI builds & uploads APKs: see `.github/workflows/build.yml`.

## Debugging tips

| Symptom                          | Cause                                       |
|----------------------------------|---------------------------------------------|
| Bridge socket not found          | App not installed or service not started    |
| `permission denied` from client  | Termux app is a different UID (normal)      |
| `bulkTransfer(timeout)`          | USB not claimed or endpoint stalled         |
| Status banner shows `Waiting…`   | USB permission not yet granted              |
| Build fails `aapt2`              | Run `./gradlew --stop && ./gradlew clean`   |

Enable verbose logs:

```bash
adb logcat -s TermuxBridge:* CH341Manager:*
```

## License

MIT for everything in this repo. WCH's `CH341PARV1.1.jar` is copyrighted by
Nanjing Qinheng Microelectronics (WCH) and is included only for use with their
own hardware.

## Acknowledgments

- WCH (Nanjing Qinheng Microelectronics) for the underlying CH341PAR SDK.
- Termux team for `LocalServerSocket` exposing a sane IPC surface on Android.
