# MonoPlayer

Android music player built with Kotlin, Jetpack Compose, Media3 (ExoPlayer), and a native Rust DSP engine (`monoplayer_core`) bridged via JNI.

## Features

- **Phase-Coherent Stereo-to-Mono DSP (Rust / JNI)**
  - STFT-based Hilbert / Energy / Phase-Aligned / Dynamic PCA / Naive mono algorithms + Stereo Reference mode
  - Z-axis Spatial Depth & Narrow Stereo stage
  - Anti-Clipping & De-Clicking reconstruction (`DeclipEngine`)
  - Real-time ITU-R BS.1770-4 K-weighted Loudness Normalizer (`Normal Hi-Fi` & `Car Cabin` modes with GPS/IMU vehicle speed tracking)
  - 3-Band Dynamic EQ + 10-Band TPT SVF Equalizer
  - `MasteringPeakLimiter` + smooth soft-clipper protecting outputs below `0 dBFS`
- **Real-Time Latency-Compensated Visualizer & Car Cockpit HUD**
  - DAW-style Input/Output Limiter & Boost transfer curve monitor in Car Cabin mode showing raw input vs boosted/limited output live
  - Spectrum, Phase Scope, Rainy Glass (`RainGlassEngine`), and De-clip telemetry views synced via `TelemetrySyncBuffer`
- **Library & Playback**
  - Background playback via `MediaSessionService` (`MonoPlaybackService`), playlist management, queue/position persistence, and Bluetooth auto-play support

## Project Structure

- `app/` — Android Jetpack Compose app (`minSdk 26`, `targetSdk 36`, `Java 17`)
- `rust/monoplayer_core/` — Native Rust DSP & visual kernel crate (`cdylib` + `rlib`)
- `app/src/main/jniLibs/` — Prebuilt `arm64-v8a` and `x86_64` shared libraries (`libmonoplayer_core.so`)

## Building & Testing

### Android App & JVM Unit Tests

```bash
./gradlew testDebugUnitTest assembleDebug
```

### Rust DSP Crate (`monoplayer_core`)

```bash
cd rust/monoplayer_core
cargo test
cargo build --release
```

To cross-compile Android `.so` binaries, copy `rust/monoplayer_core/.cargo/config.toml.example` to `rust/monoplayer_core/.cargo/config.toml` with your NDK paths and build:

```bash
cd rust/monoplayer_core
cargo build --release --target aarch64-linux-android
cargo build --release --target x86_64-linux-android
```

## Open Source Licenses & Notices

All third-party libraries and copyright/license attributions used by MonoPlayer are listed in [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md) and [NOTICE](NOTICE), and can also be viewed in-app via **設定 → オープンソースライセンス**.

