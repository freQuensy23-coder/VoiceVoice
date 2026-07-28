# VoiceVoice

A modern Android starter app built with Kotlin, Jetpack Compose, Material 3,
Gradle Kotlin DSL, and a version catalog.

## Requirements

- Android Studio with Android SDK 37
- JDK 17

## Build

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew lintDebug
```

The app supports Android 8.0 (API 26) and newer.

## Codex-backed manual tests

The standalone Codex CLI Android manual-testing harness, YAML format, local commands, evidence contract, and CI trust boundary are documented in [manual_test/README.md](manual_test/README.md).
