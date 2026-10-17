# MImic — Build & Install Guide

## Requirements
- Android Studio (recent, with AGP 9.x support) or the bundled Gradle wrapper.
- JDK 17+ (the project's Gradle toolchain resolves this automatically).
- Android SDK with API 37 installed (`sdk.dir` in `local.properties`).
- A device/emulator on Android 7.0 (API 24) or newer.

## Build a debug APK
```
./gradlew :app:assembleDebug
```
Output: `app/build/outputs/apk/debug/app-debug.apk`

## Install on a connected device
```
./gradlew :app:installDebug
```
or in Android Studio press **Run ▶**. On first launch, grant audio access so the app can
read the music already on the device.

## Run tests
```
./gradlew :app:testDebugUnitTest            # JVM unit tests (no device)
./gradlew :app:connectedDebugAndroidTest    # Room tests (needs device/emulator)
```

## Signed release build (optional)
Release signing activates only when a `keystore.properties` exists at the project root.
It is gitignored and never committed. Create it with:
```
storeFile=/absolute/path/to/your-release-key.jks
storePassword=********
keyAlias=mimic
keyPassword=********
```
Generate a key with `keytool -genkeypair -v -keystore your-release-key.jks -keyalg RSA \
-keysize 2048 -validity 10000 -alias mimic`. Then:
```
./gradlew :app:assembleRelease
```
Without `keystore.properties`, the release build is produced **unsigned** (it will not
fail). Keep the same keystore for all future updates so Android accepts them as upgrades.

## App identity
- applicationId: `com.example.mimic` (unchanged across versions for update compatibility)
- versionName 2.0 / versionCode 2
