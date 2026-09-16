# YDL Android — V1 (Bundled)

yt-dlp is bundled inside the APK at build time via Chaquopy.
No server needed. Works offline after install.

## File placement

| File | Destination in your project |
|---|---|
| `src/main/python/ydl_bridge.py` | `app/src/main/python/ydl_bridge.py` |
| `kotlin/YdlBridge.kt` | your Kotlin source package |
| `kotlin/YdlModels.kt` | your Kotlin source package |
| `kotlin/YdlParser.kt` | your Kotlin source package |
| `kotlin/YdlDownloadManager.kt` | your Kotlin source package |
| `kotlin/YdlViewModel.kt` | your Kotlin source package |
| `build.gradle.kts` | merge into `app/build.gradle.kts` |

## settings.gradle.kts — add once

```kotlin
pluginManagement {
    plugins {
        id("com.chaquo.python") version "15.0.0" apply false
    }
}
```

## AndroidManifest.xml — permissions

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"
    android:maxSdkVersion="28" />
```

## YdlViewModel.kt — use V1 import

```kotlin
import com.yourapp.ydl.v1.YdlBridge
```

## Trade-off

Breaks when YouTube changes APIs. Fix = new APK release with updated yt-dlp version in `build.gradle.kts`.
Use V2 to avoid this.
