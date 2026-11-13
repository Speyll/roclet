# Changelog

## 0.2.0

- Added Speaker mode: receive a Roc `rtp+rs8m` stream from a PC and play it
  through the phone's speaker or headphones.
- Speaker mode targets about 120 ms of latency, uses RS8M FEC, and shows the
  phone's Wi-Fi address for the sender.
- Added audio-focus handling: pauses on transient focus loss, ducks when
  requested, and stops on permanent loss.
- Microphone mode is unchanged.
- Documented a PipeWire ROC sink setup for Linux senders.

## 0.1.1

- Finalized third-party and OpenFEC licensing notices.
- Cleaned up F-Droid packaging and reproducibility documentation.

## 0.1.0

First public release.

- Streams the phone microphone to a ROC receiver over Wi-Fi using Roc's `rtp+rs8m`
  (source) and `rs8m` (repair) profiles.
- Configurable receiver address and source/repair ports, remembered between launches.
- Foreground service: streaming continues with the screen off or the app in the
  background.
- Start and stop from the app or the ongoing notification.
- Light and dark themes.
