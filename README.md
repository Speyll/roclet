# Roclet

<p align="center">
  <img src="fastlane/metadata/android/en-US/images/icon.png" width="128" alt="Roclet app icon" />
</p>

Roclet turns an Android phone into a wireless microphone for a
[Roc Toolkit](https://roc-streaming.org) receiver over Wi-Fi, or into a
wireless speaker for a Roc sender running on a PC.

[Source](https://github.com/Speyll/Roclet) · [Issues](https://github.com/Speyll/Roclet/issues) · [Releases](https://github.com/Speyll/Roclet/releases)

## Screenshots

<p align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/Screenshot_20261019_043238_Roclet.jpg" width="30%" alt="Roclet screenshot 1" />
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/Screenshot_20261019_043244_Roclet.jpg" width="30%" alt="Roclet screenshot 2" />
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/Screenshot_20261019_043252_Roclet.jpg" width="30%" alt="Roclet screenshot 3" />
</p>

## How it works

Roclet has two modes, selected with the tabs at the top of the screen:

- **Microphone** sends the phone's microphone as a Roc `rtp+rs8m` source
  stream, with a separate `rs8m` repair stream. On the receiver it appears as a
  normal audio source. The default ports are `10001` for source and `10002` for
  repair.
- **Speaker** receives a Roc `rtp+rs8m` source stream from a PC and plays it
  through the phone's speaker or headphones. It listens on the local ports
  `10001` (source) and `10002` (repair) by default. The sender must use RS8M
  FEC. The app shows the phone's Wi-Fi address so the PC can target it.

Only the service for the selected mode runs at a time.

## Requirements

- Android 10 (API 29) or newer on an `arm64-v8a` device.
- A Wi-Fi network shared with the other device.
- For **Microphone** mode, a Roc Toolkit receiver, such as `roc-recv` or the
  PipeWire/PulseAudio Roc module.
- For **Speaker** mode on Linux, PipeWire with the ROC sink module
  (`libpipewire-module-roc-sink`, packaged as `pipewire-roc` or similar by
  most distributions).

## Setup

Install Roclet from the
[releases page](https://github.com/Speyll/Roclet/releases), open the app, and
choose a mode. Release builds are signed; uninstall a debug build before
installing a release build.

For **Microphone** mode, start the receiver on the same network, enter its
address and ports, and tap the microphone button. See the
[Roc Toolkit documentation](https://roc-streaming.org/toolkit/docs/) for
receiver installation and usage.

For **Speaker** mode, start listening in the app, then configure the PC as a
Roc sender. Do not select both modes at once: the phone cannot send and receive
with the same ports.

### PipeWire sender (Linux)

Create a drop-in configuration, replacing `PHONE_IP` with the address shown in
the app:

```ini
# ~/.config/pipewire/pipewire.conf.d/roc-sink.conf
context.modules = [
    { name = libpipewire-module-roc-sink
      args = {
          remote.ip = "PHONE_IP"
          remote.source.port = 10001
          remote.repair.port = 10002
          fec.code = rs8m
      }
    }
]
```

Restart the user PipeWire services, then choose the new ROC output device:

```sh
systemctl --user restart pipewire pipewire-pulse
```

The ROC sink appears as an audio output. Select it in the system mixer and play
audio. The receiver targets roughly 120 ms of latency; it adapts to network
jitter but cannot go below the sender's packet and FEC buffering.

## Usage

- Use the settings button to change the address and source/repair ports. They
  are remembered per mode. The same panel links to the third-party notices.
- Tap the microphone button to start streaming or listening. In Microphone mode,
  allow microphone access when asked. On Android 13 and newer, allow
  notifications so the foreground notification can appear.
- The status line shows *Starting*, *Streaming*/*Listening*, *Paused*, or
  *Stopped*. Tap the button again, or use **Stop** in the notification, to stop.
- Streaming and playback continue while the screen is off or the app is in the
  background. Speaker mode pauses on transient audio-focus loss and stops when
  another app takes audio output permanently.

## Building

Roclet builds its native dependencies (Roc Toolkit, OpenFEC, roc-java, libuv,
SpeexDSP) from the pinned sources under `scripts/native/`:

```sh
scripts/native/build-native.sh all
```

Then build the app with the Gradle wrapper:

```sh
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

You need JDK 17+, Android SDK platform/build-tools 36, and Android NDK
`21.1.6352462`. Release builds are signed only when a signing-properties file is
present; otherwise they produce an unsigned APK. See
[`scripts/native/README.md`](scripts/native/README.md) for native build details.

## Privacy

Roclet sends or receives audio only with the peer you configure. It has no
analytics, ads, telemetry, accounts, or cloud service, and stores only the
address and ports on the device.

## Contributing

Pull requests and [issues](https://github.com/Speyll/Roclet/issues) are welcome.
Run `./gradlew :app:assembleDebug` before submitting changes.

## License

Roclet is licensed under [Apache-2.0](LICENSE). Third-party components retain
their respective licenses.
