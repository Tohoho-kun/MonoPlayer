# Third-Party Open Source Licenses

MonoPlayer uses the following open-source libraries and components. All runtime app dependencies are licensed under the **Apache License 2.0**, and the native Rust DSP core (`rust/monoplayer_core`) has zero external crate dependencies.

## Runtime Android Dependencies

*(Versions match [`gradle/libs.versions.toml`](gradle/libs.versions.toml) and [`app/build.gradle.kts`](app/build.gradle.kts)).*

| Component | Artifact / Group | Version | Copyright / Maintainer | License |
| :--- | :--- | :--- | :--- | :--- |
| **AndroidX Media3** | `androidx.media3:media3-exoplayer`, `media3-session`, `media3-common` | `1.5.1` | The Android Open Source Project | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |
| **Jetpack Compose** | `androidx.compose:compose-bom` (`ui`, `ui-tooling-preview`, `material3`, `material-icons-extended`) | `2026.03.01` | The Android Open Source Project | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |
| **AndroidX Core KTX** | `androidx.core:core-ktx` | `1.18.0` | The Android Open Source Project | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |
| **AndroidX Activity Compose** | `androidx.activity:activity-compose` | `1.13.0` | The Android Open Source Project | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |
| **AndroidX Lifecycle** | `androidx.lifecycle:lifecycle-runtime-ktx`, `lifecycle-runtime-compose`, `lifecycle-viewmodel-compose`, `lifecycle-viewmodel-navigation3` | `2.10.0` | The Android Open Source Project | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |
| **AndroidX Navigation 3** | `androidx.navigation3:navigation3-runtime`, `navigation3-ui` | `1.0.1` | The Android Open Source Project | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |
| **AndroidX DocumentFile** | `androidx.documentfile:documentfile` | `1.0.1` | The Android Open Source Project | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |
| **Coil Compose** | `io.coil-kt:coil-compose` | `2.7.0` | Coil Contributors | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |
| **Kotlin & Compose Compiler** | `org.jetbrains.kotlin.*` | `2.3.20` | JetBrains s.r.o. & Kotlin Contributors | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |
| **kotlinx.serialization** | `org.jetbrains.kotlinx:kotlinx-serialization-json` | `1.8.0` | JetBrains s.r.o. | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |

## Native Rust DSP Crate (`rust/monoplayer_core`)

Per [`rust/monoplayer_core/Cargo.toml`](rust/monoplayer_core/Cargo.toml) and [`Cargo.lock`](rust/monoplayer_core/Cargo.lock), `monoplayer_core` depends solely on the Rust standard library (`std`) with **zero third-party Rust crates**.

## Test & Build-Only Dependencies

*(Used only during build/testing; not bundled in the application APK).*

| Component | Artifact | Version | License |
| :--- | :--- | :--- | :--- |
| **JUnit 4** | `junit:junit` | `4.13.2` | [Eclipse Public License 1.0](https://www.eclipse.org/legal/epl-v10.html) |
| **kotlinx-coroutines-test** | `org.jetbrains.kotlinx:kotlinx-coroutines-test` | `1.10.2` | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |
| **AndroidX Test** | `androidx.test:core`, `runner`, `ext.junit`, `espresso-core` | `1.7.0` / `1.3.0` / `3.7.0` | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |
| **Android Gradle Plugin** | `com.android.application` | `9.0.1` | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) |

## Material Theme Template Notice

Color and theme scaffolding files in [`app/src/main/kotlin/com/example/monoplayer/ui/theme/`](app/src/main/kotlin/com/example/monoplayer/ui/theme/) retain their original Apache License 2.0 header (`Copyright 2026 The Android Open Source Project`).
