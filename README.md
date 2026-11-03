# SR Video Loop

A native Android video player with independently looping background music.

## Features
- Video repeats automatically.
- Select an audio file as background music.
- Background music repeats automatically.
- The video's original audio is muted while external music is active.
- Removing external music restores the video's original audio.
- Separate music volume control.

## Build
Requirements: JDK 17 or newer, Android SDK Platform 36, and Android SDK Build Tools.

From the project directory:

```bash
gradle assembleDebug
```

APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install with:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Package: `com.ariesr91.videoloop`

The project uses Android Gradle Plugin 8.13.0, Kotlin 2.2.20, and AndroidX Media3 1.8.0.
