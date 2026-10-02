# AnkiDroid for BlackBerry 10 (Q5)

A custom Anki flashcard app built specifically for **BlackBerry Q5** running the Android 4.3 runtime (BB10).

## Features

- 📚 Full Anki `.apkg` deck import
- 🔄 Two-way sync with AnkiWeb (upload & download)
- 🔀 Offline-first: study without internet, auto-merges on reconnect
- 🎛️ Respects PC deck settings (new cards/day, review limits, learning steps)
- ⌨️ BlackBerry physical keyboard shortcuts (configurable)
- 🎵 Audio card support
- 🌳 Hierarchical deck tree with collapsible parents
- 💾 Persistent login (stays logged in between sessions)
- 🔒 TLS 1.2 support for Android 4.3

## Building

Requirements: Android SDK, JDK 8+

```bash
./gradlew assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

## Sync

The app uses AnkiWeb's full-collection sync protocol:
- If you studied on PC and BB10 → merges both sets of reviews
- If you only studied on PC → downloads latest collection
- If you only studied on BB10 → uploads to AnkiWeb

## Device

Tested on **BlackBerry Q5** (Android 4.3 / BB10 Android Runtime).

## License

MIT
